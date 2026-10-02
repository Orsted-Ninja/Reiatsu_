import time
import json
import re
import numpy as np
from typing import List, Dict, Any, Optional, Tuple
from pathlib import Path
from core.database import Database
from search.chunker import RecursiveChunker, TextChunk
from search.embedder import TextEmbedder
from search.bm25_engine import BM25Engine
from search.vector_engine import VectorEngine
from search.hybrid_fusion import reciprocal_rank_fusion
from parsers.extractor_factory import ExtractorFactory
from search.live_scanner import LiveFilesystemScanner
from core.logger import setup_logger

logger = setup_logger("HybridSearch")

class HybridSearchEngine:
    def __init__(self, db: Database = None, embedder: TextEmbedder = None, vector_engine: VectorEngine = None):
        self.db = db or Database()
        self.chunker = RecursiveChunker()
        self.embedder = embedder or TextEmbedder()
        self.bm25 = BM25Engine(self.db)
        self.vector_engine = vector_engine or VectorEngine()
        self.factory = ExtractorFactory()
        self.live_scanner = LiveFilesystemScanner(self.db)

    def is_file_indexed(self, file_path: str, content_hash: str) -> bool:
        with self.db.get_connection() as conn:
            row = conn.execute(
                "SELECT content_hash FROM file_registry WHERE file_path = ?",
                (file_path,)
            ).fetchone()
            return bool(row and row["content_hash"] == content_hash)

    def index_file(self, file_path: Path, force: bool = False) -> bool:
        try:
            path = Path(file_path).resolve()
            if not path.is_file():
                return False

            doc = self.factory.parse_file(path)
            if not doc:
                return False

            if not force and self.is_file_indexed(str(path), doc.content_hash):
                return True

            chunks = self.chunker.chunk_text(doc.full_text, str(path), doc.file_name)
            if not chunks:
                with self.db.get_connection() as conn:
                    conn.execute("""
                        INSERT OR REPLACE INTO file_registry 
                        (file_path, file_name, extension, file_size, last_modified, content_hash, image_hash, is_scanned)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, (str(path), doc.file_name, doc.extension, doc.file_size, doc.last_modified,
                          doc.content_hash, doc.image_hash, int(doc.is_scanned)))
                    conn.commit()
                return True

            # Cap vector chunks to 100 max per file for responsive indexing on dual-core CPU
            MAX_VECTOR_CHUNKS = 100
            if len(chunks) > MAX_VECTOR_CHUNKS:
                vector_chunks = chunks[:70] + chunks[-30:]
            else:
                vector_chunks = chunks

            # Generate chunk embeddings
            chunk_texts = [c.text for c in vector_chunks]
            embeddings = self.embedder.embed_batch(chunk_texts)

            # Compute document centroid (mean vector normalized)
            mean_vec = np.mean(embeddings, axis=0)
            norm = np.linalg.norm(mean_vec)
            if norm > 0:
                mean_vec = mean_vec / norm
            centroid_list = mean_vec.tolist()

            # Index into BM25 (full chunks) and VectorEngine (vector chunks + document centroid)
            self.bm25.index_chunks(chunks)
            self.vector_engine.index_chunks(vector_chunks, embeddings)
            self.vector_engine.index_document_centroid(str(path), doc.file_name, centroid_list, len(chunks))

            # Store in SQLite registry and doc_centroids
            with self.db.get_connection() as conn:
                conn.execute("""
                    INSERT OR REPLACE INTO file_registry 
                    (file_path, file_name, extension, file_size, last_modified, content_hash, image_hash, is_scanned)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, (str(path), doc.file_name, doc.extension, doc.file_size, doc.last_modified,
                      doc.content_hash, doc.image_hash, int(doc.is_scanned)))

                conn.execute("""
                    INSERT OR REPLACE INTO doc_centroids
                    (file_path, chunk_count, centroid_json)
                    VALUES (?, ?, ?)
                """, (str(path), len(chunks), json.dumps(centroid_list)))

                conn.commit()

            logger.info(f"Indexed {doc.file_name} ({len(chunks)} chunks, centroid stored)")
            return True
        except Exception as e:
            logger.error(f"Failed to index {file_path}: {e}")
            return False

    def is_file_chunked(self, file_path: str | Path) -> Dict[str, Any]:
        """Checks whether a file has active chunks in SQLite FTS5 and file_registry."""
        path_str = str(Path(file_path).resolve())
        with self.db.get_connection() as conn:
            reg = conn.execute("SELECT file_path, content_hash, last_modified FROM file_registry WHERE file_path = ?", (path_str,)).fetchone()
            chunks = conn.execute("SELECT count(*) as cnt FROM doc_fts WHERE file_path = ?", (path_str,)).fetchone()
            cnt = chunks["cnt"] if chunks else 0
            return {
                "is_chunked": bool(reg and cnt > 0),
                "chunk_count": cnt,
                "file_path": path_str
            }

    def chunk_file(self, file_path: Path | str) -> bool:
        """On-demand chunking and indexing of a specific file."""
        p = Path(file_path).resolve()
        if not p.exists() or not p.is_file():
            return False
        return self.index_file(p, force=True)

    def unchunk_file(self, file_path: Path | str) -> bool:
        """Explicitly unchunks and removes a file from SQLite and ChromaDB while leaving file on disk."""
        try:
            path_str = str(Path(file_path).resolve())
            self.remove_file(path_str)
            logger.info(f"Unchunked and removed from index: {path_str}")
            return True
        except Exception as e:
            logger.error(f"Failed to unchunk {file_path}: {e}")
            return False

    def search_with_trace(
        self,
        query: str,
        top_k: int = 15,
        file_types: Optional[List[str]] = None,
        target_drive: Optional[str] = None,
        target_dirs: Optional[List[str | Path]] = None,
        search_mode: str = "hybrid",
        enable_live_scan: bool = True,
        force_live_scan: bool = False,
        auto_chunk: bool = False
    ) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
        """
        Executes search with support for search modes ('hybrid', 'live_only', 'indexed_only'),
        targeted drive/directory scope, first-page excerpt matching, and user-controlled auto-chunking.
        """
        t_start = time.perf_counter()

        # If user did not supply target_drive, detect if it's in the query
        if not target_drive:
            target_drive = self.live_scanner.detect_target_drive(query)

        # MODE 1: LIVE FILESYSTEM DISCOVERY ONLY (Bypasses pre-indexed chunks)
        final_files = []
        bm25_matches = []
        vector_matches = []
        t_bm25 = 0.0
        t_vector = 0.0
        t_rrf = 0.0
        t_live = 0.0
        live_hits = 0

        # MODE 1: LIVE FILESYSTEM DISCOVERY ONLY (Bypasses pre-indexed chunks)
        if search_mode == "live_only":
            t0 = time.perf_counter()
            live_matches = self.live_scanner.scan_live(
                query=query,
                target_dirs=target_dirs,
                target_drive=target_drive,
                file_types=file_types,
                max_matches=top_k * 2,
                timeout_seconds=12.0 if (target_drive or target_dirs) else 8.0,
                auto_chunk=auto_chunk,
                auto_index_callback=self.index_file
            )
            t_live = (time.perf_counter() - t0) * 1000
            live_hits = len(live_matches)
            final_files = live_matches

        elif search_mode in ["hybrid", "indexed_only"]:
            # 1. Lexical BM25 search
            t0 = time.perf_counter()
            candidate_k = max(60, top_k * 10)
            bm25_matches = self.bm25.search(query, top_k=candidate_k)
            t_bm25 = (time.perf_counter() - t0) * 1000

            # 2. Semantic vector search
            t0 = time.perf_counter()
            query_vec = self.embedder.embed_text(query)
            vector_matches = self.vector_engine.search_chunks(query_vec, top_k=candidate_k)
            t_vector = (time.perf_counter() - t0) * 1000

            # 3. Reciprocal Rank Fusion
            t0 = time.perf_counter()
            fused_files = reciprocal_rank_fusion(bm25_matches, vector_matches, k=60, top_k=top_k * 2, query=query)
            t_rrf = (time.perf_counter() - t0) * 1000

            # 4. Optional file type filter
            if file_types:
                clean_types = {t.lower() if t.startswith(".") else f".{t.lower()}" for t in file_types}
                fused_files = [
                    f for f in fused_files 
                    if Path(f["file_path"]).suffix.lower() in clean_types
                ]

            # If a specific drive was targeted, filter or prioritize indexed files on that drive
            if target_drive:
                drv_prefix = target_drive.upper()[:2]
                fused_files = [f for f in fused_files if Path(f["file_path"]).anchor.upper().startswith(drv_prefix)]

            final_files = fused_files[:top_k]

            # 5. Autonomous Tier-2 Live Deep Filesystem Discovery (for hybrid mode)
            should_live_scan = (search_mode == "hybrid") and enable_live_scan
            if should_live_scan:
                t0 = time.perf_counter()
                live_matches = self.live_scanner.scan_live(
                    query=query,
                    target_dirs=target_dirs,
                    target_drive=target_drive,
                    file_types=file_types,
                    max_matches=top_k * 2,
                    timeout_seconds=12.0 if (target_drive or target_dirs) else 8.0,
                    auto_chunk=auto_chunk,
                    auto_index_callback=self.index_file
                )
                t_live = (time.perf_counter() - t0) * 1000
                live_hits = len(live_matches)

                # Merge live matches with existing results, avoiding duplicate paths
                existing_paths = {f["file_path"].lower() for f in final_files}
                for lm in live_matches:
                    if lm["file_path"].lower() not in existing_paths:
                        final_files.append(lm)
                        existing_paths.add(lm["file_path"].lower())

        # Universal Anchor Gating & Document Ontology Guardian
        from core.query_parser import QueryRoleParser
        from core.document_ontology import DocumentOntology
        from core.identity_expander import get_identity_constraints

        roles = QueryRoleParser.parse(query)
        ontology_entity = DocumentOntology.match_entity(query, roles)
        identity_constraint = get_identity_constraints(query)

        validated_files = []
        for f in final_files:
            snip_l = (f.get("best_snippet") or "").lower()
            fn_l = f.get("file_name", "").lower()
            all_text = f"{fn_l} {snip_l}"

            if ontology_entity:
                is_qual, is_ver, snip = DocumentOntology.validate_candidate(snip_l, fn_l, ontology_entity)
                if is_qual:
                    if is_ver:
                        f["fused_score"] += 0.250
                        f["is_verified_identity"] = ontology_entity.display_badge
                    else:
                        f["fused_score"] += 0.120
                    if snip:
                        f["best_snippet"] = snip

                    ext = Path(f["file_path"]).suffix.lower()
                    if ext in [".pdf", ".docx", ".jpg", ".png", ".jpeg"]:
                        f["fused_score"] += 0.050
                    elif ext in [".py", ".json", ".md", ".java", ".c", ".cpp", ".h", ".cs", ".js"]:
                        f["fused_score"] -= 0.200

                    validated_files.append(f)
                else:
                    logger.debug(f"Ontology Guardian disqualified non-matching file '{f.get('file_name')}' for {ontology_entity.display_badge}")

            elif identity_constraint:
                req_terms = identity_constraint["required_terms"]
                regex = identity_constraint.get("regex")
                has_regex_hit = bool(regex.search(f.get("best_snippet") or "")) if regex else False
                has_term_hit = False
                for term in req_terms:
                    if " " in term:
                        if term in all_text:
                            has_term_hit = True
                            break
                    else:
                        if re.search(rf'\b{re.escape(term)}\b', all_text):
                            has_term_hit = True
                            break

                if has_regex_hit or has_term_hit:
                    if has_regex_hit:
                        f["fused_score"] += 0.250
                        f["is_verified_identity"] = identity_constraint["identity_type"]
                    else:
                        f["fused_score"] += 0.120

                    ext = Path(f["file_path"]).suffix.lower()
                    if ext in [".pdf", ".docx", ".jpg", ".png", ".jpeg"]:
                        f["fused_score"] += 0.050
                    elif ext in [".py", ".json", ".md", ".java", ".c", ".cpp", ".h", ".cs", ".js"]:
                        f["fused_score"] -= 0.200

                    validated_files.append(f)
                else:
                    logger.info(f"Identity Guardian disqualified non-matching file '{f.get('file_name')}' for {identity_constraint['identity_type']} search")

            elif roles.has_anchors:
                # Universal Rule of Anchor Invariance: file MUST match at least one anchor!
                has_anchor_hit = False
                for a in roles.anchors:
                    if " " in a:
                        if a in all_text:
                            has_anchor_hit = True
                            break
                    else:
                        if re.search(rf'\b{re.escape(a)}\b', all_text):
                            has_anchor_hit = True
                            break

                if has_anchor_hit:
                    validated_files.append(f)
                else:
                    logger.debug(f"Anchor Gating disqualified file '{f.get('file_name')}' lacking core anchors {roles.anchors}")
            else:
                validated_files.append(f)

        final_files = validated_files

        # Re-sort by fused score
        final_files.sort(key=lambda x: x["fused_score"], reverse=True)
        final_files = final_files[:top_k]

        # Attach chunk governance metadata to every result
        for f in final_files:
            if "is_chunked" not in f or f.get("is_live_discovery"):
                c_info = self.is_file_chunked(f["file_path"])
                f["is_chunked"] = c_info["is_chunked"]
                f["chunk_count"] = c_info["chunk_count"]

        t_total = (time.perf_counter() - t_start) * 1000
        live_diag = getattr(self.live_scanner, "last_scan_diagnostics", {})

        trace = {
            "query": query,
            "target_drive": target_drive,
            "search_mode": search_mode,
            "bm25_hits": len(bm25_matches),
            "bm25_time_ms": round(t_bm25, 2),
            "vector_hits": len(vector_matches),
            "vector_time_ms": round(t_vector, 2),
            "rrf_time_ms": round(t_rrf, 2),
            "live_scan_hits": live_hits,
            "live_scan_time_ms": round(t_live, 2),
            "total_search_time_ms": round(t_total, 2),
            "top_score": round(final_files[0]["fused_score"], 4) if final_files else 0.0,
            "live_diagnostics": live_diag
        }

        return final_files, trace

    def search(
        self,
        query: str,
        top_k: int = 15,
        file_types: Optional[List[str]] = None,
        target_drive: Optional[str] = None,
        target_dirs: Optional[List[str | Path]] = None,
        search_mode: str = "hybrid",
        enable_live_scan: bool = True,
        force_live_scan: bool = False,
        auto_chunk: bool = False
    ) -> List[Dict[str, Any]]:
        results, _ = self.search_with_trace(
            query=query, top_k=top_k, file_types=file_types,
            target_drive=target_drive, target_dirs=target_dirs,
            search_mode=search_mode,
            enable_live_scan=enable_live_scan, force_live_scan=force_live_scan,
            auto_chunk=auto_chunk
        )
        return results

    def remove_file(self, file_path: str):
        self.bm25.remove_file(file_path)
        self.vector_engine.remove_file(file_path)
        with self.db.get_connection() as conn:
            conn.execute("DELETE FROM doc_centroids WHERE file_path = ?", (file_path,))
            conn.execute("DELETE FROM file_registry WHERE file_path = ?", (file_path,))
            conn.commit()

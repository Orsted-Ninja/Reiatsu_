from dataclasses import dataclass
from typing import List, Dict, Any, Optional
from pathlib import Path
import os
import json
import filecmp
import numpy as np
import imagehash
from core.database import Database
from search.vector_engine import VectorEngine
from search.hybrid_search import HybridSearchEngine
from core.system_ops import find_supported_files
from config import SUPPORTED_EXTENSIONS, PROTECTED_KEYWORDS
from core.logger import setup_logger

logger = setup_logger("DedupEngine")

@dataclass
class DuplicateGroup:
    group_id: str
    match_type: str  # "EXACT_HASH", "SEMANTIC_TEXT", "PERCEPTUAL_IMAGE"
    confidence: float
    files: List[Dict[str, Any]]
    recommended_keep_path: str
    candidates_to_delete: List[str]
    reclaimable_bytes: int

class DuplicateDetector:
    def __init__(
        self,
        db: Database = None,
        search_engine: HybridSearchEngine = None,
        vector_engine: VectorEngine = None
    ):
        self.db = db or Database()
        self.search_engine = search_engine or HybridSearchEngine(self.db)
        self.vector_engine = vector_engine or self.search_engine.vector_engine

    def scan_folder_for_dedup(self, folder: str | Path) -> int:
        """Helper to scan and index a directory on-demand before running duplicate detection."""
        target_path = Path(folder).resolve()
        if not target_path.exists() or not target_path.is_dir():
            logger.warning(f"Scan directory does not exist: {target_path}")
            return 0

        files = find_supported_files(target_path, SUPPORTED_EXTENSIONS)
        logger.info(f"Indexing {len(files)} files from {target_path} for deduplication...")
        indexed_count = 0
        for f in files:
            try:
                if self.search_engine.index_file(f):
                    indexed_count += 1
            except Exception as e:
                logger.debug(f"Error indexing {f}: {e}")
        return indexed_count

    def find_all_duplicates(self, folder: Optional[str | Path] = None) -> List[DuplicateGroup]:
        """
        Runs comprehensive multi-modal deduplication across:
        1. Exact SHA-256 byte-by-byte copies
        2. Perceptual image copies (pHash Hamming distance <= 4)
        3. Semantic document revisions (centroid vector cosine similarity >= 0.88)
        Prevents redundant cross-tier reporting.
        """
        if folder:
            self.scan_folder_for_dedup(folder)

        groups: List[DuplicateGroup] = []
        
        # 1. Exact duplicates
        exact_groups = self.find_exact_duplicates()
        groups.extend(exact_groups)

        # Track existing exact delete candidates and pairs to avoid duplicate warnings
        exact_deleted_paths = set()
        exact_pairs = set()
        for g in exact_groups:
            for c in g.candidates_to_delete:
                exact_deleted_paths.add(os.path.normcase(os.path.abspath(c)))
            file_paths = [os.path.normcase(os.path.abspath(f["file_path"])) for f in g.files]
            for i in range(len(file_paths)):
                for j in range(i + 1, len(file_paths)):
                    exact_pairs.add(tuple(sorted([file_paths[i], file_paths[j]])))

        # 2. Perceptual image duplicates (filter out pairs already in exact duplicates)
        img_groups = self.find_image_duplicates(exact_pairs=exact_pairs)
        for g in img_groups:
            # Filter candidates that are already queued for deletion in exact groups
            filtered_del = [c for c in g.candidates_to_delete if os.path.normcase(os.path.abspath(c)) not in exact_deleted_paths]
            if filtered_del:
                g.candidates_to_delete = filtered_del
                filtered_del_norm = {os.path.normcase(os.path.abspath(c)) for c in filtered_del}
                g.reclaimable_bytes = sum(f["file_size"] for f in g.files if os.path.normcase(os.path.abspath(f["file_path"])) in filtered_del_norm)
                groups.append(g)
                for c in filtered_del:
                    exact_deleted_paths.add(os.path.normcase(os.path.abspath(c)))

        # 3. Semantic document duplicates (filter out pairs already in exact duplicates)
        sem_groups = self.find_semantic_document_duplicates(exact_pairs=exact_pairs)
        for g in sem_groups:
            filtered_del = [c for c in g.candidates_to_delete if os.path.normcase(os.path.abspath(c)) not in exact_deleted_paths]
            if filtered_del:
                g.candidates_to_delete = filtered_del
                filtered_del_norm = {os.path.normcase(os.path.abspath(c)) for c in filtered_del}
                g.reclaimable_bytes = sum(f["file_size"] for f in g.files if os.path.normcase(os.path.abspath(f["file_path"])) in filtered_del_norm)
                groups.append(g)
                for c in filtered_del:
                    exact_deleted_paths.add(os.path.normcase(os.path.abspath(c)))

        return groups

    def is_protected(self, file_name: str) -> bool:
        lower_name = file_name.lower()
        return any(kw in lower_name for kw in PROTECTED_KEYWORDS)

    def find_exact_duplicates(self) -> List[DuplicateGroup]:
        """Tier 1: Byte-level exact duplicates via SHA-256 with byte-by-byte verification for large files."""
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT content_hash, count(*) as cnt 
                FROM file_registry 
                WHERE content_hash IS NOT NULL AND content_hash != ''
                GROUP BY content_hash 
                HAVING cnt > 1
            """)
            duplicate_hashes = [row["content_hash"] for row in cursor.fetchall()]

            for h in duplicate_hashes:
                file_rows = conn.execute("""
                    SELECT file_path, file_name, file_size, last_modified, extension 
                    FROM file_registry 
                    WHERE content_hash = ?
                    ORDER BY last_modified DESC
                """, (h,)).fetchall()

                files = [dict(r) for r in file_rows]
                # Filter out files that no longer exist on disk and ensure unique physical paths
                existing_files = [f for f in files if Path(f["file_path"]).exists()]
                seen_paths = set()
                unique_files = []
                for f in existing_files:
                    norm_p = os.path.normcase(os.path.abspath(f["file_path"]))
                    if norm_p not in seen_paths:
                        seen_paths.add(norm_p)
                        unique_files.append(f)

                if len(unique_files) < 2:
                    continue

                # Ensure sizes match
                base_size = unique_files[0]["file_size"]
                size_matched = [f for f in unique_files if f["file_size"] == base_size]
                if len(size_matched) < 2:
                    continue

                # Sort: prioritize files with protected names to KEEP them, then by newest
                size_matched.sort(
                    key=lambda x: (1 if self.is_protected(x["file_name"]) else 0, x["last_modified"]),
                    reverse=True
                )

                keep_path = size_matched[0]["file_path"]
                confirmed_files = [size_matched[0]]

                # Byte-by-byte verification for large files (>= 10 MB) to eliminate false positives
                for other in size_matched[1:]:
                    other_path = other["file_path"]
                    if other["file_size"] >= 10 * 1024 * 1024:
                        try:
                            if filecmp.cmp(keep_path, other_path, shallow=False):
                                confirmed_files.append(other)
                        except Exception:
                            continue
                    else:
                        confirmed_files.append(other)

                if len(confirmed_files) < 2:
                    continue

                delete_paths = [f["file_path"] for f in confirmed_files[1:]]
                reclaimable = sum(f["file_size"] for f in confirmed_files[1:])

                groups.append(DuplicateGroup(
                    group_id=f"exact_{h[:12]}",
                    match_type="EXACT_HASH",
                    confidence=1.0,
                    files=confirmed_files,
                    recommended_keep_path=keep_path,
                    candidates_to_delete=delete_paths,
                    reclaimable_bytes=reclaimable
                ))

        return groups

    def find_image_duplicates(self, max_hamming_distance: int = 4, exact_pairs: Optional[set] = None) -> List[DuplicateGroup]:
        """Tier 3: Near-duplicate images via perceptual hash (pHash)."""
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT file_path, file_name, file_size, last_modified, image_hash 
                FROM file_registry 
                WHERE image_hash IS NOT NULL AND image_hash != ''
            """)
            image_records = [dict(r) for r in cursor]

        # Filter to existing files only and ensure unique physical paths
        existing_images = [img for img in image_records if Path(img["file_path"]).exists()]
        seen_img_paths = set()
        unique_images = []
        for img in existing_images:
            norm_p = os.path.normcase(os.path.abspath(img["file_path"]))
            if norm_p not in seen_img_paths:
                seen_img_paths.add(norm_p)
                unique_images.append(img)

        # Pre-parse all hashes to avoid O(N^2) hex parsing overhead
        for img in unique_images:
            try:
                img["phash"] = imagehash.hex_to_hash(img["image_hash"])
            except Exception:
                img["phash"] = None

        valid_records = [img for img in unique_images if img.get("phash") is not None]

        visited = set()
        for i, img1 in enumerate(valid_records):
            p1 = img1["file_path"]
            norm_p1 = os.path.normcase(os.path.abspath(p1))
            if norm_p1 in visited:
                continue

            h1 = img1["phash"]
            matched_group = [img1]
            min_matched_distance = 64

            for j in range(i + 1, len(valid_records)):
                img2 = valid_records[j]
                p2 = img2["file_path"]
                norm_p2 = os.path.normcase(os.path.abspath(p2))
                if norm_p2 in visited:
                    continue

                # Skip if already identified in exact hash duplicates
                if exact_pairs:
                    pair_key = tuple(sorted([norm_p1, norm_p2]))
                    if pair_key in exact_pairs:
                        continue

                distance = h1 - img2["phash"]
                if distance <= max_hamming_distance:
                    matched_group.append(img2)
                    visited.add(norm_p2)
                    if distance < min_matched_distance:
                        min_matched_distance = distance

            if len(matched_group) > 1:
                visited.add(norm_p1)
                matched_group.sort(
                    key=lambda x: (1 if self.is_protected(x["file_name"]) else 0, x["last_modified"]),
                    reverse=True
                )
                keep_path = matched_group[0]["file_path"]
                delete_paths = [f["file_path"] for f in matched_group[1:]]
                reclaimable = sum(f["file_size"] for f in matched_group[1:])

                groups.append(DuplicateGroup(
                    group_id=f"img_{img1['image_hash'][:8]}",
                    match_type="PERCEPTUAL_IMAGE",
                    confidence=max(0.80, 1.0 - (min_matched_distance / 64.0)),
                    files=matched_group,
                    recommended_keep_path=keep_path,
                    candidates_to_delete=delete_paths,
                    reclaimable_bytes=reclaimable
                ))

        return groups

    def find_semantic_document_duplicates(
        self, threshold: float = 0.88, exact_pairs: Optional[set] = None
    ) -> List[DuplicateGroup]:
        """
        Tier 2: Near-duplicate documents via document-level centroid vector cosine similarity.
        Directly computes cosine similarity across document centroids stored in doc_centroids.
        Resilient against ChromaDB desync, exact duplicates, and Windows path format differences.
        """
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT c.file_path, r.file_name, r.file_size, r.last_modified, r.content_hash, c.centroid_json
                FROM doc_centroids c
                JOIN file_registry r ON c.file_path = r.file_path
            """)
            docs = [dict(r) for r in cursor]

        # Filter out files that no longer exist on disk and ensure unique physical paths
        valid_docs = [d for d in docs if Path(d["file_path"]).exists()]
        seen_doc_paths = set()
        unique_docs = []
        for d in valid_docs:
            norm_p = os.path.normcase(os.path.abspath(d["file_path"]))
            if norm_p not in seen_doc_paths:
                seen_doc_paths.add(norm_p)
                unique_docs.append(d)

        if len(unique_docs) < 2:
            return groups

        # Load and normalize embeddings with NumPy for fast exact cosine comparison
        try:
            centroids = []
            parsed_docs = []
            for d in unique_docs:
                try:
                    vec = json.loads(d["centroid_json"])
                    centroids.append(vec)
                    parsed_docs.append(d)
                except Exception as e_vec:
                    logger.debug(f"Failed parsing centroid vector for {d['file_path']}: {e_vec}")

            if len(parsed_docs) < 2:
                return groups

            embeddings = np.array(centroids, dtype=np.float32)
            norms = np.linalg.norm(embeddings, axis=1, keepdims=True)
            norms[norms == 0] = 1.0
            embeddings = embeddings / norms

            # Compute full pairwise cosine similarity matrix: O(N^2) vectorized in < 1ms
            sim_matrix = np.dot(embeddings, embeddings.T)

            processed_pairs = set()
            for i in range(len(parsed_docs)):
                doc_i = parsed_docs[i]
                norm_pi = os.path.normcase(os.path.abspath(doc_i["file_path"]))

                for j in range(i + 1, len(parsed_docs)):
                    doc_j = parsed_docs[j]
                    norm_pj = os.path.normcase(os.path.abspath(doc_j["file_path"]))

                    # Skip exact byte-level duplicates (already handled in Tier 1)
                    if doc_i["content_hash"] == doc_j["content_hash"]:
                        continue

                    pair_key = tuple(sorted([norm_pi, norm_pj]))
                    if pair_key in processed_pairs:
                        continue
                    if exact_pairs and pair_key in exact_pairs:
                        continue

                    similarity = float(sim_matrix[i, j])
                    if similarity >= threshold:
                        processed_pairs.add(pair_key)
                        pair = [
                            {"file_path": doc_i["file_path"], "file_name": doc_i["file_name"], "file_size": doc_i["file_size"], "last_modified": doc_i["last_modified"]},
                            {"file_path": doc_j["file_path"], "file_name": doc_j["file_name"], "file_size": doc_j["file_size"], "last_modified": doc_j["last_modified"]}
                        ]
                        # Keep protected or newer version
                        pair.sort(
                            key=lambda x: (1 if self.is_protected(x["file_name"]) else 0, x["last_modified"]),
                            reverse=True
                        )
                        keep_path = pair[0]["file_path"]
                        delete_paths = [pair[1]["file_path"]]
                        reclaimable = pair[1]["file_size"]

                        groups.append(DuplicateGroup(
                            group_id=f"sem_{doc_i['file_name'][:8]}",
                            match_type="SEMANTIC_TEXT",
                            confidence=round(similarity, 3),
                            files=pair,
                            recommended_keep_path=keep_path,
                            candidates_to_delete=delete_paths,
                            reclaimable_bytes=reclaimable
                        ))

        except Exception as e:
            logger.error(f"Error computing semantic document duplicates: {e}")

        return groups

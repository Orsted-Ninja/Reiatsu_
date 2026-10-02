from dataclasses import dataclass
from typing import List, Dict, Any, Optional
from pathlib import Path
import json
import filecmp
import imagehash
from core.database import Database
from search.vector_engine import VectorEngine
from search.hybrid_search import HybridSearchEngine
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

    def find_all_duplicates(self) -> List[DuplicateGroup]:
        groups: List[DuplicateGroup] = []
        groups.extend(self.find_exact_duplicates())
        groups.extend(self.find_image_duplicates())
        groups.extend(self.find_semantic_document_duplicates())
        return groups

    def find_exact_duplicates(self) -> List[DuplicateGroup]:
        """Tier 1: Byte-level exact duplicates via SHA-256 with byte-by-byte verification for large files."""
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT content_hash, count(*) as cnt 
                FROM file_registry 
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
                # Filter out files that no longer exist on disk
                existing_files = [f for f in files if Path(f["file_path"]).exists()]
                if len(existing_files) < 2:
                    continue

                keep_path = existing_files[0]["file_path"]
                confirmed_files = [existing_files[0]]

                # Byte-by-byte verification for large files (>= 10 MB) to eliminate false positives
                for other in existing_files[1:]:
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

    def find_image_duplicates(self, max_hamming_distance: int = 4) -> List[DuplicateGroup]:
        """Tier 3: Near-duplicate images via perceptual hash (pHash)."""
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT file_path, file_name, file_size, last_modified, image_hash 
                FROM file_registry 
                WHERE image_hash IS NOT NULL AND image_hash != ''
            """)
            image_records = [dict(r) for r in cursor]  # Memory optimized

        # Pre-parse all hashes to avoid O(N^2) hex parsing overhead
        for img in image_records:
            try:
                img["phash"] = imagehash.hex_to_hash(img["image_hash"])
            except Exception:
                img["phash"] = None

        valid_records = [img for img in image_records if img.get("phash") is not None]

        visited = set()
        for i, img1 in enumerate(valid_records):
            p1 = img1["file_path"]
            if p1 in visited:
                continue

            h1 = img1["phash"]
            matched_group = [img1]
            min_matched_distance = 64

            for j in range(i + 1, len(valid_records)):
                img2 = valid_records[j]
                p2 = img2["file_path"]
                if p2 in visited:
                    continue

                distance = h1 - img2["phash"]
                if distance <= max_hamming_distance:
                    matched_group.append(img2)
                    visited.add(p2)
                    if distance < min_matched_distance:
                        min_matched_distance = distance

            if len(matched_group) > 1:
                visited.add(p1)
                matched_group.sort(key=lambda x: x["last_modified"], reverse=True)
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

    def find_semantic_document_duplicates(self, threshold: float = 0.88) -> List[DuplicateGroup]:
        """
        Tier 2: Near-duplicate documents via document-level centroid vector cosine similarity.
        Only considers files registered in the current database registry.
        """
        groups = []
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT c.file_path, r.file_name, r.file_size, r.last_modified, c.centroid_json
                FROM doc_centroids c
                JOIN file_registry r ON c.file_path = r.file_path
            """)
            docs = [dict(r) for r in cursor]  # Memory optimized

        if len(docs) < 2:
            return groups

        valid_paths_map = {d["file_path"]: d for d in docs}
        processed_pairs = set()
        
        # Batch query ChromaDB to avoid O(N) isolated DB calls
        embeddings_batch = [json.loads(doc["centroid_json"]) for doc in docs]
        
        # We process in chunks if there are thousands, but for standard usage batching is fine
        # To avoid massive payload, we can batch in sizes of 1000
        batch_size = 1000
        for i in range(0, len(embeddings_batch), batch_size):
            sub_docs = docs[i:i+batch_size]
            sub_embeddings = embeddings_batch[i:i+batch_size]
            batch_results = self.vector_engine.find_similar_documents_batch(sub_embeddings, top_k=10)
            
            for idx, doc in enumerate(sub_docs):
                similar_docs = batch_results[idx]
                for sim in similar_docs:
                    m_path = sim["file_path"]
                    if m_path == doc["file_path"]:
                        continue

                    # STRICT CHECK: Must be a currently registered document in this database
                    other_doc = valid_paths_map.get(m_path)
                    if not other_doc:
                        continue

                    pair_key = tuple(sorted([doc["file_path"], m_path]))
                    if pair_key in processed_pairs:
                        continue
                    processed_pairs.add(pair_key)

                    similarity = sim["similarity"]
                    if similarity >= threshold:
                        pair = [
                            {"file_path": doc["file_path"], "file_name": doc["file_name"], "file_size": doc["file_size"], "last_modified": doc["last_modified"]},
                            {"file_path": other_doc["file_path"], "file_name": other_doc["file_name"], "file_size": other_doc["file_size"], "last_modified": other_doc["last_modified"]}
                        ]
                        pair.sort(key=lambda x: x["last_modified"], reverse=True)
                        keep_path = pair[0]["file_path"]
                        delete_paths = [pair[1]["file_path"]]
                        reclaimable = pair[1]["file_size"]

                        groups.append(DuplicateGroup(
                            group_id=f"sem_{doc['file_name'][:8]}",
                            match_type="SEMANTIC_TEXT",
                            confidence=round(similarity, 3),
                            files=pair,
                            recommended_keep_path=keep_path,
                            candidates_to_delete=delete_paths,
                            reclaimable_bytes=reclaimable
                        ))

        return groups

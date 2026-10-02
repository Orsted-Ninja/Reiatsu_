from dataclasses import dataclass
from typing import List, Dict, Any
from pathlib import Path
import time
import sqlite3
from config import DB_PATH, PROTECTED_KEYWORDS, INSTALLER_EXTENSIONS
from core.database import Database
from intelligence.dedup_engine import DuplicateDetector

@dataclass
class ReclaimCandidate:
    file_path: str
    file_name: str
    file_size: int
    category: str  # "EXACT_DUPLICATE", "STALE_INSTALLER", "LARGE_UNUSED"
    reason: str
    last_modified: float

class SpaceReclaimer:
    def __init__(self, db: Database = None, dedup_detector: DuplicateDetector = None):
        self.db = db or Database()
        self.dedup_detector = dedup_detector or DuplicateDetector(self.db)

    def is_protected(self, file_name: str) -> bool:
        lower_name = file_name.lower()
        return any(kw in lower_name for kw in PROTECTED_KEYWORDS)

    def recommend_cleanup(self, target_bytes: int = 0) -> List[ReclaimCandidate]:
        candidates: List[ReclaimCandidate] = []
        seen_paths = set()
        now = time.time()
        one_year_ago = now - (365 * 24 * 3600)
        six_months_ago = now - (180 * 24 * 3600)

        # 1. Exact duplicates (Priority 1)
        exact_groups = self.dedup_detector.find_exact_duplicates()
        for group in exact_groups:
            for fpath in group.candidates_to_delete:
                if fpath not in seen_paths and not self.is_protected(Path(fpath).name):
                    item = next((f for f in group.files if f["file_path"] == fpath), None)
                    if item:
                        candidates.append(ReclaimCandidate(
                            file_path=fpath,
                            file_name=item["file_name"],
                            file_size=item["file_size"],
                            category="EXACT_DUPLICATE",
                            reason=f"Exact duplicate of {Path(group.recommended_keep_path).name}",
                            last_modified=item["last_modified"]
                        ))
                        seen_paths.add(fpath)

        # 2. Stale installers and large unused files (Priority 2)
        # 2. Stale installers and large unused files (Priority 2)
        from config import LARGE_FILE_THRESHOLD_BYTES
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT file_path, file_name, file_size, last_modified, extension 
                FROM file_registry 
                ORDER BY file_size DESC
            """)
            
            for row in cursor:
                fpath = row["file_path"]
                if fpath in seen_paths:
                    continue

                fname = row["file_name"]
                ext = row["extension"].lower()
                fsize = row["file_size"]
                mtime = row["last_modified"]

                if self.is_protected(fname):
                    continue

                if ext in INSTALLER_EXTENSIONS and mtime < six_months_ago:
                    candidates.append(ReclaimCandidate(
                        file_path=fpath,
                        file_name=fname,
                        file_size=fsize,
                        category="STALE_INSTALLER",
                        reason="Old installer/archive untouched for > 6 months",
                        last_modified=mtime
                    ))
                    seen_paths.add(fpath)

                elif fsize > LARGE_FILE_THRESHOLD_BYTES and mtime < one_year_ago:
                    candidates.append(ReclaimCandidate(
                        file_path=fpath,
                        file_name=fname,
                        file_size=fsize,
                        category="LARGE_UNUSED",
                        reason=f"Large file (> {LARGE_FILE_THRESHOLD_BYTES // (1024*1024)} MB) unused for > 1 year",
                        last_modified=mtime
                    ))
                    seen_paths.add(fpath)

        if target_bytes > 0:
            accumulated = 0
            trimmed = []
            for c in candidates:
                trimmed.append(c)
                accumulated += c.file_size
                if accumulated >= target_bytes:
                    break
            return trimmed

        return candidates

import shutil
import time
import os
from pathlib import Path
from typing import List, Dict, Any, Optional
from config import TRASH_DIR
from core.database import Database
from core.hash_utils import compute_file_hash
from core.logger import setup_logger

logger = setup_logger("TrashManager")

class TrashManager:
    def __init__(self, trash_dir: Path = TRASH_DIR, db: Database = None):
        self.trash_dir = Path(trash_dir)
        self.trash_dir.mkdir(parents=True, exist_ok=True)
        self.db = db or Database()

    def move_to_trash(self, file_path: Path) -> Dict[str, Any]:
        path = Path(file_path).resolve()
        if not path.exists():
            return {
                "id": None,
                "status": "NOT_FOUND",
                "message": f"File does not exist: {path.name}"
            }

        try:
            file_size = path.stat().st_size
            file_hash = compute_file_hash(path)
            timestamp = int(time.time())

            bucket_name = f"{timestamp}_{file_hash[:8]}"
            bucket_dir = self.trash_dir / bucket_name
            bucket_dir.mkdir(parents=True, exist_ok=True)
            trash_dest = bucket_dir / path.name

            # Move with Windows lock protection
            shutil.move(str(path), str(trash_dest))

            with self.db.get_connection() as conn:
                conn.execute("""
                    INSERT OR REPLACE INTO trash_registry 
                    (entry_id, original_path, trash_path, file_name, file_size, timestamp, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'TRASHED')
                """, (bucket_name, str(path), str(trash_dest), path.name, file_size, timestamp))
                conn.commit()

            logger.info(f"Moved {path.name} to trash bucket {bucket_name}")
            return {
                "id": bucket_name,
                "original_path": str(path),
                "trash_path": str(trash_dest),
                "file_name": path.name,
                "file_size": file_size,
                "timestamp": timestamp,
                "status": "TRASHED"
            }

        except PermissionError:
            logger.warning(f"File {path.name} is locked by another process.")
            return {
                "id": None,
                "status": "LOCKED",
                "message": f"'{path.name}' is currently open in another program. Please close it first."
            }
        except Exception as e:
            logger.error(f"Failed to trash {path.name}: {e}")
            return {
                "id": None,
                "status": "ERROR",
                "message": f"Error moving '{path.name}': {str(e)}"
            }

    def restore(self, entry_id: str) -> Dict[str, Any]:
        with self.db.get_connection() as conn:
            row = conn.execute(
                "SELECT * FROM trash_registry WHERE entry_id = ? AND status = 'TRASHED'",
                (entry_id,)
            ).fetchone()

            if not row:
                return {"success": False, "message": "Entry not found in quarantine"}

            trash_path = Path(row["trash_path"])
            orig_path = Path(row["original_path"])

            if not trash_path.exists():
                return {"success": False, "message": "Quarantined file missing from disk"}

            try:
                dest_path = orig_path
                # Non-destructive restore: if destination already exists, generate a safe versioned name
                if dest_path.exists():
                    stem = dest_path.stem
                    suffix = dest_path.suffix
                    counter = 1
                    while dest_path.exists():
                        dest_path = dest_path.parent / f"{stem} (restored_{counter}){suffix}"
                        counter += 1

                dest_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(trash_path), str(dest_path))

                # Clean up empty bucket directory in .trash
                bucket_dir = trash_path.parent
                try:
                    if bucket_dir.exists() and not any(bucket_dir.iterdir()):
                        bucket_dir.rmdir()
                except Exception as e_rm:
                    logger.debug(f"Bucket directory cleanup skipped for {bucket_dir}: {e_rm}")

                conn.execute(
                    "UPDATE trash_registry SET status = 'RESTORED' WHERE entry_id = ?",
                    (entry_id,)
                )
                conn.commit()

                logger.info(f"Restored {row['file_name']} to {dest_path}")
                return {
                    "success": True,
                    "file_name": row["file_name"],
                    "path": str(dest_path),
                    "was_renamed": dest_path != orig_path
                }
            except Exception as e:
                logger.error(f"Error restoring {row['file_name']}: {e}")
                return {"success": False, "message": f"Error restoring file: {str(e)}"}

    def list_trash(self) -> List[Dict[str, Any]]:
        with self.db.get_connection() as conn:
            cursor = conn.execute("""
                SELECT entry_id AS id, original_path, trash_path, file_name, file_size, timestamp, status
                FROM trash_registry
                WHERE status = 'TRASHED'
                ORDER BY timestamp DESC
            """)
            return [dict(r) for r in cursor.fetchall()]

    def purge_trash(self) -> int:
        with self.db.get_connection() as conn:
            cursor = conn.execute("SELECT * FROM trash_registry WHERE status = 'TRASHED'")
            rows = cursor.fetchall()
            purged_count = 0

            for r in rows:
                trash_path = Path(r["trash_path"])
                try:
                    if trash_path.exists():
                        trash_path.unlink()
                    bucket_dir = trash_path.parent
                    if bucket_dir.exists() and not any(bucket_dir.iterdir()):
                        bucket_dir.rmdir()
                    purged_count += 1
                except Exception as e_purge:
                    logger.warning(f"Error deleting trashed file {trash_path}: {e_purge}")

            conn.execute("UPDATE trash_registry SET status = 'PURGED' WHERE status = 'TRASHED'")
            conn.commit()

        return purged_count

    def get_trash_stats(self) -> Dict[str, Any]:
        with self.db.get_connection() as conn:
            row = conn.execute("""
                SELECT COUNT(*) as cnt, COALESCE(SUM(file_size), 0) as total_bytes
                FROM trash_registry
                WHERE status = 'TRASHED'
            """).fetchone()
            return {
                "count": row["cnt"],
                "total_bytes": row["total_bytes"]
            }

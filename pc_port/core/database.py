import sqlite3
import contextlib
from pathlib import Path
from typing import Optional, List, Dict, Any
from config import DB_PATH
from core.env_guard import get_f_drive_tmp
from core.logger import setup_logger

logger = setup_logger("Database")

class Database:
    _instances: Dict[str, "Database"] = {}

    def __new__(cls, db_path: Path = DB_PATH):
        resolved_path = str(Path(db_path).resolve())
        if resolved_path not in cls._instances:
            inst = super(Database, cls).__new__(cls)
            inst._initialized = False
            cls._instances[resolved_path] = inst
        return cls._instances[resolved_path]

    def __init__(self, db_path: Path = DB_PATH):
        if getattr(self, "_initialized", False):
            return
        self.db_path = str(Path(db_path).resolve())
        self._init_pragmas_and_tables()
        self._initialized = True

    @contextlib.contextmanager
    def get_connection(self):
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        conn.row_factory = sqlite3.Row
        # Optimizations per connection
        conn.execute("PRAGMA synchronous=NORMAL;")
        conn.execute("PRAGMA foreign_keys=ON;")
        conn.execute("PRAGMA cache_size=-64000;")  # 64 MB memory cache
        
        # Strictly enforce C: drive isolation for temp tables
        tmp_dir = get_f_drive_tmp()
        # Fallback if PRAGMA temp_store=MEMORY overflows
        if tmp_dir:
            try:
                conn.execute(f"PRAGMA temp_store_directory = '{tmp_dir}';")
            except Exception as e:
                logger.debug(f"PRAGMA temp_store_directory skipped: {e}")
        conn.execute("PRAGMA temp_store=MEMORY;")
        
        try:
            with conn:  # Handles transaction commit/rollback
                yield conn
        finally:
            conn.close()

    def _init_pragmas_and_tables(self):
        with self.get_connection() as conn:
            conn.execute("PRAGMA journal_mode=WAL;")
            # 1. File metadata registry
            conn.execute("""
                CREATE TABLE IF NOT EXISTS file_registry (
                    file_path TEXT PRIMARY KEY,
                    file_name TEXT NOT NULL,
                    extension TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    last_modified REAL NOT NULL,
                    content_hash TEXT NOT NULL,
                    image_hash TEXT,
                    is_scanned INTEGER DEFAULT 0,
                    indexed_at REAL DEFAULT (strftime('%s', 'now'))
                );
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_hash ON file_registry(content_hash);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_ext ON file_registry(extension);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_file_mtime ON file_registry(last_modified);")

            # 2. Document-level vector centroid registry for semantic near-duplicates
            conn.execute("""
                CREATE TABLE IF NOT EXISTS doc_centroids (
                    file_path TEXT PRIMARY KEY,
                    chunk_count INTEGER NOT NULL,
                    centroid_json TEXT NOT NULL,
                    updated_at REAL DEFAULT (strftime('%s', 'now')),
                    FOREIGN KEY(file_path) REFERENCES file_registry(file_path) ON DELETE CASCADE
                );
            """)

            # 3. FTS5 Lexical Inverted Index
            conn.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS doc_fts USING fts5(
                    chunk_id UNINDEXED,
                    file_path,
                    file_name,
                    text,
                    tokenize='porter'
                );
            """)

            # 4. ACID Trash Registry (replacing trash_log.json)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS trash_registry (
                    entry_id TEXT PRIMARY KEY,
                    original_path TEXT NOT NULL,
                    trash_path TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_size INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'TRASHED'
                );
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_trash_status ON trash_registry(status);")

            conn.commit()
            logger.info("Database initialized with WAL mode and production tables.")

    def checkpoint(self, mode: str = "TRUNCATE") -> bool:
        """Executes a WAL checkpoint to truncate or merge WAL logs into primary database."""
        try:
            with self.get_connection() as conn:
                conn.execute(f"PRAGMA wal_checkpoint({mode});")
            return True
        except Exception as e:
            logger.warning(f"WAL checkpoint ({mode}) warning: {e}")
            return False

    def get_journal_mode(self) -> str:
        with self.get_connection() as conn:
            cursor = conn.execute("PRAGMA journal_mode;")
            row = cursor.fetchone()
            return row[0] if row else "unknown"

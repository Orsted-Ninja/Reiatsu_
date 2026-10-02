from pathlib import Path
from typing import Optional, List, Dict, Any
from core.database import Database
from search.embedder import TextEmbedder
from search.vector_engine import VectorEngine
from search.bm25_engine import BM25Engine
from search.hybrid_search import HybridSearchEngine
from intelligence.dedup_engine import DuplicateDetector
from intelligence.space_reclaimer import SpaceReclaimer
from intelligence.llm_agent import LLMAgent
from actions.trash_manager import TrashManager
from actions.action_engine import ActionEngine
from indexing.folder_watcher import StorageFolderWatcher
from indexing.background_worker import IndexingWorker
from core.system_ops import get_standard_user_folders
from core.workspace_manager import workspace_mgr, WorkspaceManager
from core.logger import setup_logger

import threading

logger = setup_logger("Coordinator")

class EngineCoordinator:
    """
    Central dependency coordinator ensuring singletons across all subsystems.
    Equipped with autonomous background filesystem discovery, real-time watchers,
    and a dual-tier search engine (Tier 1: Pre-indexed Hybrid + Tier 2: Real-time Live Discovery).
    """
    _instance = None
    _lock = threading.Lock()

    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super(EngineCoordinator, cls).__new__(cls)
                    cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        with self._lock:
            if getattr(self, "_initialized", False):
                return
            self._do_init()

    def _do_init(self):
        logger.info("Initializing EngineCoordinator singletons...")
        self.workspace_mgr = workspace_mgr
        self.db = Database()
        self.embedder = TextEmbedder()
        self.vector_engine = VectorEngine()
        self.bm25 = BM25Engine(self.db)
        self.search_engine = HybridSearchEngine(self.db, self.embedder, self.vector_engine)
        self.live_scanner = self.search_engine.live_scanner
        self.dedup_detector = DuplicateDetector(self.db, self.search_engine, self.vector_engine)
        self.space_reclaimer = SpaceReclaimer(self.db, self.dedup_detector)
        self.trash_manager = TrashManager(db=self.db)
        self.action_engine = ActionEngine()
        self.llm_agent = LLMAgent()
        self.folder_watcher = StorageFolderWatcher(self.search_engine)
        self.background_worker = IndexingWorker(self.search_engine)

        self._start_autonomous_watchers()

        self._initialized = True
        logger.info("All subsystems initialized and coordinated successfully.")

    def _start_autonomous_watchers(self):
        """Automatically monitors standard user directories for new/changed files."""
        try:
            folders = get_standard_user_folders()
            for name, path_str in folders.items():
                path_lower = path_str.lower()
                # Strictly NEVER watch project code, repository, virtualenvs, or internal workspace directories
                if "project" in name.lower() or "idea" in path_lower or "pc_port" in path_lower:
                    logger.info(f"Excluding project directory from background watcher: {path_str}")
                    continue
                p = Path(path_str)
                if p.exists() and p.is_dir():
                    self.folder_watcher.watch_folder(p)
            self.folder_watcher.start()
        except Exception as e:
            logger.warning(f"Could not start autonomous folder watchers: {e}")

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
        return self.search_engine.search(
            query=query,
            top_k=top_k,
            file_types=file_types,
            target_drive=target_drive,
            target_dirs=target_dirs,
            search_mode=search_mode,
            enable_live_scan=enable_live_scan,
            force_live_scan=force_live_scan,
            auto_chunk=auto_chunk
        )

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
    ):
        return self.search_engine.search_with_trace(
            query=query,
            top_k=top_k,
            file_types=file_types,
            target_drive=target_drive,
            target_dirs=target_dirs,
            search_mode=search_mode,
            enable_live_scan=enable_live_scan,
            force_live_scan=force_live_scan,
            auto_chunk=auto_chunk
        )

    def chunk_file(self, file_path: Path | str) -> bool:
        """Explicitly indexes and chunks a file into the database on-demand."""
        return self.search_engine.chunk_file(file_path)

    def unchunk_file(self, file_path: Path | str) -> bool:
        """Explicitly removes a file's chunks from FTS5 and ChromaDB."""
        return self.search_engine.unchunk_file(file_path)

    def is_file_chunked(self, file_path: Path | str) -> Dict[str, Any]:
        """Checks if a file is chunked in the database."""
        return self.search_engine.is_file_chunked(file_path)

    def get_indexed_stats(self) -> dict:
        with self.db.get_connection() as conn:
            files_row = conn.execute("SELECT COUNT(*) as cnt, COALESCE(SUM(file_size), 0) as total_size FROM file_registry").fetchone()
            chunks_row = conn.execute("SELECT COUNT(*) as cnt FROM doc_fts").fetchone()
            trash_stats = self.trash_manager.get_trash_stats()

            return {
                "total_files": files_row["cnt"],
                "total_bytes": files_row["total_size"],
                "total_chunks": chunks_row["cnt"],
                "trashed_files": trash_stats["count"],
                "trashed_bytes": trash_stats["total_bytes"]
            }

    def get_all_chunked_files(self) -> List[Dict[str, Any]]:
        """Returns all currently indexed/chunked files from file_registry."""
        with self.db.get_connection() as conn:
            rows = conn.execute("""
                SELECT f.file_path, f.file_name, f.file_size, f.last_modified,
                       COALESCE((SELECT COUNT(*) FROM doc_fts d WHERE d.file_path = f.file_path), 0) as chunk_count
                FROM file_registry f
                ORDER BY f.last_modified DESC
            """).fetchall()
            return [dict(r) for r in rows]


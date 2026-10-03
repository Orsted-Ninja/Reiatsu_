import os
import threading
import time
from pathlib import Path
from typing import List, Callable, Optional, Dict, Any
from concurrent.futures import ThreadPoolExecutor, as_completed
from config import SUPPORTED_EXTENSIONS
from core.system_ops import find_supported_files
from search.hybrid_search import HybridSearchEngine
from core.logger import setup_logger

logger = setup_logger("BackgroundWorker")

class IndexingWorker:
    def __init__(self, search_engine: HybridSearchEngine, max_workers: Optional[int] = None):
        self.search_engine = search_engine
        if max_workers is None:
            # Auto-scale: leave 1 core free for UI/system responsiveness
            cores = os.cpu_count() or 2
            self.max_workers = min(8, max(1, cores - 1))
        else:
            self.max_workers = max_workers
        self.is_running = False
        self._cancel_requested = False
        self._progress_lock = threading.Lock()
        self.progress: Dict[str, Any] = {
            "status": "idle",
            "total_files": 0,
            "processed_files": 0,
            "current_file": "",
            "success_count": 0,
            "error_count": 0
        }

    def start_indexing_async(
        self,
        folder_path: Path,
        on_progress_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
        on_complete_callback: Optional[Callable[[Dict[str, Any]], None]] = None
    ) -> Optional[threading.Thread]:
        if self.is_running:
            logger.warning("Indexing already in progress.")
            return None

        self._cancel_requested = False
        thread = threading.Thread(
            target=self._run_batch,
            args=(folder_path, on_progress_callback, on_complete_callback),
            daemon=True
        )
        thread.start()
        return thread

    def cancel(self):
        self._cancel_requested = True
        logger.info("Indexing cancellation requested.")

    def _update_progress(self, current_file: str, success: bool, on_progress: Optional[Callable]):
        with self._progress_lock:
            self.progress["processed_files"] += 1
            self.progress["current_file"] = current_file
            if success:
                self.progress["success_count"] += 1
            else:
                self.progress["error_count"] += 1
            snapshot = dict(self.progress)
        if on_progress:
            try:
                on_progress(snapshot)
            except Exception as e_prog:
                logger.warning(f"Error in on_progress callback: {e_prog}")

    def _run_batch(self, folder_path: Path, on_progress, on_complete):
        self.is_running = True
        with self._progress_lock:
            self.progress["status"] = "scanning"
            self.progress["total_files"] = 0
            self.progress["processed_files"] = 0
            self.progress["success_count"] = 0
            self.progress["error_count"] = 0

        try:
            target = Path(folder_path).resolve()
            if not target.exists() or (not target.is_dir() and not target.is_file()):
                with self._progress_lock:
                    self.progress["status"] = "error"
                return

            # Generator/pruning based directory walk (skips node_modules, .cache, .git)
            files = find_supported_files(target, SUPPORTED_EXTENSIONS)

            with self._progress_lock:
                self.progress["total_files"] = len(files)
                self.progress["processed_files"] = 0
                self.progress["status"] = "indexing"

            logger.info(f"Background worker queued {len(files)} files from {target} (Workers: {self.max_workers})")

            # Concurrent batch indexing using ThreadPoolExecutor
            with ThreadPoolExecutor(max_workers=self.max_workers) as executor:
                futures = {}
                for fpath in files:
                    if self._cancel_requested:
                        break
                    fut = executor.submit(self.search_engine.index_file, fpath)
                    futures[fut] = fpath.name

                for fut in as_completed(futures):
                    if self._cancel_requested:
                        with self._progress_lock:
                            self.progress["status"] = "cancelled"
                        break
                    fname = futures[fut]
                    try:
                        ok = fut.result()
                    except Exception as e:
                        logger.error(f"Error indexing {fname}: {e}")
                        ok = False
                    self._update_progress(fname, ok, on_progress)

            with self._progress_lock:
                if not self._cancel_requested and self.progress["status"] != "error":
                    self.progress["status"] = "completed"

            # Checkpoint WAL upon batch completion to maintain storage hygiene
            try:
                self.search_engine.db.checkpoint("TRUNCATE")
            except Exception as e_chk:
                logger.warning(f"Background worker WAL checkpoint warning: {e_chk}")

        finally:
            self.is_running = False
            if on_complete:
                try:
                    with self._progress_lock:
                        final_state = dict(self.progress)
                    on_complete(final_state)
                except Exception as e_comp:
                    logger.warning(f"Error in on_complete callback: {e_comp}")

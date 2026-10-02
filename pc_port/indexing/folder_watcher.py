import time
from pathlib import Path
from watchdog.observers import Observer
from watchdog.events import FileSystemEventHandler
from config import SUPPORTED_EXTENSIONS
from search.hybrid_search import HybridSearchEngine
from core.logger import setup_logger

logger = setup_logger("FolderWatcher")

from concurrent.futures import ThreadPoolExecutor

IGNORED_PATH_PATTERNS = (
    "__pycache__", ".git", ".venv", "\\venv\\", "/venv/",
    "node_modules", ".trash", ".cache", ".idea", ".vscode",
    "pc_port", "\\idea\\", "/idea/", "site-packages", ".pytest_cache"
)

class StorageChangeHandler(FileSystemEventHandler):
    def __init__(self, search_engine: HybridSearchEngine, auto_chunk: bool = False):
        super().__init__()
        self.search_engine = search_engine
        self.auto_chunk = auto_chunk
        self._last_event_time = {}
        self._executor = ThreadPoolExecutor(max_workers=1)

    def _is_ignored(self, p: Path) -> bool:
        if p.name.startswith("~$") or p.name.startswith("."):
            return True
        path_str = str(p).lower()
        return any(ign in path_str for ign in IGNORED_PATH_PATTERNS)

    def _should_process(self, path_str: str) -> bool:
        now = time.time()
        last = self._last_event_time.get(path_str, 0)
        if now - last < 2.0:
            return False
        self._last_event_time[path_str] = now
        return True

    def _process_file(self, p: Path, is_update: bool = False):
        try:
            if self._is_ignored(p):
                return
            time.sleep(0.5)  # Allow file write to complete
            if p.exists() and p.is_file():
                chunk_status = self.search_engine.is_file_chunked(p)
                if is_update and chunk_status.get("is_chunked"):
                    logger.info(f"Detected modification in indexed file: {p.name} - updating index...")
                    self.search_engine.index_file(p, force=True)
                elif self.auto_chunk:
                    logger.info(f"Auto-chunking new file: {p.name} into StorageSense...")
                    self.search_engine.index_file(p)
        except Exception as e:
            logger.warning(f"Watcher error for {p.name}: {e}")

    def on_created(self, event):
        if event.is_directory:
            return
        p = Path(event.src_path)
        if self._is_ignored(p):
            return
        if p.suffix.lower() in SUPPORTED_EXTENSIONS:
            if not self._should_process(str(p)):
                return
            self._executor.submit(self._process_file, p, False)

    def on_modified(self, event):
        if event.is_directory:
            return
        p = Path(event.src_path)
        if self._is_ignored(p):
            return
        if p.suffix.lower() in SUPPORTED_EXTENSIONS:
            if not self._should_process(str(p)):
                return
            self._executor.submit(self._process_file, p, True)

    def on_deleted(self, event):
        if event.is_directory:
            return
        p = Path(event.src_path)
        if self._is_ignored(p):
            return
        if p.suffix.lower() in SUPPORTED_EXTENSIONS:
            try:
                logger.info(f"Detected deletion of: {p.name} - removing from index...")
                self.search_engine.remove_file(str(p))
            except Exception as e:
                logger.warning(f"Error removing {p.name} from index: {e}")

class StorageFolderWatcher:
    def __init__(self, search_engine: HybridSearchEngine):
        self.search_engine = search_engine
        self.observer = Observer()
        self.handler = StorageChangeHandler(self.search_engine)
        self.watched_paths = set()

    def watch_folder(self, folder_path: Path):
        try:
            p = Path(folder_path).resolve()
            path_str = str(p).lower()
            if any(ign in path_str for ign in IGNORED_PATH_PATTERNS):
                logger.info(f"Refusing to watch internal/codebase directory: {p}")
                return
            if p.exists() and p.is_dir() and str(p) not in self.watched_paths:
                self.observer.schedule(self.handler, path=str(p), recursive=True)
                self.watched_paths.add(str(p))
                logger.info(f"Autonomous watcher monitoring: {p} (recursive)")
        except Exception as e:
            logger.warning(f"Could not attach watcher to {folder_path}: {e}")

    def start(self):
        try:
            if not self.observer.is_alive():
                self.observer.daemon = True
                self.observer.start()
                logger.info("Background filesystem observer started.")
        except Exception as e:
            logger.warning(f"Failed to start observer: {e}")

    def stop(self):
        try:
            if hasattr(self, "handler") and hasattr(self.handler, "_executor"):
                self.handler._executor.shutdown(wait=False, cancel_futures=True)
            if self.observer.is_alive():
                self.observer.stop()
                self.observer.join(timeout=2.0)
        except Exception as e_stop:
            logger.debug(f"Error during folder watcher shutdown: {e_stop}")

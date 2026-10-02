import os
import json
import shutil
from pathlib import Path
from typing import Dict, Any, Tuple, Optional

from core.logger import setup_logger

logger = setup_logger("WorkspaceManager")

# Default fallback workspace directory (preserving exact current PC path)
DEFAULT_WORKSPACE_DIR = Path("F:/ASCENT/idea/pc_port").resolve()

# Configuration file location: user home ~/.storagesense/config.json
USER_CONFIG_DIR = Path.home() / ".storagesense"
USER_CONFIG_FILE = USER_CONFIG_DIR / "config.json"
LOCAL_CONFIG_FILE = DEFAULT_WORKSPACE_DIR / "workspace_config.json"


def _get_dir_size(path: Path) -> int:
    """Calculates total size of all files in a directory in bytes."""
    total = 0
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    try:
        for entry in os.scandir(str(path)):
            if entry.is_file(follow_symlinks=False):
                total += entry.stat().st_size
            elif entry.is_dir(follow_symlinks=False):
                total += _get_dir_size(Path(entry.path))
    except Exception as e:
        logger.debug(f"Error calculating dir size for {path}: {e}")
    return total


class WorkspaceManager:
    r"""
    Manages dynamic StorageSense workspace and storage directories.
    Enables user-configurable storage of databases, ChromaDB vector stores,
    .trash sandboxes, and logs, while maintaining the default F:\ASCENT\idea\pc_port.
    """
    _instance: Optional["WorkspaceManager"] = None

    def __new__(cls, *args, **kwargs):
        if not cls._instance:
            cls._instance = super(WorkspaceManager, cls).__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if getattr(self, "_initialized", False):
            return
        self._initialized = True
        self.config_file = self._resolve_config_file()
        self._load_config()

    def _resolve_config_file(self) -> Path:
        """Determines the active config file path, creating parent dirs if needed."""
        try:
            USER_CONFIG_DIR.mkdir(parents=True, exist_ok=True)
            return USER_CONFIG_FILE
        except Exception as e:
            logger.debug(f"Could not use USER_CONFIG_DIR, falling back to LOCAL_CONFIG_FILE: {e}")
            return LOCAL_CONFIG_FILE

    def _load_config(self):
        """Loads workspace configuration from disk with fail-safe defaults."""
        self.workspace_dir = DEFAULT_WORKSPACE_DIR

        if self.config_file.exists():
            try:
                with open(self.config_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    ws = data.get("workspace_dir")
                    if ws and Path(ws).exists():
                        self.workspace_dir = Path(ws).resolve()
                    elif ws:
                        # Allow path if parent drive exists
                        p = Path(ws).resolve()
                        if Path(p.anchor).exists():
                            p.mkdir(parents=True, exist_ok=True)
                            self.workspace_dir = p
            except Exception as e:
                logger.debug(f"Failed loading workspace config from {self.config_file}: {e}")
                self.workspace_dir = DEFAULT_WORKSPACE_DIR

        self._update_paths()

    def _update_paths(self):
        """Updates internal path mappings based on the active workspace directory."""
        self.db_path = self.workspace_dir / "storagesense.db"
        self.chroma_dir = self.workspace_dir / "chroma_db"
        self.trash_dir = self.workspace_dir / ".trash"
        self.log_dir = self.workspace_dir / "logs"
        self.cache_dir = self.workspace_dir / ".cache"

        # Ensure core subdirectories exist
        for d in [self.chroma_dir, self.trash_dir, self.log_dir, self.cache_dir]:
            try:
                d.mkdir(parents=True, exist_ok=True)
            except Exception as e:
                logger.warning(f"Failed to create workspace directory {d}: {e}")

    def get_workspace_dir(self) -> Path:
        """Returns the current active workspace directory."""
        return self.workspace_dir

    def get_workspace_config(self) -> Dict[str, Any]:
        """Returns full configuration dictionary."""
        return {
            "workspace_dir": str(self.workspace_dir),
            "db_path": str(self.db_path),
            "chroma_dir": str(self.chroma_dir),
            "trash_dir": str(self.trash_dir),
            "log_dir": str(self.log_dir),
            "cache_dir": str(self.cache_dir),
            "workspace_drive": self.workspace_dir.anchor
        }

    def get_workspace_stats(self) -> Dict[str, Any]:
        """Calculates storage sizes of all active database and chunk components."""
        db_size = self.db_path.stat().st_size if self.db_path.exists() else 0
        chroma_size = _get_dir_size(self.chroma_dir)
        trash_size = _get_dir_size(self.trash_dir)
        log_size = _get_dir_size(self.log_dir)
        total_used = db_size + chroma_size + trash_size + log_size

        free_bytes = 0
        total_bytes = 0
        try:
            drive_usage = shutil.disk_usage(str(self.workspace_dir))
            free_bytes = drive_usage.free
            total_bytes = drive_usage.total
        except Exception as e:
            logger.debug(f"Failed getting drive usage for {self.workspace_dir}: {e}")

        return {
            "workspace_dir": str(self.workspace_dir),
            "workspace_drive": self.workspace_dir.anchor,
            "db_path": str(self.db_path),
            "db_size_bytes": db_size,
            "chroma_dir": str(self.chroma_dir),
            "chroma_size_bytes": chroma_size,
            "trash_dir": str(self.trash_dir),
            "trash_size_bytes": trash_size,
            "log_dir": str(self.log_dir),
            "log_size_bytes": log_size,
            "total_workspace_size_bytes": total_used,
            "drive_free_bytes": free_bytes,
            "drive_total_bytes": total_bytes
        }

    def set_workspace_dir(self, new_dir: str | Path, migrate_files: bool = False) -> Tuple[bool, str]:
        """
        Switches the active workspace directory to a new location.
        Optionally migrates existing database, vector store, and trash files.
        """
        try:
            target_path = Path(new_dir).resolve()

            # Verify drive anchor exists
            if not Path(target_path.anchor).exists():
                return False, f"Target drive '{target_path.anchor}' is not accessible or does not exist."

            # Ensure directory exists or can be created
            target_path.mkdir(parents=True, exist_ok=True)

            # Check available disk space
            try:
                usage = shutil.disk_usage(str(target_path))
                if usage.free < 100 * 1024 * 1024:  # Require at least 100MB
                    return False, f"Target disk has insufficient free space ({usage.free / (1024*1024):.1f} MB available)."
            except Exception as e:
                logger.debug(f"Could not check disk usage for {target_path}: {e}")

            old_dir = self.workspace_dir

            if migrate_files and old_dir != target_path:
                # Migrate database
                old_db = old_dir / "storagesense.db"
                new_db = target_path / "storagesense.db"
                if old_db.exists() and not new_db.exists():
                    shutil.copy2(str(old_db), str(new_db))

                # Migrate Chroma vector store
                old_chroma = old_dir / "chroma_db"
                new_chroma = target_path / "chroma_db"
                if old_chroma.exists() and not new_chroma.exists():
                    shutil.copytree(str(old_chroma), str(new_chroma))

                # Migrate trash sandbox
                old_trash = old_dir / ".trash"
                new_trash = target_path / ".trash"
                if old_trash.exists() and not new_trash.exists():
                    shutil.copytree(str(old_trash), str(new_trash))

            self.workspace_dir = target_path
            self._update_paths()

            # Save configuration to disk
            cfg_data = {
                "workspace_dir": str(self.workspace_dir),
                "updated_at": str(Path(__file__).stat().st_mtime)
            }
            with open(self.config_file, "w", encoding="utf-8") as f:
                json.dump(cfg_data, f, indent=2)

            return True, f"Workspace successfully switched to {self.workspace_dir}"
        except Exception as e:
            return False, f"Failed to switch workspace: {str(e)}"

    def reset_to_default(self) -> Tuple[bool, str]:
        r"""Resets workspace directory back to the default F:\ASCENT\idea\pc_port."""
        return self.set_workspace_dir(DEFAULT_WORKSPACE_DIR, migrate_files=False)


# Module singleton instance
workspace_mgr = WorkspaceManager()

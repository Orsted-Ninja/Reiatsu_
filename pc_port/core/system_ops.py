import os
import subprocess
import sys
import psutil
from pathlib import Path
from typing import Dict, Any, Optional
from config import BASE_DIR, WORKSPACE_DRIVE
from core.logger import setup_logger

logger = setup_logger("SystemOps")

def open_file_in_os(file_path: str) -> bool:
    """
    Launches the file in its default Windows application (Adobe Acrobat, Word, Photos, etc.)
    with interactive foreground window priority.
    """
    path = Path(file_path).resolve()
    if not path.exists():
        logger.error(f"Cannot open non-existent file: {path}")
        return False
    try:
        if sys.platform == "win32":
            norm_path = os.path.normpath(str(path))
            if path.is_dir():
                try:
                    os.startfile(norm_path)
                    return True
                except Exception as e_start:
                    logger.debug(f"Direct os.startfile for dir failed: {e_start}")
                    proc = subprocess.Popen(f'cmd.exe /c start "" "{norm_path}"', shell=True)
                    try:
                        proc.wait(timeout=1.0)
                    except subprocess.TimeoutExpired:
                        pass  # Target program launched and detached
                    except Exception as e_wait:
                        logger.debug(f"Process wait warning: {e_wait}")
                    return True

            # Tier 1: cmd.exe /c start "" "<norm_path>"
            # The Windows Shell command interpreter gives the launched application interactive foreground focus
            try:
                proc = subprocess.Popen(f'cmd.exe /c start "" "{norm_path}"', shell=True)
                try:
                    proc.wait(timeout=1.0)
                except subprocess.TimeoutExpired:
                    pass  # Target program launched and detached
                except Exception as e_wait:
                    logger.debug(f"Process wait warning: {e_wait}")
                logger.info(f"Successfully launched file via Windows Shell: {norm_path}")
                return True
            except Exception as e_cmd:
                logger.warning(f"cmd start failed for {norm_path}: {e_cmd}. Trying os.startfile...")

            # Tier 2: Native os.startfile fallback
            try:
                os.startfile(norm_path)
                logger.info(f"Successfully launched file via os.startfile: {norm_path}")
                return True
            except Exception as e_start:
                logger.warning(f"os.startfile failed for {norm_path}: {e_start}. Trying PowerShell fallback...")

            # Tier 3: PowerShell Start-Process
            try:
                ps_cmd = f'powershell.exe -NoProfile -NonInteractive -Command "Start-Process -FilePath \'{norm_path}\'"'
                p = subprocess.Popen(ps_cmd, shell=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                try:
                    p.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    pass  # Process started and running independently
                except Exception as e_wait:
                    logger.debug(f"PowerShell process wait warning: {e_wait}")
                logger.info(f"Successfully launched file via PowerShell: {norm_path}")
                return True
            except Exception as e_ps:
                logger.warning(f"PowerShell launch failed: {e_ps}")

            # Tier 4: Reveal in Explorer so user can open directly
            return show_in_explorer(file_path)
        else:
            subprocess.run(["xdg-open", str(path)], check=True)
            return True
    except Exception as e:
        logger.error(f"Failed to open file in OS: {e}")
        return False

def show_in_explorer(file_path: str) -> bool:
    """
    Opens Windows File Explorer with the target file highlighted or opens the directory.
    Guarantees correct unquoted /select, switch with quoted path.
    """
    path = Path(file_path).resolve()
    if not path.exists():
        logger.error(f"Cannot reveal non-existent file: {path}")
        return False
    try:
        if sys.platform == "win32":
            norm_path = os.path.normpath(str(path))
            if path.is_dir():
                try:
                    os.startfile(norm_path)
                    return True
                except Exception as e_dir:
                    logger.debug(f"Direct os.startfile for dir failed: {e_dir}")
                    proc = subprocess.Popen(f'cmd.exe /c start explorer.exe "{norm_path}"', shell=True)
                    try:
                        proc.wait(timeout=1.0)
                    except subprocess.TimeoutExpired:
                        pass  # Explorer started and running independently
                    except Exception as e_wait:
                        logger.debug(f"Process wait warning: {e_wait}")
                    return True

            # Target is a file: highlight it with /select
            # CRITICAL: /select, must be OUTSIDE quotes; only norm_path is quoted
            cmd_select = f'cmd.exe /c start explorer.exe /select,"{norm_path}"'
            try:
                proc = subprocess.Popen(cmd_select, shell=True)
                try:
                    proc.wait(timeout=1.0)
                except subprocess.TimeoutExpired:
                    pass  # Explorer started and running independently
                except Exception as e_wait:
                    logger.debug(f"Process wait warning: {e_wait}")
                logger.info(f"Successfully revealed file in Explorer: {norm_path}")
                return True
            except Exception as e_cmd:
                logger.warning(f"cmd start explorer failed: {e_cmd}. Trying direct os.system...")

            try:
                os.system(f'start explorer.exe /select,"{norm_path}"')
                return True
            except Exception as e_sys:
                logger.warning(f"Direct os.system start explorer failed: {e_sys}")

            # Fallback: Open parent directory directly
            try:
                os.startfile(str(path.parent))
                return True
            except Exception as e_pf:
                logger.warning(f"Fallback open parent directory failed: {e_pf}")
            return False
        else:
            subprocess.run(["open", "-R", str(path)])
            return True
    except Exception as e:
        logger.error(f"Failed to reveal in Explorer: {e}")
        return False

def get_system_telemetry() -> Dict[str, Any]:
    """Returns live hardware metrics for the telemetry bar dynamically based on current workspace drive."""
    vm = psutil.virtual_memory()
    drive_anchor = WORKSPACE_DRIVE or BASE_DIR.anchor
    disk_info = psutil.disk_usage(drive_anchor) if os.path.exists(drive_anchor) else None
    
    return {
        "ram_total_gb": round(vm.total / (1024**3), 2),
        "ram_used_gb": round(vm.used / (1024**3), 2),
        "ram_available_gb": round(vm.available / (1024**3), 2),
        "ram_percent": vm.percent,
        "cpu_percent": psutil.cpu_percent(interval=None),
        "cpu_count": psutil.cpu_count(logical=True),
        "drive_name": drive_anchor,
        "drive_free_gb": round(disk_info.free / (1024**3), 2) if disk_info else 0.0,
        "drive_total_gb": round(disk_info.total / (1024**3), 2) if disk_info else 0.0
    }

def get_standard_user_folders() -> Dict[str, str]:
    """Returns accessible user directories (including OneDrive and drives) for 1-click scanning."""
    home = Path.home()
    onedrive = home / "OneDrive"
    proj_dir = BASE_DIR.parent.resolve()
    folders = {
        "Current Project": str(proj_dir),
        f"Current Project ({proj_dir.anchor.rstrip(os.sep)})": str(proj_dir)
    }

    # Downloads
    dl = home / "Downloads"
    if dl.exists():
        folders[f"Downloads ({dl.anchor.rstrip(os.sep)})"] = str(dl)

    # Documents (OneDrive or regular)
    doc_path = (onedrive / "Documents") if (onedrive / "Documents").exists() else (home / "Documents")
    if doc_path.exists():
        folders[f"Documents ({doc_path.anchor.rstrip(os.sep)})"] = str(doc_path)

    # Desktop (OneDrive or regular)
    dt_path = (onedrive / "Desktop") if (onedrive / "Desktop").exists() else (home / "Desktop")
    if dt_path.exists():
        folders[f"Desktop ({dt_path.anchor.rstrip(os.sep)})"] = str(dt_path)

    # Pictures
    pic_path = (onedrive / "Pictures") if (onedrive / "Pictures").exists() else (home / "Pictures")
    if pic_path.exists():
        folders[f"Pictures ({pic_path.anchor.rstrip(os.sep)})"] = str(pic_path)

    return {name: path for name, path in folders.items() if Path(path).exists()}

def get_available_drives() -> Dict[str, str]:
    """Returns all available drive letters on the system."""
    drives = {}
    for part in psutil.disk_partitions(all=False):
        if part.mountpoint and os.path.exists(part.mountpoint):
            drives[f"Drive {part.device}"] = part.mountpoint
    return drives

def is_valid_file(file_path: str) -> bool:
    p = Path(file_path)
    return p.exists() and p.is_file()

def find_supported_files(root_dir, extensions: set):
    """Fast, safe directory traversal that prunes cache, git, and virtualenvs in-place."""
    root = Path(root_dir).resolve()
    if not root.exists():
        return []
    if root.is_file():
        if root.suffix.lower() in extensions and not root.name.startswith("~$"):
            return [root]
        return []

    ignore_dirs = {
        ".trash", ".cache", "__pycache__", ".git", ".venv", "venv", "env",
        "node_modules", "AppData", "site-packages", ".idea", ".vscode"
    }

    matched = []
    for dirpath, dirnames, filenames in os.walk(str(root)):
        dirnames[:] = [d for d in dirnames if d not in ignore_dirs and not d.startswith(".")]
        for f in filenames:
            if f.startswith("~$"):
                continue
            ext = os.path.splitext(f)[1].lower()
            if ext in extensions:
                matched.append(Path(dirpath) / f)
    return matched


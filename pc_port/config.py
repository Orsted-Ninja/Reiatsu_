import os
from pathlib import Path

# MUST BE CALLED BEFORE ANY HEAVY MODULES (like requests/urllib3/tempfile)
# to strictly guarantee zero writes to C: drive by patching sys.env["TEMP"]
from core.workspace_manager import workspace_mgr
BASE_DIR = workspace_mgr.get_workspace_dir()

# MUST BE CALLED BEFORE ANY HEAVY MODULES (like requests/urllib3/tempfile)
# to strictly guarantee zero writes to C: drive by patching sys.env["TEMP"]
from core.env_guard import setup_f_drive_isolation
setup_f_drive_isolation(str(BASE_DIR))

import requests  # Safe to import now

# Reclaimer Limits
LARGE_FILE_THRESHOLD_BYTES = 50 * 1024 * 1024  # 50 MB

# Dynamic Workspace & Storage Paths
WORKSPACE_DRIVE = workspace_mgr.workspace_dir.anchor
DB_PATH = workspace_mgr.db_path
CHROMA_DIR = workspace_mgr.chroma_dir
TRASH_DIR = workspace_mgr.trash_dir
LOG_DIR = workspace_mgr.log_dir


def detect_ollama_endpoint() -> str:
    """Dynamically probes candidate endpoints and returns the active one."""
    raw_host = os.environ.get("OLLAMA_HOST", "")
    candidates = []
    
    if raw_host:
        clean = raw_host if (raw_host.startswith("http://") or raw_host.startswith("https://")) else f"http://{raw_host}"
        if "0.0.0.0" in clean:
            clean = clean.replace("0.0.0.0", "127.0.0.1")
        candidates.append(clean)

    candidates.extend(["http://127.0.0.1:11434", "http://localhost:11434"])

    for c in candidates:
        try:
            r = requests.get(f"{c}/api/tags", timeout=1.0)
            if r.status_code == 200:
                return c
        except Exception:
            continue

    return "http://localhost:11434"

OLLAMA_BASE_URL = detect_ollama_endpoint()

# Model defaults (dynamically refreshed by LLMAgent upon connection)
DEFAULT_MODEL = "auto"
FALLBACK_MODEL = "gemma2:2b"

# Memory Optimization for 8 GB RAM / Dual-core Core i3
OLLAMA_NUM_CTX = 2048
OLLAMA_KEEP_ALIVE = "2m"
OLLAMA_TIMEOUT = 120

# Embedding Configuration
EMBEDDING_MODEL_NAME = "all-MiniLM-L6-v2"
EMBEDDING_DIM = 384
CHUNK_SIZE_TOKENS = 350
CHUNK_OVERLAP_TOKENS = 70

# File Types and Extensions
TEXT_EXTENSIONS = {".txt", ".md", ".csv", ".json", ".log", ".py", ".java", ".kt", ".c", ".cpp"}
DOCUMENT_EXTENSIONS = {".pdf", ".docx", ".pptx"} | TEXT_EXTENSIONS
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}
SUPPORTED_EXTENSIONS = DOCUMENT_EXTENSIONS | IMAGE_EXTENSIONS

# Protected Files for Space Reclaimer (Never delete automatically)
PROTECTED_KEYWORDS = [
    "resume", "cv", "passport", "tax", "invoice", "aadhar", "license",
    "certificate", "identity", "id_card", "statement", "w2", "pan"
]

# Stale installer extensions
INSTALLER_EXTENSIONS = {".exe", ".msi", ".dmg", ".pkg", ".iso", ".zip", ".tar", ".gz"}

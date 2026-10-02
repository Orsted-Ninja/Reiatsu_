import os
import sys
from pathlib import Path

def setup_f_drive_isolation(base_dir: str):
    """
    Enforces all AI libraries, model downloads, and temporary files to write exclusively
    within the project workspace, preventing exhaustion of limited C: drive space.
    Dynamically respects the workspace drive anchor.
    """
    try:
        base_path = Path(base_dir).resolve()
        cache_dir = base_path / ".cache"
        hf_cache = cache_dir / "huggingface"
        torch_cache = cache_dir / "torch"
        tmp_cache = cache_dir / "tmp"

        for p in [cache_dir, hf_cache, torch_cache, tmp_cache]:
            p.mkdir(parents=True, exist_ok=True)

        # Force AI libraries to cache strictly inside project .cache
        os.environ["HF_HOME"] = str(hf_cache)
        os.environ["SENTENCE_TRANSFORMERS_HOME"] = str(hf_cache)
        os.environ["TORCH_HOME"] = str(torch_cache)
        os.environ["TEMP"] = str(tmp_cache)
        os.environ["TMP"] = str(tmp_cache)

        # If OLLAMA_MODELS is not already set in environment, check if an ollama directory
        # exists on the current drive root (e.g. F:\ollama) and assign it safely
        if "OLLAMA_MODELS" not in os.environ:
            if base_path.anchor and Path(base_path.anchor).exists():
                drive_root = Path(base_path.anchor)
                candidate_ollama = drive_root / "ollama"
                if candidate_ollama.exists() and candidate_ollama.is_dir():
                    os.environ["OLLAMA_MODELS"] = str(candidate_ollama)
    except Exception as e:
        sys.stderr.write(f"Warning setting up environment isolation: {e}\n")

_current_dir = Path(__file__).resolve().parent.parent
setup_f_drive_isolation(str(_current_dir))

def get_f_drive_tmp() -> str:
    """Returns the isolated workspace tmp directory to guarantee zero writes to C: drive."""
    tmp_path = _current_dir / ".cache" / "tmp"
    tmp_path.mkdir(parents=True, exist_ok=True)
    return str(tmp_path.resolve())


import hashlib
from pathlib import Path

def compute_file_hash(file_path: Path, block_size: int = 65536) -> str:
    """
    Computes SHA-256. For files under 10 MB, computes full hash.
    For files >= 10 MB, hashes first 1MB + last 1MB + file size for speed.
    Gracefully handles locked or offline OneDrive placeholder files.
    """
    path = Path(file_path)
    try:
        stat = path.stat()
        file_size = stat.st_size
    except Exception:
        return hashlib.sha256(str(path).encode()).hexdigest()

    hasher = hashlib.sha256()

    try:
        if file_size < 10 * 1024 * 1024:
            with open(path, "rb") as f:
                while chunk := f.read(block_size):
                    hasher.update(chunk)
        else:
            # Fast composite hash for large files
            with open(path, "rb") as f:
                hasher.update(f.read(1024 * 1024)) # First 1MB
                if file_size > 2 * 1024 * 1024:
                    f.seek(file_size - (1024 * 1024))
                    hasher.update(f.read(1024 * 1024)) # Last 1MB
                hasher.update(str(file_size).encode())
        return hasher.hexdigest()
    except OSError:
        # Fallback for locked files or offline OneDrive reparse points
        fallback_str = f"{path.name}_{file_size}_{stat.st_mtime}"
        return hashlib.sha256(fallback_str.encode()).hexdigest()


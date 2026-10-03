#!/usr/bin/env python3
"""
StorageSense PC Port - Release Packager
Compiles the native executable and creates a clean, distributable ZIP package
ready for GitHub Releases.
"""

import os
import sys
import shutil
import zipfile
import hashlib
from pathlib import Path

EXCLUDE_DIRS = {
    ".cache",
    ".trash",
    ".venv",
    "venv",
    "env",
    "__pycache__",
    "chroma_db",
    "logs",
    "dist"
}

EXCLUDE_EXTS = {
    ".pyc",
    ".pyo",
    ".db",
    ".sqlite",
    ".sqlite3",
    ".log"
}

def compute_sha256(filepath: Path) -> str:
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

def main():
    script_dir = Path(__file__).resolve().parent
    dist_dir = script_dir / "dist"
    dist_dir.mkdir(parents=True, exist_ok=True)

    print("=" * 60)
    print("StorageSense PC Port - Packaging Release")
    print("=" * 60)

    # 1. Compile fresh StorageSense.exe
    build_script = script_dir / "build_exe.py"
    if build_script.exists():
        import subprocess
        print("Compiling fresh StorageSense.exe...")
        res = subprocess.run([sys.executable, str(build_script)])
        if res.returncode != 0:
            print("[ERROR] Failed to compile StorageSense.exe")
            sys.exit(1)

    zip_name = "StorageSense-PC-Port-v1.0.zip"
    zip_path = dist_dir / zip_name

    print(f"\nCreating release archive: {zip_path} ...")
    
    file_count = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, dirs, files in os.walk(script_dir):
            dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
            rel_root = Path(root).relative_to(script_dir)
            
            for file in files:
                p = Path(root) / file
                if p.suffix in EXCLUDE_EXTS:
                    continue
                if p == zip_path:
                    continue
                
                arcname = Path("StorageSense-PC-Port") / rel_root / file
                zf.write(p, str(arcname))
                file_count += 1

    size_mb = zip_path.stat().st_size / (1024 * 1024)
    sha256 = compute_sha256(zip_path)

    print("\n[SUCCESS] Release archive created successfully!")
    print(f"  Archive:  {zip_path}")
    print(f"  Files:    {file_count} files bundled")
    print(f"  Size:     {size_mb:.2f} MB")
    print(f"  SHA-256:  {sha256}")
    print("=" * 60)

if __name__ == "__main__":
    main()

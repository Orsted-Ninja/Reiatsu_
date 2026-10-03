import subprocess
import sys
import os
from pathlib import Path

if sys.platform == "win32":
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, OSError) as e_enc:
        _ = e_enc  # Fallback for non-interactive Windows consoles

BASE_DIR = Path(__file__).resolve().parent

def main():
    ui_script = BASE_DIR / "ui" / "app_streamlit.py"
    print("=" * 60)
    print("StorageSense - Local Agentic Storage Intelligence (PC Port)")
    print(f"Working Directory: {BASE_DIR}")
    print("Zero Cloud, 100% On-Device AI")
    print("=" * 60)
    print("\nStarting Desktop Web Dashboard on http://localhost:8501 ...")

    cmd = [
        sys.executable,
        "-m", "streamlit", "run",
        str(ui_script),
        "--server.port=8501",
        "--server.headless=false",
        "--server.fileWatcherType=none",
        "--browser.gatherUsageStats=false"
    ]

    try:
        proc = subprocess.run(cmd)
        sys.exit(proc.returncode)
    except KeyboardInterrupt:
        print("\nStorageSense shutdown cleanly.")
        sys.exit(0)

if __name__ == "__main__":
    main()

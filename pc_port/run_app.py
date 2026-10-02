import subprocess
import sys
import os
import time
import webbrowser
import threading
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

def open_browser_delayed(url: str, delay_seconds: float = 1.2):
    def _open():
        time.sleep(delay_seconds)
        try:
            webbrowser.open(url)
        except Exception:
            pass
    threading.Thread(target=_open, daemon=True).start()

def main():
    print("=" * 65)
    print("🧠 StorageSense — Obsidian Vault Desktop Intelligence (PC Port)")
    print(f"Working Directory: {BASE_DIR}")
    print("Zero Cloud · 100% On-Device AI · Air-Gapped Privacy")
    print("=" * 65)

    if "--streamlit" in sys.argv:
        ui_script = BASE_DIR / "ui" / "app_streamlit.py"
        print("\nStarting Legacy Streamlit Dashboard on http://localhost:8501 ...")
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
    else:
        url = "http://localhost:8501"
        print(f"\n🚀 Starting Obsidian Vault Desktop Web Dashboard on {url} ...")
        open_browser_delayed(url)

        import uvicorn
        try:
            # Change directory to BASE_DIR so imports resolve seamlessly
            os.chdir(str(BASE_DIR))
            uvicorn.run(
                "ui.server:app",
                host="127.0.0.1",
                port=8501,
                log_level="info"
            )
        except KeyboardInterrupt:
            print("\nStorageSense shutdown cleanly.")
            sys.exit(0)

if __name__ == "__main__":
    main()

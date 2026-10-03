import os
import sys
import json
import urllib.request
import urllib.error
from pathlib import Path

def get_github_token():
    try:
        import subprocess
        proc = subprocess.Popen(
            ["git", "credential-manager", "get"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        stdout, _ = proc.communicate(input="protocol=https\nhost=github.com\n\n")
        for line in stdout.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception as e:
        print(f"Error querying credential manager: {e}")
    return os.environ.get("GITHUB_TOKEN", "")

def make_request(url, method="GET", headers=None, data=None):
    if headers is None:
        headers = {}
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def upload_asset(upload_url, token, file_path: Path, asset_name: str, content_type: str, repo: str, release_id: int, headers: dict):
    if not file_path.exists():
        print(f"ERROR: File not found: {file_path}")
        return False

    file_size = file_path.stat().st_size
    print(f"\nProcessing upload for {asset_name} ({file_size / (1024*1024):.2f} MB)...")

    # 1. Check existing assets and remove duplicate
    assets_url = f"https://api.github.com/repos/{repo}/releases/{release_id}/assets"
    status, body = make_request(assets_url, headers=headers)
    if status == 200:
        assets = json.loads(body.decode("utf-8"))
        for asset in assets:
            if asset.get("name") == asset_name:
                del_url = f"https://api.github.com/repos/{repo}/releases/assets/{asset['id']}"
                print(f"Deleting existing asset {asset['name']} (ID: {asset['id']})...")
                del_status, _ = make_request(del_url, method="DELETE", headers=headers)
                print(f"Delete status: {del_status}")

    # 2. Upload asset
    endpoint = f"{upload_url}?name={asset_name}"
    upload_headers = {
        "Authorization": f"token {token}",
        "Content-Type": content_type,
        "User-Agent": "Reiatsu-Release-Publisher",
        "Content-Length": str(file_size)
    }

    with open(file_path, "rb") as f:
        file_bytes = f.read()

    print(f"Uploading {asset_name} to GitHub Releases...")
    status, body = make_request(endpoint, method="POST", headers=upload_headers, data=file_bytes)
    print(f"Upload status: {status}")
    if status in (200, 201):
        resp_data = json.loads(body.decode("utf-8"))
        print(f"SUCCESS! {asset_name} Download URL: {resp_data.get('browser_download_url')}")
        return True
    else:
        print(f"FAILED to upload {asset_name}: {status} - {body.decode('utf-8', errors='ignore')}")
        return False

def main():
    token = get_github_token()
    if not token:
        print("ERROR: Could not retrieve GitHub token.")
        sys.exit(1)

    repo = "Orsted-Ninja/Reiatsu"
    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "Reiatsu-Release-Publisher"
    }

    # Fetch release v1.0.0
    tag_name = "v1.0.0"
    url = f"https://api.github.com/repos/{repo}/releases/tags/{tag_name}"
    status, body = make_request(url, headers=headers)
    if status != 200:
        print(f"ERROR: Could not find release for tag {tag_name}: {status}")
        sys.exit(1)

    release_data = json.loads(body.decode("utf-8"))
    release_id = release_data["id"]
    upload_url = release_data["upload_url"].split("{")[0]

    print(f"Found release '{release_data.get('name')}' (ID: {release_id})")

    # Update Release Title & Notes to include PC Port
    release_body = r"""# Reiatsu & StorageSense v1.0.0 — Privacy-First On-Device AI Storage Assistant ⚡

Reiatsu is an intelligent, cross-platform on-device storage assistant for Android and Windows PC operating under a strict **Zero-Cloud, Zero-Network** guarantee.

---

### 📦 Downloads & Releases:

| Platform | Download File | Description |
| :--- | :--- | :--- |
| 📱 **Android** | [`Reiatsu-v1.0.0.apk`](https://github.com/Orsted-Ninja/Reiatsu/releases/download/v1.0.0/Reiatsu-v1.0.0.apk) | Standalone APK for Android (API 26+) with embedded OpenCV SFace face recognition, Gemma 4 E2B LLM, and hybrid search. |
| 💻 **Windows PC** | [`StorageSense.exe`](https://github.com/Orsted-Ninja/Reiatsu/releases/download/v1.0.0/StorageSense.exe) | **Native Desktop Executable (146 KB)**. Double-click to run in dedicated Chromium/Edge App Mode with system tray controls. |
| 💻 **Windows PC (Bundle)** | [`StorageSense-PC-Port-v1.0.zip`](https://github.com/Orsted-Ninja/Reiatsu/releases/download/v1.0.0/StorageSense-PC-Port-v1.0.zip) | Complete standalone PC Port distribution including `StorageSense.exe`, desktop shortcut creator, CLI tools, batch loaders, and full guides. |

---

### 🚀 Key Features:

#### 💻 Windows Desktop (StorageSense):
- **Native Desktop App Window**: Runs in isolated Chromium/Edge App Mode (`--app=http://localhost:8501`) with no browser address bars or tabs.
- **Process Lifecycle & Win32 Job Object**: Child server processes are automatically tracked by the Windows kernel—clean shutdown with zero orphaned background processes.
- **System Tray Controls**: Direct access near the Windows clock to view live application logs, restart the AI engine, or exit cleanly.
- **Dual-Tier Autonomous Retrieval**: SQLite FTS5 BM25 lexical matching + ChromaDB dense vector semantic search + JIT Live Deep Scanner.
- **Grounded AI Summaries**: Token-by-token streaming local digests via Ollama (`gemma4:e4b`, `llama3.2`) with grounded extractive offline fallback.
- **Multi-Modal Deduplication**: Exact cryptographic SHA-256 deduplication, semantic cosine centroid clustering ($\ge 0.88$), and perceptual image hashing (`pHash`).
- **Safety Protected Vault**: Heuristic safeguards protecting resumes, IDs, tax records, and certificates with two-phase `.trash` staging and 1-click restore.

#### 📱 Android Edition (Reiatsu):
- **On-Device Face Recognition & Clustering**: Embedded OpenCV SFace INT8 ONNX neural network for 128-D face embeddings + Google Photos-style face clustering.
- **On-Device LLM Inference**: MediaPipe Tasks GenAI engine running Gemma 4 E2B and Gemma 2B IT with Adreno GPU acceleration.
- **Offline ML Kit Vision**: Photo classification into People, Vehicles, Food, Text, and Screenshots.
- **Sub-Millisecond Lexical & Dense Search**: SQLite FTS4 Porter stemmer + 384-D `all-MiniLM-L6-v2` dense vectors merged via Reciprocal Rank Fusion (RRF, $k=60$).
"""

    update_payload = {
        "name": "Reiatsu & StorageSense v1.0.0 — Cross-Platform AI Storage Assistant (Android & PC)",
        "body": release_body,
        "target_commitish": "main"
    }
    patch_url = f"https://api.github.com/repos/{repo}/releases/{release_id}"
    patch_status, patch_body = make_request(
        patch_url,
        method="PATCH",
        headers={**headers, "Content-Type": "application/json"},
        data=json.dumps(update_payload).encode("utf-8")
    )
    print(f"Update release metadata status: {patch_status}")

    # Upload Assets
    repo_root = Path(__file__).resolve().parent.parent
    exe_path = repo_root / "pc_port" / "StorageSense.exe"
    zip_path = repo_root / "pc_port" / "dist" / "StorageSense-PC-Port-v1.0.zip"

    ok_exe = upload_asset(
        upload_url,
        token,
        exe_path,
        "StorageSense.exe",
        "application/vnd.microsoft.portable-executable",
        repo,
        release_id,
        headers
    )

    ok_zip = upload_asset(
        upload_url,
        token,
        zip_path,
        "StorageSense-PC-Port-v1.0.zip",
        "application/zip",
        repo,
        release_id,
        headers
    )

    if ok_exe and ok_zip:
        print("\n============================================================")
        print("ALL PC PORT ASSETS SUCCESSFULLY PUBLISHED TO GITHUB RELEASE!")
        print(f"Release URL: {release_data.get('html_url')}")
        print("============================================================")
    else:
        print("\n[WARNING] Some assets failed to upload.")
        sys.exit(1)

if __name__ == "__main__":
    main()

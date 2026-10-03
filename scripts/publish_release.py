import os
import sys
import json
import urllib.request
import urllib.error
import subprocess

def get_github_token():
    # Try git credential-manager
    try:
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

    # 1. Check existing releases
    url = f"https://api.github.com/repos/{repo}/releases"
    status, body = make_request(url, headers=headers)
    print(f"List releases status: {status}")
    if status != 200:
        print(f"Failed to fetch releases: {body.decode('utf-8', errors='ignore')}")
        sys.exit(1)

    releases = json.loads(body.decode("utf-8"))
    tag_name = "v1.0.0"
    existing_release = None
    for r in releases:
        if r.get("tag_name") == tag_name:
            existing_release = r
            break

    release_body = """# Reiatsu v1.0.0 — Privacy-First On-Device AI Storage Assistant ⚡

Reiatsu is an intelligent on-device storage assistant for Android operating under a strict **Zero-Cloud, Zero-Network** guarantee (`android.permission.INTERNET` is completely omitted).

### 🚀 Key Features in this Release:
- **On-Device Face Recognition & Clustering**:
  - Embedded **OpenCV SFace INT8 ONNX** neural network for 128-D face embeddings.
  - Automatic Google Photos-style face clustering and People gallery with custom naming and avatar bubbles.
- **On-Device LLM & Summarization**:
  - MediaPipe Tasks GenAI engine supporting **Gemma 4 E2B** and **Gemma 2B IT** with Adreno GPU acceleration.
  - Executive Multi-Section Document Digests (Overview, Key Concepts, Main Takeaways).
- **Hybrid Search Engine**:
  - Sub-millisecond SQLite FTS4 lexical matching (Porter stemmer) + 384-D `all-MiniLM-L6-v2` dense vector semantic search merged via Reciprocal Rank Fusion (RRF, $k=60$).
- **Smart Photo Classification**:
  - Categorizes photos into *People*, *Vehicles*, *Food*, *Text*, and *Screenshots* offline via Google ML Kit Vision.
- **Safety Protections & Reversible Vault**:
  - Full SHA-256 cryptographic deduplication.
  - Automated safeguards protecting tax, passport, resume, and identity documents.
  - Two-phase staging trash with instant 1-tap rollbacks.

### 📦 Installation Instructions:
1. Download `Reiatsu-v1.0.0.apk` below.
2. Install the APK on your Android device (Android 8.0+ / API 26+).
3. Grant **All files access** (`MANAGE_EXTERNAL_STORAGE`) when prompted to allow on-device indexing.
4. *(Optional)* Place Gemma `.litertlm` or `.bin` models into `/sdcard/StorageSense/models/` for full LLM generative capabilities.
"""

    if existing_release:
        release_id = existing_release["id"]
        upload_url = existing_release["upload_url"].split("{")[0]
        print(f"Using existing release {tag_name} (ID: {release_id})")
    else:
        create_payload = {
            "tag_name": tag_name,
            "target_commitish": "main",
            "name": "Reiatsu v1.0.0 — On-Device AI Storage Assistant",
            "body": release_body,
            "draft": False,
            "prerelease": False
        }
        status, body = make_request(
            url,
            method="POST",
            headers={**headers, "Content-Type": "application/json"},
            data=json.dumps(create_payload).encode("utf-8")
        )
        if status not in (200, 201):
            print(f"Failed to create release: {status} - {body.decode('utf-8', errors='ignore')}")
            sys.exit(1)
        created_data = json.loads(body.decode("utf-8"))
        release_id = created_data["id"]
        upload_url = created_data["upload_url"].split("{")[0]
        print(f"Created release {tag_name} (ID: {release_id})")

    # Locate APK
    apk_path = os.path.abspath("app/build/outputs/apk/debug/app-debug.apk")
    if not os.path.exists(apk_path):
        print(f"ERROR: APK not found at {apk_path}")
        sys.exit(1)

    apk_size = os.path.getsize(apk_path)
    asset_name = "Reiatsu-v1.0.0.apk"
    print(f"Uploading APK: {apk_path} ({apk_size / (1024*1024):.2f} MB) as {asset_name}...")

    # Check if asset already exists and delete it first if so
    assets_url = f"https://api.github.com/repos/{repo}/releases/{release_id}/assets"
    status, body = make_request(assets_url, headers=headers)
    if status == 200:
        assets = json.loads(body.decode("utf-8"))
        for asset in assets:
            if asset.get("name") == asset_name:
                del_url = f"https://api.github.com/repos/{repo}/releases/assets/{asset['id']}"
                print(f"Deleting existing asset {asset['name']} (ID: {asset['id']})...")
                make_request(del_url, method="DELETE", headers=headers)

    # Upload new asset
    upload_endpoint = f"{upload_url}?name={asset_name}"
    upload_headers = {
        "Authorization": f"token {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "User-Agent": "Reiatsu-Release-Publisher",
        "Content-Length": str(apk_size)
    }

    with open(apk_path, "rb") as f:
        apk_bytes = f.read()

    status, body = make_request(upload_endpoint, method="POST", headers=upload_headers, data=apk_bytes)
    print(f"Upload status: {status}")
    if status in (200, 201):
        resp_data = json.loads(body.decode("utf-8"))
        print(f"SUCCESS! Asset download URL: {resp_data.get('browser_download_url')}")
    else:
        print(f"Upload failed: {status} - {body.decode('utf-8', errors='ignore')}")
        sys.exit(1)

if __name__ == "__main__":
    main()

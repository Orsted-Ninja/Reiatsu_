"""
StorageSense Model Downloader & ADB Push Utility
Downloads on-device Gemma models and pushes them to /sdcard/StorageSense/models/ on the phone.
"""
import os
import sys
import time
import argparse
import subprocess
from pathlib import Path
import urllib.request

STORAGE_PATH_ON_PHONE = "/sdcard/StorageSense/models"

MODELS = {
    "1": {
        "name": "gemma-4-e2b-it.litertlm (HuggingFace, ~2.58 GB, Gemma 4 E2B Instruct - PRIMARY)",
        "filename": "gemma-4-e2b-it.litertlm",
        "url": "https://huggingface.co/bardusco-tau/gemma-4-e2b-it-litertlm/resolve/main/gemma-4-e2b-it.litertlm"
    },
    "2": {
        "name": "gemma-2b-it-gpu-int4.bin (Google MediaPipe INT4 GPU Quantized, ~1.29 GB, SECONDARY FALLBACK)",
        "filename": "gemma-2b-it-gpu-int4.bin",
        "url": "https://huggingface.co/autoocrat0413/gemma-2b-it-gpu-int4-mediapipe/resolve/main/gemma-2b-it-gpu-int4.bin"
    },
    "3": {
        "name": "gemma-4-text-only.litertlm (HuggingFace, ~2.58 GB, Compact Instruct)",
        "filename": "gemma-4-text-only.litertlm",
        "url": "https://huggingface.co/developerabu/gemma-4-e2b-text-only-litertlm/resolve/main/gemma-4-text-only.litertlm"
    }
}

def download_with_resume(url: str, target_path: Path):
    target_path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = target_path.with_suffix(".part")
    
    print(f"[DOWNLOAD] Initiating download to: {target_path}")
    print(f"[SOURCE] {url}")
    
    # 1. Prefer native curl for maximum speed, resume, and progress display
    curl_bin = "curl.exe" if os.name == "nt" else "curl"
    try:
        cmd = [curl_bin, "-L", "-C", "-", "--progress-bar", "-o", str(temp_path), url]
        res = subprocess.run(cmd)
        if res.returncode == 0 and temp_path.exists() and temp_path.stat().st_size > 100_000_000:
            if target_path.exists():
                target_path.unlink()
            temp_path.rename(target_path)
            print(f"\n[SUCCESS] Download completed! ({target_path.stat().st_size / (1024*1024*1024):.2f} GB)")
            return True
    except Exception as e:
        print(f"[CURL] Fallback triggered: {e}")

    # 2. Fallback to requests streaming
    try:
        import requests
        existing = temp_path.stat().st_size if temp_path.exists() else 0
        headers = {"User-Agent": "Mozilla/5.0"}
        if existing > 0:
            headers["Range"] = f"bytes={existing}-"
        
        with requests.get(url, headers=headers, stream=True, timeout=30) as r:
            r.raise_for_status()
            mode = "ab" if existing > 0 else "wb"
            total = int(r.headers.get("content-length", 0)) + existing
            downloaded = existing
            with open(temp_path, mode) as f:
                for chunk in r.iter_content(chunk_size=1024*1024):
                    if chunk:
                        f.write(chunk)
                        downloaded += len(chunk)
                        if total > 0:
                            sys.stdout.write(f"\rDownloading: {downloaded*100.0/total:.1f}% ({downloaded/(1024*1024):.1f}/{total/(1024*1024):.1f} MB)")
                        else:
                            sys.stdout.write(f"\rDownloaded: {downloaded/(1024*1024):.1f} MB")
                        sys.stdout.flush()
        if target_path.exists():
            target_path.unlink()
        temp_path.rename(target_path)
        print(f"\n[SUCCESS] Download completed!")
        return True
    except Exception as e:
        print(f"\n[ERROR] Download failed: {e}")
        return False
        return False

def check_adb_device():
    res = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = [l.strip() for l in res.stdout.splitlines() if l.strip() and not l.startswith("*")]
    devices = [l.split()[0] for l in lines[1:] if "device" in l and not "offline" in l]
    return devices

def push_to_phone(local_file_path: Path):
    print(f"\n[ADB] Checking connected devices...")
    devices = check_adb_device()
    if not devices:
        print("[ADB] Waiting for phone to be recognized via USB debugging...")
        for i in range(5):
            time.sleep(2)
            devices = check_adb_device()
            if devices:
                break
                
    if not devices:
        print("\n[WARNING] No active ADB device found. Please verify:")
        print("  1. Phone USB cable is securely connected to PC.")
        print("  2. USB debugging is ON in Developer options.")
        print("  3. On phone prompt, tap 'Always allow from this computer'.")
        return False

    print(f"[ADB] Found device: {devices[0]}")
    print(f"[ADB] Creating directory {STORAGE_PATH_ON_PHONE} on phone...")
    subprocess.run(["adb", "shell", f"mkdir -p {STORAGE_PATH_ON_PHONE}"], check=False)
    
    print(f"[ADB] Pushing {local_file_path.name} ({local_file_path.stat().st_size / (1024*1024*1024):.2f} GB) to {STORAGE_PATH_ON_PHONE}...")
    push_res = subprocess.run(["adb", "push", str(local_file_path), f"{STORAGE_PATH_ON_PHONE}/{local_file_path.name}"])
    if push_res.returncode == 0:
        print(f"\n[SUCCESS] Model pushed successfully to phone!")
        subprocess.run(["adb", "shell", f"ls -lh {STORAGE_PATH_ON_PHONE}"])
        return True
    else:
        print("\n[ERROR] ADB push failed. Please verify your phone is unlocked.")
        return False

def main():
    parser = argparse.ArgumentParser(description="StorageSense Model Setup")
    parser.add_argument("--auto", action="store_true", help="Download Gemma 4 E2B and push to phone automatically")
    parser.add_argument("--push", action="store_true", help="Push existing downloaded model to phone")
    parser.add_argument("--model", default="1", help="Model choice (1 or 2)")
    args = parser.parse_args()
    
    cache_dir = Path.home() / ".storagesense_models"
    cache_dir.mkdir(parents=True, exist_ok=True)
    
    selected_info = MODELS.get(args.model, MODELS["1"])
    target_file = cache_dir / selected_info["filename"]
    
    if args.auto:
        print("=" * 60)
        print("   StorageSense — Automated Gemma 4 E2B Setup")
        print("=" * 60)
        if not target_file.exists() or target_file.stat().st_size < 1_000_000_000:
            print(f"[INFO] Downloading {selected_info['name']}...")
            ok = download_with_resume(selected_info["url"], target_file)
            if not ok:
                return
        else:
            print(f"[CACHE] Found local cached weights at: {target_file}")
            
        push_to_phone(target_file)
        return
        
    if args.push:
        for f in cache_dir.glob("*.litertlm"):
            if f.stat().st_size > 100_000_000:
                push_to_phone(f)
                return
        for f in cache_dir.glob("*.bin"):
            if f.stat().st_size > 100_000_000:
                push_to_phone(f)
                return
        print("[INFO] No cached model found in ~/.storagesense_models. Run with --auto to download.")
        return

    print("=" * 60)
    print("   StorageSense — On-Device Gemma Model Acquisition")
    print("=" * 60)
    print("Choose model to download and push to phone:")
    for k, v in MODELS.items():
        print(f"  [{k}] {v['name']}")
    print("  [3] Push existing local file from this PC to phone")
    print("  [0] Exit")
    print("=" * 60)
    
    choice = input("Enter choice (1-3): ").strip()
    if choice in MODELS:
        info = MODELS[choice]
        target = cache_dir / info["filename"]
        if not target.exists() or target.stat().st_size < 1_000_000_000:
            download_with_resume(info["url"], target)
        else:
            print(f"\n[CACHE] Model already exists locally at: {target}")
        push_to_phone(target)
    elif choice == "3":
        p = input("Enter full path to local model file on PC: ").strip().strip('"\'')
        if p and Path(p).exists():
            push_to_phone(Path(p))

if __name__ == "__main__":
    main()

import subprocess
import time
import sys
import os
from pathlib import Path

MODEL_FILE = Path(os.path.expanduser("~/.storagesense_models/gemma-2b-it-gpu-int4.bin"))
APK_FILE = Path(r"C:\Users\ASUS\.gradle-ascent-build\app\outputs\apk\debug\app-debug.apk")

print("[AUTO-PUSH] Waiting for phone to connect via ADB...")
sys.stdout.flush()

device_id = None
for i in range(120): # Wait up to 4 minutes
    res = subprocess.run(["adb", "devices"], capture_output=True, text=True)
    lines = [line.strip() for line in res.stdout.splitlines() if line.strip()]
    devices = [l.split()[0] for l in lines[1:] if "device" in l and not "offline" in l]
    if devices:
        device_id = devices[0]
        print(f"[AUTO-PUSH] Found connected device: {device_id}!")
        break
    time.sleep(2)

if not device_id:
    print("[ERROR] Timed out waiting for phone. Please ensure phone is unlocked with USB debugging ON.")
    sys.exit(1)

# Step 1: Install APK
print(f"[AUTO-PUSH] Installing APK {APK_FILE.name}...")
subprocess.run(["adb", "-s", device_id, "install", "-r", "-d", "-t", str(APK_FILE)])

# Step 2: Push Model
print(f"[AUTO-PUSH] Creating /sdcard/StorageSense/models on phone...")
subprocess.run(["adb", "-s", device_id, "shell", "mkdir", "-p", "/sdcard/StorageSense/models"])

print(f"[AUTO-PUSH] Pushing {MODEL_FILE.name} (1.29 GB) to phone...")
push_res = subprocess.run(["adb", "-s", device_id, "push", str(MODEL_FILE), "/sdcard/StorageSense/models/gemma-2b-it-gpu-int4.bin"])

# Step 3: Launch MainActivity
print("[AUTO-PUSH] Launching StorageSense on phone...")
subprocess.run(["adb", "-s", device_id, "shell", "am", "start", "-n", "com.storagesense.app.debug/com.storagesense.app.ui.MainActivity"])

# Step 4: Verify files
print("[AUTO-PUSH] Verification of /sdcard/StorageSense/models/:")
subprocess.run(["adb", "-s", device_id, "shell", "ls", "-lh", "/sdcard/StorageSense/models/"])

print("[SUCCESS] Auto-push complete!")

#!/usr/bin/env python3
"""
StorageSense PC Port - Native Executable Builder
Compiles the high-performance native Windows launcher (StorageSense.exe)
using Microsoft .NET Framework C# Compiler (csc.exe).
"""

import os
import sys
import subprocess
from pathlib import Path

def main():
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent

    print("=" * 60)
    print("StorageSense - Native Executable (.exe) Builder")
    print("=" * 60)

    # 1. Check for csc.exe compiler
    csc_candidates = [
        r"C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
        r"C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe",
    ]
    csc_path = None
    for cand in csc_candidates:
        if os.path.isfile(cand):
            csc_path = cand
            break

    if not csc_path:
        print("[ERROR] Microsoft .NET C# compiler (csc.exe) not found.")
        print("Please ensure Microsoft .NET Framework 4.5+ is enabled on Windows.")
        sys.exit(1)

    print(f"[OK] Found C# Compiler: {csc_path}")

    # 2. Check icon
    res_dir = script_dir / "resources"
    res_dir.mkdir(parents=True, exist_ok=True)
    ico_path = res_dir / "app_icon.ico"
    png_source = repo_root / "app" / "src" / "main" / "res" / "drawable" / "app_logo.png"

    if not ico_path.exists() and png_source.exists():
        try:
            from PIL import Image
            img = Image.open(png_source)
            img.save(ico_path, format="ICO", sizes=[(16,16), (32,32), (48,48), (64,64), (128,128), (256,256)])
            print(f"[OK] Generated Windows icon: {ico_path}")
        except Exception as e:
            print(f"[WARN] Could not generate icon with Pillow: {e}")

    # 3. Compile StorageSense.exe
    launcher_cs = script_dir / "launcher" / "StorageSenseLauncher.cs"
    out_exe = script_dir / "StorageSense.exe"

    if not launcher_cs.exists():
        print(f"[ERROR] Source file not found: {launcher_cs}")
        sys.exit(1)

    cmd = [
        csc_path,
        "/target:winexe",
        f"/out:{out_exe}",
        "/reference:System.Windows.Forms.dll",
        "/reference:System.Drawing.dll",
        "/optimize+",
        "/platform:anycpu"
    ]

    if ico_path.exists():
        cmd.append(f"/win32icon:{ico_path}")

    cmd.append(str(launcher_cs))

    print("\nCompiling StorageSense.exe...")
    result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        print("[ERROR] Compilation failed:")
        print(result.stdout)
        print(result.stderr)
        sys.exit(result.returncode)

    size_kb = out_exe.stat().st_size / 1024.0
    print(f"\n[SUCCESS] Successfully built: {out_exe}")
    print(f"Executable Size: {size_kb:.1f} KB")
    print("\nFeatures embedded:")
    print("  - Native Edge/Chrome App Window mode (no browser bars or tabs)")
    print("  - Automatic Python & environment detection")
    print("  - Win32 Job Object (zero orphaned background processes)")
    print("  - System Tray management (open, logs, restart, exit)")
    print("  - Double-click ready on any Windows 10/11 PC!")
    print("=" * 60)

if __name__ == "__main__":
    main()

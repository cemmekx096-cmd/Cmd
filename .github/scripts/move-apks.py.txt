#!/usr/bin/env python3
"""
Move and organize APK files from build output to repo structure
Usage: python move-apks.py
"""

import os
import shutil
from pathlib import Path

def find_apks(source_dir='src'):
    """Find all APK files in source directory"""
    apks = []
    source_path = Path(source_dir)
    
    for apk_file in source_path.rglob('*.apk'):
        # Only get release APKs
        if 'release' in str(apk_file):
            apks.append(apk_file)
    
    return apks

def organize_apks(apks, output_dir='apk-files'):
    """Copy APKs to organized output directory"""
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    copied = []
    
    for apk in apks:
        # Generate clean filename
        # Example: src/id/anichin/build/outputs/apk/release/id.anichin-v1.0.0.apk
        # -> id.anichin-v1.0.0.apk
        
        dest_name = apk.name
        dest_path = output_path / dest_name
        
        # Copy APK
        print(f"Copying: {apk.name}")
        shutil.copy2(apk, dest_path)
        copied.append(dest_path)
    
    return copied

def main():
    print("="*60)
    print("APK ORGANIZER")
    print("="*60)
    
    # Find APKs
    print("\n[1] Finding APK files...")
    apks = find_apks()
    print(f"Found {len(apks)} APK file(s)")
    
    if not apks:
        print("⚠️  No APKs found!")
        print("Make sure you've built the extensions first.")
        return
    
    # List found APKs
    print("\nAPKs to organize:")
    for apk in apks:
        print(f"  - {apk.name}")
    
    # Organize APKs
    print("\n[2] Organizing APKs...")
    copied = organize_apks(apks)
    
    print(f"\n✅ Successfully organized {len(copied)} APK(s)")
    print(f"Output directory: apk-files/")
    
    # Show results
    print("\n" + "="*60)
    print("ORGANIZED FILES")
    print("="*60)
    for apk in copied:
        size = apk.stat().st_size / 1024 / 1024  # MB
        print(f"{apk.name} ({size:.2f} MB)")
    print("="*60)

if __name__ == '__main__':
    main()
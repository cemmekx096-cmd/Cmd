#!/usr/bin/env python3
"""
Generate index.min.json for Aniyomi extension repository
Usage: python generate-index.py <apk-dir> <output-dir>
"""

import json
import os
import re
import sys
from pathlib import Path
from zipfile import ZipFile
import xml.etree.ElementTree as ET

def extract_apk_info(apk_path):
    """Extract metadata from APK file"""
    try:
        with ZipFile(apk_path, 'r') as apk:
            # Read AndroidManifest.xml (binary format, simplified parsing)
            manifest = apk.read('AndroidManifest.xml')
            
            # Try to extract package name from APK filename
            # Format: lang.sourcename-vX.Y.Z.apk
            filename = os.path.basename(apk_path)
            
            # Parse filename pattern
            match = re.match(r'(\w+)\.(\w+)-v?([\d.]+)\.apk', filename)
            if match:
                lang, name, version = match.groups()
                pkg = f"eu.kanade.tachiyomi.animeextension.{lang}.{name}"
                
                return {
                    'name': name.replace('_', ' ').title(),
                    'pkg': pkg,
                    'apk': filename,
                    'lang': lang,
                    'code': int(version.replace('.', '')),
                    'version': version,
                    'nsfw': False
                }
    except Exception as e:
        print(f"Error parsing {apk_path}: {e}")
    
    return None

def generate_index(apk_dir, output_dir):
    """Generate index.min.json from APK directory"""
    apk_dir = Path(apk_dir)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    extensions = []
    
    # Find all APK files
    apk_files = list(apk_dir.glob('*.apk'))
    print(f"Found {len(apk_files)} APK files")
    
    for apk_path in apk_files:
        print(f"Processing: {apk_path.name}")
        info = extract_apk_info(apk_path)
        
        if info:
            extensions.append(info)
            print(f"  ✓ {info['name']} v{info['version']}")
        else:
            print(f"  ✗ Failed to parse")
    
    # Sort by name
    extensions.sort(key=lambda x: x['name'])
    
    # Write index.min.json
    index_path = output_dir / 'index.min.json'
    with open(index_path, 'w', encoding='utf-8') as f:
        json.dump(extensions, f, separators=(',', ':'), ensure_ascii=False)
    
    print(f"\n✅ Generated {index_path}")
    print(f"   Total extensions: {len(extensions)}")
    
    # Also write human-readable version
    index_pretty = output_dir / 'index.json'
    with open(index_pretty, 'w', encoding='utf-8') as f:
        json.dump(extensions, f, indent=2, ensure_ascii=False)
    
    print(f"✅ Generated {index_pretty} (pretty)")
    
    return extensions

def main():
    if len(sys.argv) < 3:
        print("Usage: python generate-index.py <apk-dir> <output-dir>")
        print("Example: python generate-index.py ./apk-files ./repo-output")
        sys.exit(1)
    
    apk_dir = sys.argv[1]
    output_dir = sys.argv[2]
    
    if not os.path.isdir(apk_dir):
        print(f"Error: APK directory not found: {apk_dir}")
        sys.exit(1)
    
    extensions = generate_index(apk_dir, output_dir)
    
    # Print summary
    print("\n" + "="*50)
    print("SUMMARY")
    print("="*50)
    
    by_lang = {}
    for ext in extensions:
        lang = ext['lang']
        by_lang[lang] = by_lang.get(lang, 0) + 1
    
    for lang, count in sorted(by_lang.items()):
        print(f"{lang.upper()}: {count} extension(s)")
    
    print("="*50)

if __name__ == '__main__':
    main()
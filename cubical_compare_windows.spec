# -*- mode: python ; coding: utf-8 -*-

import os
import sys
from PyInstaller.utils.hooks import collect_data_files, collect_submodules

block_cipher = None

engine_path = os.path.abspath(os.path.join('android', 'app', 'src', 'main', 'python'))
if engine_path not in sys.path:
    sys.path.insert(0, engine_path)

# Windows and Android must ship the same measured renderer implementation.
ccengine_hiddenimports = collect_submodules('ccengine')
ccengine_datas = collect_data_files('ccengine')

a = Analysis(
    ['comparison_engine/cli.py'],
    pathex=['.', engine_path],
    binaries=[],
    datas=[
        ('sample_timeline.json', '.'),
    ] + ccengine_datas,
    hiddenimports=[
        'PIL',
        'PIL._imaging',
        'PIL.ImageDraw',
        'PIL.ImageFilter',
        'PIL.ImageFont',
        'openpyxl',
        'comparison_engine',
        'comparison_engine.models',
        'comparison_engine.sample_data',
    ] + ccengine_hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='ComparisonTimelineStudio',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=True,
    upx_exclude=[],
    name='ComparisonTimelineStudio',
)

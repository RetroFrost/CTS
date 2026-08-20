# -*- mode: python ; coding: utf-8 -*-

block_cipher = None

a = Analysis(
    ['comparison_engine/cli.py'],
    pathex=['.'],
    binaries=[],
    datas=[
        ('sample_timeline.json', '.'),
    ],
    hiddenimports=[
        'PIL',
        'PIL._imaging',
        'PIL.ImageDraw',
        'PIL.ImageFilter',
        'PIL.ImageFont',
        'comparison_engine',
        'comparison_engine.models',
        'comparison_engine.typography',
        'comparison_engine.badge_renderer',
        'comparison_engine.card_renderer',
        'comparison_engine.column_renderer',
        'comparison_engine.animation_curves',
        'comparison_engine.overlay_animations',
        'comparison_engine.timeline_renderer',
        'comparison_engine.video_exporter',
        'comparison_engine.sample_data',
    ],
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

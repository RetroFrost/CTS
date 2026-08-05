# -*- mode: python ; coding: utf-8 -*-
from PyInstaller.utils.hooks import collect_submodules
hidden = collect_submodules('ccengine')
a = Analysis(['engine_cli.py'], pathex=['.'], binaries=[], datas=[], hiddenimports=hidden, hookspath=[], hooksconfig={}, runtime_hooks=[], excludes=['PySide6','PyQt6'], noarchive=False)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name='cubical-create-engine', debug=False, bootloader_ignore_signals=False, strip=False, upx=True, console=True)

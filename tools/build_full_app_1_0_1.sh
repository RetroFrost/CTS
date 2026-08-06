#!/usr/bin/env bash
set -euo pipefail

VERSION=1.0.1
ROOT="$(pwd)"

cat .migration-data/exact-renderer-part0* | base64 -d > /tmp/exact-renderer-changes.tar.gz
tar -xzf /tmp/exact-renderer-changes.tar.gz

python - <<'PY'
from pathlib import Path

def replace_required(path_name: str, old: str, new: str) -> None:
    path = Path(path_name)
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"Missing expected text in {path_name}: {old}")
    path.write_text(text.replace(old, new))

replace_required("CMakeLists.txt", "project(CubicalCompare VERSION 1.0.0", "project(CubicalCompare VERSION 1.0.1")
replace_required("native/windows/CubicalCompare.manifest", 'version="1.0.0.0"', 'version="1.0.1.0"')
replace_required("tests/test_release_identity.py", 'version="1.0.0.0"', 'version="1.0.1.0"')
replace_required("packaging/flatpak/io.github.retrofrost.CTS.metainfo.xml", '<release version="1.0.0" date="2026-08-05">', '<release version="1.0.1" date="2026-08-06">')
PY

PYTHONPATH=engine CUBICAL_COMPARE_FFMPEG=/usr/bin/ffmpeg pytest -q
python -m compileall -q engine
g++ -std=c++20 -I native/core/include -c native/core/src/project.cpp -o /tmp/project.o

grep -F 'Official model' native/linux-gtk/main.cpp
grep -F 'Click to Insert Data' native/linux-gtk/main.cpp
grep -F 'What Males Learn At Each Age' native/linux-gtk/main.cpp
grep -F 'Types Of Relationships' native/linux-gtk/main.cpp

rm -rf engine/build engine/dist
(
  cd engine
  PYTHONPATH="$ROOT/engine" pyinstaller --noconfirm --clean cubical-compare-engine.spec
)
test -x engine/dist/cubical-compare-engine/cubical-compare-engine
test -d engine/dist/cubical-compare-engine/_internal

rm -rf engine-self-test
CUBICAL_COMPARE_FFMPEG=/usr/bin/ffmpeg \
  engine/dist/cubical-compare-engine/cubical-compare-engine self-test --directory engine-self-test
test -s engine-self-test/self-test.mp4

rm -rf build-linux
cmake -S . -B build-linux -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr
cmake --build build-linux --parallel 2
test -x build-linux/cubical-compare
for marker in 'Official model' 'Click to Insert Data' 'What Males Learn At Each Age' 'Types Of Relationships'; do
  strings build-linux/cubical-compare | grep -F "$marker"
done

CUBICAL_COMPARE_ENGINE="$ROOT/engine/dist/cubical-compare-engine/cubical-compare-engine" \
CUBICAL_COMPARE_FFMPEG=/usr/bin/ffmpeg \
  xvfb-run -a ./build-linux/cubical-compare --self-test

rm -rf stage-linux dist deb-root
cmake --install build-linux --prefix "$ROOT/stage-linux"
install -d stage-linux/libexec/cubical-compare/engine
cp -a engine/dist/cubical-compare-engine/. stage-linux/libexec/cubical-compare/engine/
install -Dm644 README.md stage-linux/share/doc/cubical-compare/README.md
printf '%s\n' "$(git rev-parse HEAD)" > stage-linux/share/doc/cubical-compare/BUILD-COMMIT
printf '%s\n' "$VERSION" > stage-linux/share/doc/cubical-compare/BUILD-VERSION

CUBICAL_COMPARE_ENGINE="$ROOT/stage-linux/libexec/cubical-compare/engine/cubical-compare-engine" \
CUBICAL_COMPARE_FFMPEG=/usr/bin/ffmpeg \
  xvfb-run -a stage-linux/bin/cubical-compare --self-test

mkdir -p deb-root/DEBIAN deb-root/usr dist
cp -a stage-linux/. deb-root/usr/
cat > deb-root/DEBIAN/control <<EOF
Package: cubical-compare
Version: ${VERSION}
Section: video
Priority: optional
Architecture: amd64
Maintainer: RetroFrost
Depends: ffmpeg, libgtk-4-1
Description: Native comparison-video editor
 Cubical Compare imports spreadsheet data, edits comparison cards,
 previews the model animation and exports MP4 video through its
 privately bundled rendering engine.
EOF

dpkg-deb --build --root-owner-group deb-root "dist/Cubical-Compare-${VERSION}-linux-amd64.deb"
rm -rf /tmp/cubical-deb-check
dpkg-deb -x "dist/Cubical-Compare-${VERSION}-linux-amd64.deb" /tmp/cubical-deb-check
for marker in 'Official model' 'Click to Insert Data' 'What Males Learn At Each Age' 'Types Of Relationships'; do
  strings /tmp/cubical-deb-check/usr/bin/cubical-compare | grep -F "$marker"
done
test "$(cat /tmp/cubical-deb-check/usr/share/doc/cubical-compare/BUILD-VERSION)" = "$VERSION"
sha256sum "dist/Cubical-Compare-${VERSION}-linux-amd64.deb" > dist/SHA256SUMS.txt

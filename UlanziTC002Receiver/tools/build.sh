#!/bin/sh
# Runs inside the build container. Usage: tools/build.sh [--release] [--host-tests]
set -e
cd /work
python3 tools/fetch-sdk.py
bundle() {  # $1 = preset. out/ is Dropbox-synced: remove files, never the directory (rm -rf on it fails)
  cmake --preset "$1" >/dev/null
  cmake --build --preset "$1"
  mkdir -p out/device/lib out/device/ui
  rm -f out/device/ui/* out/device/lib/*
  cp build/"$1"/device/lib/libzkgui.so out/device/lib/
  cp build/"$1"/device/EasyUI.cfg.json out/device/
  cp -r res/ui/. out/device/ui/   # resources come straight from the source tree, never from a stale build dir
  arm-linux-gnueabihf-strip --strip-unneeded out/device/lib/libzkgui.so
  echo "bundle: out/device"
}
case "$1" in
  --host-tests)
    cmake --preset host >/dev/null
    cmake --build --preset host
    ./build/host/presenter_test
    ;;
  --release) bundle z21-release ;;
  *) bundle z21 ;;
esac

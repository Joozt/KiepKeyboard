#!/bin/sh
# Runs inside the build container. Usage: tools/build.sh [--release] [--host-tests]
set -e
cd /work
python3 tools/fetch-sdk.py
case "$1" in
  --host-tests)
    cmake --preset host >/dev/null
    cmake --build --preset host
    ./build/host/presenter_test
    ;;
  --release)
    cmake --preset z21-release >/dev/null
    cmake --build --preset z21-release
    rm -rf out/device && mkdir -p out && cp -r build/z21-release/device out/device
    ;;
  *)
    cmake --preset z21 >/dev/null
    cmake --build --preset z21
    rm -rf out/device && mkdir -p out && cp -r build/z21/device out/device
    ;;
esac
if [ -f out/device/lib/libzkgui.so ]; then
  arm-linux-gnueabihf-strip --strip-unneeded out/device/lib/libzkgui.so
  echo "bundle: out/device"
fi

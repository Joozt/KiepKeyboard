# Build image for the KiepDisplay TC002 receiver. Everything that compiles lives here;
# the Windows host only runs adb and the Python tools.
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl xz-utils unzip cmake ninja-build g++ binutils file \
    python3 python3-pil fonts-dejavu-core squashfs-tools \
 && rm -rf /var/lib/apt/lists/*
# Same GCC generation (8.3.0, Linaro-derived) as the vendor's Z21 toolchain: matching libstdc++ symbol versions.
ARG TC_URL=https://developer.arm.com/-/media/Files/downloads/gnu-a/8.3-2019.03/binrel/gcc-arm-8.3-2019.03-x86_64-arm-linux-gnueabihf.tar.xz
RUN curl -fsSL "$TC_URL" | tar -xJ -C /opt \
 && ln -s /opt/gcc-arm-8.3-2019.03-x86_64-arm-linux-gnueabihf /opt/toolchain
ENV PATH=/opt/toolchain/bin:$PATH
WORKDIR /work

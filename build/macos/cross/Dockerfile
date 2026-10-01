FROM ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl python3 clang-18 lld-18 llvm-18-dev make cmake \
    g++ patch git libarchive-tools cpio xz-utils zlib1g-dev libxml2-dev \
    libssl-dev zip unzip xvfb xauth libgtk-3-0 libxtst6 libasound2t64 \
    fonts-dejavu-core && rm -rf /var/lib/apt/lists/*

COPY inputs.json fetch-inputs.py /opt/bootstrap/
RUN --mount=type=cache,id=wisprail-macos-inputs,target=/var/cache/wisprail-inputs,sharing=locked \
    python3 /opt/bootstrap/fetch-inputs.py /opt/inputs

ENV PATH="/opt/osxcross/bin:/usr/lib/llvm-18/bin:/opt/java/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
ENV JAVA_HOME=/opt/java
COPY prepare-toolchain.py /opt/bootstrap/
RUN python3 /opt/bootstrap/prepare-toolchain.py
WORKDIR /work

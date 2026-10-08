FROM ubuntu:24.04
RUN apt-get update && apt-get install -y --no-install-recommends \
    autoconf automake ca-certificates clang cmake cpio curl git libbz2-dev liblzma-dev \
    libssl-dev libtool libxml2-dev lld llvm-dev make patch pkg-config python3 \
    tar unzip uuid-dev xz-utils zip zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/tpoechtrager/osxcross.git /opt/osxcross-source \
    && git -C /opt/osxcross-source checkout 27d21e4977c9751d01199c7a226a6faf494c3dd9
COPY CLTools_macOSNMOS_SDK.pkg /tmp/SDK.pkg
RUN git clone https://github.com/tpoechtrager/xar.git /opt/xar \
    && git -C /opt/xar checkout 5fa4675419cfec60ac19a9c7f7c2d0e7c831a497 \
    && cd /opt/xar/xar && CFLAGS=-w ./configure --prefix=/opt/osxcross \
    && make -j4 && make install \
    && git clone https://github.com/tpoechtrager/pbzx.git /opt/pbzx \
    && git -C /opt/pbzx checkout 2a4d7c3300c826d918def713a24d25c237c8ed53 \
    && clang -O2 -I/opt/osxcross/include -L/opt/osxcross/lib /opt/pbzx/pbzx.c \
       -o /opt/osxcross/bin/pbzx -llzma -lxar -Wl,-rpath,/opt/osxcross/lib
# The SDK package comes directly from Apple's public software-update catalog.
RUN mkdir -p /opt/osxcross-source/build /tmp/sdk-package /tmp/sdk \
    && /opt/osxcross/bin/xar -xf /tmp/SDK.pkg -C /tmp/sdk-package \
    && cd /tmp/sdk && /opt/osxcross/bin/pbzx -n /tmp/sdk-package/Payload | cpio -id \
    && cd /tmp/sdk/Library/Developer/CommandLineTools/SDKs \
    && tar -cJf /opt/osxcross-source/tarballs/MacOSX15.5.sdk.tar.xz MacOSX15.5.sdk
ENV PATH="/usr/lib/llvm-18/bin:${PATH}"
RUN cd /opt/osxcross-source && UNATTENDED=1 BUILD_FLAVOR=llvm OSX_VERSION_MIN=13.0 \
    ENABLE_ARCHS="arm64 x86_64" TARGET_DIR=/opt/osxcross ./build.sh
RUN git clone https://github.com/hogliux/bomutils.git /opt/bomutils \
    && git -C /opt/bomutils checkout c247002e2f49878bab32dd0fcf805810ae0992cb \
    && make -C /opt/bomutils CXX=clang++ all && make -C /opt/bomutils install
COPY microsoft-jdk-21.0.11-linux-x64.tar.gz /tmp/host-jdk.tar.gz
COPY microsoft-jdk-21.0.11-macos-x64.tar.gz /tmp/mac-x64-jdk.tar.gz
COPY microsoft-jdk-21.0.11-macos-aarch64.tar.gz /tmp/mac-arm64-jdk.tar.gz
COPY apple-codesign-0.29.0-x86_64-unknown-linux-musl.tar.gz /tmp/rcodesign.tar.gz
RUN mkdir -p /opt/jdk-host /opt/jdk-mac-amd64 /opt/jdk-mac-arm64 /opt/rcodesign \
    && tar -xf /tmp/host-jdk.tar.gz -C /opt/jdk-host --strip-components=1 \
    && tar -xf /tmp/mac-x64-jdk.tar.gz -C /opt/jdk-mac-amd64 --strip-components=2 \
    && tar -xf /tmp/mac-arm64-jdk.tar.gz -C /opt/jdk-mac-arm64 --strip-components=2 \
    && tar -xf /tmp/rcodesign.tar.gz -C /opt/rcodesign --strip-components=1
ENV OSXCROSS_ROOT=/opt/osxcross
ENV JAVA_HOME=/opt/jdk-host
ENV PATH="/opt/osxcross/bin:/opt/rcodesign:/opt/jdk-host/bin:${PATH}"
WORKDIR /workspace
# Build context is packaging/target/tools after fetch-tools.py --target macos.
ENTRYPOINT ["bash", "packaging/macos/cross/build.sh"]

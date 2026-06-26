#!/bin/bash
# 运行 GraalVM native-image 构建出的单一可执行文件。
# 需要 target/ 下的 JDK 原生库(libawt 等,native-image 自动产出)
# 和 target/native-libs/ 下的 Skija + LWJGL .so。
cd "$(dirname "$0")/target"
LD_LIBRARY_PATH=.:native-libs ./qplayer-native \
  -Dqplayer.aot=true \
  -Dqml4j.rhino.opt=-1 \
  -Dorg.lwjgl.librarypath=native-libs \
  -Dskija.library.path=native-libs \
  -Dqplayer.glfw.platform=x11 \
  "$@"

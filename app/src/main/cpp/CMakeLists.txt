cmake_minimum_required(VERSION 3.22.1)
project(dfelzma C)

# LZMA2 decoder from the public-domain LZMA SDK (see lzma/), exposed to Java through lzma2_jni.c.
add_library(dfelzma SHARED
        lzma2_jni.c
        lzma/LzmaDec.c
        lzma/Lzma2Dec.c)

target_compile_options(dfelzma PRIVATE -O3 -fvisibility=hidden -Wall)

# 16 KB page-size devices (Android 15+) require 16 KB-aligned ELF segments.
target_link_options(dfelzma PRIVATE -Wl,-z,max-page-size=16384)

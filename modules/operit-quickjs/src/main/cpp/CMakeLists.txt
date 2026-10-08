cmake_minimum_required(VERSION 3.22.1)

project(quickjsjni)

# Vendored QuickJS sources (bellard/quickjs@535a7c250ff4a577ec36c3e103daab6dadeea650,
# VERSION 2026-06-04) — see VENDOR.md. Upstream cloned them at configure time via
# operit_git_source.cmake; the local path replaces FetchContent (plan decision C6).
set(QUICKJS_DIR "${CMAKE_CURRENT_LIST_DIR}/vendor/quickjs")

file(READ "${QUICKJS_DIR}/VERSION" QUICKJS_VERSION_RAW)
string(STRIP "${QUICKJS_VERSION_RAW}" QUICKJS_VERSION)

set(OPERIT_QUICKJS_GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${OPERIT_QUICKJS_GENERATED_DIR}")
file(WRITE
    "${OPERIT_QUICKJS_GENERATED_DIR}/operit_quickjs.h"
    "#pragma once\n#include \"${QUICKJS_DIR}/quickjs.h\"\n"
)

add_library(
    quickjsjni
    SHARED
    quickjs_jni.cpp
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/dtoa.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
    ${QUICKJS_DIR}/quickjs.c
)

target_compile_definitions(
    quickjsjni
    PRIVATE
    _GNU_SOURCE
    CONFIG_VERSION="${QUICKJS_VERSION}"
    NDEBUG
)

target_compile_options(
    quickjsjni
    PRIVATE
    # Keep QuickJS optimized on latency-sensitive paths even in debug app builds.
    -O3
    -fwrapv
    -funsigned-char
    -Wall
    -Wextra
    -Wno-sign-compare
    -Wno-missing-field-initializers
    -Wno-unused-parameter
    -Wwrite-strings
    -Wchar-subscripts
)

target_include_directories(
    quickjsjni
    PRIVATE
    "${OPERIT_QUICKJS_GENERATED_DIR}"
)

set_target_properties(
    quickjsjni
    PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

target_link_libraries(
    quickjsjni
    android
    log
    m
)

target_link_options(quickjsjni PRIVATE "-Wl,-z,max-page-size=16384")

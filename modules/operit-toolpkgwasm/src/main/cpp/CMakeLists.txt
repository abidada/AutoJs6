# Operit toolpkgwasm 1:1 port (upstream app/src/main/cpp/CMakeLists.txt, toolpkgwasm target).
#
# Builds libtoolpkgwasm.so — the WAMR-backed WebAssembly runtime for Operit ToolPkg plugins,
# consumed by com.ai.assistance.operit.util.ToolPkgWasmNative.
#
# Deviation from upstream: WAMR (wasm-micro-runtime) is VENDORED under ./vendor/wamr (core/ +
# build-scripts/ only) instead of being git-fetched at configure time via operit_git_source.cmake,
# so the build is offline-reproducible. Pin recorded in ./vendor/wamr/VENDOR.md.
cmake_minimum_required(VERSION 3.22.1)

project(toolpkgwasm)

function(force_release_flags_for_debug target_name)
    if(TARGET ${target_name})
        target_compile_definitions(${target_name} PRIVATE $<$<CONFIG:Debug>:NDEBUG>)
        target_compile_options(${target_name} PRIVATE $<$<CONFIG:Debug>:-O3 -g0>)
    endif()
endfunction()

function(enable_16kb_page_alignment target_name)
    if(TARGET ${target_name})
        target_link_options(${target_name} PRIVATE
                "-Wl,-z,max-page-size=16384"
                "-Wl,-z,common-page-size=16384"
        )
    endif()
endfunction()

# ABI -> WAMR build target (mirrors upstream resolve_wamr_android_target).
if("${ANDROID_ABI}" STREQUAL "arm64-v8a")
    set(TOOLPKG_WAMR_BUILD_TARGET "AARCH64")
elseif("${ANDROID_ABI}" STREQUAL "armeabi-v7a")
    set(TOOLPKG_WAMR_BUILD_TARGET "ARMV7A")
elseif("${ANDROID_ABI}" STREQUAL "x86")
    set(TOOLPKG_WAMR_BUILD_TARGET "X86_32")
elseif("${ANDROID_ABI}" STREQUAL "x86_64")
    set(TOOLPKG_WAMR_BUILD_TARGET "X86_64")
else()
    message(FATAL_ERROR "Unsupported Android ABI for ToolPkg WAMR: ${ANDROID_ABI}")
endif()

set(WAMR_ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/wamr")
set(WAMR_BUILD_PLATFORM "android")
set(WAMR_BUILD_TARGET "${TOOLPKG_WAMR_BUILD_TARGET}")
set(WAMR_BUILD_INTERP 1)
set(WAMR_BUILD_FAST_INTERP 1)
set(WAMR_BUILD_AOT 0)
set(WAMR_BUILD_JIT 0)
set(WAMR_BUILD_FAST_JIT 0)
set(WAMR_BUILD_LIBC_BUILTIN 1)
set(WAMR_BUILD_LIBC_WASI 0)
set(WAMR_BUILD_LIBC_UVWASI 0)
set(WAMR_BUILD_LIBC_EMCC 0)
set(WAMR_BUILD_MULTI_MODULE 0)
set(WAMR_BUILD_LIB_PTHREAD 0)
set(WAMR_BUILD_LIB_PTHREAD_SEMAPHORE 0)
set(WAMR_BUILD_LIB_WASI_THREADS 0)
set(WAMR_BUILD_THREAD_MGR 0)
set(WAMR_BUILD_SHARED_HEAP 0)
set(WAMR_BUILD_WASI_NN 0)
set(WAMR_BUILD_WASM_CACHE 0)
set(WAMR_BUILD_DEBUG_INTERP 0)
set(WAMR_BUILD_COPY_CALL_STACK 0)
set(WAMR_BUILD_MINI_LOADER 0)
set(WAMR_BUILD_SIMD 0)
set(WAMR_BUILD_REF_TYPES 0)

include("${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake")

add_library(
        toolpkg_wamr_vmlib
        STATIC
        ${WAMR_RUNTIME_LIB_SOURCE}
)

target_include_directories(
        toolpkg_wamr_vmlib
        PUBLIC
        "${WAMR_ROOT_DIR}/core/iwasm/include"
)

# Operit port (D-13): WAMR's stack guard-page protection (init_stack_guard_pages ->
# os_mprotect(PROT_NONE) on the thread's stack boundary) FAILS on Android/ART threads, which
# makes wasm_runtime_full_init() return false and breaks ToolPkg WASM entirely. Disabling the
# stack HW bound check skips that path (WAMR then uses software bounds checking — same
# semantics, slightly slower). Verified on device: init succeeds with this define.
target_compile_definitions(toolpkg_wamr_vmlib PRIVATE WASM_DISABLE_STACK_HW_BOUND_CHECK=1)

force_release_flags_for_debug(toolpkg_wamr_vmlib)

add_library(
        toolpkgwasm
        SHARED
        native_toolpkg_wasm.cpp
)

target_include_directories(
        toolpkgwasm
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        "${WAMR_ROOT_DIR}/core/iwasm/include"
)

find_library(
        log-lib
        log
)

target_link_libraries(
        toolpkgwasm
        toolpkg_wamr_vmlib
        ${log-lib}
        android
        m
        dl
)

force_release_flags_for_debug(toolpkgwasm)
enable_16kb_page_alignment(toolpkgwasm)

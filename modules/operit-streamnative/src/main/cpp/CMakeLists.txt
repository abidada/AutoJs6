# Operit streamnative 1:1 port (upstream app/src/main/cpp/CMakeLists.txt, streamnative target).
#
# Builds libstreamnative.so — the streaming markdown/XML/JSON splitter consumed by
# com.ai.assistance.operit.util.streamnative.* (StreamMarkdownRenderer, ConversationService,
# WaifuMessageProcessor, StructuredAssistantContentParser). Self-contained: only the C++ STL
# and liblog are required (no third-party deps).
#
# NOT ported here (separate phases):
#   - toolpkgwasm  (native_toolpkg_wasm.cpp) — needs the WAMR runtime (wasm_export.h).
#   - sherpa-ncnn  (upstream STT) — trimmed C15 (speech delegated to bibi).
cmake_minimum_required(VERSION 3.22.1)

project(streamnative)

# Keep the app variant as Debug but compile this heavy native lib with release-like optimization
# (mirrors upstream force_release_flags_for_debug).
function(force_release_flags_for_debug target_name)
    if(TARGET ${target_name})
        target_compile_definitions(${target_name} PRIVATE $<$<CONFIG:Debug>:NDEBUG>)
        target_compile_options(${target_name} PRIVATE $<$<CONFIG:Debug>:-O3 -g0>)
    endif()
endfunction()

# Android 15+ 16 KB page size support.
function(enable_16kb_page_alignment target_name)
    if(TARGET ${target_name})
        target_link_options(${target_name} PRIVATE
                "-Wl,-z,max-page-size=16384"
                "-Wl,-z,common-page-size=16384"
        )
    endif()
endfunction()

add_library(
        streamnative
        SHARED
        streamnative/native_xml_splitter.cpp
        streamnative/native_markdown_splitter.cpp
        streamnative/StreamOperators.cpp
        streamnative/plugins/StreamXmlPlugin.cpp
        streamnative/plugins/BaseJsonPlugin.cpp
        streamnative/plugins/StreamJsonPlugin.cpp
        streamnative/plugins/StreamPureJsonPlugin.cpp
        streamnative/plugins/StreamMarkdownPlugin.cpp
        streamnative/StreamBuilders.cpp
        streamnative/HotStream.cpp
        streamnative/StringExtensions.cpp
)

target_include_directories(
        streamnative
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(
        log-lib
        log
)

target_link_libraries(
        streamnative
        ${log-lib}
)

force_release_flags_for_debug(streamnative)
enable_16kb_page_alignment(streamnative)

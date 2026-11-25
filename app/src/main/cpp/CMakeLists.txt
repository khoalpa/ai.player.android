cmake_minimum_required(VERSION 3.22)
project(aiplayer_context LANGUAGES C CXX)

include(FetchContent)
if (ANDROID AND GGML_VULKAN AND DEFINED ENV{VULKAN_SDK})
    # Use host SDK headers and shader compiler, but link the Android NDK Vulkan loader.
    find_path(Vulkan_INCLUDE_DIR vulkan/vulkan.hpp
        HINTS "$ENV{VULKAN_SDK}/include" "$ENV{VULKAN_SDK}/Include"
        NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    find_program(Vulkan_GLSLC_EXECUTABLE glslc
        HINTS "$ENV{VULKAN_SDK}/bin" "$ENV{VULKAN_SDK}/Bin"
        NO_CMAKE_FIND_ROOT_PATH)
    find_package(SPIRV-Headers CONFIG REQUIRED
        HINTS "$ENV{VULKAN_SDK}" NO_CMAKE_FIND_ROOT_PATH)
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(llama_cpp
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/refs/tags/b12675
    TLS_VERIFY TRUE
    TIMEOUT 120
    INACTIVITY_TIMEOUT 25)
FetchContent_MakeAvailable(llama_cpp)

add_library(aiplayer_context SHARED context_translation.cpp)
target_compile_features(aiplayer_context PRIVATE cxx_std_17)
target_link_libraries(aiplayer_context PRIVATE llama log)

set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
FetchContent_Declare(sentencepiece
    URL https://codeload.github.com/google/sentencepiece/tar.gz/refs/tags/v0.2.1
    TLS_VERIFY TRUE
    TIMEOUT 120)
FetchContent_MakeAvailable(sentencepiece)
if(ANDROID)
    target_link_libraries(sentencepiece-static PUBLIC log)
endif()

add_library(aiplayer_m2m SHARED m2m_tokenizer.cpp)
target_compile_features(aiplayer_m2m PRIVATE cxx_std_17)
target_include_directories(aiplayer_m2m PRIVATE ${sentencepiece_SOURCE_DIR}/src)
target_link_libraries(aiplayer_m2m PRIVATE sentencepiece-static)

cmake_minimum_required(VERSION 3.22)
project(aiplayer_context LANGUAGES C CXX)

include(FetchContent)
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
target_link_libraries(aiplayer_context PRIVATE llama)

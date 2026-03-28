cmake_minimum_required(VERSION 3.22.1)
project(satellite)

find_package(game-activity REQUIRED CONFIG)

# ── libsodium (built from source via FetchContent) ──────────────────────────
include(FetchContent)
FetchContent_Declare(
    libsodium
    URL      https://download.libsodium.org/libsodium/releases/libsodium-1.0.20-stable.tar.gz
    URL_HASH SHA256=b6b1d2a8802cd8bfa611638c0e9ce31d14ef324e16062c39a76854091cba6d7f
)
FetchContent_MakeAvailable(libsodium)

# libsodium doesn't ship a CMakeLists.txt that works well with FetchContent.
# Build the minimal subset we need as a static library.
set(SODIUM_SRC ${libsodium_SOURCE_DIR}/src/libsodium)

# Generate version.h (normally created by ./configure)
file(WRITE ${SODIUM_SRC}/include/sodium/version.h
"#ifndef sodium_version_H\n\
#define sodium_version_H\n\
#include \"export.h\"\n\
#define SODIUM_VERSION_STRING \"1.0.20\"\n\
#define SODIUM_LIBRARY_VERSION_MAJOR 26\n\
#define SODIUM_LIBRARY_VERSION_MINOR 2\n\
SODIUM_EXPORT const char *sodium_version_string(void);\n\
SODIUM_EXPORT int sodium_library_version_major(void);\n\
SODIUM_EXPORT int sodium_library_version_minor(void);\n\
SODIUM_EXPORT int sodium_library_minimal(void);\n\
#endif\n")

file(GLOB_RECURSE SODIUM_SOURCES ${SODIUM_SRC}/*.c)

add_library(sodium_static STATIC ${SODIUM_SOURCES})
target_include_directories(sodium_static
    PUBLIC  ${SODIUM_SRC}/include
    PRIVATE ${SODIUM_SRC}/include/sodium
)
target_compile_definitions(sodium_static PRIVATE
    CONFIGURED=1
    NATIVE_LITTLE_ENDIAN=1
    HAVE_INLINE_ASM=1
    HAVE_TI_MODE=1
    HAVE_WEAK_SYMBOLS=1
    HAVE_ATOMIC_OPS=1
    HAVE_ALLOCA_H=1
    HAVE_MMAP=1
    HAVE_MLOCK=1
    HAVE_MADVISE=1
    HAVE_MPROTECT=1
    HAVE_POSIX_MEMALIGN=1
    HAVE_ARC4RANDOM=0
    HAVE_SYS_MMAN_H=1
    __STDC_LIMIT_MACROS=1
    __STDC_CONSTANT_MACROS=1
)
target_compile_options(sodium_static PRIVATE -O2 -fvisibility=hidden -Wno-unused-function -Wno-unused-variable)

# ── Main satellite library ──────────────────────────────────────────────────
add_library(satellite SHARED satellite_jni.cpp)

target_compile_options(satellite PRIVATE -O2 -Wall -Wextra -Wpedantic)

target_link_libraries(satellite
    log
    android
    sodium_static
    -Wl,--whole-archive
    game-activity::game-activity_static
    -Wl,--no-whole-archive)

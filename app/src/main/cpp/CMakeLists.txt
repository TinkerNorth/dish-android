cmake_minimum_required(VERSION 3.22.1)
project(satellite)

# libsodium is fetched below as a release tarball. Adopt CMP0135 (CMake >= 3.24)
# so extracted files are stamped at extraction time — the modern default — which
# also silences FetchContent's DOWNLOAD_EXTRACT_TIMESTAMP developer warning. The
# guard keeps the pinned CMake 3.22.1 (whose policy set predates CMP0135) working.
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW)
endif()

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
    # bionic exposes sysconf(_SC_PAGESIZE) (a macro), so let libsodium query the
    # page size at runtime — the branch ./configure normally selects. Without this
    # it fell through to `#warning Unknown page size` and a hardcoded fallback.
    HAVE_SYSCONF=1
    __STDC_LIMIT_MACROS=1
    __STDC_CONSTANT_MACROS=1
)
target_compile_options(sodium_static PRIVATE
    $<IF:$<CONFIG:Debug>,-O0,-O3>
    -fvisibility=hidden -Wno-unused-function -Wno-unused-variable)

# ── Main satellite library ──────────────────────────────────────────────────
add_library(satellite SHARED
    satellite_jni.cpp
    gamepad_input.cpp
    usb_host.cpp
    usb_parsers.cpp
    usb_hid_descriptor.cpp
    hotpath_latency.cpp)

# Release (RelWithDebInfo) is the streaming hot path: -O3 + ThinLTO cross-inlines the JNI dispatch,
# parsers, and encoders; --gc-sections drops the dead code that opens up. Debug stays -O0 for fast
# iteration (AGP still supplies -g).
target_compile_options(satellite PRIVATE
    $<IF:$<CONFIG:Debug>,-O0,-O3>
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>
    -ffunction-sections -fdata-sections
    -Wall -Wextra -Wpedantic)
target_link_options(satellite PRIVATE
    "-Wl,-z,max-page-size=16384"
    -Wl,--gc-sections
    $<$<NOT:$<CONFIG:Debug>>:-flto=thin>)

target_link_libraries(satellite
    log
    android
    sodium_static
    -Wl,--whole-archive
    game-activity::game-activity_static
    -Wl,--no-whole-archive)

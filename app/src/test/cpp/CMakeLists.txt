# Host build for the host-testable JNI split (docs/architecture.md "Host-testable C++ split"):
# one googletest target per pure layer, so the keycode/deadzone/state-machine logic, the wire
# layouts, the USB decoders and the audio path are all provable without a device.
#
# Driven by Gradle's :app:nativeTest task, not the AGP NDK build. Run
# manually:  cmake -S app/src/test/cpp -B build/native-test
#            cmake --build build/native-test
#            ctest --test-dir build/native-test --output-on-failure
cmake_minimum_required(VERSION 3.22)
project(satellite_host_tests C CXX)

# Extracted archives take the extraction time, as the app build's CMakeLists also asks: the
# FetchContent tarballs below are content-addressed by URL_HASH, so a changed URL rebuilds.
if(POLICY CMP0135)
  cmake_policy(SET CMP0135 NEW)
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pull in googletest. Pinned to a specific tag. Bump deliberately, the
# action-pin lint in CI doesn't gate FetchContent but supply-chain hygiene
# still applies.
include(FetchContent)
FetchContent_Declare(
    googletest
    URL      https://github.com/google/googletest/archive/refs/tags/v1.15.2.tar.gz
    URL_HASH SHA256=7b42b4d6ed48810c5362c265a17faebe90dc2373c885e5216439d37927f02926
)
# Don't install gtest; we just want the test executable.
set(INSTALL_GTEST OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(googletest)

enable_testing()
find_package(Threads REQUIRED)

set(SATELLITE_CPP "${CMAKE_SOURCE_DIR}/../../main/cpp")

# The pinned libsodium the app seals with (src/main/cpp/CMakeLists.txt), built from the same
# release tarball, so the send path's bytes and its seal cost are those of the one that ships.
FetchContent_Declare(
    libsodium
    URL      https://download.libsodium.org/libsodium/releases/libsodium-1.0.20-stable.tar.gz
    URL_HASH SHA256=b6b1d2a8802cd8bfa611638c0e9ce31d14ef324e16062c39a76854091cba6d7f
)
FetchContent_MakeAvailable(libsodium)
set(SODIUM_SRC ${libsodium_SOURCE_DIR}/src/libsodium)
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
add_library(sodium_host STATIC ${SODIUM_SOURCES})
target_include_directories(sodium_host SYSTEM
    PUBLIC  ${SODIUM_SRC}/include
    PRIVATE ${SODIUM_SRC}/include/sodium
)
target_compile_definitions(sodium_host
    PUBLIC  SODIUM_STATIC=1
    PRIVATE CONFIGURED=1 NATIVE_LITTLE_ENDIAN=1 HAVE_INLINE_ASM=1 HAVE_ATOMIC_OPS=1
            __STDC_LIMIT_MACROS=1 __STDC_CONSTANT_MACROS=1)
# The app's POSIX feature set on a POSIX host; a Windows host takes libsodium's own _WIN32 paths
# (VirtualLock, RtlGenRandom from advapi32) instead. PE has no symbol visibility, so there the
# software AES takes its static lookup table rather than the hidden-visibility one.
if(WIN32)
    target_link_libraries(sodium_host PUBLIC advapi32)
    target_compile_definitions(sodium_host PRIVATE FAVOR_PERFORMANCE=1)
else()
    target_compile_definitions(sodium_host PRIVATE
        HAVE_WEAK_SYMBOLS=1 HAVE_ALLOCA_H=1 HAVE_MMAP=1 HAVE_MLOCK=1 HAVE_MADVISE=1
        HAVE_MPROTECT=1 HAVE_POSIX_MEMALIGN=1 HAVE_SYS_MMAN_H=1 HAVE_SYSCONF=1)
endif()
# libsodium's 128-bit field arithmetic where its own configure would enable it: the app build's
# probe, unchanged.
function(check_sodium_ti_mode)
    include(CheckCSourceCompiles)
    set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)
    set(CMAKE_REQUIRED_DEFINITIONS -DNATIVE_LITTLE_ENDIAN=1)
    check_c_source_compiles([=[
#if !defined(__clang__) && !defined(__GNUC__) && !defined(__SIZEOF_INT128__)
# error mode(TI) is a gcc extension, and __int128 is not available
#endif
#if defined(__clang__) && !defined(__x86_64__) && !defined(__aarch64__)
# error clang does not properly handle the 128-bit type on 32-bit systems
#endif
#ifndef NATIVE_LITTLE_ENDIAN
# error libsodium currently expects a little endian CPU for the 128-bit type
#endif
#include <stddef.h>
#include <stdint.h>
#if defined(__SIZEOF_INT128__)
typedef unsigned __int128 uint128_t;
#else
typedef unsigned uint128_t __attribute__((mode(TI)));
#endif
void fcontract(uint128_t *t) {
  *t += 0x8000000000000 - 1;
  *t *= *t;
  *t >>= 84;
}
]=] SODIUM_HOST_HAVE_TI_MODE)
endfunction()
check_sodium_ti_mode()
if(SODIUM_HOST_HAVE_TI_MODE)
    target_compile_definitions(sodium_host PRIVATE HAVE_TI_MODE=1)
endif()
target_compile_options(sodium_host PRIVATE -O3)

add_executable(gamepad_input_test
    gamepad_input_test.cpp
    "${SATELLITE_CPP}/gamepad_input.cpp"
)
target_include_directories(gamepad_input_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(gamepad_input_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(gamepad_input_test PRIVATE GTest::gtest_main)

# Pure wire-encoder unit tests: no Android / JNI dependency, just the byte-layout pins for every
# inner payload wire_encoders.h encodes or decodes, and the datagram ceilings.
add_executable(wire_encoders_test
    wire_encoders_test.cpp
)
target_include_directories(wire_encoders_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(wire_encoders_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(wire_encoders_test PRIVATE GTest::gtest_main)

# usb_parsers.cpp fences its USB I/O behind __ANDROID__, so the decoders and rumble
# builders build and test host-side.
add_executable(usb_parsers_test
    usb_parsers_test.cpp
    "${SATELLITE_CPP}/usb_parsers.cpp"
    "${SATELLITE_CPP}/usb_hid_descriptor.cpp"
)
target_include_directories(usb_parsers_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(usb_parsers_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(usb_parsers_test PRIVATE GTest::gtest_main)

# Pure HID report-descriptor parser + layout-driven decode (no Android/USB I/O).
add_executable(usb_hid_descriptor_test
    usb_hid_descriptor_test.cpp
    "${SATELLITE_CPP}/usb_hid_descriptor.cpp"
)
target_include_directories(usb_hid_descriptor_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(usb_hid_descriptor_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(usb_hid_descriptor_test PRIVATE GTest::gtest_main)

# UDP send-counter exhaustion guard: the header the JNI send path draws every
# wire counter from (no wrap into ChaCha20 nonce reuse, contract §Crypto).
add_executable(send_counter_test
    send_counter_test.cpp
)
target_include_directories(send_counter_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(send_counter_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(send_counter_test PRIVATE GTest::gtest_main Threads::Threads)

# Controller-audio reorder window. Header-only and dependency-free by design, so
# this target links no codec at all: the ordering rules are exactly the part
# that should be provable without one. Mirrors satellite's
# tests/test_audio_jitter.cpp case for case, because both ends of one stream
# have to agree on what counts as lost, late, or worth concealing.
add_executable(audio_jitter_test
    audio_jitter_test.cpp
)
target_include_directories(audio_jitter_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(audio_jitter_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(audio_jitter_test PRIVATE GTest::gtest_main)

# The bridge thread's per-bind connection-id refs: header-only and JNI-free (the refs are a
# template parameter), so "one string per bind, released once the bind is gone" is provable here.
add_executable(bridge_connection_ids_test
    bridge_connection_ids_test.cpp
)
target_include_directories(bridge_connection_ids_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(bridge_connection_ids_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(bridge_connection_ids_test PRIVATE GTest::gtest_main)

add_executable(heartbeat_thread_test
    heartbeat_thread_test.cpp
)
target_include_directories(heartbeat_thread_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(heartbeat_thread_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(heartbeat_thread_test PRIVATE GTest::gtest_main Threads::Threads)

# The opt-in hot-path bench: std-only, so its windows, percentiles and the ping policy the
# heartbeat thread asks are provable without a device.
add_executable(hotpath_latency_test
    hotpath_latency_test.cpp
    "${SATELLITE_CPP}/hotpath_latency.cpp"
)
target_include_directories(hotpath_latency_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(hotpath_latency_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(hotpath_latency_test PRIVATE GTest::gtest_main Threads::Threads)

# The libopus wrapper, round-tripped through real encoders and decoders. Needs
# the codec, so it fetches the same pinned release the app builds against (see
# src/main/cpp/CMakeLists.txt): testing the settings against a different libopus
# would prove nothing about the one that ships.
FetchContent_Declare(
    opus
    URL      https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
             https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(opus)

add_executable(audio_codec_test
    audio_codec_test.cpp
    "${SATELLITE_CPP}/audio_codec.cpp"
)
target_include_directories(audio_codec_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(audio_codec_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(audio_codec_test PRIVATE GTest::gtest_main opus)

# The JNI-free units the JNI layer drives: sessions, slots, the send path, the receive path and the
# queues that hand bridge reports and speaker audio to their threads.
# Their socket calls go through udp_socket.h, POSIX on a POSIX host and Winsock through the
# test-side udp_socket_windows.cpp on a Windows one.
set(DISH_CORE_SOURCES
    "${SATELLITE_CPP}/session_table.cpp"
    "${SATELLITE_CPP}/slot_table.cpp"
    "${SATELLITE_CPP}/send_path.cpp"
    "${SATELLITE_CPP}/receive_path.cpp"
    "${SATELLITE_CPP}/bridge_queue.cpp"
    "${SATELLITE_CPP}/speaker_queue.cpp"
    "${SATELLITE_CPP}/gamepad_input.cpp"
    "${SATELLITE_CPP}/hotpath_latency.cpp"
    "${SATELLITE_CPP}/audio_codec.cpp"
)
if(WIN32)
    list(APPEND DISH_CORE_SOURCES udp_socket_windows.cpp)
else()
    list(APPEND DISH_CORE_SOURCES "${SATELLITE_CPP}/udp_socket.cpp")
endif()
# dish_core_lockstats is the same units in the DISH_LOCK_STATS build: every named lock counts its
# acquisitions per thread role (tracked_mutex.h).
add_library(dish_core STATIC ${DISH_CORE_SOURCES})
add_library(dish_core_lockstats STATIC ${DISH_CORE_SOURCES})
target_compile_definitions(dish_core_lockstats PUBLIC DISH_LOCK_STATS)
foreach(core dish_core dish_core_lockstats)
    target_include_directories(${core} PUBLIC "${SATELLITE_CPP}")
    target_compile_options(${core} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(${core} PUBLIC sodium_host opus Threads::Threads)
    if(WIN32)
        target_link_libraries(${core} PUBLIC ws2_32)
    endif()
endforeach()

# The satellite's end of a session on loopback, for the tests that put datagrams on a socket.
add_library(fake_satellite STATIC fake_satellite.cpp)
target_include_directories(fake_satellite PUBLIC "${CMAKE_SOURCE_DIR}" "${SATELLITE_CPP}")
target_compile_options(fake_satellite PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(fake_satellite PUBLIC sodium_host)
if(WIN32)
    target_link_libraries(fake_satellite PUBLIC ws2_32)
endif()

# tracked_mutex.h as the app builds it, and in the DISH_LOCK_STATS build the lock laws and the
# bench's lock table read.
add_executable(tracked_mutex_test tracked_mutex_test.cpp)
add_executable(tracked_mutex_stats_test tracked_mutex_test.cpp)
target_compile_definitions(tracked_mutex_stats_test PRIVATE DISH_LOCK_STATS)
foreach(mutex_test tracked_mutex_test tracked_mutex_stats_test)
    target_include_directories(${mutex_test} PRIVATE "${SATELLITE_CPP}")
    target_compile_options(${mutex_test} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(${mutex_test} PRIVATE GTest::gtest_main Threads::Threads)
endforeach()

# The lock-free ring and the worker wake the hand-off queues are built on: header-only.
foreach(primitive_test mpmc_ring_test worker_wake_test)
    add_executable(${primitive_test} ${primitive_test}.cpp)
    target_include_directories(${primitive_test} PRIVATE "${SATELLITE_CPP}")
    target_compile_options(${primitive_test} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(${primitive_test} PRIVATE GTest::gtest_main Threads::Threads)
endforeach()

# send_path_test also holds the units to the bytes pinned in wire_vectors.h.
foreach(unit_test udp_socket_test session_table_test send_path_test slot_table_test
                  receive_path_test bridge_queue_test speaker_queue_test)
    add_executable(${unit_test} ${unit_test}.cpp)
    target_compile_options(${unit_test} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    target_link_libraries(${unit_test} PRIVATE GTest::gtest_main fake_satellite dish_core)
endforeach()

# Which locks each thread role takes on the hot path, held to lists that only shrink.
add_executable(lock_laws_test lock_laws_test.cpp)
target_compile_options(lock_laws_test PRIVATE -Wall -Wextra -Wpedantic -Werror)
target_link_libraries(lock_laws_test PRIVATE GTest::gtest_main fake_satellite dish_core_lockstats)

# The host bench (tools/bench/README.md, "Host bench"). Its --smoke runs every scenario briefly and
# fails only on the harness's own accounting, never on a latency.
option(DISH_BENCH "Build bench_send_path and run its --smoke under ctest" ON)
option(DISH_BENCH_LOCKSTATS "Build bench_send_path_lockstats, the bench with lock statistics" OFF)
if(DISH_BENCH)
    add_executable(bench_send_path send_path_bench.cpp)
    target_link_libraries(bench_send_path PRIVATE fake_satellite dish_core)
    add_test(NAME send_path_bench_smoke COMMAND bench_send_path --smoke)
endif()
if(DISH_BENCH_LOCKSTATS)
    add_executable(bench_send_path_lockstats send_path_bench.cpp)
    target_link_libraries(bench_send_path_lockstats PRIVATE fake_satellite dish_core_lockstats)
    add_test(NAME send_path_bench_lockstats_smoke
             COMMAND bench_send_path_lockstats --smoke
                     --json "${CMAKE_BINARY_DIR}/send_path_bench_lockstats_smoke.json")
endif()
foreach(bench bench_send_path bench_send_path_lockstats)
    if(TARGET ${bench})
        target_compile_options(${bench} PRIVATE -Wall -Wextra -Wpedantic -Werror)
    endif()
endforeach()

include(GoogleTest)

# DISCOVERY_MODE PRE_TEST, not the default POST_BUILD: CMake 4.x names the
# scratch file its post-build discovery step writes after a hash of an argument
# it never actually passes, so every target here writes the same
# cmake_test_discovery_*.json. That step is appended to the target's LINK
# command, so a parallel build races several targets over one file, and the run
# either fails outright or hands ctest one target's tests listed under another
# (seen locally on 4.4.0: totals both above and below the real one). Discovering
# at ctest time instead is serial by construction and generator-independent, so
# it holds for the Makefile generator CI uses as well as Ninja here.
gtest_discover_tests(gamepad_input_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(wire_encoders_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(usb_parsers_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(usb_hid_descriptor_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(send_counter_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(send_path_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(udp_socket_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(session_table_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(slot_table_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(receive_path_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(bridge_queue_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(speaker_queue_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(mpmc_ring_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(worker_wake_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(tracked_mutex_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(tracked_mutex_stats_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(lock_laws_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(audio_jitter_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(heartbeat_thread_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(hotpath_latency_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(audio_codec_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(bridge_connection_ids_test DISCOVERY_MODE PRE_TEST)

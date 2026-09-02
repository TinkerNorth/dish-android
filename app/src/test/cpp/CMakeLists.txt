# Host build for the pure gamepad-input layer. Replaces the JVM-side
# GamepadInputProcessorTest that was deleted when the processor moved into
# C++; without this target the keycode/deadzone/state-machine logic would
# have no test coverage at all.
#
# Driven by Gradle's :app:nativeTest task, not the AGP NDK build. Run
# manually:  cmake -S app/src/test/cpp -B build/native-test
#            cmake --build build/native-test
#            ctest --test-dir build/native-test --output-on-failure
cmake_minimum_required(VERSION 3.22)
project(satellite_host_tests CXX)

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

set(SATELLITE_CPP "${CMAKE_SOURCE_DIR}/../../main/cpp")

add_executable(gamepad_input_test
    gamepad_input_test.cpp
    "${SATELLITE_CPP}/gamepad_input.cpp"
)
target_include_directories(gamepad_input_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(gamepad_input_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(gamepad_input_test PRIVATE GTest::gtest_main)

# Pure wire-encoder unit tests: no Android / JNI dependency, just the LE
# byte-layout pins for MSG_MOTION (0x000A) and MSG_BATTERY (0x000B).
add_executable(wire_encoders_test
    wire_encoders_test.cpp
)
target_include_directories(wire_encoders_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(wire_encoders_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(wire_encoders_test PRIVATE GTest::gtest_main)

# usb_parsers.cpp fences its USB I/O behind __ANDROID__, so the decoders and rumble
# builders build and test host-side.
add_executable(usb_parsers_test
    usb_parsers_test.cpp
    "${SATELLITE_CPP}/usb_parsers.cpp"
    "${SATELLITE_CPP}/usb_hid_descriptor.cpp"
)
target_include_directories(usb_parsers_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(usb_parsers_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(usb_parsers_test PRIVATE GTest::gtest_main)

# Pure HID report-descriptor parser + layout-driven decode (no Android/USB I/O).
add_executable(usb_hid_descriptor_test
    usb_hid_descriptor_test.cpp
    "${SATELLITE_CPP}/usb_hid_descriptor.cpp"
)
target_include_directories(usb_hid_descriptor_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(usb_hid_descriptor_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(usb_hid_descriptor_test PRIVATE GTest::gtest_main)

# UDP send-counter exhaustion guard: the header the JNI send path draws every
# wire counter from (no wrap into ChaCha20 nonce reuse, contract §Crypto).
add_executable(send_counter_test
    send_counter_test.cpp
)
target_include_directories(send_counter_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(send_counter_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(send_counter_test PRIVATE GTest::gtest_main)

# Controller-audio reorder window. Header-only and dependency-free by design, so
# this target links no codec at all: the ordering rules are exactly the part
# that should be provable without one. Mirrors satellite's
# tests/test_audio_jitter.cpp case for case, because both ends of one stream
# have to agree on what counts as lost, late, or worth concealing.
add_executable(audio_jitter_test
    audio_jitter_test.cpp
)
target_include_directories(audio_jitter_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(audio_jitter_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(audio_jitter_test PRIVATE GTest::gtest_main)

# The libopus wrapper, round-tripped through real encoders and decoders. Needs
# the codec, so it fetches the same pinned release the app builds against (see
# src/main/cpp/CMakeLists.txt): testing the settings against a different libopus
# would prove nothing about the one that ships.
FetchContent_Declare(
    opus
    URL      https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
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
target_compile_options(audio_codec_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(audio_codec_test PRIVATE GTest::gtest_main opus)

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
gtest_discover_tests(audio_jitter_test DISCOVERY_MODE PRE_TEST)
gtest_discover_tests(audio_codec_test DISCOVERY_MODE PRE_TEST)

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

# Pull in googletest. Pinned to a specific tag — bump deliberately, the
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

# Pure wire-encoder unit tests — no Android / JNI dependency, just the LE
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
)
target_include_directories(usb_parsers_test PRIVATE "${SATELLITE_CPP}")
target_compile_options(usb_parsers_test PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(usb_parsers_test PRIVATE GTest::gtest_main)

include(GoogleTest)
gtest_discover_tests(gamepad_input_test)
gtest_discover_tests(wire_encoders_test)
gtest_discover_tests(usb_parsers_test)

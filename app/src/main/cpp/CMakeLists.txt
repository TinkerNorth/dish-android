cmake_minimum_required(VERSION 3.22.1)
project(satellite)

find_package(game-activity REQUIRED CONFIG)

add_library(satellite SHARED satellite_jni.cpp)

target_compile_options(satellite PRIVATE -O2 -Wall)

target_link_libraries(satellite
    log
    android
    -Wl,--whole-archive
    game-activity::game-activity_static
    -Wl,--no-whole-archive)

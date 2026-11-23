#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# The C++ host tests, on Linux only (they use sem_timedwait and SO_BUSY_POLL).
#
#   scripts/native-tests.sh host     the tests and the host bench with the lock-statistics build,
#                                    then the measurements report tests over the JSON the bench
#                                    smokes wrote
#   scripts/native-tests.sh armv7    the tests as 32-bit ARM binaries under qemu-arm; needs
#                                    g++-arm-linux-gnueabihf and qemu-user
#
# scripts/native-tests-docker.sh runs this inside a container, for a host that is not Linux.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

JOBS="${JOBS:-$(nproc)}"

case "${1:-}" in
  host)
    cmake -S app/src/test/cpp -B build/native-test -DDISH_BENCH_LOCKSTATS=ON
    cmake --build build/native-test --parallel "$JOBS"
    ctest --test-dir build/native-test --output-on-failure --no-tests=error
    build/native-test/bench_send_path --smoke
    # measurements.yml's report, also read against the JSON both smokes under ctest wrote.
    DISH_BENCH_JSON="build/native-test/send_path_bench_smoke.json:build/native-test/send_path_bench_lockstats_smoke.json" \
      python3 -m unittest discover -s scripts -p 'measurements_report_test.py' -t scripts
    ;;
  armv7)
    # NEON is part of Android's armeabi-v7a baseline, but this compiler defaults to VFPv3-D16
    # alone, which opus's NEON sources cannot build against.
    cmake -S app/src/test/cpp -B build/native-test-armv7 \
      -DCMAKE_SYSTEM_NAME=Linux \
      -DCMAKE_SYSTEM_PROCESSOR=arm \
      -DCMAKE_C_COMPILER=arm-linux-gnueabihf-gcc \
      -DCMAKE_CXX_COMPILER=arm-linux-gnueabihf-g++ \
      -DCMAKE_C_FLAGS=-mfpu=neon \
      -DCMAKE_CXX_FLAGS=-mfpu=neon \
      "-DCMAKE_CROSSCOMPILING_EMULATOR=qemu-arm;-L;/usr/arm-linux-gnueabihf"
    cmake --build build/native-test-armv7 --parallel "$JOBS"
    file build/native-test-armv7/gamepad_input_test
    file build/native-test-armv7/gamepad_input_test | grep -q 'ELF 32-bit LSB .* ARM'
    ctest --test-dir build/native-test-armv7 --output-on-failure --no-tests=error
    ;;
  *)
    echo "usage: native-tests.sh host|armv7" >&2
    exit 2
    ;;
esac

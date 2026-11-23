#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-3.0-or-later
# Copyright (C) 2026 Dish contributors.
# scripts/native-tests.sh inside an ubuntu:24.04 container, the image android-ci.yml's runner is,
# so the C++ host tests run on a Mac or Windows machine too.
#
#   scripts/native-tests-docker.sh [host|armv7|all]     default: all
#
# DOCKER_CPUS and DOCKER_MEMORY bound the container (default 4 CPUs, 3g).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

lane="${1:-all}"
cpus="${DOCKER_CPUS:-4}"
memory="${DOCKER_MEMORY:-3g}"

# The build tree lives in the container, not in the mounted repository: a host build directory
# holds the host's binaries, and a container build tree left behind would confuse a host build.
docker run --rm --cpus "$cpus" --memory "$memory" -e "JOBS=$cpus" -e "LANE=$lane" \
  -v "$PWD":/src:ro ubuntu:24.04 bash -euo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends ca-certificates cmake file g++ g++-arm-linux-gnueabihf make python3 qemu-user rsync >/dev/null
    rsync -a --exclude build --exclude .git --exclude .gradle --exclude .claude /src/ /work/
    cd /work
    if [ "$LANE" = host ] || [ "$LANE" = all ]; then scripts/native-tests.sh host; fi
    if [ "$LANE" = armv7 ] || [ "$LANE" = all ]; then scripts/native-tests.sh armv7; fi
  '

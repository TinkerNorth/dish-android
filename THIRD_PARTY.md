# Third-party attributions

This project bundles or derives from third-party material. The app's compiled dependencies
are listed in-app (Settings → Licenses, generated into
`app/src/main/assets/licenses/licenses.json`). Material that is not a build dependency is
attributed here.

## SDL (Simple DirectMedia Layer): controller IDs and Switch Pro motion scaling

The USB controller recognition table in `app/src/main/cpp/usb_parsers.cpp` (the `kImported`
array) is a curated subset of the vendor/product IDs and controller-type classifications in
SDL's `src/joystick/controller_list.h`. No SDL code is compiled into this project; only the
device-identification facts were reused, remapped to this project's USB report parsers, and
flagged unverified (recognized but never auto-claimed to Direct).

The default Switch Pro motion scaling in the same file also follows SDL's
values (gyro raw / 14.2842 deg/s, accel raw / 4096 g) before remapping to
the wire scale. That is a reuse of constants only, not SDL code.

SDL is distributed under the zlib license.

```
Simple DirectMedia Layer
Copyright (C) 1997-2024 Sam Lantinga <slouken@libsdl.org>
Controller list portions Copyright (C) Valve Corporation

This software is provided 'as-is', without any express or implied
warranty.  In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
```

Upstream: https://github.com/libsdl-org/SDL (`src/joystick/controller_list.h`)

## Linux kernel input/HID drivers: USB-direct rumble and motion math

The USB-direct output reports and some motion calibration in
`app/src/main/cpp/usb_parsers.cpp` reuse report byte layouts, init-packet
sequences, calibration constants, and an amplitude table documented in the
upstream Linux kernel drivers. No kernel source is compiled into this
project; only device-protocol facts were reused and remapped onto this
project's parsers:

- Xbox 360 and Xbox One (GIP) rumble reports and GIP init sequences, from
  `drivers/input/joystick/xpad.c`.
- DualShock 4 and DualSense IMU calibration (gyro 1024 units per deg/s,
  accel 8192 units per g), from `drivers/hid/hid-playstation.c`.
- Switch Pro HD-rumble amplitude table, from `drivers/hid/hid-nintendo.c`
  (`joycon_encode_rumble`, `joycon_rumble_amplitudes`).

The Linux kernel is licensed GPL-2.0-only. Upstream:
https://github.com/torvalds/linux

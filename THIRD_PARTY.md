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

The Steam Controller state-packet layout, button bit assignments, trigger full-scale, pad
rotation angle, feature-report framing, and setting/message identifiers in the same file follow
SDL's `src/joystick/hidapi/SDL_hidapi_steam.c` and the Valve-authored headers beside it
(`src/joystick/hidapi/steam/controller_structs.h` and `controller_constants.h`, Copyright (C)
2020-2021 Valve Corporation). Protocol facts only; no SDL code is compiled in.

Upstream: https://github.com/libsdl-org/SDL (`src/joystick/controller_list.h`,
`src/joystick/hidapi/steam/`)

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
- Steam Controller stand-alone-mode message sequence and its EPIPE retry, from
  `drivers/hid/hid-steam.c` (`steam_set_lizard_mode`, `steam_send_report`).

The Linux kernel is licensed GPL-2.0-only. Upstream:
https://github.com/torvalds/linux

## Wolf (Games on Whales): Moonlight protocol facts

The Moonlight (GameStream) client path in `app/src/main/java/com/tinkernorth/dish/core/net/moonlight/`
and `app/src/main/java/com/tinkernorth/dish/source/connection/moonlight/` derives its wire
formats, struct layouts, pairing crypto steps, control-stream packet framing, and RTSP handshake
from Wolf's documentation and its host-side (server) implementation:

- Control packet framing and AES-GCM sealing, plus the input/event struct layouts, follow
  `src/moonlight-protocol/moonlight/control.hpp` and `docs/.../control-specs.adoc` /
  `input-data.adoc`. The unit tests pin our encoder and sealer byte-for-byte against Wolf's
  captured vectors in `tests/testControl.cpp` and `tests/testCrypto.cpp`.
- The 5-phase PIN pairing crypto (AES key derivation, ECB challenge exchange, SHA-256 hashes,
  RSA signatures) mirrors Wolf's server logic in `src/moonlight-protocol/moonlight.cpp` and
  `src/moonlight-server/rest/endpoints.hpp`, implemented as the client counterpart.
- The RTSP request/response shapes follow `docs/.../rtsp.adoc` and
  `src/moonlight-protocol/rtsp/parser.hpp`.

No Wolf code is compiled into this project; only protocol facts and struct layouts were reused
and reimplemented in Kotlin. Wolf is licensed MIT. Upstream:
https://github.com/games-on-whales/wolf

## cgutman/enet: ENet client subset (ported to Kotlin)

`app/src/main/java/com/tinkernorth/dish/core/net/moonlight/enet/` is a minimal pure-Kotlin port
of the ENet reliable-UDP client subset the Moonlight control stream needs (the connect
handshake, reliable send/receive on one channel, acknowledgements, ping and disconnect). It was
ported from the MIT-licensed C source of the cgutman/enet fork (the fork and commit Wolf pins,
`44c85e16279553d9c052e572bcbfcd745fb74abf`): `host.c`, `peer.c`, `protocol.c`, and
`include/enet/protocol.h`. Also ported: the peer liveness rules, meaning the round-trip
estimate and retransmission timeout of `enet_protocol_handle_acknowledge` and the give-up
conditions of `enet_protocol_check_timeouts`, along with `protocol.c`'s `commandSizes` table.

Only the needed subset is reproduced. This client never *sends* a fragmented, unsequenced,
throttle or bandwidth command, and does not compress; it does measure and acknowledge all of
them on receive, because a peer packs several commands into one datagram and a command whose
size is unknown costs every command behind it. ENet is licensed MIT.

```
Copyright (c) 2002-2020 Lee Salzman

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

Upstream: https://github.com/cgutman/enet

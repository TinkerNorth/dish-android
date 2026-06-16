# Third-party attributions

This project bundles or derives from third-party material. The app's compiled dependencies
are listed in-app (Settings → Licenses, generated into
`app/src/main/assets/licenses/licenses.json`). Material that is not a build dependency is
attributed here.

## SDL (Simple DirectMedia Layer) — controller identification data

The USB controller recognition table in `app/src/main/cpp/usb_parsers.cpp` (the `kImported`
array) is a curated subset of the vendor/product IDs and controller-type classifications in
SDL's `src/joystick/controller_list.h`. No SDL code is compiled into this project; only the
device-identification facts were reused, remapped to this project's USB report parsers, and
flagged unverified (recognized but never auto-claimed to Direct).

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

// SPDX-License-Identifier: LGPL-3.0-or-later
// Copyright (C) 2026 Dish contributors.
//
// printf-style text appended to a std::string, for the diagnostics JSON the cold paths build.

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <string>

namespace dish_text {

// Formats into a stack buffer of `Scratch` bytes and appends what fits: text longer than the buffer
// is cut at its end, as snprintf into a fixed buffer always did, and an encoding error appends
// nothing. The call reads snprintf's result (cert-err33-c), so neither case goes unseen by the
// code. `fmt` takes at least one argument; a literal needs no format.
template <size_t Scratch, typename Arg, typename... Args>
void appendFormatted(std::string& out, const char* fmt, const Arg& arg, const Args&... args) {
    char buf[Scratch];
    const int written = std::snprintf(buf, sizeof(buf), fmt, arg, args...);
    if (written < 0) { return; }
    out.append(buf, std::min(static_cast<size_t>(written), sizeof(buf) - 1U));
}

} // namespace dish_text

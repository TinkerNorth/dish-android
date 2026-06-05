// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"

#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <algorithm>

#define TAG "SatelliteUsbParse"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace usbparsers {

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_BACK;
using gamepad::XUSB_DPAD_DOWN;
using gamepad::XUSB_DPAD_LEFT;
using gamepad::XUSB_DPAD_RIGHT;
using gamepad::XUSB_DPAD_UP;
using gamepad::XUSB_LB;
using gamepad::XUSB_RB;
using gamepad::XUSB_START;
using gamepad::XUSB_THUMB_L;
using gamepad::XUSB_THUMB_R;
using gamepad::XUSB_X;
using gamepad::XUSB_Y;

static const KnownDevice kKnown[] = {
    {0x045E, 0x028E, "Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x028F, "Xbox 360 Wireless Receiver (wired)", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x02A1, "Xbox 360 Wireless Controller (PC)", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x0291, "Xbox 360 Wireless Receiver (rev 1)", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x0719, "Xbox 360 Wireless Receiver (rev 2)", Parser::XINPUT_360, InitKind::NONE},

    {0x045E, 0x02D1, "Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02DD, "Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02E3, "Xbox One Elite Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02EA, "Xbox One S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02FD, "Xbox One S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B00, "Xbox Elite Series 2 Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B05, "Xbox Elite Series 2 Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B0A, "Xbox Adaptive Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B12, "Xbox Series X|S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B13, "Xbox Series X|S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B22, "Xbox Adaptive Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},

    {0x046D, 0xC218, "Logitech F310 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xC219, "Logitech F710 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xC21D, "Logitech F310 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xC21E, "Logitech F510 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xC21F, "Logitech F710 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xCAA3, "Logitech G29 Driving Force (XInput)", Parser::XINPUT_360, InitKind::NONE},

    {0x2DC8, 0x9000, "8BitDo Pro 2", Parser::XINPUT_360, InitKind::NONE},
    {0x2DC8, 0x9001, "8BitDo SN30 Pro", Parser::XINPUT_360, InitKind::NONE},
    {0x2DC8, 0x9003, "8BitDo SN30 Pro+", Parser::XINPUT_360, InitKind::NONE},
    {0x2DC8, 0x310A, "8BitDo Pro 2 (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x2DC8, 0x6101, "8BitDo Ultimate (XInput)", Parser::XINPUT_360, InitKind::NONE},
    {0x2DC8, 0x6001, "8BitDo M30", Parser::XINPUT_360, InitKind::NONE},

    {0x0738, 0x4716, "Mad Catz Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4726, "Mad Catz Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4728, "Mad Catz Street Fighter IV FightPad", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4736, "Mad Catz MicroCon Gamepad", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4738, "Mad Catz Wired Xbox 360 Controller (SFIV)", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4740, "Mad Catz Beat Pad", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xCB02, "Saitek Cyborg Rumble Pad PC/Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0105, "HSM3 Xbox360 dancepad", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0113, "Afterglow AX.1 Gamepad for Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x011F, "Rock Candy Wired Controller for Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0301, "Logic3 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0401, "Logic3 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0413, "Afterglow AX.1 Gen 2 for Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0501, "PDP Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x000A, "Hori Co. DOA4 FightStick", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x000D, "Hori Fighting Stick EX2", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x0016, "Hori Real Arcade Pro.EX", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x001B, "Hori Real Arcade Pro VX", Parser::XINPUT_360, InitKind::NONE},
    {0x1038, 0x1430, "SteelSeries Stratus Duo", Parser::XINPUT_360, InitKind::NONE},
    {0x1038, 0x1431, "SteelSeries Stratus Duo", Parser::XINPUT_360, InitKind::NONE},
    {0x12AB, 0x0004, "PowerA Pro Ex", Parser::XINPUT_360, InitKind::NONE},
    {0x12AB, 0x0301, "PDP AFTERGLOW AX.1", Parser::XINPUT_360, InitKind::NONE},
    {0x1532, 0x0037, "Razer Sabertooth", Parser::XINPUT_360, InitKind::NONE},
    {0x1532, 0x0A00, "Razer Atrox Arcade Stick", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5000, "Razer Atrox Arcade Stick", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5300, "PowerA MINI PROEX", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5303, "Xbox Airflo Wired Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5500, "Hori XBOX 360 EX 2 with Turbo", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5501, "Hori Real Arcade Pro VX-SA", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5506, "Hori SOULCALIBUR V Stick", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x550D, "Hori GEM Xbox controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x550E, "Hori Real Arcade Pro V Kai 360", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x551A, "PowerA FUSION Pro Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x561A, "PowerA FUSION Controller", Parser::XINPUT_360, InitKind::NONE},

    {0x0E6F, 0x0139, "Afterglow Prismatic Wired Xbox One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x013B, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0146, "Rock Candy Xbox One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0161, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0162, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0163, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0164, "PDP Battlefield One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0165, "PDP Titanfall 2", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0063, "Hori Real Arcade Pro Hayabusa (Xbox One)", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0067, "Hori HORIPAD ONE", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0078, "Hori Real Arcade Pro V Kai (Xbox One)", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x541A, "PowerA Xbox One Mini Wired", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x542A, "Xbox 360 Pro EX Controller (XOne)", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x543A, "PowerA Xbox One Wired", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x551A, "PowerA FUSION Pro Wired Xbox One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x561A, "PowerA FUSION Wired Xbox One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x791A, "PowerA Fusion FightPad", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x1532, 0x0A03, "Razer Wildcat", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},

    {0x054C, 0x05C4, "Sony DualShock 4 (CUH-ZCT1)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x054C, 0x09CC, "Sony DualShock 4 v2 (CUH-ZCT2)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x054C, 0x0BA0, "Sony DualShock 4 USB Wireless Adapter", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x005C, "Hori Real Arcade Pro 4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x005E, "Hori Fighting Commander PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x008C, "Hori Real Arcade Pro 4 V", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x00EE, "Hori Wired Mini PS4 Controller", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D01, "Nacon Revolution Pro Controller", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D02, "Nacon Revolution Pro Controller v2", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D08, "Nacon Daija Arcade Stick", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D10, "Nacon Revolution Infinite", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x0401, "Razer Panthera Arcade Stick", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1000, "Razer Raiju", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1100, "Razer Raiju Tournament", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1200, "Razer Raiju Ultimate", Parser::DUALSHOCK4, InitKind::NONE},
    {0x7545, 0x0104, "Armor3 Armor Titan", Parser::DUALSHOCK4, InitKind::NONE},

    {0x054C, 0x0CE6, "Sony DualSense", Parser::DUALSENSE, InitKind::NONE},
    {0x054C, 0x0DF2, "Sony DualSense Edge", Parser::DUALSENSE, InitKind::NONE},

    {0x057E, 0x2009, "Nintendo Switch Pro Controller", Parser::SWITCH_PRO_USB, InitKind::SWITCH_PRO_HANDSHAKE},
    {0x057E, 0x200E, "Nintendo Joy-Con Charging Grip", Parser::SWITCH_PRO_USB, InitKind::SWITCH_PRO_HANDSHAKE},
    {0x057E, 0x2017, "Nintendo SNES Online Controller", Parser::SWITCH_PRO_USB, InitKind::SWITCH_PRO_HANDSHAKE},

    {0x18D1, 0x9400, "Google Stadia Controller", Parser::STADIA, InitKind::NONE},
};

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid) {
    for (const auto& d : kKnown) {
        if (d.vid == vid && d.pid == pid) return &d;
    }
    return nullptr;
}

const char* parserName(Parser p) {
    switch (p) {
    case Parser::XINPUT_360:
        return "Xbox 360 protocol";
    case Parser::XBOX_ONE_GIP:
        return "Xbox One protocol";
    case Parser::DUALSHOCK4:
        return "DualShock 4 protocol";
    case Parser::DUALSENSE:
        return "DualSense protocol";
    case Parser::SWITCH_PRO_USB:
        return "Switch Pro protocol";
    case Parser::STADIA:
        return "Stadia protocol";
    case Parser::GENERIC_HID_GAMEPAD:
        return "Generic HID gamepad";
    case Parser::NONE:
        return "Unknown";
    }
    return "Unknown";
}

bool parserHasImu(Parser p) {
    return p == Parser::SWITCH_PRO_USB;
}

namespace {

bool bulkWrite(int fd, uint8_t epOut, const uint8_t* data, size_t len, unsigned timeoutMs) {
    if (epOut == 0) return false;
    struct usbdevfs_bulktransfer xfer = {};
    xfer.ep = epOut;
    xfer.len = (unsigned int)len;
    xfer.timeout = timeoutMs;
    xfer.data = (void*)data;
    int n = ioctl(fd, USBDEVFS_BULK, &xfer);
    if (n < 0) {
        LOGE("USBDEVFS_BULK out to 0x%02X failed: %s", epOut, strerror(errno));
        return false;
    }
    return (size_t)n == len;
}

int16_t scaleU8Centered(uint8_t v, bool invert) {
    int32_t s = invert ? (128 - (int32_t)v) : ((int32_t)v - 128);
    int32_t scaled = s * 257;
    if (scaled > 32767) scaled = 32767;
    if (scaled < -32768) scaled = -32768;
    return (int16_t)scaled;
}

// Maps a 12-bit Switch stick value (centered near 2048) to a full-range XUSB axis. The push and
// pull sides of each axis are auto-ranged independently because a Pro Controller's throw is usually
// asymmetric: scaling both sides by one shared reach leaves the smaller side short of the rail.
// Each side stretches its own learned reach to the full extent, so every direction can hit the
// edge. Center stays at the nominal 2048.
// Inner deadzone in the raw 12-bit domain. The Switch Pro's stick center wanders per unit (a resting
// stick can sit a few hundred counts off 2048) and we read no factory calibration, so without this
// the auto-range amplifies that offset into large resting drift. Counts within the deadzone read as
// center; the throw beyond it is auto-ranged to full scale.
static constexpr int32_t kSwitchStickRawDeadzone = 320;

int16_t scaleSwitchStickAuto(uint16_t raw12, AxisAutoRange& axis) {
    int32_t centered = (int32_t)raw12 - 2048;
    int32_t mag = centered >= 0 ? centered : -centered;
    if (mag <= kSwitchStickRawDeadzone) return 0;
    int32_t adj = mag - kSwitchStickRawDeadzone;
    if (centered >= 0) {
        if (adj > axis.posReach) axis.posReach = adj;
        int32_t scaled = (adj * 32767) / axis.posReach;
        if (scaled > 32767) scaled = 32767;
        return (int16_t)scaled;
    }
    if (adj > axis.negReach) axis.negReach = adj;
    int32_t scaled = (adj * 32768) / axis.negReach;
    if (scaled > 32768) scaled = 32768;
    return (int16_t)(-scaled);
}

int16_t rdLe16(const uint8_t* b, int off) {
    return (int16_t)((uint16_t)b[off] | ((uint16_t)b[off + 1] << 8));
}

// Switch Pro IMU default scaling (no factory calibration yet). SDL uses gyro = raw / 14.2842 deg/s
// and accel = raw / 4096 g; the wire format wants deg/s / 2000 * 32767 and g / 4 * 32767, so the
// combined integer factors are 32767/28568 (gyro) and 32767/16384 (accel).
int16_t switchGyroToWire(int16_t raw) {
    int64_t wire = (int64_t)raw * 32767 / 28568;
    if (wire > 32767) wire = 32767;
    if (wire < -32768) wire = -32768;
    return (int16_t)wire;
}

int16_t switchAccelToWire(int16_t raw) {
    int64_t wire = (int64_t)raw * 32767 / 16384;
    if (wire > 32767) wire = 32767;
    if (wire < -32768) wire = -32768;
    return (int16_t)wire;
}

uint16_t setDpadFromHat(uint16_t buttons, uint8_t hat) {
    buttons = (uint16_t)(buttons & ~(XUSB_DPAD_UP | XUSB_DPAD_DOWN | XUSB_DPAD_LEFT | XUSB_DPAD_RIGHT));
    switch (hat & 0x0F) {
    case 0:
        buttons |= XUSB_DPAD_UP;
        break;
    case 1:
        buttons |= (uint16_t)(XUSB_DPAD_UP | XUSB_DPAD_RIGHT);
        break;
    case 2:
        buttons |= XUSB_DPAD_RIGHT;
        break;
    case 3:
        buttons |= (uint16_t)(XUSB_DPAD_DOWN | XUSB_DPAD_RIGHT);
        break;
    case 4:
        buttons |= XUSB_DPAD_DOWN;
        break;
    case 5:
        buttons |= (uint16_t)(XUSB_DPAD_DOWN | XUSB_DPAD_LEFT);
        break;
    case 6:
        buttons |= XUSB_DPAD_LEFT;
        break;
    case 7:
        buttons |= (uint16_t)(XUSB_DPAD_UP | XUSB_DPAD_LEFT);
        break;
    default:
        break;
    }
    return buttons;
}

// Xbox 360 wired interrupt-IN report. Fixed 20 bytes; byte 0 is report type (0x00 for input),
// byte 1 is the length. Stick axes are little-endian int16, triggers are 8-bit unsigned.
bool decodeXInput360(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 14) return false;
    if (buf[0] != 0x00) return false;

    uint16_t b = 0;
    if (buf[2] & 0x01) b |= XUSB_DPAD_UP;
    if (buf[2] & 0x02) b |= XUSB_DPAD_DOWN;
    if (buf[2] & 0x04) b |= XUSB_DPAD_LEFT;
    if (buf[2] & 0x08) b |= XUSB_DPAD_RIGHT;
    if (buf[2] & 0x10) b |= XUSB_START;
    if (buf[2] & 0x20) b |= XUSB_BACK;
    if (buf[2] & 0x40) b |= XUSB_THUMB_L;
    if (buf[2] & 0x80) b |= XUSB_THUMB_R;
    if (buf[3] & 0x01) b |= XUSB_LB;
    if (buf[3] & 0x02) b |= XUSB_RB;
    if (buf[3] & 0x10) b |= XUSB_A;
    if (buf[3] & 0x20) b |= XUSB_B;
    if (buf[3] & 0x40) b |= XUSB_X;
    if (buf[3] & 0x80) b |= XUSB_Y;
    s.wButtons = b;

    s.bLT = buf[4];
    s.bRT = buf[5];

    s.sLX = (int16_t)((uint16_t)buf[6] | ((uint16_t)buf[7] << 8));
    s.sLY = (int16_t)((uint16_t)buf[8] | ((uint16_t)buf[9] << 8));
    s.sRX = (int16_t)((uint16_t)buf[10] | ((uint16_t)buf[11] << 8));
    s.sRY = (int16_t)((uint16_t)buf[12] | ((uint16_t)buf[13] << 8));
    return true;
}

// Xbox One GIP input report 0x20. Triggers are 10-bit little-endian (0..1023); scaled to XUSB's
// 0..255 below. Sticks are little-endian int16, same convention as XInput.
bool decodeXboxOneGip(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 18) return false;
    if (buf[0] != 0x20) return false;

    uint16_t b = 0;
    if (buf[4] & 0x04) b |= XUSB_START;
    if (buf[4] & 0x08) b |= XUSB_BACK;
    if (buf[4] & 0x10) b |= XUSB_A;
    if (buf[4] & 0x20) b |= XUSB_B;
    if (buf[4] & 0x40) b |= XUSB_X;
    if (buf[4] & 0x80) b |= XUSB_Y;
    if (buf[5] & 0x01) b |= XUSB_DPAD_UP;
    if (buf[5] & 0x02) b |= XUSB_DPAD_DOWN;
    if (buf[5] & 0x04) b |= XUSB_DPAD_LEFT;
    if (buf[5] & 0x08) b |= XUSB_DPAD_RIGHT;
    if (buf[5] & 0x10) b |= XUSB_LB;
    if (buf[5] & 0x20) b |= XUSB_RB;
    if (buf[5] & 0x40) b |= XUSB_THUMB_L;
    if (buf[5] & 0x80) b |= XUSB_THUMB_R;
    s.wButtons = b;

    uint16_t lt = (uint16_t)buf[6] | ((uint16_t)buf[7] << 8);
    uint16_t rt = (uint16_t)buf[8] | ((uint16_t)buf[9] << 8);
    if (lt > 1023) lt = 1023;
    if (rt > 1023) rt = 1023;
    s.bLT = (uint8_t)((lt * 255) / 1023);
    s.bRT = (uint8_t)((rt * 255) / 1023);

    s.sLX = (int16_t)((uint16_t)buf[10] | ((uint16_t)buf[11] << 8));
    s.sLY = (int16_t)((uint16_t)buf[12] | ((uint16_t)buf[13] << 8));
    s.sRX = (int16_t)((uint16_t)buf[14] | ((uint16_t)buf[15] << 8));
    s.sRY = (int16_t)((uint16_t)buf[16] | ((uint16_t)buf[17] << 8));
    return true;
}

// DualShock 4 USB report 0x01. Sticks are uint8 with 128 = center. Y axes are down-positive so
// they're inverted to match XUSB's up-positive convention. Face buttons are remapped to the
// XInput "muscle memory" positions: Cross is A, Circle is B, Square is X, Triangle is Y.
bool decodeDualShock4(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 10) return false;
    if (buf[0] != 0x01) return false;

    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);

    uint16_t b = 0;
    if (buf[5] & 0x10) b |= XUSB_X;      // Square
    if (buf[5] & 0x20) b |= XUSB_A;      // Cross
    if (buf[5] & 0x40) b |= XUSB_B;      // Circle
    if (buf[5] & 0x80) b |= XUSB_Y;      // Triangle
    if (buf[6] & 0x01) b |= XUSB_LB;     // L1
    if (buf[6] & 0x02) b |= XUSB_RB;     // R1
    if (buf[6] & 0x10) b |= XUSB_BACK;   // Share
    if (buf[6] & 0x20) b |= XUSB_START;  // Options
    if (buf[6] & 0x40) b |= XUSB_THUMB_L;
    if (buf[6] & 0x80) b |= XUSB_THUMB_R;
    b = setDpadFromHat(b, buf[5] & 0x0F);
    s.wButtons = b;

    s.bLT = buf[8];
    s.bRT = buf[9];
    return true;
}

// DualSense USB report 0x01. Same axis conventions as DS4 but the byte layout shifts: triggers
// move to bytes 5/6 and the button bytes are at 8/9/10.
bool decodeDualSense(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 11) return false;
    if (buf[0] != 0x01) return false;

    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);

    s.bLT = buf[5];
    s.bRT = buf[6];

    uint16_t b = 0;
    if (buf[8] & 0x10) b |= XUSB_X;      // Square
    if (buf[8] & 0x20) b |= XUSB_A;      // Cross
    if (buf[8] & 0x40) b |= XUSB_B;      // Circle
    if (buf[8] & 0x80) b |= XUSB_Y;      // Triangle
    if (buf[9] & 0x01) b |= XUSB_LB;
    if (buf[9] & 0x02) b |= XUSB_RB;
    if (buf[9] & 0x10) b |= XUSB_BACK;   // Create
    if (buf[9] & 0x20) b |= XUSB_START;  // Options
    if (buf[9] & 0x40) b |= XUSB_THUMB_L;
    if (buf[9] & 0x80) b |= XUSB_THUMB_R;
    b = setDpadFromHat(b, buf[8] & 0x0F);
    s.wButtons = b;
    return true;
}

// Switch Pro standard full input report 0x30 over USB. Buttons are split across three bytes
// (right/shared/left) and sticks are packed 12-bit values. The XUSB mapping matches by physical
// position rather than label, the same convention used for DualShock 4: Switch A (right face) →
// XUSB_B (right face), Switch B (bottom) → XUSB_A (bottom), Switch X (top) → XUSB_Y, Switch Y
// (left) → XUSB_X. This is what PC games and ViGEm expect.
bool decodeSwitchProUsb(const uint8_t* buf, size_t len, DeviceState& s, StickAutoRange& sticks) {
    if (len < 12) return false;
    if (buf[0] != 0x30) return false;

    const uint8_t br = buf[3];
    const uint8_t bs = buf[4];
    const uint8_t bl = buf[5];

    uint16_t b = 0;
    if (br & 0x01) b |= XUSB_X;     // Y label (left position) → XUSB X
    if (br & 0x02) b |= XUSB_Y;     // X label (top position) → XUSB Y
    if (br & 0x04) b |= XUSB_A;     // B label (bottom position) → XUSB A
    if (br & 0x08) b |= XUSB_B;     // A label (right position) → XUSB B
    if (br & 0x40) b |= XUSB_RB;
    if (bs & 0x01) b |= XUSB_BACK;  // Minus
    if (bs & 0x02) b |= XUSB_START; // Plus
    if (bs & 0x04) b |= XUSB_THUMB_R;
    if (bs & 0x08) b |= XUSB_THUMB_L;
    if (bl & 0x01) b |= XUSB_DPAD_DOWN;
    if (bl & 0x02) b |= XUSB_DPAD_UP;
    if (bl & 0x04) b |= XUSB_DPAD_RIGHT;
    if (bl & 0x08) b |= XUSB_DPAD_LEFT;
    if (bl & 0x40) b |= XUSB_LB;
    s.wButtons = b;

    // ZL/ZR are digital on the Pro, so triggers are either fully pressed or released.
    s.bLT = (bl & 0x80) ? 255 : 0;
    s.bRT = (br & 0x80) ? 255 : 0;

    const uint16_t lx = (uint16_t)buf[6] | (((uint16_t)buf[7] & 0x0F) << 8);
    const uint16_t ly = ((uint16_t)buf[7] >> 4) | ((uint16_t)buf[8] << 4);
    const uint16_t rx = (uint16_t)buf[9] | (((uint16_t)buf[10] & 0x0F) << 8);
    const uint16_t ry = ((uint16_t)buf[10] >> 4) | ((uint16_t)buf[11] << 4);

    s.sLX = scaleSwitchStickAuto(lx, sticks.lx);
    s.sLY = scaleSwitchStickAuto(ly, sticks.ly);
    s.sRX = scaleSwitchStickAuto(rx, sticks.rx);
    s.sRY = scaleSwitchStickAuto(ry, sticks.ry);

    // IMU: three 12-byte frames start at byte 13 (accel int16 LE at +0/+2/+4, gyro at +6/+8/+10).
    // Use the first frame. Straight axis mapping; signs may need an on-device flip to match the
    // wire convention.
    if (len >= 25) {
        s.accelX = switchAccelToWire(rdLe16(buf, 13));
        s.accelY = switchAccelToWire(rdLe16(buf, 15));
        s.accelZ = switchAccelToWire(rdLe16(buf, 17));
        s.gyroX = switchGyroToWire(rdLe16(buf, 19));
        s.gyroY = switchGyroToWire(rdLe16(buf, 21));
        s.gyroZ = switchGyroToWire(rdLe16(buf, 23));
        s.motionValid = true;
    }
    return true;
}

// Stadia controller HID report 0x03 (basic gamepad layout).
bool decodeStadia(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 11) return false;
    if (buf[0] != 0x03) return false;
    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);
    s.bLT = buf[5];
    s.bRT = buf[6];
    uint16_t b = 0;
    if (buf[8] & 0x40) b |= XUSB_A;
    if (buf[8] & 0x20) b |= XUSB_B;
    if (buf[8] & 0x10) b |= XUSB_X;
    if (buf[8] & 0x08) b |= XUSB_Y;
    if (buf[8] & 0x04) b |= XUSB_LB;
    if (buf[8] & 0x02) b |= XUSB_RB;
    if (buf[9] & 0x80) b |= XUSB_START;
    if (buf[9] & 0x40) b |= XUSB_BACK;
    if (buf[9] & 0x20) b |= XUSB_THUMB_L;
    if (buf[9] & 0x10) b |= XUSB_THUMB_R;
    b = setDpadFromHat(b, buf[7] & 0x0F);
    s.wButtons = b;
    return true;
}

} // namespace

bool decodeReport(Parser p, const uint8_t* buf, size_t len, DeviceState& s, StickAutoRange* sticks) {
    switch (p) {
    case Parser::XINPUT_360:
        return decodeXInput360(buf, len, s);
    case Parser::XBOX_ONE_GIP:
        return decodeXboxOneGip(buf, len, s);
    case Parser::DUALSHOCK4:
        return decodeDualShock4(buf, len, s);
    case Parser::DUALSENSE:
        return decodeDualSense(buf, len, s);
    case Parser::SWITCH_PRO_USB:
        return sticks != nullptr && decodeSwitchProUsb(buf, len, s, *sticks);
    case Parser::STADIA:
        return decodeStadia(buf, len, s);
    case Parser::GENERIC_HID_GAMEPAD:
        return decodeGenericHidGamepad(buf, len, s);
    case Parser::NONE:
        return false;
    }
    return false;
}

bool decodeGenericHidGamepad(const uint8_t* buf, size_t len, DeviceState& s) {
    // Conservative shape check: most generic HID gamepads produce reports >= 7 bytes (4 axes,
    // hat+buttons low/high). Anything shorter probably isn't gamepad-shaped; bail rather than
    // publish noise.
    if (len < 7) return false;
    s.sLX = scaleU8Centered(buf[0], false);
    s.sLY = scaleU8Centered(buf[1], true);
    s.sRX = scaleU8Centered(buf[2], false);
    s.sRY = scaleU8Centered(buf[3], true);
    uint16_t b = 0;
    uint8_t hat = buf[4] & 0x0F;
    b = setDpadFromHat(b, hat);
    uint8_t btnLo = buf[4];
    uint8_t btnHi = len > 5 ? buf[5] : 0;
    if (btnLo & 0x10) b |= XUSB_A;
    if (btnLo & 0x20) b |= XUSB_B;
    if (btnLo & 0x40) b |= XUSB_X;
    if (btnLo & 0x80) b |= XUSB_Y;
    if (btnHi & 0x01) b |= XUSB_LB;
    if (btnHi & 0x02) b |= XUSB_RB;
    if (btnHi & 0x04) b |= XUSB_BACK;
    if (btnHi & 0x08) b |= XUSB_START;
    if (btnHi & 0x10) b |= XUSB_THUMB_L;
    if (btnHi & 0x20) b |= XUSB_THUMB_R;
    s.wButtons = b;
    s.bLT = (btnHi & 0x40) ? 255 : 0;
    s.bRT = (btnHi & 0x80) ? 255 : 0;
    return true;
}

bool runInit(int fd, uint8_t epOut, Parser p, InitKind init) {
    switch (init) {
    case InitKind::NONE:
        return true;
    case InitKind::XBOX_ONE_POWERON: {
        // GIP "power on" sequence: tells the controller to start sending input reports. Without
        // this, modern Xbox One/Series pads stay silent on the IN endpoint.
        static const uint8_t kPowerOn[] = {0x05, 0x20, 0x00, 0x01, 0x00};
        bool ok = bulkWrite(fd, epOut, kPowerOn, sizeof(kPowerOn), 200);
        if (!ok) LOGE("Xbox One power-on write failed");
        return ok;
    }
    case InitKind::SWITCH_PRO_HANDSHAKE: {
        (void)p;
        if (epOut == 0) {
            LOGE("Switch Pro: no OUT endpoint, cannot init");
            return false;
        }
        // Status request. The Pro responds with controller info on its IN endpoint; we don't
        // need to read the reply, only send the request so the device transitions out of any
        // residual state the kernel driver left it in when we stole the interface.
        static const uint8_t kStatus[] = {0x80, 0x02};
        if (!bulkWrite(fd, epOut, kStatus, sizeof(kStatus), 100)) {
            LOGE("Switch Pro: status request failed");
            return false;
        }
        usleep(40000);

        // Disable USB timeout. Without this the controller sleeps after a few seconds of idle
        // and stops emitting input reports.
        static const uint8_t kDisableTimeout[] = {0x80, 0x04};
        if (!bulkWrite(fd, epOut, kDisableTimeout, sizeof(kDisableTimeout), 100)) {
            LOGI("Switch Pro: disable-timeout write failed (non-fatal)");
        }
        usleep(40000);

        // Set input report mode 0x30 (standard full report: buttons + sticks + IMU). The format
        // is one rumble + subcommand HID output report: report id 0x01, packet counter, 8-byte
        // neutral rumble pattern, subcommand id 0x03, argument 0x30.
        uint8_t setReportMode[] = {
            0x01,
            0x00,
            0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40,
            0x03, 0x30,
        };
        if (!bulkWrite(fd, epOut, setReportMode, sizeof(setReportMode), 200)) {
            LOGE("Switch Pro: set-report-mode write failed");
            return false;
        }
        LOGI("Switch Pro USB init sequence sent");
        return true;
    }
    }
    return false;
}

} // namespace usbparsers

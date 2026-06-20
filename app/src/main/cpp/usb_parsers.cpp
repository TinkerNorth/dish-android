// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"

#include <string.h>
#include <algorithm>

// The decoders and report builders here are pure and host-tested (usb_parsers_test.cpp). Only the
// USB transfer helpers need the kernel ioctls, so they are fenced to the Android build.
#ifdef __ANDROID__
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>

#define TAG "SatelliteUsbParse"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#endif

namespace usbparsers {

using gamepad::DeviceState;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_BACK;
using gamepad::XUSB_DPAD_DOWN;
using gamepad::XUSB_DPAD_LEFT;
using gamepad::XUSB_DPAD_RIGHT;
using gamepad::XUSB_DPAD_UP;
using gamepad::XUSB_GUIDE;
using gamepad::XUSB_LB;
using gamepad::XUSB_RB;
using gamepad::XUSB_START;
using gamepad::XUSB_THUMB_L;
using gamepad::XUSB_THUMB_R;
using gamepad::XUSB_X;
using gamepad::XUSB_Y;

// DualShock 4 / DualSense calibrated-IMU resolution (Linux hid-playstation).
static constexpr int32_t kPsGyroResPerDegS = 1024;
static constexpr int32_t kPsAccelResPerG = 8192;

static const KnownDevice kKnown[] = {
    {0x045E, 0x028E, "Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x028F, "Xbox 360 Wireless Receiver (wired)", Parser::XINPUT_360, InitKind::NONE},
    {0x045E, 0x02A1, "Xbox 360 Wireless Controller (PC)", Parser::XINPUT_360_WIRELESS,
     InitKind::NONE},
    {0x045E, 0x0291, "Xbox 360 Wireless Receiver (rev 1)", Parser::XINPUT_360_WIRELESS,
     InitKind::NONE},
    {0x045E, 0x0719, "Xbox 360 Wireless Receiver (rev 2)", Parser::XINPUT_360_WIRELESS,
     InitKind::NONE},

    {0x045E, 0x02D1, "Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02DD, "Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02E3, "Xbox One Elite Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02EA, "Xbox One S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_S},
    {0x045E, 0x02FD, "Xbox One S Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B00, "Xbox Elite Series 2 Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_S},
    {0x045E, 0x0B05, "Xbox Elite Series 2 Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B0A, "Xbox Adaptive Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B12, "Xbox Series X|S Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x0B13, "Xbox Series X|S Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
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
    {0x0738, 0x4738, "Mad Catz Wired Xbox 360 Controller (SFIV)", Parser::XINPUT_360,
     InitKind::NONE},
    {0x0738, 0x4740, "Mad Catz Beat Pad", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xCB02, "Saitek Cyborg Rumble Pad PC/Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0105, "HSM3 Xbox360 dancepad", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0113, "Afterglow AX.1 Gamepad for Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x011F, "Rock Candy Wired Controller for Xbox 360", Parser::XINPUT_360,
     InitKind::NONE},
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

    {0x0E6F, 0x0139, "Afterglow Prismatic Wired Xbox One", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x013B, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0146, "Rock Candy Xbox One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0161, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0162, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0163, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0164, "PDP Battlefield One", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0165, "PDP Titanfall 2", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0063, "Hori Real Arcade Pro Hayabusa (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0067, "Hori HORIPAD ONE", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0078, "Hori Real Arcade Pro V Kai (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x541A, "PowerA Xbox One Mini Wired", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x542A, "Xbox 360 Pro EX Controller (XOne)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x543A, "PowerA Xbox One Wired", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x551A, "PowerA FUSION Pro Wired Xbox One", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x561A, "PowerA FUSION Wired Xbox One", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
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

    {0x057E, 0x2009, "Nintendo Switch Pro Controller", Parser::SWITCH_PRO_USB,
     InitKind::SWITCH_PRO_HANDSHAKE},
    {0x057E, 0x200E, "Nintendo Joy-Con Charging Grip", Parser::SWITCH_PRO_USB,
     InitKind::SWITCH_PRO_HANDSHAKE},
    {0x057E, 0x2017, "Nintendo SNES Online Controller", Parser::SWITCH_PRO_USB,
     InitKind::SWITCH_PRO_HANDSHAKE},

    {0x18D1, 0x9400, "Google Stadia Controller", Parser::STADIA, InitKind::NONE},
};

// Device IDs below are a curated subset of SDL's src/joystick/controller_list.h (zlib license,
// Copyright (C) Valve Corporation; see THIRD_PARTY.md). They are NOT hardware-verified here:
// lookupKnown recognises them (name + the family parser) and the user can opt into Direct, where
// probeDecodable still guards the byte layout, but isVerifiedFastLane returns false for them so
// auto-claim never silently moves an untested model onto Direct. Bluetooth-only PIDs, USB-differs
// entries, dongles that re-enumerate, and non-gamepads (wheels/guitars) from SDL are omitted.
static const KnownDevice kImported[] = {
    {0x0079, 0x18D4, "GPD Win 2 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x044F, 0xB326, "Thrustmaster Gamepad GP XID", Parser::XINPUT_360, InitKind::NONE},
    {0x046D, 0xC242, "Logitech ChillStream", Parser::XINPUT_360, InitKind::NONE},
    {0x056E, 0x2004, "Elecom JC-U3613M", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0x4718, "Mad Catz SFIV FightStick SE", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xB726, "Mad Catz Xbox 360 Controller (MW2)", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xBEEF, "Mad Catz JOYTECH NEO SE", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xCB03, "Saitek P3200 Rumble Pad", Parser::XINPUT_360, InitKind::NONE},
    {0x0738, 0xF738, "Mad Catz Super SFIV FightStick TE S", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0125, "PDP Injustice FightStick (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0127, "PDP Injustice FightPad (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0131, "PDP EA Soccer Gamepad", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0133, "PDP Battlefield 4 Gamepad", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0143, "PDP Mortal Kombat X FightStick (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0147, "PDP Marvel Controller (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0201, "PDP Gamepad for Xbox 360", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0213, "PDP Afterglow Gamepad (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x021F, "PDP Rock Candy Gamepad (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0313, "PDP Afterglow Gamepad (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0x0314, "PDP Afterglow Gamepad (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0E6F, 0xF900, "PDP Afterglow AX.1 (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x000C, "Hori Pad EX Turbo", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x00DB, "Hori Dragon Quest Slime Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x0F0D, 0x011E, "Hori Fighting Stick Alpha (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x11C9, 0x55F0, "Nacon GC-100XF", Parser::XINPUT_360, InitKind::NONE},
    {0x12AB, 0x0303, "Mortal Kombat Klassic FightStick", Parser::XINPUT_360, InitKind::NONE},
    {0x146B, 0x0601, "BigBen Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x15E4, 0x3F00, "PowerA Mini Pro Elite", Parser::XINPUT_360, InitKind::NONE},
    {0x15E4, 0x3F0A, "Xbox Airflo Wired Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x15E4, 0x3F10, "Batarang Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x162E, 0xBEEF, "Joytech Neo-Se Take2", Parser::XINPUT_360, InitKind::NONE},
    {0x1689, 0xFD00, "Razer Onza Tournament Edition", Parser::XINPUT_360, InitKind::NONE},
    {0x1689, 0xFD01, "Razer Onza Classic Edition", Parser::XINPUT_360, InitKind::NONE},
    {0x1689, 0xFE00, "Razer Sabertooth", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF016, "Mad Catz Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF018, "Mad Catz SFIV SE FightStick", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF019, "Mad Catz Brawlstick (360)", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF021, "Mad Catz Ghost Recon FS Gamepad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF023, "MLG Pro Circuit Controller (Xbox)", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF025, "Mad Catz Call of Duty FightPad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF027, "Mad Catz FPS Pro", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF028, "Street Fighter IV FightPad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF02E, "Mad Catz FightPad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF036, "Mad Catz MicroCon Gamepad Pro", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF038, "Street Fighter IV FightStick TE", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF039, "Mad Catz MvC2 TE", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF03A, "Mad Catz SFxT FightStick Pro", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF03D, "SFIV Arcade Stick TE (Chun Li)", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF03E, "Mad Catz MLG FightStick TE", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF03F, "Mad Catz FightStick SoulCalibur", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF042, "Mad Catz FightStick TES+", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF080, "Mad Catz FightStick TE2", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF501, "HoriPad EX2 Turbo", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF502, "Hori Real Arcade Pro VX SA", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF503, "Hori Fighting Stick VX", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF504, "Hori Real Arcade Pro EX", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF505, "Hori Fighting Stick EX2B", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF506, "Hori Real Arcade Pro EX Premium VLX", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF900, "Harmonix Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF901, "GameStop Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF902, "Mad Catz Gamepad 2", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF903, "Tron Xbox 360 Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF904, "PDP Versus Fighting Pad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xF906, "Mortal Kombat FightStick", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xFA01, "Mad Catz Gamepad", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xFD00, "Razer Onza TE", Parser::XINPUT_360, InitKind::NONE},
    {0x1BAD, 0xFD01, "Razer Onza", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x530A, "Xbox 360 Pro EX Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x531A, "PowerA Pro Ex", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5397, "FUS1ON Tournament Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5502, "Hori Fighting Stick VX Alt", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5503, "Hori Fighting Edge", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5508, "Hori Pad A", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5510, "Hori Fighting Commander ONE", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5B02, "Thrustmaster GPX Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0x5D04, "Razer Sabertooth", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0xFAFA, "Aplay Controller", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0xFAFC, "Afterglow Gamepad 1", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0xFAFD, "Afterglow Gamepad 3", Parser::XINPUT_360, InitKind::NONE},
    {0x24C6, 0xFAFE, "Rock Candy Gamepad (360)", Parser::XINPUT_360, InitKind::NONE},

    {0x03F0, 0x0495, "HP HyperX Clutch Gladiate", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x044F, 0xD012, "Thrustmaster eSwap Pro (Xbox)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x045E, 0x02FF, "Xbox One Controller (GIP)", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0738, 0x4A01, "Mad Catz FightStick TE 2 (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x013A, "PDP Xbox One Controller", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0145, "PDP Mortal Kombat X FightPad (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x015C, "PDP @Play Wired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x015D, "PDP Mirror's Edge Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x015F, "PDP Metallic Wired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0160, "PDP NFL Face-Off Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0166, "PDP Mass Effect Andromeda Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0167, "PDP Halo Wars 2 Face-Off Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0205, "PDP Victrix Pro Fight Stick", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0246, "PDP Rock Candy Controller (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x0262, "PDP Wired Controller (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x02B3, "PDP Afterglow Prismatic Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x02C8, "PDP Kingdom Hearts Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x02D6, "Victrix Gambit Tournament Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0E6F, 0x02DA, "PDP Xbox Series X Afterglow", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x00C5, "Hori Fighting Commander (Xbox One)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x0F0D, 0x0150, "Hori Fighting Commander OCTA (Xbox)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x10F5, 0x7009, "Turtle Beach Recon Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x10F5, 0x7013, "Turtle Beach REACT-R", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x1532, 0x0A14, "Razer Wolverine Ultimate", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x1532, 0x0A15, "Razer Wolverine Tournament Edition", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2001, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2002, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2003, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2004, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2005, "PowerA Xbox Series X Wired Controller Core", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2006, "PowerA Xbox Series X Wired Controller Core", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x2009, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x200A, "PowerA Xbox Series X EnWired Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x4001, "PowerA Fusion Pro 2 Wired (Xbox)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x20D6, 0x4002, "PowerA Spectra Infinity Wired (Xbox)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x581A, "BDA XB1 Classic Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x591A, "PowerA FUSION Pro Controller", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x24C6, 0x592A, "BDA XB1 Spectra Pro", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x2DC8, 0x2002, "8BitDo Ultimate Wired Controller for Xbox", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},
    {0x2E24, 0x0652, "Hyperkin Duke", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x2E24, 0x1618, "Hyperkin Duke", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x2E24, 0x1688, "Hyperkin X91", Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON},
    {0x146B, 0x0611, "Nacon Revolution 3 (Xbox mode)", Parser::XBOX_ONE_GIP,
     InitKind::XBOX_ONE_POWERON},

    {0x054C, 0x05C5, "STRIKEPAD PS4 Grip Add-on", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0738, 0x8250, "Mad Catz FightPad Pro PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0738, 0x8384, "Mad Catz FightStick TE S+ PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0738, 0x8480, "Mad Catz FightStick TE 2 PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0738, 0x8481, "Mad Catz FightStick TE 2+ PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0C12, 0x0E10, "Armor 3 Pad PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0C12, 0x0E15, "Game:Pad 4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0C12, 0x0EF6, "Hitbox Arcade Stick", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0C12, 0x1CF6, "EMIO PS4 Elite Controller", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0E6F, 0x0207, "Victrix Pro FS V2 (PS4)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0E6F, 0x020A, "Victrix Pro FS PS4/PS5 (PS4 mode)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x0055, "Hori HORIPAD 4 FPS", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x0066, "Hori HORIPAD 4 FPS Plus", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x0084, "Hori Fighting Commander PS4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x0087, "Hori Fighting Stick mini 4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x008A, "Hori Real Arcade Pro 4", Parser::DUALSHOCK4, InitKind::NONE},
    {0x0F0D, 0x0162, "Hori Fighting Commander OCTA (PS4)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x11C0, 0x4001, "PS4 Fun Controller", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D09, "Nacon Daija Fight Stick", Parser::DUALSHOCK4, InitKind::NONE},
    {0x146B, 0x0D13, "Nacon Revolution Pro Controller 3", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1004, "Razer Raiju 2 Ultimate", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1007, "Razer Raiju 2 Tournament Edition", Parser::DUALSHOCK4, InitKind::NONE},
    {0x1532, 0x1008, "Razer Panthera Evo Fightstick", Parser::DUALSHOCK4, InitKind::NONE},
    {0x20D6, 0x792A, "PowerA Fusion Fight Pad (PS4)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x2C22, 0x2000, "Qanba Drone", Parser::DUALSHOCK4, InitKind::NONE},
    {0x2C22, 0x2300, "Qanba Obsidian", Parser::DUALSHOCK4, InitKind::NONE},
    {0x2C22, 0x2500, "Qanba Dragon", Parser::DUALSHOCK4, InitKind::NONE},
    {0x3285, 0x0D16, "Nacon Revolution 5 Pro (PS4 dongle)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x3285, 0x0D17, "Nacon Revolution 5 Pro (PS4 wired)", Parser::DUALSHOCK4, InitKind::NONE},
    {0x9886, 0x0025, "Astro C40", Parser::DUALSHOCK4, InitKind::NONE},

    {0x0E6F, 0x0209, "Victrix Pro FS PS4/PS5 (PS5 mode)", Parser::DUALSENSE, InitKind::NONE},
    {0x1532, 0x100B, "Razer Wolverine V2 Pro (Wired)", Parser::DUALSENSE, InitKind::NONE},
    {0x1532, 0x1012, "Razer Kitsune", Parser::DUALSENSE, InitKind::NONE},
    {0x3285, 0x0D18, "Nacon Revolution 5 Pro (PS5 dongle)", Parser::DUALSENSE, InitKind::NONE},
    {0x3285, 0x0D19, "Nacon Revolution 5 Pro (PS5 wired)", Parser::DUALSENSE, InitKind::NONE},
};

template <size_t N>
static const KnownDevice* findIn(const KnownDevice (&arr)[N], uint16_t vid, uint16_t pid) {
    for (const auto& d : arr) {
        if (d.vid == vid && d.pid == pid) return &d;
    }
    return nullptr;
}

const KnownDevice* lookupKnown(uint16_t vid, uint16_t pid) {
    if (const KnownDevice* k = findIn(kKnown, vid, pid)) return k;
    return findIn(kImported, vid, pid);
}

bool isVerifiedFastLane(uint16_t vid, uint16_t pid) {
    const KnownDevice* k = findIn(kKnown, vid, pid);
    return k != nullptr && k->parser != Parser::NONE;
}

const char* parserName(Parser p) {
    switch (p) {
    case Parser::XINPUT_360:
        return "Xbox 360 protocol";
    case Parser::XINPUT_360_WIRELESS:
        return "Xbox 360 wireless protocol";
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
    return p == Parser::SWITCH_PRO_USB || p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE;
}

bool parserHasRumble(Parser p) {
    switch (p) {
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
    case Parser::XBOX_ONE_GIP:
    case Parser::DUALSHOCK4:
    case Parser::DUALSENSE:
    case Parser::SWITCH_PRO_USB:
        return true;
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return false;
    }
    return false;
}

namespace {

#ifdef __ANDROID__
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
#endif

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
// Inner deadzone in the raw 12-bit domain. The Switch Pro's stick center wanders per unit (a
// resting stick can sit a few hundred counts off 2048) and we read no factory calibration, so
// without this the auto-range amplifies that offset into large resting drift. Counts within the
// deadzone read as center; the throw beyond it is auto-ranged to full scale.
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

// DS4/DualSense raw -> calibrated (1024/deg-s) -> wire (deg-s / 2000 * 32767).
int16_t ds4GyroAxisToWire(int32_t raw, const PsImuCalib& c, int axis) {
    int64_t calibrated = (int64_t)c.gyroNumer[axis] * raw / c.gyroDenom[axis];
    int64_t wire = calibrated * 32767 / (kPsGyroResPerDegS * 2000);
    if (wire > 32767) wire = 32767;
    if (wire < -32768) wire = -32768;
    return (int16_t)wire;
}

// DS4/DualSense raw -> calibrated (8192/g) -> wire (g / 4 * 32767).
int16_t ds4AccelAxisToWire(int32_t raw, const PsImuCalib& c, int axis) {
    int64_t calibrated =
        (int64_t)c.accelNumer[axis] * (raw - c.accelBias[axis]) / c.accelDenom[axis];
    int64_t wire = calibrated * 32767 / (kPsAccelResPerG * 4);
    if (wire > 32767) wire = 32767;
    if (wire < -32768) wire = -32768;
    return (int16_t)wire;
}

uint16_t setDpadFromHat(uint16_t buttons, uint8_t hat) {
    buttons =
        (uint16_t)(buttons & ~(XUSB_DPAD_UP | XUSB_DPAD_DOWN | XUSB_DPAD_LEFT | XUSB_DPAD_RIGHT));
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
// 0..255 below. Sticks are little-endian int16, same convention as XInput. The Guide button arrives
// in a separate virtual-key report (0x07, state in byte 4); it is sticky and merged into the main
// report via ParserState so a guide press survives the interleaved 0x20 frames.
bool decodeXboxOneGip(const uint8_t* buf, size_t len, DeviceState& s, ParserState& st) {
    if (len >= 5 && buf[0] == 0x07) {
        st.xboxGuideHeld = (buf[4] & 0x03) != 0;
        s = st.xboxLastMain;
        if (st.xboxGuideHeld) {
            s.wButtons |= XUSB_GUIDE;
        } else {
            s.wButtons = (uint16_t)(s.wButtons & ~XUSB_GUIDE);
        }
        return true;
    }
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

    st.xboxLastMain = s;
    if (st.xboxGuideHeld) s.wButtons |= XUSB_GUIDE;
    return true;
}

// DualShock 4 USB report 0x01. Sticks are uint8 with 128 = center. Y axes are down-positive so
// they're inverted to match XUSB's up-positive convention. Face buttons are remapped to the
// XInput "muscle memory" positions: Cross is A, Circle is B, Square is X, Triangle is Y.
bool decodeDualShock4(const uint8_t* buf, size_t len, DeviceState& s, const PsImuCalib* calib) {
    if (len < 10) return false;
    if (buf[0] != 0x01) return false;

    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);

    uint16_t b = 0;
    if (buf[5] & 0x10) b |= XUSB_X;
    if (buf[5] & 0x20) b |= XUSB_A;
    if (buf[5] & 0x40) b |= XUSB_B;
    if (buf[5] & 0x80) b |= XUSB_Y;
    if (buf[6] & 0x01) b |= XUSB_LB;
    if (buf[6] & 0x02) b |= XUSB_RB;
    if (buf[6] & 0x10) b |= XUSB_BACK;
    if (buf[6] & 0x20) b |= XUSB_START;
    if (buf[6] & 0x40) b |= XUSB_THUMB_L;
    if (buf[6] & 0x80) b |= XUSB_THUMB_R;
    b = setDpadFromHat(b, buf[5] & 0x0F);
    s.wButtons = b;

    s.bLT = buf[8];
    s.bRT = buf[9];

    // gyro pitch/yaw/roll at 13/15/17, accel x/y/z at 19/21/23 (int16 LE). Axis signs are an
    // unflipped straight map, still unverified on hardware like the Switch IMU.
    if (calib != nullptr && calib->valid && len >= 25) {
        s.gyroX = ds4GyroAxisToWire(rdLe16(buf, 13), *calib, 0);
        s.gyroY = ds4GyroAxisToWire(rdLe16(buf, 15), *calib, 1);
        s.gyroZ = ds4GyroAxisToWire(rdLe16(buf, 17), *calib, 2);
        s.accelX = ds4AccelAxisToWire(rdLe16(buf, 19), *calib, 0);
        s.accelY = ds4AccelAxisToWire(rdLe16(buf, 21), *calib, 1);
        s.accelZ = ds4AccelAxisToWire(rdLe16(buf, 23), *calib, 2);
        s.motionValid = true;
    }
    return true;
}

// DualSense USB report 0x01. Same axis conventions as DS4 but the byte layout shifts: triggers
// move to bytes 5/6 and the button bytes are at 8/9/10.
bool decodeDualSense(const uint8_t* buf, size_t len, DeviceState& s, const PsImuCalib* calib) {
    if (len < 11) return false;
    if (buf[0] != 0x01) return false;

    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);

    s.bLT = buf[5];
    s.bRT = buf[6];

    uint16_t b = 0;
    if (buf[8] & 0x10) b |= XUSB_X;
    if (buf[8] & 0x20) b |= XUSB_A;
    if (buf[8] & 0x40) b |= XUSB_B;
    if (buf[8] & 0x80) b |= XUSB_Y;
    if (buf[9] & 0x01) b |= XUSB_LB;
    if (buf[9] & 0x02) b |= XUSB_RB;
    if (buf[9] & 0x10) b |= XUSB_BACK;
    if (buf[9] & 0x20) b |= XUSB_START;
    if (buf[9] & 0x40) b |= XUSB_THUMB_L;
    if (buf[9] & 0x80) b |= XUSB_THUMB_R;
    b = setDpadFromHat(b, buf[8] & 0x0F);
    s.wButtons = b;

    // gyro at 16/18/20, accel at 22/24/26 (int16 LE); same calibration as DS4, signs unverified.
    if (calib != nullptr && calib->valid && len >= 28) {
        s.gyroX = ds4GyroAxisToWire(rdLe16(buf, 16), *calib, 0);
        s.gyroY = ds4GyroAxisToWire(rdLe16(buf, 18), *calib, 1);
        s.gyroZ = ds4GyroAxisToWire(rdLe16(buf, 20), *calib, 2);
        s.accelX = ds4AccelAxisToWire(rdLe16(buf, 22), *calib, 0);
        s.accelY = ds4AccelAxisToWire(rdLe16(buf, 24), *calib, 1);
        s.accelZ = ds4AccelAxisToWire(rdLe16(buf, 26), *calib, 2);
        s.motionValid = true;
    }
    return true;
}

// Switch Pro standard full input report 0x30 over USB. Buttons are split across three bytes
// (right/shared/left) and sticks are packed 12-bit values. The XUSB mapping matches by physical
// position rather than label, the same convention used for DualShock 4: Switch A (right face) →
// XUSB_B (right face), Switch B (bottom) → XUSB_A (bottom), Switch X (top) → XUSB_Y, Switch Y
// (left) → XUSB_X. This is what PC games and ViGEm expect.
bool decodeSwitchProUsb(const uint8_t* buf, size_t len, DeviceState& s, ParserState& sticks) {
    if (len < 12) return false;
    if (buf[0] != 0x30) return false;

    const uint8_t br = buf[3];
    const uint8_t bs = buf[4];
    const uint8_t bl = buf[5];

    uint16_t b = 0;
    if (br & 0x01) b |= XUSB_X;
    if (br & 0x02) b |= XUSB_Y;
    if (br & 0x04) b |= XUSB_A;
    if (br & 0x08) b |= XUSB_B;
    if (br & 0x40) b |= XUSB_RB;
    if (bs & 0x01) b |= XUSB_BACK;
    if (bs & 0x02) b |= XUSB_START;
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

    // Average the bundled IMU subframes (the pad packs up to three ~5ms samples per report; one
    // 12-byte frame = accel int16 LE x3 then gyro x3, first at byte 13), then rotate the Switch IMU
    // frame onto the DS4 wire convention (wire gyro X=pitch, Y=yaw, Z=roll); the pad reports those
    // on raw gyro Y/Z/X. Pitch and roll are negated to match the DS4 sign convention. Hardware
    // testing confirmed pitch and yaw; roll's sign and the accel signs are unverified.
    size_t imuFrames = len >= 13 ? (len - 13) / 12 : 0;
    if (imuFrames > 3) imuFrames = 3;
    if (imuFrames > 0) {
        int32_t ax = 0, ay = 0, az = 0, gx = 0, gy = 0, gz = 0;
        for (size_t f = 0; f < imuFrames; f++) {
            int off = 13 + 12 * (int)f;
            ax += rdLe16(buf, off);
            ay += rdLe16(buf, off + 2);
            az += rdLe16(buf, off + 4);
            gx += rdLe16(buf, off + 6);
            gy += rdLe16(buf, off + 8);
            gz += rdLe16(buf, off + 10);
        }
        int32_t n = (int32_t)imuFrames;
        int32_t pitchAvg = -(gy / n);
        int32_t rollAvg = -(gx / n);
        if (pitchAvg > 32767) pitchAvg = 32767;
        if (rollAvg > 32767) rollAvg = 32767;
        s.gyroX = switchGyroToWire((int16_t)pitchAvg);
        s.gyroY = switchGyroToWire((int16_t)(gz / n));
        s.gyroZ = switchGyroToWire((int16_t)rollAvg);
        s.accelX = switchAccelToWire((int16_t)(ay / n));
        s.accelY = switchAccelToWire((int16_t)(az / n));
        s.accelZ = switchAccelToWire((int16_t)(ax / n));
        s.motionValid = true;
    }
    return true;
}

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

// Switch Pro HD-rumble amplitude codes from the Linux hid-nintendo table; frequency held at the
// neutral default so a coarse strong/weak motor still encodes a faithful buzz. See docs/rumble.md.
struct SwitchAmpCode {
    uint8_t high;
    uint16_t low;
    uint16_t amp;
};

const SwitchAmpCode kSwitchAmpCodes[] = {
    {0x00, 0x0040, 0},   {0x02, 0x8040, 10},  {0x08, 0x0042, 17},  {0x10, 0x0044, 33},
    {0x40, 0x0050, 230}, {0x70, 0x005c, 387}, {0xa0, 0x0068, 650}, {0xc8, 0x0072, 1003},
};

void switchEncodeMotor(uint8_t* out, uint16_t magnitude) {
    uint32_t amp = (uint32_t)magnitude * 1003u / 65535u;
    const SwitchAmpCode* code = &kSwitchAmpCodes[0];
    for (const auto& e : kSwitchAmpCodes) {
        if (e.amp <= amp) {
            code = &e;
        } else {
            break;
        }
    }
    out[0] = 0x00;
    out[1] = (uint8_t)(0x01 + code->high);
    out[2] = (uint8_t)(0x40 + ((code->low >> 8) & 0xFF));
    out[3] = (uint8_t)(code->low & 0xFF);
}

} // namespace

bool parsePsCalibration(const uint8_t* buf, size_t len, PsImuCalib& out) {
    out = PsImuCalib{};
    if (len < 35) return false; // gyro/accel calibration occupies bytes 1..34
    int32_t gyroBias[3] = {rdLe16(buf, 1), rdLe16(buf, 3), rdLe16(buf, 5)};
    int32_t gyroPlus[3] = {rdLe16(buf, 7), rdLe16(buf, 11), rdLe16(buf, 15)};
    int32_t gyroMinus[3] = {rdLe16(buf, 9), rdLe16(buf, 13), rdLe16(buf, 17)};
    int32_t speed2x = rdLe16(buf, 19) + rdLe16(buf, 21);
    for (int i = 0; i < 3; i++) {
        int32_t a = gyroPlus[i] - gyroBias[i];
        int32_t b = gyroMinus[i] - gyroBias[i];
        int32_t denom = (a < 0 ? -a : a) + (b < 0 ? -b : b);
        if (denom == 0) return false;
        out.gyroNumer[i] = speed2x * kPsGyroResPerDegS;
        out.gyroDenom[i] = denom;
    }
    int32_t accPlus[3] = {rdLe16(buf, 23), rdLe16(buf, 27), rdLe16(buf, 31)};
    int32_t accMinus[3] = {rdLe16(buf, 25), rdLe16(buf, 29), rdLe16(buf, 33)};
    for (int i = 0; i < 3; i++) {
        int32_t range2g = accPlus[i] - accMinus[i];
        if (range2g == 0) return false;
        out.accelBias[i] = accPlus[i] - range2g / 2;
        out.accelNumer[i] = 2 * kPsAccelResPerG;
        out.accelDenom[i] = range2g;
    }
    out.valid = true;
    return true;
}

bool decodeReport(Parser p, const uint8_t* buf, size_t len, DeviceState& s, ParserState* sticks) {
    switch (p) {
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
        return decodeXInput360(buf, len, s);
    case Parser::XBOX_ONE_GIP:
        return sticks != nullptr && decodeXboxOneGip(buf, len, s, *sticks);
    case Parser::DUALSHOCK4:
        return decodeDualShock4(buf, len, s, sticks ? &sticks->psImu : nullptr);
    case Parser::DUALSENSE:
        return decodeDualSense(buf, len, s, sticks ? &sticks->psImu : nullptr);
    case Parser::SWITCH_PRO_USB:
        return sticks != nullptr && decodeSwitchProUsb(buf, len, s, *sticks);
    case Parser::STADIA:
        return decodeStadia(buf, len, s);
    case Parser::GENERIC_HID_GAMEPAD:
        return sticks != nullptr && sticks->hidLayout.valid
                   ? usbhid::decodeFromLayout(buf, len, s, sticks->hidLayout)
                   : decodeGenericHidGamepad(buf, len, s);
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

size_t buildGipInitPacket(InitKind init, int index, uint8_t seq, uint8_t* out, size_t outCap) {
    // GIP init packets from Linux xpad. power-on/LED/auth-done are universal; the S-init is the
    // extra set-mode packet the Xbox One S / Elite Series 2 need. Byte 2 carries the sequence.
    static const uint8_t kPowerOn[] = {0x05, 0x20, 0x00, 0x01, 0x00};
    static const uint8_t kSInit[] = {0x05, 0x20, 0x00, 0x0F, 0x06};
    static const uint8_t kLedOn[] = {0x0A, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14};
    static const uint8_t kAuthDone[] = {0x06, 0x20, 0x00, 0x02, 0x01, 0x00};

    struct Pkt {
        const uint8_t* data;
        size_t len;
    };
    static const Pkt kPowerOnSeq[] = {
        {kPowerOn, sizeof(kPowerOn)}, {kLedOn, sizeof(kLedOn)}, {kAuthDone, sizeof(kAuthDone)}};
    static const Pkt kSSeq[] = {{kPowerOn, sizeof(kPowerOn)},
                                {kSInit, sizeof(kSInit)},
                                {kLedOn, sizeof(kLedOn)},
                                {kAuthDone, sizeof(kAuthDone)}};

    const Pkt* seqArr = nullptr;
    int count = 0;
    if (init == InitKind::XBOX_ONE_POWERON) {
        seqArr = kPowerOnSeq;
        count = 3;
    } else if (init == InitKind::XBOX_ONE_S) {
        seqArr = kSSeq;
        count = 4;
    } else {
        return 0;
    }
    if (index < 0 || index >= count) return 0;
    size_t len = seqArr[index].len;
    if (len > outCap) return 0;
    memcpy(out, seqArr[index].data, len);
    out[2] = seq;
    return len;
}

// Per-device rumble output reports. Motor convention: strong = large/low-frequency (left), weak =
// small/high-frequency (right), both wire-scale 0..65535. Report layouts and sources (Linux xpad,
// hid-playstation, hid-nintendo) are documented in docs/rumble.md.
size_t buildRumbleReport(Parser p, uint16_t strong, uint16_t weak, uint8_t seq, uint8_t* out,
                         size_t outCap) {
    switch (p) {
    case Parser::XINPUT_360:
        if (outCap < 8) return 0;
        out[0] = 0x00;
        out[1] = 0x08;
        out[2] = 0x00;
        out[3] = (uint8_t)(strong >> 8);
        out[4] = (uint8_t)(weak >> 8);
        out[5] = 0x00;
        out[6] = 0x00;
        out[7] = 0x00;
        return 8;
    case Parser::XINPUT_360_WIRELESS:
        // Wireless receivers wrap the motor levels in a 12-byte frame (Linux xpad xpad360w).
        if (outCap < 12) return 0;
        out[0] = 0x00;
        out[1] = 0x01;
        out[2] = 0x0F;
        out[3] = 0xC0;
        out[4] = 0x00;
        out[5] = (uint8_t)(strong >> 8);
        out[6] = (uint8_t)(weak >> 8);
        out[7] = 0x00;
        out[8] = 0x00;
        out[9] = 0x00;
        out[10] = 0x00;
        out[11] = 0x00;
        return 12;
    case Parser::XBOX_ONE_GIP:
        if (outCap < 13) return 0;
        out[0] = 0x09;
        out[1] = 0x00;
        out[2] = seq;
        out[3] = 0x09;
        out[4] = 0x00;
        out[5] = 0x0F;
        out[6] = 0x00;
        out[7] = 0x00;
        out[8] = (uint8_t)(strong / 512);
        out[9] = (uint8_t)(weak / 512);
        out[10] = 0xFF;
        out[11] = 0x00;
        out[12] = 0xFF;
        return 13;
    case Parser::DUALSHOCK4:
        if (outCap < 32) return 0;
        memset(out, 0, 32);
        out[0] = 0x05;
        out[1] = 0x01;
        out[4] = (uint8_t)(weak >> 8);
        out[5] = (uint8_t)(strong >> 8);
        return 32;
    case Parser::DUALSENSE:
        if (outCap < 63) return 0;
        memset(out, 0, 63);
        out[0] = 0x02;
        out[1] = 0x01;
        out[3] = (uint8_t)(weak >> 8);
        out[4] = (uint8_t)(strong >> 8);
        return 63;
    case Parser::SWITCH_PRO_USB:
        if (outCap < 10) return 0;
        memset(out, 0, 10);
        out[0] = 0x10;
        out[1] = (uint8_t)(seq & 0x0F);
        switchEncodeMotor(&out[2], strong);
        switchEncodeMotor(&out[6], weak);
        return 10;
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return 0;
    }
    return 0;
}

#ifdef __ANDROID__
bool runInit(int fd, uint8_t epOut, Parser p, InitKind init) {
    switch (init) {
    case InitKind::NONE:
        return true;
    case InitKind::XBOX_ONE_POWERON:
    case InitKind::XBOX_ONE_S: {
        // GIP init: power-on tells the pad to start sending input reports; the rest of the sequence
        // (LED, auth-done, and the S set-mode) starts the models the lone power-on left silent.
        uint8_t buf[16];
        for (int i = 0;; i++) {
            size_t n = buildGipInitPacket(init, i, (uint8_t)i, buf, sizeof(buf));
            if (n == 0) break;
            bool ok = bulkWrite(fd, epOut, buf, n, 200);
            if (i == 0 && !ok) {
                LOGE("Xbox One power-on write failed");
                return false;
            }
            usleep(10000);
        }
        return true;
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
            0x01, 0x00, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40, 0x03, 0x30,
        };
        if (!bulkWrite(fd, epOut, setReportMode, sizeof(setReportMode), 200)) {
            LOGE("Switch Pro: set-report-mode write failed");
            return false;
        }
        usleep(40000);

        // Enable vibration (subcommand 0x48, arg 0x01) so later rumble-only (0x10) reports take
        // effect.
        uint8_t enableVibration[] = {
            0x01, 0x01, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40, 0x48, 0x01,
        };
        if (!bulkWrite(fd, epOut, enableVibration, sizeof(enableVibration), 200)) {
            LOGI("Switch Pro: enable-vibration write failed (non-fatal)");
        }
        LOGI("Switch Pro USB init sequence sent");
        return true;
    }
    }
    return false;
}

bool runRumble(int fd, uint8_t epOut, Parser p, uint16_t strong, uint16_t weak, uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildRumbleReport(p, strong, weak, seq, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}
#endif

} // namespace usbparsers

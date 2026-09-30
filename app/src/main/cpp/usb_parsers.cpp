// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_parsers.h"

#include <string.h>
#include <algorithm>

// The decoders and report builders here are pure and host-tested (usb_parsers_test.cpp). Only the
// USB transfer helpers need the kernel ioctls, so they are fenced to the Android build.
#ifdef __ANDROID__
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#endif

namespace usbparsers {

using gamepad::DeviceState;
using gamepad::hatDirectionBits;
using gamepad::WBUTTON_MIC_MUTE;
using gamepad::XUSB_A;
using gamepad::XUSB_B;
using gamepad::XUSB_BACK;
using gamepad::XUSB_DPAD_DOWN;
using gamepad::XUSB_DPAD_LEFT;
using gamepad::XUSB_DPAD_MASK;
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

namespace {

// DualShock 4 / DualSense calibrated-IMU resolution (Linux hid-playstation).
constexpr int32_t kPsGyroResPerDegS = 1024;
constexpr int32_t kPsAccelResPerG = 8192;

} // namespace

constexpr KnownDevice kKnown[] = {
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
    {0x1949, 0x041A, "Amazon Luna Controller", Parser::XINPUT_360, InitKind::NONE},
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

// Device IDs below are a curated subset of SDL's src/joystick/controller_list.h and its hidapi
// drivers (zlib license, Copyright (C) Valve Corporation; see THIRD_PARTY.md). They are NOT
// hardware-verified here:
// lookupKnown recognises them (name + the family parser) and the user can opt into Direct, where
// probeDecodable still guards the byte layout, but isVerifiedFastLane returns false for them so
// auto-claim never silently moves an untested model onto Direct. Bluetooth-only PIDs, USB-differs
// entries, dongles that re-enumerate, and non-gamepads (wheels/guitars) from SDL are omitted.
constexpr KnownDevice kImported[] = {
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

    {0x0E6F, 0x0180, "PDP Faceoff Wired Pro Controller (Switch)", Parser::GENERIC_HID_GAMEPAD,
     InitKind::NONE, ButtonOrder::SWITCH},
    {0x0E6F, 0x0181, "PDP Faceoff Deluxe Wired Pro Controller (Switch)",
     Parser::GENERIC_HID_GAMEPAD, InitKind::NONE, ButtonOrder::SWITCH},
    {0x0E6F, 0x0184, "PDP Faceoff Deluxe+ Audio Controller (Switch)", Parser::GENERIC_HID_GAMEPAD,
     InitKind::NONE, ButtonOrder::SWITCH},
    {0x0E6F, 0x0185, "PDP Wired Fight Pad Pro (Switch)", Parser::GENERIC_HID_GAMEPAD,
     InitKind::NONE, ButtonOrder::SWITCH},
    {0x0E6F, 0x0187, "PDP Rock Candy Wired Controller (Switch)", Parser::GENERIC_HID_GAMEPAD,
     InitKind::NONE, ButtonOrder::SWITCH},

    {0x28DE, 0x1102, "Valve Steam Controller", Parser::STEAM_CONTROLLER, InitKind::STEAM_QUIET},
    {0x28DE, 0x1142, "Valve Steam Controller (dongle)", Parser::STEAM_CONTROLLER,
     InitKind::STEAM_QUIET},
};

namespace {

template <size_t N>
const KnownDevice* findIn(const KnownDevice (&arr)[N], const uint16_t vid, const uint16_t pid) {
    for (const KnownDevice& d : arr) {
        const bool isTheModel = d.vid == vid && d.pid == pid;
        if (isTheModel) return &d;
    }
    return nullptr;
}

constexpr uint8_t kIfClassVendor = 0xFF;
constexpr uint8_t kXInputSubclass = 0x5D;
constexpr uint8_t kXInputProtocol = 0x01;
constexpr uint8_t kGipSubclass = 0x47;
constexpr uint8_t kGipProtocol = 0xD0;

bool isWiredXInputInterface(const uint8_t ifClass, const uint8_t ifSubclass,
                            const uint8_t ifProtocol) {
    const bool isVendorClass = ifClass == kIfClassVendor;
    const bool isXInput = ifSubclass == kXInputSubclass && ifProtocol == kXInputProtocol;
    return isVendorClass && isXInput;
}

bool isGipInterface(const uint8_t ifClass, const uint8_t ifSubclass, const uint8_t ifProtocol) {
    const bool isVendorClass = ifClass == kIfClassVendor;
    const bool isGip = ifSubclass == kGipSubclass && ifProtocol == kGipProtocol;
    return isVendorClass && isGip;
}

} // namespace

const KnownDevice* lookupKnown(const uint16_t vid, const uint16_t pid) {
    const KnownDevice* verified = findIn(kKnown, vid, pid);
    if (verified != nullptr) return verified;
    return findIn(kImported, vid, pid);
}

bool isVerifiedFastLane(const uint16_t vid, const uint16_t pid) {
    const KnownDevice* k = findIn(kKnown, vid, pid);
    return k != nullptr && k->parser != Parser::NONE;
}

bool modelExpectsFrameworkGamepad(const uint16_t vid, const uint16_t pid) {
    const KnownDevice* k = lookupKnown(vid, pid);
    return k == nullptr || k->parser != Parser::STEAM_CONTROLLER;
}

bool probePermitsClaim(const ProbeOutcome outcome, const bool verifiedFastLane) {
    switch (outcome) {
    case ProbeOutcome::DECODED:
        return true;
    case ProbeOutcome::SILENT:
        return verifiedFastLane;
    case ProbeOutcome::UNDECODED:
        return false;
    }
    return false;
}

Classification classifyDevice(const uint16_t vid, const uint16_t pid, const uint8_t ifClass,
                              const uint8_t ifSubclass, const uint8_t ifProtocol) {
    const KnownDevice* known = lookupKnown(vid, pid);
    if (known != nullptr) return {known->parser, known->init, known->name, known->order};
    // Wired XInput streams unsolicited; GIP needs the power-on packet first.
    if (isWiredXInputInterface(ifClass, ifSubclass, ifProtocol)) {
        return {Parser::XINPUT_360, InitKind::NONE, nullptr};
    }
    if (isGipInterface(ifClass, ifSubclass, ifProtocol)) {
        return {Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON, nullptr};
    }
    return {Parser::GENERIC_HID_GAMEPAD, InitKind::NONE, nullptr};
}

const char* parserName(const Parser p) {
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
    case Parser::STEAM_CONTROLLER:
        return "Steam Controller protocol";
    case Parser::NONE:
        return "Unknown";
    }
    return "Unknown";
}

bool parserHasImu(const Parser p) {
    switch (p) {
    case Parser::SWITCH_PRO_USB:
    case Parser::DUALSHOCK4:
    case Parser::DUALSENSE:
    case Parser::STEAM_CONTROLLER:
        return true;
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
    case Parser::XBOX_ONE_GIP:
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return false;
    }
    return false;
}

bool parserHasRumble(const Parser p) {
    switch (p) {
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
    case Parser::XBOX_ONE_GIP:
    case Parser::DUALSHOCK4:
    case Parser::DUALSENSE:
    case Parser::SWITCH_PRO_USB:
        return true;
    // The Steam Controller has no rumble motors, only trackpad voice coils driven by pulse trains;
    // its simple-rumble command is Steam Deck firmware only.
    case Parser::STEAM_CONTROLLER:
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return false;
    }
    return false;
}

bool parserFrameworkRumbleUnreliable(const Parser p) { return p == Parser::SWITCH_PRO_USB; }

bool parserHasTouchpad(const Parser p) { return p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE; }

namespace {

#ifdef __ANDROID__
constexpr const char* kLogTag = "SatelliteUsbParse";

__attribute__((format(printf, 1, 2))) void logInfo(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_INFO, kLogTag, fmt, args);
    va_end(args);
}

__attribute__((format(printf, 1, 2))) void logError(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, kLogTag, fmt, args);
    va_end(args);
}

bool bulkWrite(const int fd, const uint8_t epOut, const uint8_t* data, const size_t len,
               const unsigned timeoutMs) {
    if (epOut == 0) return false;
    struct usbdevfs_bulktransfer xfer = {};
    xfer.ep = epOut;
    xfer.len = (unsigned int)len;
    xfer.timeout = timeoutMs;
    xfer.data = (void*)data;
    const int n = ioctl(fd, USBDEVFS_BULK, &xfer);
    if (n < 0) {
        logError("USBDEVFS_BULK out to 0x%02X failed: %s", epOut, strerror(errno));
        return false;
    }
    return (size_t)n == len;
}

constexpr size_t kFeatureReportBytes = 64;
constexpr int kFeatureReportAttempts = 25;
constexpr unsigned kFeatureReportRetryUs = 20000;
constexpr uint8_t kSteamFeatureReportId = 0;

// SET_REPORT(Feature, report id 0), always a full 64-byte buffer. EPIPE here is the wireless
// dongle under load, not a real failure; SDL and hid-steam both retry it rather than give up.
// hid-steam allows 50 tries; the cap is lower here so that even a wholly unresponsive device
// finishes init and teardown well inside the Kotlin side's 4s path-transition timeout.
bool sendFeatureReport(const int fd, const int interfaceNumber, const uint8_t* data,
                       const size_t len) {
    if (interfaceNumber < 0 || len > kFeatureReportBytes) return false;
    uint8_t buf[kFeatureReportBytes] = {};
    memcpy(buf, data, len);
    for (int attempt = 0; attempt < kFeatureReportAttempts; attempt++) {
        struct usbdevfs_ctrltransfer ct = {};
        ct.bRequestType = USB_REQUEST_TYPE_OUT_CLASS_INTERFACE;
        ct.bRequest = USB_REQUEST_SET_REPORT;
        ct.wValue = (uint16_t)((HID_REPORT_TYPE_FEATURE << 8) | kSteamFeatureReportId);
        ct.wIndex = (uint16_t)interfaceNumber;
        ct.wLength = (uint16_t)sizeof(buf);
        ct.timeout = USB_CONTROL_TIMEOUT_MS;
        ct.data = buf;
        if (ioctl(fd, USBDEVFS_CONTROL, &ct) >= 0) return true;
        if (errno != EPIPE) break;
        usleep(kFeatureReportRetryUs);
    }
    logError("SET_REPORT 0x%02X to iface %d failed: %s", data[0], interfaceNumber, strerror(errno));
    return false;
}
#endif

constexpr int64_t kAxisMax = 32767;
constexpr int64_t kAxisMin = -32768;
constexpr uint8_t kTriggerMax = 255;
constexpr int32_t kU8AxisCenter = 128;
// 255 * 257 = 65535: an 8-bit axis stretched over the whole 16-bit range.
constexpr int32_t kU8ToI16 = 257;
constexpr int32_t kWireAxisSpan = 65535;
constexpr int kHighByteShift = 8;
constexpr int kNibbleShift = 4;
constexpr uint8_t kLowNibbleMask = 0x0F;

int16_t clampI16(const int64_t v) { return (int16_t)std::clamp(v, kAxisMin, kAxisMax); }

uint8_t highByte(const uint16_t v) { return (uint8_t)(v >> kHighByteShift); }

int16_t rdLe16(const uint8_t* b, const int off) {
    return (int16_t)((uint16_t)b[off] | ((uint16_t)b[off + 1] << kHighByteShift));
}

uint32_t rdLe24(const uint8_t* b) {
    return (uint32_t)b[0] | ((uint32_t)b[1] << kHighByteShift) |
           ((uint32_t)b[2] << (2 * kHighByteShift));
}

// Two 12-bit values packed little-endian into three bytes: the first is [0] plus the low nibble of
// [1], the second the high nibble of [1] plus [2].
void unpackTwo12Bit(const uint8_t* p, uint16_t& first, uint16_t& second) {
    first = (uint16_t)((uint16_t)p[0] | (((uint16_t)p[1] & kLowNibbleMask) << kHighByteShift));
    second = (uint16_t)(((uint16_t)p[1] >> kNibbleShift) | ((uint16_t)p[2] << kNibbleShift));
}

int16_t scaleU8Centered(const uint8_t v, const bool invert) {
    const int32_t centered = (int32_t)v - kU8AxisCenter;
    const int32_t oriented = invert ? -centered : centered;
    return clampI16((int64_t)oriented * kU8ToI16);
}

// Four uint8 stick bytes with 128 = center, in the order LX LY RX RY; the Y axes are down-positive
// and so inverted to XUSB's up-positive convention.
void decodeU8Sticks(const uint8_t* sticks, DeviceState& s) {
    s.sLX = scaleU8Centered(sticks[0], false);
    s.sLY = scaleU8Centered(sticks[1], true);
    s.sRX = scaleU8Centered(sticks[2], false);
    s.sRY = scaleU8Centered(sticks[3], true);
}

// A 12-bit Switch stick value centred near 2048. The push and pull sides of each axis are
// auto-ranged independently because a Pro Controller's throw is usually asymmetric, and the raw
// deadzone absorbs the per-unit resting offset (no factory calibration is read) that the auto-range
// would otherwise amplify into drift.
constexpr int32_t kSwitchStickRawCenter = 2048;
constexpr int32_t kSwitchStickRawDeadzone = 320;
constexpr int32_t kSwitchStickPositiveRail = 32767;
constexpr int32_t kSwitchStickNegativeRail = 32768;

// Stretches a deflection past the deadzone to the rail against the largest deflection this side has
// seen, learning a longer throw on the way.
int32_t stretchToRail(const int32_t adj, int32_t& reach, const int32_t rail) {
    if (adj > reach) reach = adj;
    return (adj * rail) / reach;
}

int16_t scaleSwitchStickAuto(const uint16_t raw12, AxisAutoRange& axis) {
    const int32_t centered = (int32_t)raw12 - kSwitchStickRawCenter;
    const int32_t mag = centered >= 0 ? centered : -centered;
    const bool isAtRest = mag <= kSwitchStickRawDeadzone;
    if (isAtRest) return 0;
    const int32_t adj = mag - kSwitchStickRawDeadzone;
    const bool isPushed = centered >= 0;
    if (isPushed) return (int16_t)stretchToRail(adj, axis.posReach, kSwitchStickPositiveRail);
    return (int16_t)(-stretchToRail(adj, axis.negReach, kSwitchStickNegativeRail));
}

// Inclusive logical maxima of the touch surfaces (hid-sony / hid-playstation): the DS4 pad is
// 1920x942, the DualSense's is 1920x1080.
constexpr uint16_t kDs4TouchMaxX = 1919;
constexpr uint16_t kDs4TouchMaxY = 941;
constexpr uint16_t kDualSenseTouchMaxX = 1919;
constexpr uint16_t kDualSenseTouchMaxY = 1079;

// Touchpad surfaces differ per model but the wire is resolution-agnostic: full-range int16,
// same normalization the on-screen overlay applies to its view space.
int16_t touchAxisToWire(const uint16_t raw, const uint16_t maxRaw) {
    const uint16_t onSurface = std::min(raw, maxRaw);
    const int32_t scaled = ((int32_t)onSurface * kWireAxisSpan) / maxRaw + (int32_t)kAxisMin;
    return clampI16(scaled);
}

// One DS4/DualSense touch point: [0] bit7 = inactive, bits 0-6 = contact id; 12-bit x/y packed
// into [1..3]. Coordinates zero on lift to match the overlay's lift frames.
constexpr uint8_t kPsTouchInactiveBit = 0x80;
constexpr uint8_t kPsTouchIdMask = 0x7F;
constexpr size_t kPsTouchPointBytes = 4;

void decodePsTouchPoint(const uint8_t* p, const uint16_t maxX, const uint16_t maxY, bool& active,
                        uint8_t& id, int16_t& x, int16_t& y) {
    active = (p[0] & kPsTouchInactiveBit) == 0;
    id = (uint8_t)(p[0] & kPsTouchIdMask);
    if (!active) {
        x = 0;
        y = 0;
        return;
    }
    uint16_t rawX = 0;
    uint16_t rawY = 0;
    unpackTwo12Bit(p + 1, rawX, rawY);
    x = touchAxisToWire(rawX, maxX);
    y = touchAxisToWire(rawY, maxY);
}

// The wire's full scales: gyro +-2000 deg/s and accel +-4 g map onto +-32767.
constexpr int64_t kWireGyroFullScaleDegS = 2000;
constexpr int64_t kWireAccelFullScaleG = 4;

// Switch Pro IMU default scaling (no factory calibration yet). SDL uses gyro = raw / 14.2842 deg/s
// and accel = raw / 4096 g; against the wire's full scales the combined integer factors are
// 32767/28568 (gyro) and 32767/16384 (accel).
constexpr int64_t kSwitchGyroDivisor = 28568;
constexpr int64_t kSwitchAccelDivisor = 16384;

int16_t switchGyroToWire(const int16_t raw) {
    return clampI16((int64_t)raw * kAxisMax / kSwitchGyroDivisor);
}

int16_t switchAccelToWire(const int16_t raw) {
    return clampI16((int64_t)raw * kAxisMax / kSwitchAccelDivisor);
}

// DS4/DualSense raw -> calibrated (1024/deg-s) -> wire.
int16_t ds4GyroAxisToWire(const int32_t raw, const PsImuCalib& c, const int axis) {
    const int64_t calibrated = (int64_t)c.gyroNumer[axis] * raw / c.gyroDenom[axis];
    return clampI16(calibrated * kAxisMax / (kPsGyroResPerDegS * kWireGyroFullScaleDegS));
}

// DS4/DualSense raw -> calibrated (8192/g) -> wire.
int16_t ds4AccelAxisToWire(const int32_t raw, const PsImuCalib& c, const int axis) {
    const int64_t calibrated =
        (int64_t)c.accelNumer[axis] * (raw - c.accelBias[axis]) / c.accelDenom[axis];
    return clampI16(calibrated * kAxisMax / (kPsAccelResPerG * kWireAccelFullScaleG));
}

uint16_t setDpadFromHat(const uint16_t buttons, const uint8_t hat) {
    const uint16_t withoutDpad = (uint16_t)(buttons & ~XUSB_DPAD_MASK);
    const int direction = (int)(hat & kLowNibbleMask);
    return (uint16_t)(withoutDpad | hatDirectionBits(direction));
}

// One bit of a report's button byte and the XUSB bit it drives.
struct ButtonBit {
    uint8_t mask;
    uint16_t xusb;
};

template <size_t N> uint16_t buttonsFromByte(const uint8_t byte, const ButtonBit (&bits)[N]) {
    uint16_t buttons = 0;
    for (const ButtonBit& bit : bits) {
        const bool isDown = (byte & bit.mask) != 0;
        if (isDown) buttons |= bit.xusb;
    }
    return buttons;
}

// Both Xbox reports lay the four stick axes out as little-endian int16 in the order LX LY RX RY;
// only the base offset moves.
void readSticksLe16(const uint8_t* buf, const int base, DeviceState& s) {
    s.sLX = rdLe16(buf, base);
    s.sLY = rdLe16(buf, base + 2);
    s.sRX = rdLe16(buf, base + 4);
    s.sRY = rdLe16(buf, base + 6);
}

// Xbox 360 wired interrupt-IN report: type byte (0x00 for input), length byte, dpad/menu byte,
// face byte, the two 8-bit triggers, then the sticks.
constexpr size_t kX360ReportMinLen = 14;
constexpr uint8_t kX360InputReportType = 0x00;
constexpr size_t kX360DpadByte = 2;
constexpr size_t kX360FaceByte = 3;
constexpr size_t kX360LeftTriggerByte = 4;
constexpr size_t kX360RightTriggerByte = 5;
constexpr int kX360SticksOffset = 6;
constexpr ButtonBit kX360DpadByteBits[] = {
    {0x01, XUSB_DPAD_UP}, {0x02, XUSB_DPAD_DOWN}, {0x04, XUSB_DPAD_LEFT}, {0x08, XUSB_DPAD_RIGHT},
    {0x10, XUSB_START},   {0x20, XUSB_BACK},      {0x40, XUSB_THUMB_L},   {0x80, XUSB_THUMB_R},
};
constexpr ButtonBit kX360FaceByteBits[] = {
    {0x01, XUSB_LB}, {0x02, XUSB_RB}, {0x04, XUSB_GUIDE}, {0x10, XUSB_A},
    {0x20, XUSB_B},  {0x40, XUSB_X},  {0x80, XUSB_Y},
};

bool decodeXInput360(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < kX360ReportMinLen) return false;
    if (buf[0] != kX360InputReportType) return false;

    const uint16_t dpadAndMenus = buttonsFromByte(buf[kX360DpadByte], kX360DpadByteBits);
    const uint16_t faceAndBumpers = buttonsFromByte(buf[kX360FaceByte], kX360FaceByteBits);
    s.wButtons = (uint16_t)(dpadAndMenus | faceAndBumpers);
    s.bLT = buf[kX360LeftTriggerByte];
    s.bRT = buf[kX360RightTriggerByte];
    readSticksLe16(buf, kX360SticksOffset, s);
    return true;
}

// Xbox One GIP: the main input report 0x20 (face byte, dpad byte, two 10-bit LE triggers, then the
// sticks) and the virtual-key report 0x07 that carries the Guide button in byte 4.
constexpr uint8_t kGipVirtualKeyReport = 0x07;
constexpr size_t kGipVirtualKeyMinLen = 5;
constexpr size_t kGipVirtualKeyStateByte = 4;
constexpr uint8_t kGipVirtualKeyPressedMask = 0x03;
constexpr uint8_t kGipInputReport = 0x20;
constexpr size_t kGipInputMinLen = 18;
constexpr size_t kGipFaceByte = 4;
constexpr size_t kGipDpadByte = 5;
constexpr size_t kGipLeftTriggerOffset = 6;
constexpr size_t kGipRightTriggerOffset = 8;
constexpr int kGipSticksOffset = 10;
constexpr uint16_t kGipTriggerMax = 1023;
constexpr ButtonBit kGipFaceByteBits[] = {
    {0x04, XUSB_START}, {0x08, XUSB_BACK}, {0x10, XUSB_A},
    {0x20, XUSB_B},     {0x40, XUSB_X},    {0x80, XUSB_Y},
};
constexpr ButtonBit kGipDpadByteBits[] = {
    {0x01, XUSB_DPAD_UP}, {0x02, XUSB_DPAD_DOWN}, {0x04, XUSB_DPAD_LEFT}, {0x08, XUSB_DPAD_RIGHT},
    {0x10, XUSB_LB},      {0x20, XUSB_RB},        {0x40, XUSB_THUMB_L},   {0x80, XUSB_THUMB_R},
};

uint8_t gipTriggerToXusb(const uint8_t lo, const uint8_t hi) {
    const uint16_t raw = (uint16_t)((uint16_t)lo | ((uint16_t)hi << kHighByteShift));
    const uint16_t clamped = std::min(raw, kGipTriggerMax);
    return (uint8_t)((clamped * kTriggerMax) / kGipTriggerMax);
}

// The Guide button is sticky, so it is held in ParserState and replayed over the last main report,
// which is all this frame can publish.
bool applyXboxVirtualKey(const uint8_t* buf, DeviceState& s, ParserState& st) {
    st.xboxGuideHeld = (buf[kGipVirtualKeyStateByte] & kGipVirtualKeyPressedMask) != 0;
    s = st.xboxLastMain;
    if (st.xboxGuideHeld) {
        s.wButtons |= XUSB_GUIDE;
    } else {
        s.wButtons = (uint16_t)(s.wButtons & ~XUSB_GUIDE);
    }
    return true;
}

bool decodeXboxOneGip(const uint8_t* buf, const size_t len, DeviceState& s, ParserState& st) {
    const bool isVirtualKey = len >= kGipVirtualKeyMinLen && buf[0] == kGipVirtualKeyReport;
    if (isVirtualKey) return applyXboxVirtualKey(buf, s, st);
    if (len < kGipInputMinLen) return false;
    if (buf[0] != kGipInputReport) return false;

    const uint16_t faceAndMenus = buttonsFromByte(buf[kGipFaceByte], kGipFaceByteBits);
    const uint16_t dpadBumpersAndSticks = buttonsFromByte(buf[kGipDpadByte], kGipDpadByteBits);
    s.wButtons = (uint16_t)(faceAndMenus | dpadBumpersAndSticks);
    s.bLT = gipTriggerToXusb(buf[kGipLeftTriggerOffset], buf[kGipLeftTriggerOffset + 1]);
    s.bRT = gipTriggerToXusb(buf[kGipRightTriggerOffset], buf[kGipRightTriggerOffset + 1]);
    readSticksLe16(buf, kGipSticksOffset, s);

    st.xboxLastMain = s;
    if (st.xboxGuideHeld) s.wButtons |= XUSB_GUIDE;
    return true;
}

// DualShock 4 and DualSense USB report 0x01. The face buttons map to the XInput "muscle memory"
// positions (Cross is A, Circle is B, Square is X, Triangle is Y) and the two pads share the button
// bits; only the byte offsets move.
constexpr uint8_t kPsInputReport = 0x01;
constexpr size_t kPsSticksOffset = 1;
constexpr uint8_t kPsTouchClickBit = 0x02;
constexpr ButtonBit kPsFaceByteBits[] = {
    {0x10, XUSB_X},
    {0x20, XUSB_A},
    {0x40, XUSB_B},
    {0x80, XUSB_Y},
};
constexpr ButtonBit kPsShoulderByteBits[] = {
    {0x01, XUSB_LB},    {0x02, XUSB_RB},      {0x10, XUSB_BACK},
    {0x20, XUSB_START}, {0x40, XUSB_THUMB_L}, {0x80, XUSB_THUMB_R},
};

constexpr size_t kDs4MinLen = 10;
constexpr size_t kDs4FaceByte = 5;
constexpr size_t kDs4ShoulderByte = 6;
constexpr size_t kDs4TouchClickByte = 7;
constexpr size_t kDs4LeftTriggerByte = 8;
constexpr size_t kDs4RightTriggerByte = 9;
// gyro pitch/yaw/roll then accel x/y/z as int16 LE. Axis signs are an unflipped straight map,
// still unverified on hardware like the Switch IMU.
constexpr int kDs4GyroOffset = 13;
constexpr int kDs4AccelOffset = 19;
constexpr size_t kDs4MotionMinLen = 25;
constexpr size_t kDs4BatteryByte = 30;
constexpr size_t kDs4BatteryMinLen = 31;
// Bundled 9-byte touch frames (a timestamp byte plus two points): the count at [33], the frames
// from [34].
constexpr size_t kDs4TouchFrameCountByte = 33;
constexpr size_t kDs4TouchFramesOffset = 34;
constexpr size_t kDs4TouchFrameBytes = 9;
constexpr size_t kDs4TouchFrameTimestampBytes = 1;
constexpr size_t kDs4MaxTouchFrames = 3;
constexpr size_t kDs4TouchMinLen = kDs4TouchFramesOffset + kDs4TouchFrameBytes;

constexpr size_t kDs5MinLen = 11;
constexpr size_t kDs5LeftTriggerByte = 5;
constexpr size_t kDs5RightTriggerByte = 6;
constexpr size_t kDs5FaceByte = 8;
constexpr size_t kDs5ShoulderByte = 9;
// Byte 10 carries the touchpad click and, beside it, the mic-mute button.
constexpr size_t kDs5MiscByte = 10;
constexpr uint8_t kDs5MicMuteButtonBit = 0x04;
constexpr int kDs5GyroOffset = 16;
constexpr int kDs5AccelOffset = 22;
constexpr size_t kDs5MotionMinLen = 28;
// Two 4-byte points in every report (no DS4-style frame bundling).
constexpr size_t kDs5Touch0Offset = 33;
constexpr size_t kDs5Touch1Offset = 37;
constexpr size_t kDs5TouchMinLen = 41;
constexpr size_t kDs5BatteryByte = 53;
constexpr size_t kDs5BatteryMinLen = 54;

// The Sony pads report charge in tenths (0 = 0..9 %, 1 = 10..19 %, ...), and the platforms show
// the midpoint of each band, capped at 100.
constexpr unsigned kPercentPerTenth = 10;
constexpr unsigned kTenthMidpointPercent = 5;
constexpr unsigned kPercentFull = 100;
constexpr uint8_t kSonyTenthsMask = 0x0F;
constexpr uint8_t kDs4CableBit = 0x10;
constexpr uint8_t kSonyTenthsStillCharging = 10;
constexpr uint8_t kSonyTenthsFull = 11;
constexpr int kDs5BatteryStateShift = 4;
constexpr uint8_t kDs5BatteryDischarging = 0x0;
constexpr uint8_t kDs5BatteryCharging = 0x1;
constexpr uint8_t kDs5BatteryFull = 0x2;

uint8_t sonyTenthsToPercent(const uint8_t tenths) {
    const unsigned pct = (unsigned)tenths * kPercentPerTenth + kTenthMidpointPercent;
    return (uint8_t)std::min(pct, kPercentFull);
}

uint16_t decodePsButtons(const uint8_t faceByte, const uint8_t shoulderByte) {
    const uint16_t face = buttonsFromByte(faceByte, kPsFaceByteBits);
    const uint16_t shoulders = buttonsFromByte(shoulderByte, kPsShoulderByteBits);
    return setDpadFromHat((uint16_t)(face | shoulders), (uint8_t)(faceByte & kLowNibbleMask));
}

// Both pads lay the IMU out as six int16 LE words, gyro then accel; only the base offsets move.
void decodePsMotion(const uint8_t* buf, const int gyroBase, const int accelBase,
                    const PsImuCalib& calib, DeviceState& s) {
    s.gyroX = ds4GyroAxisToWire(rdLe16(buf, gyroBase), calib, 0);
    s.gyroY = ds4GyroAxisToWire(rdLe16(buf, gyroBase + 2), calib, 1);
    s.gyroZ = ds4GyroAxisToWire(rdLe16(buf, gyroBase + 4), calib, 2);
    s.accelX = ds4AccelAxisToWire(rdLe16(buf, accelBase), calib, 0);
    s.accelY = ds4AccelAxisToWire(rdLe16(buf, accelBase + 2), calib, 1);
    s.accelZ = ds4AccelAxisToWire(rdLe16(buf, accelBase + 4), calib, 2);
    s.motionValid = true;
}

void setBattery(DeviceState& s, const uint8_t level, const uint8_t status) {
    s.batteryLevel = level;
    s.batteryStatus = status;
}

void setBatteryUnknown(DeviceState& s) {
    setBattery(s, PAD_BATTERY_LEVEL_UNKNOWN, PAD_BATTERY_STATUS_UNKNOWN);
}

// hid-playstation's status[0]: low nibble the charge in tenths, 0x10 the cable. With the cable in,
// 10 is still charging, 11 is full, and 12..15 are the firmware's error states.
void decodeDs4Battery(const uint8_t status, DeviceState& s) {
    const uint8_t tenths = (uint8_t)(status & kSonyTenthsMask);
    const bool cableIn = (status & kDs4CableBit) != 0;
    s.batteryValid = true;
    if (!cableIn) return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_DISCHARGING);

    const bool stillCharging = tenths <= kSonyTenthsStillCharging;
    if (stillCharging)
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_CHARGING);

    const bool full = tenths == kSonyTenthsFull;
    if (full) return setBattery(s, (uint8_t)kPercentFull, PAD_BATTERY_STATUS_FULL);
    setBatteryUnknown(s);
}

// hid-playstation's status: low nibble the charge in tenths, high nibble the state. 0xA/0xB are a
// temperature or voltage fault and 0xF a charging fault; a fault has no charge worth showing.
void decodeDualSenseBattery(const uint8_t status, DeviceState& s) {
    const uint8_t tenths = (uint8_t)(status & kSonyTenthsMask);
    const uint8_t state = (uint8_t)(status >> kDs5BatteryStateShift);
    s.batteryValid = true;
    switch (state) {
    case kDs5BatteryDischarging:
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_DISCHARGING);
    case kDs5BatteryCharging:
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_CHARGING);
    case kDs5BatteryFull:
        return setBattery(s, (uint8_t)kPercentFull, PAD_BATTERY_STATUS_FULL);
    default:
        return setBatteryUnknown(s);
    }
}

// The press is an edge, the wire bit is a state: the latch flips on the way down.
void applyMicMuteLatch(const uint8_t buttonByte, ParserState& sticks, uint16_t& buttons) {
    const bool muteDown = (buttonByte & kDs5MicMuteButtonBit) != 0;
    const bool isAFreshPress = muteDown && !sticks.micMuteHeld;
    if (isAFreshPress) sticks.micMuted = !sticks.micMuted;
    sticks.micMuteHeld = muteDown;
    if (sticks.micMuted) buttons |= WBUTTON_MIC_MUTE;
}

// [33] is the bundled frame count; only the newest frame matters, since the wire stream
// supersedes per send.
void decodeDs4Touch(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < kDs4TouchMinLen) return;
    const uint8_t bundled = buf[kDs4TouchFrameCountByte];
    if (bundled == 0) return;
    const size_t frames = std::min<size_t>(bundled, kDs4MaxTouchFrames);
    const size_t newest = kDs4TouchFramesOffset + kDs4TouchFrameBytes * (frames - 1);
    if (len < newest + kDs4TouchFrameBytes) return;
    const uint8_t* points = buf + newest + kDs4TouchFrameTimestampBytes;
    decodePsTouchPoint(points, kDs4TouchMaxX, kDs4TouchMaxY, s.touch0Active, s.touch0Id, s.touch0X,
                       s.touch0Y);
    decodePsTouchPoint(points + kPsTouchPointBytes, kDs4TouchMaxX, kDs4TouchMaxY, s.touch1Active,
                       s.touch1Id, s.touch1X, s.touch1Y);
    s.touchClick = (buf[kDs4TouchClickByte] & kPsTouchClickBit) != 0;
    s.touchValid = true;
}

bool decodeDualShock4(const uint8_t* buf, const size_t len, DeviceState& s,
                      const PsImuCalib* calib) {
    if (len < kDs4MinLen) return false;
    if (buf[0] != kPsInputReport) return false;

    decodeU8Sticks(buf + kPsSticksOffset, s);
    s.wButtons = decodePsButtons(buf[kDs4FaceByte], buf[kDs4ShoulderByte]);
    s.bLT = buf[kDs4LeftTriggerByte];
    s.bRT = buf[kDs4RightTriggerByte];

    const bool hasMotion = calib != nullptr && calib->valid && len >= kDs4MotionMinLen;
    if (hasMotion) decodePsMotion(buf, kDs4GyroOffset, kDs4AccelOffset, *calib, s);

    decodeDs4Touch(buf, len, s);
    if (len >= kDs4BatteryMinLen) decodeDs4Battery(buf[kDs4BatteryByte], s);
    return true;
}

void decodeDualSenseTouch(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < kDs5TouchMinLen) return;
    decodePsTouchPoint(buf + kDs5Touch0Offset, kDualSenseTouchMaxX, kDualSenseTouchMaxY,
                       s.touch0Active, s.touch0Id, s.touch0X, s.touch0Y);
    decodePsTouchPoint(buf + kDs5Touch1Offset, kDualSenseTouchMaxX, kDualSenseTouchMaxY,
                       s.touch1Active, s.touch1Id, s.touch1X, s.touch1Y);
    s.touchClick = (buf[kDs5MiscByte] & kPsTouchClickBit) != 0;
    s.touchValid = true;
}

// Takes the whole ParserState rather than just the IMU calibration because the mic-mute button
// needs a latch across reports; a null one decodes everything except the mute state, which is the
// honest answer for a caller that kept no per-device memory.
bool decodeDualSense(const uint8_t* buf, const size_t len, DeviceState& s, ParserState* sticks) {
    if (len < kDs5MinLen) return false;
    if (buf[0] != kPsInputReport) return false;
    const PsImuCalib* calib = sticks != nullptr ? &sticks->psImu : nullptr;

    decodeU8Sticks(buf + kPsSticksOffset, s);
    s.bLT = buf[kDs5LeftTriggerByte];
    s.bRT = buf[kDs5RightTriggerByte];

    uint16_t buttons = decodePsButtons(buf[kDs5FaceByte], buf[kDs5ShoulderByte]);
    if (sticks != nullptr) applyMicMuteLatch(buf[kDs5MiscByte], *sticks, buttons);
    s.wButtons = buttons;

    const bool hasMotion = calib != nullptr && calib->valid && len >= kDs5MotionMinLen;
    if (hasMotion) decodePsMotion(buf, kDs5GyroOffset, kDs5AccelOffset, *calib, s);

    decodeDualSenseTouch(buf, len, s);
    if (len >= kDs5BatteryMinLen) decodeDualSenseBattery(buf[kDs5BatteryByte], s);
    return true;
}

// Switch Pro standard full input report 0x30 over USB: the battery byte, three button bytes
// (right/shared/left) with ZL/ZR as digital bits, two packed 12-bit sticks, then up to three IMU
// frames. The XUSB mapping matches by physical position rather than label, the same convention
// used for DualShock 4: Switch A (right face) → XUSB_B, Switch B (bottom) → XUSB_A, Switch X (top)
// → XUSB_Y, Switch Y (left) → XUSB_X. This is what PC games and ViGEm expect.
constexpr uint8_t kSwitchInputReport = 0x30;
constexpr size_t kSwitchInputMinLen = 12;
constexpr size_t kSwitchBatteryByte = 2;
constexpr size_t kSwitchRightByte = 3;
constexpr size_t kSwitchSharedByte = 4;
constexpr size_t kSwitchLeftByte = 5;
constexpr uint8_t kSwitchZrBit = 0x80;
constexpr uint8_t kSwitchZlBit = 0x80;
constexpr size_t kSwitchLeftStickOffset = 6;
constexpr size_t kSwitchRightStickOffset = 9;
constexpr ButtonBit kSwitchRightByteBits[] = {
    {0x01, XUSB_X}, {0x02, XUSB_Y}, {0x04, XUSB_A}, {0x08, XUSB_B}, {0x40, XUSB_RB},
};
constexpr ButtonBit kSwitchSharedByteBits[] = {
    {0x01, XUSB_BACK},
    {0x02, XUSB_START},
    {0x04, XUSB_THUMB_R},
    {0x08, XUSB_THUMB_L},
};
constexpr ButtonBit kSwitchLeftByteBits[] = {
    {0x01, XUSB_DPAD_DOWN}, {0x02, XUSB_DPAD_UP}, {0x04, XUSB_DPAD_RIGHT},
    {0x08, XUSB_DPAD_LEFT}, {0x40, XUSB_LB},
};

uint16_t decodeSwitchProButtons(const uint8_t right, const uint8_t shared, const uint8_t left) {
    const uint16_t rightSide = buttonsFromByte(right, kSwitchRightByteBits);
    const uint16_t middle = buttonsFromByte(shared, kSwitchSharedByteBits);
    const uint16_t leftSide = buttonsFromByte(left, kSwitchLeftByteBits);
    return (uint16_t)(rightSide | middle | leftSide);
}

void decodeSwitchProSticks(const uint8_t* buf, ParserState& sticks, DeviceState& s) {
    uint16_t lx = 0;
    uint16_t ly = 0;
    uint16_t rx = 0;
    uint16_t ry = 0;
    unpackTwo12Bit(buf + kSwitchLeftStickOffset, lx, ly);
    unpackTwo12Bit(buf + kSwitchRightStickOffset, rx, ry);
    s.sLX = scaleSwitchStickAuto(lx, sticks.lx);
    s.sLY = scaleSwitchStickAuto(ly, sticks.ly);
    s.sRX = scaleSwitchStickAuto(rx, sticks.rx);
    s.sRY = scaleSwitchStickAuto(ry, sticks.ry);
}

// hid-nintendo's bat_con: bits 7..5 the charge in five steps (empty, critical, low, medium,
// full), bit 4 charging, bit 0 host-powered. The percent is the step's midpoint, the same coarse
// number the framework shows for this pad.
constexpr int kSwitchBatteryStepShift = 5;
constexpr uint8_t kSwitchChargingBit = 0x10;
constexpr uint8_t kSwitchHostPoweredBit = 0x01;
constexpr uint8_t kSwitchBatteryStepPercent[] = {5, 25, 50, 75, 100};
constexpr uint8_t kSwitchBatteryFullStep = 4;

void decodeSwitchProBattery(const uint8_t batCon, DeviceState& s) {
    const uint8_t step = (uint8_t)(batCon >> kSwitchBatteryStepShift);
    const bool charging = (batCon & kSwitchChargingBit) != 0;
    const bool hostPowered = (batCon & kSwitchHostPoweredBit) != 0;
    const bool stepIsKnown = step <= kSwitchBatteryFullStep;
    s.batteryValid = true;
    s.batteryLevel = stepIsKnown ? kSwitchBatteryStepPercent[step] : PAD_BATTERY_LEVEL_UNKNOWN;
    if (charging) {
        s.batteryStatus = PAD_BATTERY_STATUS_CHARGING;
        return;
    }
    const bool toppedOffOnTheCable = hostPowered && step == kSwitchBatteryFullStep;
    s.batteryStatus =
        toppedOffOnTheCable ? PAD_BATTERY_STATUS_FULL : PAD_BATTERY_STATUS_DISCHARGING;
}

struct SwitchImuSum {
    int32_t ax, ay, az, gx, gy, gz;
    int32_t frames;
};

// The pad packs up to three ~5ms samples per report; one 12-byte frame is accel int16 LE x3 then
// gyro x3, the first at byte 13.
constexpr size_t kSwitchImuFirstFrame = 13;
constexpr size_t kSwitchImuFrameBytes = 12;
constexpr size_t kSwitchImuMaxFrames = 3;

SwitchImuSum sumSwitchImuFrames(const uint8_t* buf, const size_t len) {
    SwitchImuSum sum = {0, 0, 0, 0, 0, 0, 0};
    const size_t available =
        len >= kSwitchImuFirstFrame ? (len - kSwitchImuFirstFrame) / kSwitchImuFrameBytes : 0;
    const size_t frames = std::min(available, kSwitchImuMaxFrames);
    for (size_t f = 0; f < frames; f++) {
        const int off = (int)(kSwitchImuFirstFrame + kSwitchImuFrameBytes * f);
        sum.ax += rdLe16(buf, off);
        sum.ay += rdLe16(buf, off + 2);
        sum.az += rdLe16(buf, off + 4);
        sum.gx += rdLe16(buf, off + 6);
        sum.gy += rdLe16(buf, off + 8);
        sum.gz += rdLe16(buf, off + 10);
    }
    sum.frames = (int32_t)frames;
    return sum;
}

// Rotate the Switch IMU frame onto the DS4 wire convention (wire gyro X=pitch, Y=yaw, Z=roll); the
// pad reports those on raw gyro Y/Z/X. Pitch and roll are negated to match the DS4 sign
// convention. Hardware testing confirmed pitch and yaw; roll's sign and the accel signs are
// unverified.
void applySwitchProMotion(const SwitchImuSum& sum, DeviceState& s) {
    const int32_t n = sum.frames;
    s.gyroX = switchGyroToWire(clampI16(-(sum.gy / n)));
    s.gyroY = switchGyroToWire(clampI16(sum.gz / n));
    s.gyroZ = switchGyroToWire(clampI16(-(sum.gx / n)));
    s.accelX = switchAccelToWire(clampI16(sum.ay / n));
    s.accelY = switchAccelToWire(clampI16(sum.az / n));
    s.accelZ = switchAccelToWire(clampI16(sum.ax / n));
    s.motionValid = true;
}

bool decodeSwitchProUsb(const uint8_t* buf, const size_t len, DeviceState& s, ParserState& sticks) {
    if (len < kSwitchInputMinLen) return false;
    if (buf[0] != kSwitchInputReport) return false;

    const uint8_t rightByte = buf[kSwitchRightByte];
    const uint8_t sharedByte = buf[kSwitchSharedByte];
    const uint8_t leftByte = buf[kSwitchLeftByte];

    s.wButtons = decodeSwitchProButtons(rightByte, sharedByte, leftByte);
    s.bLT = (leftByte & kSwitchZlBit) ? kTriggerMax : 0;
    s.bRT = (rightByte & kSwitchZrBit) ? kTriggerMax : 0;

    decodeSwitchProSticks(buf, sticks, s);
    decodeSwitchProBattery(buf[kSwitchBatteryByte], s);

    const SwitchImuSum imu = sumSwitchImuFrames(buf, len);
    const bool hasMotion = imu.frames > 0;
    if (hasMotion) applySwitchProMotion(imu, s);
    return true;
}

// Stadia report 0x03: four centred uint8 sticks, two 8-bit triggers, a hat byte, then the face and
// system button bytes.
constexpr uint8_t kStadiaInputReport = 0x03;
constexpr size_t kStadiaMinLen = 11;
constexpr size_t kStadiaSticksOffset = 1;
constexpr size_t kStadiaLeftTriggerByte = 5;
constexpr size_t kStadiaRightTriggerByte = 6;
constexpr size_t kStadiaHatByte = 7;
constexpr size_t kStadiaFaceByte = 8;
constexpr size_t kStadiaSystemByte = 9;
constexpr ButtonBit kStadiaFaceByteBits[] = {
    {0x40, XUSB_A}, {0x20, XUSB_B},  {0x10, XUSB_X},
    {0x08, XUSB_Y}, {0x04, XUSB_LB}, {0x02, XUSB_RB},
};
constexpr ButtonBit kStadiaSystemByteBits[] = {
    {0x80, XUSB_START},
    {0x40, XUSB_BACK},
    {0x20, XUSB_THUMB_L},
    {0x10, XUSB_THUMB_R},
};

uint16_t decodeStadiaButtons(const uint8_t faceByte, const uint8_t systemByte, const uint8_t hat) {
    const uint16_t face = buttonsFromByte(faceByte, kStadiaFaceByteBits);
    const uint16_t system = buttonsFromByte(systemByte, kStadiaSystemByteBits);
    return setDpadFromHat((uint16_t)(face | system), (uint8_t)(hat & kLowNibbleMask));
}

bool decodeStadia(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < kStadiaMinLen) return false;
    if (buf[0] != kStadiaInputReport) return false;
    decodeU8Sticks(buf + kStadiaSticksOffset, s);
    s.bLT = buf[kStadiaLeftTriggerByte];
    s.bRT = buf[kStadiaRightTriggerByte];
    s.wButtons =
        decodeStadiaButtons(buf[kStadiaFaceByte], buf[kStadiaSystemByte], buf[kStadiaHatByte]);
    return true;
}

// Steam Controller state packet, layout from SDL's src/joystick/hidapi/steam headers (zlib,
// Copyright (C) Valve Corporation; see THIRD_PARTY.md). Buttons occupy the low 24 bits of a 64-bit
// field whose bytes 3 and 4 are the analog triggers.
constexpr uint32_t kSteamRightBumper = 0x000004;
constexpr uint32_t kSteamLeftBumper = 0x000008;
constexpr uint32_t kSteamNorth = 0x000010;
constexpr uint32_t kSteamEast = 0x000020;
constexpr uint32_t kSteamWest = 0x000040;
constexpr uint32_t kSteamSouth = 0x000080;
constexpr uint32_t kSteamDpadUp = 0x000100;
constexpr uint32_t kSteamDpadRight = 0x000200;
constexpr uint32_t kSteamDpadLeft = 0x000400;
constexpr uint32_t kSteamDpadDown = 0x000800;
constexpr uint32_t kSteamMenu = 0x001000;
constexpr uint32_t kSteamGuide = 0x002000;
constexpr uint32_t kSteamEscape = 0x004000;
constexpr uint32_t kSteamLeftPadClicked = 0x020000;
constexpr uint32_t kSteamRightPadClicked = 0x040000;
constexpr uint32_t kSteamLeftPadFinger = 0x080000;
constexpr uint32_t kSteamRightPadFinger = 0x100000;
constexpr uint32_t kSteamStickButton = 0x400000;
constexpr uint32_t kSteamLeftPadAndStick = 0x800000;

// Packet framing per the Linux hid-steam driver: {version u16 LE, type, payload length, payload}.
constexpr size_t kSteamStateLen = 48;
constexpr uint8_t kSteamVersionLo = 0x01;
constexpr uint8_t kSteamVersionHi = 0x00;
constexpr uint8_t kSteamStateType = 0x01;
constexpr uint8_t kSteamWirelessType = 0x03;
constexpr size_t kSteamWirelessEventLen = 5;
constexpr uint8_t kSteamWirelessPayloadLen = 0x01;
constexpr uint8_t kSteamWirelessDisconnect = 0x01;
constexpr uint8_t kSteamWirelessConnect = 0x02;
constexpr size_t kSteamButtonsOffset = 8;
constexpr size_t kSteamLeftTriggerByte = 11;
constexpr size_t kSteamRightTriggerByte = 12;
constexpr int kSteamLeftAxesOffset = 16;
constexpr int kSteamRightPadOffset = 20;
constexpr int kSteamAccelOffset = 28;
constexpr int kSteamGyroOffset = 34;
constexpr int32_t kSteamTriggerMaxAnalog = 26000;
// The 8-bit trigger byte replicated into the 15-bit scale SDL reads the pad at.
constexpr int kSteamTriggerWidenShift = 7;
constexpr int64_t kQ16One = 65536;
// 15 degrees in Q16; the pads sit rotated on the shell and SDL applies the same correction.
constexpr int64_t kSteamPadCos = 63303;
constexpr int64_t kSteamPadSin = 16962;
// Steam reports gyro over +-2000 deg/s and accel over +-2g against the wire's +-2000 deg/s and
// +-4 g, so gyro passes through at 32767/32768 and accel halves.
constexpr int64_t kSteamGyroDivisor = 32768;
constexpr int64_t kSteamAccelDivisor = 65536;

bool isSteamPacket(const uint8_t* buf, const uint8_t type) {
    const bool isTheVersion = buf[0] == kSteamVersionLo && buf[1] == kSteamVersionHi;
    return isTheVersion && buf[2] == type;
}

uint8_t steamTriggerToWire(const uint8_t raw) {
    const int32_t widened = ((int32_t)raw << kSteamTriggerWidenShift) | raw;
    const int32_t clamped = std::min(widened, kSteamTriggerMaxAnalog);
    return (uint8_t)((clamped * kTriggerMax) / kSteamTriggerMaxAnalog);
}

// SDL adds a further +1000 to each axis while a finger is down. That is harmless for a touch
// surface but would park a stick off centre, so it is deliberately not carried over.
void steamRotatePad(const int32_t x, const int32_t y, int16_t& outX, int16_t& outY) {
    outX = clampI16((kSteamPadCos * x - kSteamPadSin * y) / kQ16One);
    outY = clampI16((kSteamPadSin * x + kSteamPadCos * y) / kQ16One);
}

int16_t steamGyroToWire(const int32_t raw) {
    return clampI16((int64_t)raw * kAxisMax / kSteamGyroDivisor);
}

int16_t steamAccelToWire(const int32_t raw) {
    return clampI16((int64_t)raw * kAxisMax / kSteamAccelDivisor);
}

uint16_t decodeSteamButtons(const uint32_t btn) {
    uint16_t buttons = 0;
    if (btn & kSteamSouth) buttons |= XUSB_A;
    if (btn & kSteamEast) buttons |= XUSB_B;
    if (btn & kSteamWest) buttons |= XUSB_X;
    if (btn & kSteamNorth) buttons |= XUSB_Y;
    if (btn & kSteamLeftBumper) buttons |= XUSB_LB;
    if (btn & kSteamRightBumper) buttons |= XUSB_RB;
    if (btn & kSteamMenu) buttons |= XUSB_BACK;
    if (btn & kSteamEscape) buttons |= XUSB_START;
    if (btn & kSteamGuide) buttons |= XUSB_GUIDE;
    if (btn & kSteamDpadUp) buttons |= XUSB_DPAD_UP;
    if (btn & kSteamDpadDown) buttons |= XUSB_DPAD_DOWN;
    if (btn & kSteamDpadLeft) buttons |= XUSB_DPAD_LEFT;
    if (btn & kSteamDpadRight) buttons |= XUSB_DPAD_RIGHT;
    if (btn & kSteamRightPadClicked) buttons |= XUSB_THUMB_R;
    return buttons;
}

// One pair of axes carries either the stick or the left pad. The finger-down bit says which; the
// interleave bit means pad frames alternate with stick frames worth holding on to.
void trackSteamLeftStick(const uint8_t* buf, const uint32_t btn, ParserState& st,
                         uint16_t& buttons) {
    const bool padOnLeft = (btn & kSteamLeftPadFinger) != 0;
    const bool interleaved = (btn & kSteamLeftPadAndStick) != 0;
    if (!padOnLeft) {
        st.steamStickX = rdLe16(buf, kSteamLeftAxesOffset);
        st.steamStickY = rdLe16(buf, kSteamLeftAxesOffset + 2);
        const bool clickIsTheStick = !interleaved && (btn & kSteamLeftPadClicked) != 0;
        if (clickIsTheStick) buttons |= XUSB_THUMB_L;
    } else if (!interleaved) {
        st.steamStickX = 0;
        st.steamStickY = 0;
    }
    if (btn & kSteamStickButton) buttons |= XUSB_THUMB_L;
}

void decodeSteamRightPad(const uint8_t* buf, const uint32_t btn, DeviceState& s) {
    const bool fingerDown = (btn & kSteamRightPadFinger) != 0;
    if (!fingerDown) {
        s.sRX = 0;
        s.sRY = 0;
        return;
    }
    steamRotatePad(rdLe16(buf, kSteamRightPadOffset), rdLe16(buf, kSteamRightPadOffset + 2), s.sRX,
                   s.sRY);
}

void decodeSteamMotion(const uint8_t* buf, DeviceState& s) {
    const int16_t ax = rdLe16(buf, kSteamAccelOffset);
    const int16_t ay = rdLe16(buf, kSteamAccelOffset + 2);
    const int16_t az = rdLe16(buf, kSteamAccelOffset + 4);
    const int16_t gx = rdLe16(buf, kSteamGyroOffset);
    const int16_t gy = rdLe16(buf, kSteamGyroOffset + 2);
    const int16_t gz = rdLe16(buf, kSteamGyroOffset + 4);
    const bool imuIsAlive = (ax | ay | az | gx | gy | gz) != 0;
    if (!imuIsAlive) return;

    s.gyroX = steamGyroToWire(gx);
    s.gyroY = steamGyroToWire(gz);
    s.gyroZ = steamGyroToWire(gy);
    s.accelX = steamAccelToWire(ax);
    s.accelY = steamAccelToWire(az);
    s.accelZ = steamAccelToWire(-(int32_t)ay);
    s.motionValid = true;
}

bool decodeSteamController(const uint8_t* buf, const size_t len, DeviceState& s, ParserState& st) {
    if (len < kSteamStateLen) return false;
    if (!isSteamPacket(buf, kSteamStateType)) return false;

    const uint32_t btn = rdLe24(buf + kSteamButtonsOffset);

    uint16_t buttons = decodeSteamButtons(btn);
    s.bLT = steamTriggerToWire(buf[kSteamLeftTriggerByte]);
    s.bRT = steamTriggerToWire(buf[kSteamRightTriggerByte]);

    trackSteamLeftStick(buf, btn, st, buttons);
    s.wButtons = buttons;
    s.sLX = st.steamStickX;
    s.sLY = st.steamStickY;

    decodeSteamRightPad(buf, btn, s);
    decodeSteamMotion(buf, s);
    return true;
}

// The fixed-offset guess for a HID gamepad whose descriptor did not parse: four centred uint8
// sticks, a byte of hat nibble plus face buttons, and a byte of the rest with the triggers as its
// top two bits. Anything shorter is not gamepad-shaped and is dropped rather than published as
// noise.
constexpr size_t kGenericHidMinReportLen = 7;
constexpr size_t kHidButtonsLoByte = 4;
constexpr size_t kHidButtonsHiByte = 5;
constexpr uint8_t kHidLeftTriggerBit = 0x40;
constexpr uint8_t kHidRightTriggerBit = 0x80;
constexpr ButtonBit kHidButtonsLoBits[] = {
    {0x10, XUSB_A},
    {0x20, XUSB_B},
    {0x40, XUSB_X},
    {0x80, XUSB_Y},
};
constexpr ButtonBit kHidButtonsHiBits[] = {
    {0x01, XUSB_LB},    {0x02, XUSB_RB},      {0x04, XUSB_BACK},
    {0x08, XUSB_START}, {0x10, XUSB_THUMB_L}, {0x20, XUSB_THUMB_R},
};

uint16_t decodeGenericHidButtons(const uint8_t btnLo, const uint8_t btnHi) {
    const uint16_t face = buttonsFromByte(btnLo, kHidButtonsLoBits);
    const uint16_t rest = buttonsFromByte(btnHi, kHidButtonsHiBits);
    return setDpadFromHat((uint16_t)(face | rest), (uint8_t)(btnLo & kLowNibbleMask));
}

// Switch Pro HD-rumble amplitude codes from the Linux hid-nintendo table; frequency held at the
// neutral default so a coarse strong/weak motor still encodes a faithful buzz. See docs/rumble.md.
struct SwitchAmpCode {
    uint8_t high;
    uint16_t low;
    uint16_t amp;
};

constexpr SwitchAmpCode kSwitchAmpCodes[] = {
    {0x00, 0x0040, 0},   {0x02, 0x8040, 10},  {0x08, 0x0042, 17},  {0x10, 0x0044, 33},
    {0x40, 0x0050, 230}, {0x70, 0x005c, 387}, {0xa0, 0x0068, 650}, {0xc8, 0x0072, 1003},
};

constexpr uint32_t kWireMotorMax = 65535;
constexpr uint32_t kSwitchAmpMax = 1003;
constexpr uint8_t kSwitchMotorHighBase = 0x01;
constexpr uint8_t kSwitchMotorLowBase = 0x40;

// The largest table code at or below the wire level, as four HD-rumble bytes.
void switchEncodeMotor(uint8_t* out, const uint16_t magnitude) {
    const uint32_t amp = (uint32_t)magnitude * kSwitchAmpMax / kWireMotorMax;
    const SwitchAmpCode* code = &kSwitchAmpCodes[0];
    for (const SwitchAmpCode& e : kSwitchAmpCodes) {
        if (e.amp <= amp) {
            code = &e;
        } else {
            break;
        }
    }
    out[0] = 0x00;
    out[1] = (uint8_t)(kSwitchMotorHighBase + code->high);
    out[2] = (uint8_t)(kSwitchMotorLowBase + highByte(code->low));
    out[3] = (uint8_t)code->low;
}

// Gyro calibration occupies bytes 1..21: a per-axis bias, the readings at the two rotation
// extremes, and the two speed words whose sum is the scale. A zero span means the pad never
// answered with usable calibration.
bool parsePsGyroCalibration(const uint8_t* buf, PsImuCalib& out) {
    const int32_t gyroBias[3] = {rdLe16(buf, 1), rdLe16(buf, 3), rdLe16(buf, 5)};
    const int32_t gyroPlus[3] = {rdLe16(buf, 7), rdLe16(buf, 11), rdLe16(buf, 15)};
    const int32_t gyroMinus[3] = {rdLe16(buf, 9), rdLe16(buf, 13), rdLe16(buf, 17)};
    const int32_t speed2x = rdLe16(buf, 19) + rdLe16(buf, 21);
    for (int i = 0; i < 3; i++) {
        const int32_t a = gyroPlus[i] - gyroBias[i];
        const int32_t b = gyroMinus[i] - gyroBias[i];
        const int32_t denom = (a < 0 ? -a : a) + (b < 0 ? -b : b);
        if (denom == 0) return false;
        out.gyroNumer[i] = speed2x * kPsGyroResPerDegS;
        out.gyroDenom[i] = denom;
    }
    return true;
}

// Accel calibration occupies bytes 23..34: the +1g and -1g reading per axis, whose midpoint is
// the bias and whose span is the full 2g range.
bool parsePsAccelCalibration(const uint8_t* buf, PsImuCalib& out) {
    const int32_t accPlus[3] = {rdLe16(buf, 23), rdLe16(buf, 27), rdLe16(buf, 31)};
    const int32_t accMinus[3] = {rdLe16(buf, 25), rdLe16(buf, 29), rdLe16(buf, 33)};
    for (int i = 0; i < 3; i++) {
        const int32_t range2g = accPlus[i] - accMinus[i];
        if (range2g == 0) return false;
        out.accelBias[i] = accPlus[i] - range2g / 2;
        out.accelNumer[i] = 2 * kPsAccelResPerG;
        out.accelDenom[i] = range2g;
    }
    return true;
}

} // namespace

bool parsePsCalibration(const uint8_t* buf, const size_t len, PsImuCalib& out) {
    out = PsImuCalib{};
    if (len < PS_CALIBRATION_REPORT_BYTES) return false;
    if (!parsePsGyroCalibration(buf, out)) return false;
    if (!parsePsAccelCalibration(buf, out)) return false;
    out.valid = true;
    return true;
}

bool decodeReport(const Parser p, const uint8_t* buf, const size_t len, DeviceState& s,
                  ParserState* sticks) {
    switch (p) {
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
        return decodeXInput360(buf, len, s);
    case Parser::XBOX_ONE_GIP:
        return sticks != nullptr && decodeXboxOneGip(buf, len, s, *sticks);
    case Parser::DUALSHOCK4:
        return decodeDualShock4(buf, len, s, sticks ? &sticks->psImu : nullptr);
    case Parser::DUALSENSE:
        return decodeDualSense(buf, len, s, sticks);
    case Parser::SWITCH_PRO_USB:
        return sticks != nullptr && decodeSwitchProUsb(buf, len, s, *sticks);
    case Parser::STADIA:
        return decodeStadia(buf, len, s);
    case Parser::STEAM_CONTROLLER:
        return sticks != nullptr && decodeSteamController(buf, len, s, *sticks);
    case Parser::GENERIC_HID_GAMEPAD: {
        const bool hasALayout = sticks != nullptr && sticks->hidLayout.valid;
        if (hasALayout) return usbhid::decodeFromLayout(buf, len, s, sticks->hidLayout);
        return decodeGenericHidGamepad(buf, len, s);
    }
    case Parser::NONE:
        return false;
    }
    return false;
}

// Event framing and the one-byte payload values follow the Linux hid-steam driver
// (steam_raw_event, ID_CONTROLLER_WIRELESS).
WirelessEvent checkWirelessEvent(const Parser p, const uint8_t* buf, const size_t len) {
    if (p != Parser::STEAM_CONTROLLER) return WirelessEvent::NONE;
    if (len < kSteamWirelessEventLen) return WirelessEvent::NONE;
    if (!isSteamPacket(buf, kSteamWirelessType)) return WirelessEvent::NONE;
    if (buf[3] != kSteamWirelessPayloadLen) return WirelessEvent::NONE;
    switch (buf[4]) {
    case kSteamWirelessDisconnect:
        return WirelessEvent::DISCONNECT;
    case kSteamWirelessConnect:
        return WirelessEvent::CONNECT;
    default:
        return WirelessEvent::NONE;
    }
}

bool decodeGenericHidGamepad(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < kGenericHidMinReportLen) return false;
    decodeU8Sticks(buf, s);
    const uint8_t btnLo = buf[kHidButtonsLoByte];
    const uint8_t btnHi = buf[kHidButtonsHiByte];
    s.wButtons = decodeGenericHidButtons(btnLo, btnHi);
    s.bLT = (btnHi & kHidLeftTriggerBit) ? kTriggerMax : 0;
    s.bRT = (btnHi & kHidRightTriggerBit) ? kTriggerMax : 0;
    return true;
}

namespace {

struct InitPacket {
    const uint8_t* data;
    size_t len;
};

// A device's init packets in the order they must be sent. An empty one means the device has no
// sequence for that stage.
struct InitSequence {
    const InitPacket* packets;
    int count;
};

template <size_t N> constexpr int countOf(const InitPacket (&)[N]) { return (int)N; }

// Copies the index-th packet of a sequence into out. Zero means there is no such packet, or it
// does not fit, which is how both callers learn the sequence has ended.
size_t copyInitPacket(const InitSequence& sequence, const int index, uint8_t* out,
                      const size_t outCap) {
    if (index < 0 || index >= sequence.count) return 0;
    const size_t len = sequence.packets[index].len;
    if (len > outCap) return 0;
    memcpy(out, sequence.packets[index].data, len);
    return len;
}

// GIP init packets from Linux xpad. Power-on, LED and auth-done are universal; the S-init is the
// extra set-mode packet the Xbox One S / Elite Series 2 need. Byte 2 carries the sequence number,
// which buildGipInitPacket stamps in.
constexpr uint8_t kGipPowerOn[] = {0x05, 0x20, 0x00, 0x01, 0x00};
constexpr uint8_t kGipSInit[] = {0x05, 0x20, 0x00, 0x0F, 0x06};
constexpr uint8_t kGipLedOn[] = {0x0A, 0x20, 0x00, 0x03, 0x00, 0x01, 0x14};
constexpr uint8_t kGipAuthDone[] = {0x06, 0x20, 0x00, 0x02, 0x01, 0x00};

constexpr InitPacket kGipPowerOnSeq[] = {
    {kGipPowerOn, sizeof(kGipPowerOn)},
    {kGipLedOn, sizeof(kGipLedOn)},
    {kGipAuthDone, sizeof(kGipAuthDone)},
};

constexpr InitPacket kGipSSeq[] = {
    {kGipPowerOn, sizeof(kGipPowerOn)},
    {kGipSInit, sizeof(kGipSInit)},
    {kGipLedOn, sizeof(kGipLedOn)},
    {kGipAuthDone, sizeof(kGipAuthDone)},
};

InitSequence gipSequenceFor(const InitKind init) {
    if (init == InitKind::XBOX_ONE_POWERON) return {kGipPowerOnSeq, countOf(kGipPowerOnSeq)};
    if (init == InitKind::XBOX_ONE_S) return {kGipSSeq, countOf(kGipSSeq)};
    return {nullptr, 0};
}

} // namespace

size_t buildGipInitPacket(const InitKind init, const int index, const uint8_t seq, uint8_t* out,
                          const size_t outCap) {
    const size_t len = copyInitPacket(gipSequenceFor(init), index, out, outCap);
    if (len == 0) return 0;
    out[2] = seq;
    return len;
}

namespace {

// Steam Controller framing is {message id, payload length, payload}. The message ids and the
// choice of the shortest working sequence follow the Linux hid-steam driver.
constexpr uint8_t kSteamClearMappings[] = {0x81, 0x00};

// Left and right trackpad mode = none (which kills mouse emulation), IMU mode = raw accel | raw
// gyro.
constexpr uint8_t kSteamQuietSettings[] = {0x87, 0x09, 0x07, 0x07, 0x00, 0x08,
                                           0x07, 0x00, 0x30, 0x18, 0x00};

constexpr uint8_t kSteamDefaultMappings[] = {0x85, 0x00};
constexpr uint8_t kSteamDefaultSettings[] = {0x8E, 0x00};

// Loading the defaults does not by itself hand the right pad back as a mouse: SDL follows it with
// an explicit right trackpad mode = absolute mouse, and leaving that out is the one way this
// teardown could still return a pad its owner cannot use.
constexpr uint8_t kSteamRestoreMouse[] = {0x87, 0x03, 0x08, 0x00, 0x00};

constexpr InitPacket kSteamQuietSeq[] = {
    {kSteamClearMappings, sizeof(kSteamClearMappings)},
    {kSteamQuietSettings, sizeof(kSteamQuietSettings)},
};

constexpr InitPacket kSteamRestoreSeq[] = {
    {kSteamDefaultMappings, sizeof(kSteamDefaultMappings)},
    {kSteamDefaultSettings, sizeof(kSteamDefaultSettings)},
    {kSteamRestoreMouse, sizeof(kSteamRestoreMouse)},
};

InitSequence steamSequenceFor(const SteamConfig stage) {
    const bool quiet = stage == SteamConfig::QUIET;
    if (quiet) return {kSteamQuietSeq, countOf(kSteamQuietSeq)};
    return {kSteamRestoreSeq, countOf(kSteamRestoreSeq)};
}

} // namespace

size_t buildSteamConfigPacket(const SteamConfig stage, const int index, uint8_t* out,
                              const size_t outCap) {
    return copyInitPacket(steamSequenceFor(stage), index, out, outCap);
}

namespace {

// Per-device rumble output reports. Motor convention: strong = large/low-frequency (left), weak =
// small/high-frequency (right), both wire-scale 0..65535. Report layouts and sources (Linux xpad,
// hid-playstation, hid-nintendo) are documented in docs/rumble.md.

// Xbox 360 wired: {type, length 0x08, 0, strong, weak, 0, 0, 0}.
constexpr uint8_t kX360RumbleTemplate[] = {0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
constexpr size_t kX360RumbleStrongByte = 3;
constexpr size_t kX360RumbleWeakByte = 4;

// Xbox 360 wireless receiver: the motor levels wrapped in the xpad360w frame.
constexpr uint8_t kX360WirelessRumbleTemplate[] = {0x00, 0x01, 0x0F, 0xC0, 0x00, 0x00,
                                                   0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
constexpr size_t kX360WirelessRumbleStrongByte = 5;
constexpr size_t kX360WirelessRumbleWeakByte = 6;

// GIP rumble command: the sequence at [2], the motor mask 0x0F at [5] addressing all four motors,
// then the left trigger, right trigger, strong and weak levels, one byte each.
constexpr uint8_t kGipRumbleTemplate[] = {0x09, 0x00, 0x00, 0x09, 0x00, 0x0F, 0x00,
                                          0x00, 0x00, 0x00, 0xFF, 0x00, 0xFF};
constexpr size_t kGipRumbleSeqByte = 2;
constexpr size_t kGipRumbleLeftTriggerByte = 6;
constexpr size_t kGipRumbleRightTriggerByte = 7;
constexpr size_t kGipRumbleStrongByte = 8;
constexpr size_t kGipRumbleWeakByte = 9;
constexpr uint16_t kGipMotorDivisor = 512;

// DualShock 4 output report 0x05: the valid flags at [1], weak then strong at [4]/[5], RGB from
// [6]. A colour write claims only the lightbar, so it never stomps a live rumble.
constexpr uint8_t kDs4OutputReportId = 0x05;
constexpr size_t kDs4OutputReportLen = 32;
constexpr size_t kDs4ValidFlagsByte = 1;
constexpr uint8_t kDs4ValidFlagMotors = 0x01;
constexpr uint8_t kDs4ValidFlagLightbar = 0x02;
constexpr size_t kDs4WeakMotorByte = 4;
constexpr size_t kDs4StrongMotorByte = 5;
constexpr size_t kDs4LightbarByte = 6;

// DualSense output report 0x02 field offsets and flag bits, in this file's convention: the report
// id lives at out[0], so these are hid-playstation's RID-stripped offsets plus one.
constexpr uint8_t kDs5OutputReportId = 0x02;
constexpr size_t kDs5OutputReportLen = 63;
constexpr size_t kDs5ValidFlag0Byte = 1;
constexpr uint8_t kDs5ValidFlag0Motors = 0x01;
constexpr uint8_t kDs5ValidFlag0TriggerRight = 0x04;
constexpr uint8_t kDs5ValidFlag0TriggerLeft = 0x08;
constexpr size_t kDs5ValidFlag1Byte = 2;
constexpr uint8_t kDs5ValidFlag1MicMuteLed = 0x01;
constexpr uint8_t kDs5ValidFlag1PowerSave = 0x02;
constexpr uint8_t kDs5ValidFlag1Lightbar = 0x04;
constexpr uint8_t kDs5ValidFlag1PlayerLeds = 0x10;
constexpr size_t kDs5WeakMotorByte = 3;
constexpr size_t kDs5StrongMotorByte = 4;
constexpr size_t kDs5MicMuteLedByte = 9;
constexpr size_t kDs5PowerSaveByte = 10;
constexpr uint8_t kDs5PowerSaveMicMute = 0x10;
constexpr size_t kDs5TriggerRightOffset = 11;
constexpr size_t kDs5TriggerLeftOffset = 22;
constexpr size_t kDs5ValidFlag2Byte = 39;
constexpr uint8_t kDs5ValidFlag2LightbarSetup = 0x02;
constexpr size_t kDs5LightbarSetupByte = 42;
// LIGHTBAR_SETUP light-out stops the firmware's own blue glow so the host colour shows; a one-time
// handoff, as hid-playstation does at probe.
constexpr uint8_t kDs5LightbarSetupLightOut = 0x02;
constexpr size_t kDs5PlayerLedByte = 44;
constexpr uint8_t kDs5PlayerLedMask = 0x1F;
constexpr size_t kDs5LightbarByte = 45;

// Switch Pro: the rumble-only report 0x10 and the rumble + subcommand report 0x01 both carry the
// 4-bit packet counter at [1] and the two 4-byte HD-rumble blocks from [2] and [6].
constexpr uint8_t kSwitchRumbleReport = 0x10;
constexpr size_t kSwitchRumbleReportLen = 10;
constexpr uint8_t kSwitchSubcommandReport = 0x01;
constexpr size_t kSwitchSubcommandReportLen = 12;
constexpr size_t kSwitchCounterByte = 1;
constexpr uint8_t kSwitchPacketCounterMask = 0x0F;
constexpr size_t kSwitchLeftMotorOffset = 2;
constexpr size_t kSwitchRightMotorOffset = 6;
constexpr size_t kSwitchSubcommandByte = 10;
constexpr size_t kSwitchSubcommandArgByte = 11;
constexpr uint8_t kSwitchSubcmdPlayerLights = 0x30;
constexpr uint8_t kSwitchPlayerLedMask = 0x0F;

uint8_t gipMotorLevel(const uint16_t wireLevel) { return (uint8_t)(wireLevel / kGipMotorDivisor); }

// Copies a fixed report template into out; 0 when it does not fit.
template <size_t N>
size_t startFromTemplate(const uint8_t (&tmpl)[N], uint8_t* out, const size_t outCap) {
    if (outCap < N) return 0;
    memcpy(out, tmpl, N);
    return N;
}

// Zeroes a report of `len` bytes and stamps its id; 0 when it does not fit.
size_t startZeroedReport(const uint8_t reportId, const size_t len, uint8_t* out,
                         const size_t outCap) {
    if (outCap < len) return 0;
    memset(out, 0, len);
    out[0] = reportId;
    return len;
}

size_t buildX360Rumble(const uint16_t strong, const uint16_t weak, uint8_t* out,
                       const size_t outCap) {
    const size_t n = startFromTemplate(kX360RumbleTemplate, out, outCap);
    if (n == 0) return 0;
    out[kX360RumbleStrongByte] = highByte(strong);
    out[kX360RumbleWeakByte] = highByte(weak);
    return n;
}

size_t buildX360WirelessRumble(const uint16_t strong, const uint16_t weak, uint8_t* out,
                               const size_t outCap) {
    const size_t n = startFromTemplate(kX360WirelessRumbleTemplate, out, outCap);
    if (n == 0) return 0;
    out[kX360WirelessRumbleStrongByte] = highByte(strong);
    out[kX360WirelessRumbleWeakByte] = highByte(weak);
    return n;
}

// One report drives all four GIP motors, so every write carries all four levels.
size_t buildGipRumble(const uint8_t seq, const uint16_t leftTrigger, const uint16_t rightTrigger,
                      const uint16_t strong, const uint16_t weak, uint8_t* out,
                      const size_t outCap) {
    const size_t n = startFromTemplate(kGipRumbleTemplate, out, outCap);
    if (n == 0) return 0;
    out[kGipRumbleSeqByte] = seq;
    out[kGipRumbleLeftTriggerByte] = gipMotorLevel(leftTrigger);
    out[kGipRumbleRightTriggerByte] = gipMotorLevel(rightTrigger);
    out[kGipRumbleStrongByte] = gipMotorLevel(strong);
    out[kGipRumbleWeakByte] = gipMotorLevel(weak);
    return n;
}

size_t buildDs4Rumble(const uint16_t strong, const uint16_t weak, uint8_t* out,
                      const size_t outCap) {
    const size_t n = startZeroedReport(kDs4OutputReportId, kDs4OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs4ValidFlagsByte] = kDs4ValidFlagMotors;
    out[kDs4WeakMotorByte] = highByte(weak);
    out[kDs4StrongMotorByte] = highByte(strong);
    return n;
}

size_t buildDs5Rumble(const uint16_t strong, const uint16_t weak, uint8_t* out,
                      const size_t outCap) {
    const size_t n = startZeroedReport(kDs5OutputReportId, kDs5OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs5ValidFlag0Byte] = kDs5ValidFlag0Motors;
    out[kDs5WeakMotorByte] = highByte(weak);
    out[kDs5StrongMotorByte] = highByte(strong);
    return n;
}

size_t buildSwitchRumble(const uint16_t strong, const uint16_t weak, const uint8_t seq,
                         uint8_t* out, const size_t outCap) {
    const size_t n = startZeroedReport(kSwitchRumbleReport, kSwitchRumbleReportLen, out, outCap);
    if (n == 0) return 0;
    out[kSwitchCounterByte] = (uint8_t)(seq & kSwitchPacketCounterMask);
    switchEncodeMotor(&out[kSwitchLeftMotorOffset], strong);
    switchEncodeMotor(&out[kSwitchRightMotorOffset], weak);
    return n;
}

// Stamps the shadowed lamp onto a DualSense 0x02 report built for something else; the firmware
// applies whatever the valid flags claim, and every builder here starts from a zeroed report. The
// power-save bit rides along because the lamp and the microphone amplifier are one thing on this
// pad, and pulse counts as lit.
void reassertDs5MicMuteLed(const FeedbackState& st, uint8_t* out) {
    if (!st.ds5MicMuteLedSet) return;
    out[kDs5ValidFlag1Byte] |= kDs5ValidFlag1MicMuteLed | kDs5ValidFlag1PowerSave;
    out[kDs5MicMuteLedByte] = st.ds5MicMuteLed;
    const bool lampIsLit = st.ds5MicMuteLed != MIC_MUTE_LED_OFF;
    out[kDs5PowerSaveByte] = lampIsLit ? kDs5PowerSaveMicMute : 0x00;
}

size_t buildDs4Lightbar(const uint8_t r, const uint8_t g, const uint8_t b, uint8_t* out,
                        const size_t outCap) {
    const size_t n = startZeroedReport(kDs4OutputReportId, kDs4OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs4ValidFlagsByte] = kDs4ValidFlagLightbar;
    out[kDs4LightbarByte] = r;
    out[kDs4LightbarByte + 1] = g;
    out[kDs4LightbarByte + 2] = b;
    return n;
}

size_t buildDs5Lightbar(FeedbackState& st, const uint8_t r, const uint8_t g, const uint8_t b,
                        uint8_t* out, const size_t outCap) {
    const size_t n = startZeroedReport(kDs5OutputReportId, kDs5OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs5ValidFlag1Byte] = kDs5ValidFlag1Lightbar;
    if (!st.ds5LightbarSetupSent) {
        out[kDs5ValidFlag2Byte] = kDs5ValidFlag2LightbarSetup;
        out[kDs5LightbarSetupByte] = kDs5LightbarSetupLightOut;
        st.ds5LightbarSetupSent = true;
    }
    out[kDs5LightbarByte] = r;
    out[kDs5LightbarByte + 1] = g;
    out[kDs5LightbarByte + 2] = b;
    reassertDs5MicMuteLed(st, out);
    return n;
}

size_t buildDs5PlayerLeds(const FeedbackState& st, const uint8_t ledMask, uint8_t* out,
                          const size_t outCap) {
    const size_t n = startZeroedReport(kDs5OutputReportId, kDs5OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs5ValidFlag1Byte] = kDs5ValidFlag1PlayerLeds;
    out[kDs5PlayerLedByte] = (uint8_t)(ledMask & kDs5PlayerLedMask);
    reassertDs5MicMuteLed(st, out);
    return n;
}

// The player-lights subcommand rides the rumble+subcommand report; neutral HD-rumble blocks keep
// the motors untouched.
size_t buildSwitchPlayerLeds(const uint8_t ledMask, const uint8_t seq, uint8_t* out,
                             const size_t outCap) {
    if (outCap < kSwitchSubcommandReportLen) return 0;
    out[0] = kSwitchSubcommandReport;
    out[kSwitchCounterByte] = (uint8_t)(seq & kSwitchPacketCounterMask);
    switchEncodeMotor(&out[kSwitchLeftMotorOffset], 0);
    switchEncodeMotor(&out[kSwitchRightMotorOffset], 0);
    out[kSwitchSubcommandByte] = kSwitchSubcmdPlayerLights;
    out[kSwitchSubcommandArgByte] = (uint8_t)(ledMask & kSwitchPlayerLedMask);
    return kSwitchSubcommandReportLen;
}

} // namespace

size_t buildRumbleReport(const Parser p, const uint16_t strong, const uint16_t weak,
                         const uint8_t seq, uint8_t* out, const size_t outCap) {
    switch (p) {
    case Parser::XINPUT_360:
        return buildX360Rumble(strong, weak, out, outCap);
    case Parser::XINPUT_360_WIRELESS:
        return buildX360WirelessRumble(strong, weak, out, outCap);
    case Parser::XBOX_ONE_GIP:
        return buildGipRumble(seq, 0, 0, strong, weak, out, outCap);
    case Parser::DUALSHOCK4:
        return buildDs4Rumble(strong, weak, out, outCap);
    case Parser::DUALSENSE:
        return buildDs5Rumble(strong, weak, out, outCap);
    case Parser::SWITCH_PRO_USB:
        return buildSwitchRumble(strong, weak, seq, out, outCap);
    case Parser::STEAM_CONTROLLER:
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return 0;
    }
    return 0;
}

bool parserHasLightbar(const Parser p) { return p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE; }

bool parserHasPlayerLeds(const Parser p) {
    return p == Parser::DUALSENSE || p == Parser::SWITCH_PRO_USB;
}

bool parserHasTriggerEffects(const Parser p) { return p == Parser::DUALSENSE; }

bool parserHasHapticLanes(const Parser p) { return p == Parser::DUALSENSE; }

bool parserHasTriggerRumble(const Parser p) { return p == Parser::XBOX_ONE_GIP; }

bool parserHasMicMuteLed(const Parser p) { return p == Parser::DUALSENSE; }

size_t buildMergedRumbleReport(const Parser p, FeedbackState& st, const uint8_t seq, uint8_t* out,
                               const size_t outCap) {
    switch (p) {
    case Parser::XBOX_ONE_GIP:
        return buildGipRumble(seq, st.leftTrigger, st.rightTrigger, st.strong, st.weak, out,
                              outCap);
    case Parser::DUALSENSE: {
        const size_t n = buildDs5Rumble(st.strong, st.weak, out, outCap);
        if (n != 0) reassertDs5MicMuteLed(st, out);
        return n;
    }
    case Parser::XINPUT_360:
    case Parser::XINPUT_360_WIRELESS:
    case Parser::DUALSHOCK4:
    case Parser::SWITCH_PRO_USB:
    case Parser::STEAM_CONTROLLER:
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return buildRumbleReport(p, st.strong, st.weak, seq, out, outCap);
    }
    return 0;
}

size_t buildLightbarReport(const Parser p, FeedbackState& st, const uint8_t r, const uint8_t g,
                           const uint8_t b, uint8_t* out, const size_t outCap) {
    switch (p) {
    case Parser::DUALSHOCK4:
        return buildDs4Lightbar(r, g, b, out, outCap);
    case Parser::DUALSENSE:
        return buildDs5Lightbar(st, r, g, b, out, outCap);
    default:
        return 0;
    }
}

size_t buildPlayerLedsReport(const Parser p, const FeedbackState& st, const uint8_t ledMask,
                             const uint8_t seq, uint8_t* out, const size_t outCap) {
    switch (p) {
    case Parser::DUALSENSE:
        return buildDs5PlayerLeds(st, ledMask, out, outCap);
    case Parser::SWITCH_PRO_USB:
        return buildSwitchPlayerLeds(ledMask, seq, out, outCap);
    default:
        return 0;
    }
}

size_t buildTriggerEffectsReport(const Parser p, const FeedbackState& st,
                                 const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                                 const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN], uint8_t* out,
                                 const size_t outCap) {
    if (p != Parser::DUALSENSE) return 0;
    const size_t n = startZeroedReport(kDs5OutputReportId, kDs5OutputReportLen, out, outCap);
    if (n == 0) return 0;
    out[kDs5ValidFlag0Byte] = kDs5ValidFlag0TriggerRight | kDs5ValidFlag0TriggerLeft;
    memcpy(out + kDs5TriggerRightOffset, right, TRIGGER_EFFECT_BLOCK_LEN);
    memcpy(out + kDs5TriggerLeftOffset, left, TRIGGER_EFFECT_BLOCK_LEN);
    reassertDs5MicMuteLed(st, out);
    return n;
}

size_t buildMicMuteLedReport(const Parser p, FeedbackState& st, const uint8_t state, uint8_t* out,
                             const size_t outCap) {
    if (p != Parser::DUALSENSE) return 0;
    if (state > MIC_MUTE_LED_PULSE) return 0;
    if (outCap < kDs5OutputReportLen) return 0;
    st.ds5MicMuteLed = state;
    st.ds5MicMuteLedSet = true;
    startZeroedReport(kDs5OutputReportId, kDs5OutputReportLen, out, outCap);
    reassertDs5MicMuteLed(st, out);
    return kDs5OutputReportLen;
}

#ifdef __ANDROID__
namespace {

constexpr size_t kInitPacketMaxBytes = 16;
constexpr size_t kOutReportMaxBytes = 64;
constexpr unsigned kGipWriteTimeoutMs = 200;
constexpr unsigned kOutWriteTimeoutMs = 100;
constexpr unsigned kGipInterPacketUs = 10000;
constexpr unsigned kSwitchStatusTimeoutMs = 100;
constexpr unsigned kSwitchModeTimeoutMs = 200;

bool runSteamQuietInit(const int fd, const int interfaceNumber) {
    uint8_t buf[kInitPacketMaxBytes];
    for (int i = 0;; i++) {
        const size_t n = buildSteamConfigPacket(SteamConfig::QUIET, i, buf, sizeof(buf));
        if (n == 0) break;
        if (!sendFeatureReport(fd, interfaceNumber, buf, n)) {
            logError("Steam Controller: quiet-mode packet %d failed", i);
            return false;
        }
    }
    logInfo("Steam Controller quiet-mode sequence sent");
    return true;
}

// GIP init: power-on tells the pad to start sending input reports; the rest of the sequence (LED,
// auth-done, and the S set-mode) starts the models the lone power-on left silent.
bool runGipInit(const int fd, const uint8_t epOut, const InitKind init) {
    uint8_t buf[kInitPacketMaxBytes];
    for (int i = 0;; i++) {
        const size_t n = buildGipInitPacket(init, i, (uint8_t)i, buf, sizeof(buf));
        if (n == 0) break;
        const bool ok = bulkWrite(fd, epOut, buf, n, kGipWriteTimeoutMs);
        const bool powerOnFailed = i == 0 && !ok;
        if (powerOnFailed) {
            logError("Xbox One power-on write failed");
            return false;
        }
        usleep(kGipInterPacketUs);
    }
    return true;
}

// Status request. The Pro answers with controller info on its IN endpoint; the reply is never
// read. Sending the request is what moves the device out of whatever residual state the kernel
// driver left it in when the interface was stolen.
constexpr uint8_t kSwitchStatus[] = {0x80, 0x02};

// Without this the controller sleeps after a few seconds of idle and stops emitting reports.
constexpr uint8_t kSwitchDisableTimeout[] = {0x80, 0x04};

// Input report mode 0x30 (standard full report: buttons + sticks + IMU), carried as one rumble +
// subcommand HID output report: report id 0x01, packet counter, 8-byte neutral rumble pattern,
// subcommand id 0x03, argument 0x30.
constexpr uint8_t kSwitchSetReportMode[] = {
    0x01, 0x00, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40, 0x03, 0x30,
};

// Subcommand 0x48 arg 0x01, so later rumble-only (0x10) reports take effect.
constexpr uint8_t kSwitchEnableVibration[] = {
    0x01, 0x01, 0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40, 0x48, 0x01,
};

constexpr unsigned kSwitchInitSettleUs = 40000;

struct SwitchInitStep {
    const uint8_t* packet;
    size_t length;
    unsigned timeoutMs;
    unsigned settleUs;
    bool isFatal;
    const char* what;
};

// A fatal step failing leaves the pad unusable, so the sequence stops; the other two only cost a
// feature and the pad still streams without them.
constexpr SwitchInitStep kSwitchInitSequence[] = {
    {kSwitchStatus, sizeof(kSwitchStatus), kSwitchStatusTimeoutMs, kSwitchInitSettleUs, true,
     "status request"},
    {kSwitchDisableTimeout, sizeof(kSwitchDisableTimeout), kSwitchStatusTimeoutMs,
     kSwitchInitSettleUs, false, "disable-timeout write"},
    {kSwitchSetReportMode, sizeof(kSwitchSetReportMode), kSwitchModeTimeoutMs, kSwitchInitSettleUs,
     true, "set-report-mode write"},
    {kSwitchEnableVibration, sizeof(kSwitchEnableVibration), kSwitchModeTimeoutMs, 0, false,
     "enable-vibration write"},
};

bool runSwitchInitStep(const int fd, const uint8_t epOut, const SwitchInitStep& step) {
    const bool sent = bulkWrite(fd, epOut, step.packet, step.length, step.timeoutMs);
    if (!sent && step.isFatal) {
        logError("Switch Pro: %s failed", step.what);
        return false;
    }
    if (!sent) logInfo("Switch Pro: %s failed (non-fatal)", step.what);
    if (step.settleUs != 0) usleep(step.settleUs);
    return true;
}

bool runSwitchProHandshake(const int fd, const uint8_t epOut) {
    if (epOut == 0) {
        logError("Switch Pro: no OUT endpoint, cannot init");
        return false;
    }
    for (const SwitchInitStep& step : kSwitchInitSequence) {
        if (!runSwitchInitStep(fd, epOut, step)) return false;
    }
    logInfo("Switch Pro USB init sequence sent");
    return true;
}

// One OUT report, as built by the feedback builders: nothing to write is a refusal, not a no-op.
bool writeOutReport(const int fd, const uint8_t epOut, const uint8_t* buf, const size_t n) {
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, kOutWriteTimeoutMs);
}

} // namespace

bool runInit(const int fd, const int interfaceNumber, const uint8_t epOut, const InitKind init) {
    switch (init) {
    case InitKind::NONE:
        return true;
    case InitKind::STEAM_QUIET:
        return runSteamQuietInit(fd, interfaceNumber);
    case InitKind::XBOX_ONE_POWERON:
    case InitKind::XBOX_ONE_S:
        return runGipInit(fd, epOut, init);
    case InitKind::SWITCH_PRO_HANDSHAKE:
        return runSwitchProHandshake(fd, epOut);
    }
    return false;
}

void runTeardown(const int fd, const int interfaceNumber, const Parser p) {
    if (p != Parser::STEAM_CONTROLLER) return;
    uint8_t buf[kInitPacketMaxBytes];
    for (int i = 0;; i++) {
        const size_t n = buildSteamConfigPacket(SteamConfig::RESTORE, i, buf, sizeof(buf));
        if (n == 0) break;
        sendFeatureReport(fd, interfaceNumber, buf, n);
    }
    logInfo("Steam Controller restored to stand-alone mode");
}

bool runRumble(const int fd, const uint8_t epOut, const Parser p, const uint16_t strong,
               const uint16_t weak, const uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildRumbleReport(p, strong, weak, seq, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}

bool runMergedRumble(const int fd, const uint8_t epOut, const Parser p, FeedbackState& st,
                     const uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildMergedRumbleReport(p, st, seq, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}

bool runLightbar(const int fd, const uint8_t epOut, const Parser p, FeedbackState& st,
                 const uint8_t r, const uint8_t g, const uint8_t b) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildLightbarReport(p, st, r, g, b, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}

bool runPlayerLeds(const int fd, const uint8_t epOut, const Parser p, const FeedbackState& st,
                   const uint8_t ledMask, const uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildPlayerLedsReport(p, st, ledMask, seq, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}

bool runTriggerEffects(const int fd, const uint8_t epOut, const Parser p, const FeedbackState& st,
                       const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                       const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN]) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildTriggerEffectsReport(p, st, left, right, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}

bool runMicMuteLed(const int fd, const uint8_t epOut, const Parser p, FeedbackState& st,
                   const uint8_t state) {
    if (epOut == 0) return false;
    uint8_t buf[kOutReportMaxBytes];
    const size_t n = buildMicMuteLedReport(p, st, state, buf, sizeof(buf));
    return writeOutReport(fd, epOut, buf, n);
}
#endif

} // namespace usbparsers

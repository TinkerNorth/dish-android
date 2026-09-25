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

bool modelExpectsFrameworkGamepad(uint16_t vid, uint16_t pid) {
    const KnownDevice* k = lookupKnown(vid, pid);
    return k == nullptr || k->parser != Parser::STEAM_CONTROLLER;
}

bool probePermitsClaim(ProbeOutcome outcome, bool verifiedFastLane) {
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

constexpr uint8_t kIfClassVendor = 0xFF;
constexpr uint8_t kXInputSubclass = 0x5D;
constexpr uint8_t kXInputProtocol = 0x01;
constexpr uint8_t kGipSubclass = 0x47;
constexpr uint8_t kGipProtocol = 0xD0;

Classification classifyDevice(uint16_t vid, uint16_t pid, uint8_t ifClass, uint8_t ifSubclass,
                              uint8_t ifProtocol) {
    const KnownDevice* known = lookupKnown(vid, pid);
    if (known != nullptr) { return {known->parser, known->init, known->name, known->order}; }
    // Wired XInput streams unsolicited; GIP needs the power-on packet first.
    if (ifClass == kIfClassVendor && ifSubclass == kXInputSubclass &&
        ifProtocol == kXInputProtocol) {
        return {Parser::XINPUT_360, InitKind::NONE, nullptr};
    }
    if (ifClass == kIfClassVendor && ifSubclass == kGipSubclass && ifProtocol == kGipProtocol) {
        return {Parser::XBOX_ONE_GIP, InitKind::XBOX_ONE_POWERON, nullptr};
    }
    return {Parser::GENERIC_HID_GAMEPAD, InitKind::NONE, nullptr};
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
    case Parser::STEAM_CONTROLLER:
        return "Steam Controller protocol";
    case Parser::NONE:
        return "Unknown";
    }
    return "Unknown";
}

bool parserHasImu(Parser p) {
    return p == Parser::SWITCH_PRO_USB || p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE ||
           p == Parser::STEAM_CONTROLLER;
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

// Symptom-named (not just `== SWITCH_PRO_USB`) so the list can grow if another proprietary family
// exposes the same phantom framework vibrator.
bool parserFrameworkRumbleUnreliable(Parser p) { return p == Parser::SWITCH_PRO_USB; }

// Parser-level like parserHasImu: every device speaking the DS4/DualSense report format carries
// the touch bytes, even the licensed sticks whose "pad" is only a click button. Those simply
// never report a contact, so nothing streams.
bool parserHasTouchpad(Parser p) { return p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE; }

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

constexpr size_t kFeatureReportBytes = 64;
constexpr int kFeatureReportAttempts = 25;
constexpr unsigned kFeatureReportRetryUs = 20000;

// SET_REPORT(Feature, report id 0), always a full 64-byte buffer. EPIPE here is the wireless
// dongle under load, not a real failure; SDL and hid-steam both retry it rather than give up.
// hid-steam allows 50 tries; the cap is lower here so that even a wholly unresponsive device
// finishes init and teardown well inside the Kotlin side's 4s path-transition timeout.
bool sendFeatureReport(int fd, int interfaceNumber, const uint8_t* data, size_t len) {
    if (interfaceNumber < 0 || len > kFeatureReportBytes) return false;
    uint8_t buf[kFeatureReportBytes] = {};
    memcpy(buf, data, len);
    for (int attempt = 0; attempt < kFeatureReportAttempts; attempt++) {
        struct usbdevfs_ctrltransfer ct = {};
        ct.bRequestType = 0x21;            // OUT | Class | Interface
        ct.bRequest = 0x09;                // SET_REPORT
        ct.wValue = (uint16_t)(0x03 << 8); // Feature report, id 0
        ct.wIndex = (uint16_t)interfaceNumber;
        ct.wLength = (uint16_t)sizeof(buf);
        ct.timeout = 250;
        ct.data = buf;
        if (ioctl(fd, USBDEVFS_CONTROL, &ct) >= 0) return true;
        if (errno != EPIPE) break;
        usleep(kFeatureReportRetryUs);
    }
    LOGE("SET_REPORT 0x%02X to iface %d failed: %s", data[0], interfaceNumber, strerror(errno));
    return false;
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

// Inclusive logical maxima of the touch surfaces (hid-sony / hid-playstation): the DS4 pad is
// 1920x942, the DualSense's is 1920x1080.
constexpr uint16_t kDs4TouchMaxX = 1919;
constexpr uint16_t kDs4TouchMaxY = 941;
constexpr uint16_t kDualSenseTouchMaxX = 1919;
constexpr uint16_t kDualSenseTouchMaxY = 1079;

// Touchpad surfaces differ per model but the wire is resolution-agnostic: full-range int16,
// same normalization the on-screen overlay applies to its view space.
int16_t touchAxisToWire(uint16_t raw, uint16_t maxRaw) {
    if (raw > maxRaw) raw = maxRaw;
    int32_t scaled = ((int32_t)raw * 65535) / maxRaw - 32768;
    if (scaled > 32767) scaled = 32767;
    if (scaled < -32768) scaled = -32768;
    return (int16_t)scaled;
}

// One DS4/DualSense touch point: [0] bit7 = inactive, bits 0-6 = contact id; 12-bit x/y packed
// into [1..3]. Coordinates zero on lift to match the overlay's lift frames.
void decodePsTouchPoint(const uint8_t* p, uint16_t maxX, uint16_t maxY, bool& active, uint8_t& id,
                        int16_t& x, int16_t& y) {
    active = (p[0] & 0x80) == 0;
    id = (uint8_t)(p[0] & 0x7F);
    if (!active) {
        x = 0;
        y = 0;
        return;
    }
    uint16_t rawX = (uint16_t)p[1] | (((uint16_t)p[2] & 0x0F) << 8);
    uint16_t rawY = ((uint16_t)p[2] >> 4) | ((uint16_t)p[3] << 4);
    x = touchAxisToWire(rawX, maxX);
    y = touchAxisToWire(rawY, maxY);
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
    const uint16_t withoutDpad = (uint16_t)(buttons & ~XUSB_DPAD_MASK);
    const int direction = (int)(hat & 0x0F);
    return (uint16_t)(withoutDpad | hatDirectionBits(direction));
}

// Both Xbox reports lay the four stick axes out as little-endian int16 in the order LX LY RX RY;
// only the base offset moves.
void readSticksLe16(const uint8_t* buf, const int base, DeviceState& s) {
    s.sLX = rdLe16(buf, base);
    s.sLY = rdLe16(buf, base + 2);
    s.sRX = rdLe16(buf, base + 4);
    s.sRY = rdLe16(buf, base + 6);
}

uint16_t decodeXInput360Buttons(const uint8_t dpadByte, const uint8_t faceByte) {
    uint16_t buttons = 0;
    if (dpadByte & 0x01) buttons |= XUSB_DPAD_UP;
    if (dpadByte & 0x02) buttons |= XUSB_DPAD_DOWN;
    if (dpadByte & 0x04) buttons |= XUSB_DPAD_LEFT;
    if (dpadByte & 0x08) buttons |= XUSB_DPAD_RIGHT;
    if (dpadByte & 0x10) buttons |= XUSB_START;
    if (dpadByte & 0x20) buttons |= XUSB_BACK;
    if (dpadByte & 0x40) buttons |= XUSB_THUMB_L;
    if (dpadByte & 0x80) buttons |= XUSB_THUMB_R;
    if (faceByte & 0x01) buttons |= XUSB_LB;
    if (faceByte & 0x02) buttons |= XUSB_RB;
    if (faceByte & 0x04) buttons |= XUSB_GUIDE;
    if (faceByte & 0x10) buttons |= XUSB_A;
    if (faceByte & 0x20) buttons |= XUSB_B;
    if (faceByte & 0x40) buttons |= XUSB_X;
    if (faceByte & 0x80) buttons |= XUSB_Y;
    return buttons;
}

// Xbox 360 wired interrupt-IN report. Fixed 20 bytes; byte 0 is report type (0x00 for input),
// byte 1 is the length. Triggers are 8-bit unsigned.
bool decodeXInput360(const uint8_t* buf, size_t len, DeviceState& s) {
    if (len < 14) return false;
    if (buf[0] != 0x00) return false;

    s.wButtons = decodeXInput360Buttons(buf[2], buf[3]);
    s.bLT = buf[4];
    s.bRT = buf[5];
    readSticksLe16(buf, 6, s);
    return true;
}

uint16_t decodeGipButtons(const uint8_t faceByte, const uint8_t dpadByte) {
    uint16_t buttons = 0;
    if (faceByte & 0x04) buttons |= XUSB_START;
    if (faceByte & 0x08) buttons |= XUSB_BACK;
    if (faceByte & 0x10) buttons |= XUSB_A;
    if (faceByte & 0x20) buttons |= XUSB_B;
    if (faceByte & 0x40) buttons |= XUSB_X;
    if (faceByte & 0x80) buttons |= XUSB_Y;
    if (dpadByte & 0x01) buttons |= XUSB_DPAD_UP;
    if (dpadByte & 0x02) buttons |= XUSB_DPAD_DOWN;
    if (dpadByte & 0x04) buttons |= XUSB_DPAD_LEFT;
    if (dpadByte & 0x08) buttons |= XUSB_DPAD_RIGHT;
    if (dpadByte & 0x10) buttons |= XUSB_LB;
    if (dpadByte & 0x20) buttons |= XUSB_RB;
    if (dpadByte & 0x40) buttons |= XUSB_THUMB_L;
    if (dpadByte & 0x80) buttons |= XUSB_THUMB_R;
    return buttons;
}

// GIP triggers are 10-bit little-endian (0..1023); XUSB carries them as 0..255.
uint8_t gipTriggerToXusb(const uint8_t lo, const uint8_t hi) {
    const uint16_t raw = (uint16_t)((uint16_t)lo | ((uint16_t)hi << 8));
    const uint16_t clamped = raw > 1023 ? (uint16_t)1023 : raw;
    return (uint8_t)((clamped * 255) / 1023);
}

// The Guide button arrives in its own virtual-key report (0x07, state in byte 4) rather than in a
// main frame. It is sticky, so it is held in ParserState and replayed over the last main report,
// which is all this frame can publish.
bool applyXboxVirtualKey(const uint8_t* buf, DeviceState& s, ParserState& st) {
    st.xboxGuideHeld = (buf[4] & 0x03) != 0;
    s = st.xboxLastMain;
    if (st.xboxGuideHeld) {
        s.wButtons |= XUSB_GUIDE;
    } else {
        s.wButtons = (uint16_t)(s.wButtons & ~XUSB_GUIDE);
    }
    return true;
}

// Xbox One GIP input report 0x20. Sticks are little-endian int16, same convention as XInput.
bool decodeXboxOneGip(const uint8_t* buf, size_t len, DeviceState& s, ParserState& st) {
    const bool isVirtualKey = len >= 5 && buf[0] == 0x07;
    if (isVirtualKey) return applyXboxVirtualKey(buf, s, st);
    if (len < 18) return false;
    if (buf[0] != 0x20) return false;

    s.wButtons = decodeGipButtons(buf[4], buf[5]);
    s.bLT = gipTriggerToXusb(buf[6], buf[7]);
    s.bRT = gipTriggerToXusb(buf[8], buf[9]);
    readSticksLe16(buf, 10, s);

    // What is stored is the pad's own frame; the sticky Guide bit is merged only into what this
    // call publishes, so a later virtual-key release has nothing to undo.
    st.xboxLastMain = s;
    if (st.xboxGuideHeld) s.wButtons |= XUSB_GUIDE;
    return true;
}

// DualShock 4 USB report 0x01. Sticks are uint8 with 128 = center. Y axes are down-positive so
// they're inverted to match XUSB's up-positive convention. Face buttons are remapped to the
// XInput "muscle memory" positions: Cross is A, Circle is B, Square is X, Triangle is Y.
// The Sony pads report charge in tenths (0 = 0..9 %, 1 = 10..19 %, ...), and the platforms show
// the midpoint of each band, capped at 100.
static uint8_t sonyTenthsToPercent(uint8_t tenths) {
    const unsigned pct = (unsigned)tenths * 10u + 5u;
    return (uint8_t)(pct > 100u ? 100u : pct);
}

// The DS4 and the DualSense carry the same button bits; only the byte offsets move.
uint16_t decodePsButtons(const uint8_t faceByte, const uint8_t shoulderByte) {
    uint16_t buttons = 0;
    if (faceByte & 0x10) buttons |= XUSB_X;
    if (faceByte & 0x20) buttons |= XUSB_A;
    if (faceByte & 0x40) buttons |= XUSB_B;
    if (faceByte & 0x80) buttons |= XUSB_Y;
    if (shoulderByte & 0x01) buttons |= XUSB_LB;
    if (shoulderByte & 0x02) buttons |= XUSB_RB;
    if (shoulderByte & 0x10) buttons |= XUSB_BACK;
    if (shoulderByte & 0x20) buttons |= XUSB_START;
    if (shoulderByte & 0x40) buttons |= XUSB_THUMB_L;
    if (shoulderByte & 0x80) buttons |= XUSB_THUMB_R;
    return setDpadFromHat(buttons, (uint8_t)(faceByte & 0x0F));
}

void decodePsSticks(const uint8_t* buf, DeviceState& s) {
    s.sLX = scaleU8Centered(buf[1], false);
    s.sLY = scaleU8Centered(buf[2], true);
    s.sRX = scaleU8Centered(buf[3], false);
    s.sRY = scaleU8Centered(buf[4], true);
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
    const uint8_t tenths = (uint8_t)(status & 0x0F);
    const bool cableIn = (status & 0x10) != 0;
    s.batteryValid = true;
    if (!cableIn) return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_DISCHARGING);

    const bool stillCharging = tenths <= 10;
    if (stillCharging)
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_CHARGING);

    const bool full = tenths == 11;
    if (full) return setBattery(s, 100, PAD_BATTERY_STATUS_FULL);
    setBatteryUnknown(s);
}

// hid-playstation's status: low nibble the charge in tenths, high nibble the state. 0xA/0xB are a
// temperature or voltage fault and 0xF a charging fault; a fault has no charge worth showing.
void decodeDualSenseBattery(const uint8_t status, DeviceState& s) {
    const uint8_t tenths = (uint8_t)(status & 0x0F);
    const uint8_t state = (uint8_t)(status >> 4);
    s.batteryValid = true;
    switch (state) {
    case 0x0:
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_DISCHARGING);
    case 0x1:
        return setBattery(s, sonyTenthsToPercent(tenths), PAD_BATTERY_STATUS_CHARGING);
    case 0x2:
        return setBattery(s, 100, PAD_BATTERY_STATUS_FULL);
    default:
        return setBatteryUnknown(s);
    }
}

// The press is an edge, the wire bit is a state: flip the latch on the way down only, so holding
// the button does not chatter the mute on and off at report rate.
void applyMicMuteLatch(const uint8_t buttonByte, ParserState& sticks, uint16_t& buttons) {
    const bool muteDown = (buttonByte & 0x04) != 0;
    const bool isAFreshPress = muteDown && !sticks.micMuteHeld;
    if (isAFreshPress) sticks.micMuted = !sticks.micMuted;
    sticks.micMuteHeld = muteDown;
    if (sticks.micMuted) buttons |= WBUTTON_MIC_MUTE;
}

// [33] is the bundled 9-byte frame count (timestamp plus two points); only the newest frame
// matters, since the wire stream supersedes per send. Zero frames means "no touch update", not
// "all lifted", so touchValid stays false and the last sent state persists.
void decodeDs4Touch(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < 43 || buf[33] == 0) return;
    const uint8_t frames = buf[33] > 3 ? 3 : buf[33];
    const size_t base = 34 + 9u * (size_t)(frames - 1);
    if (len < base + 9) return;
    decodePsTouchPoint(buf + base + 1, kDs4TouchMaxX, kDs4TouchMaxY, s.touch0Active, s.touch0Id,
                       s.touch0X, s.touch0Y);
    decodePsTouchPoint(buf + base + 5, kDs4TouchMaxX, kDs4TouchMaxY, s.touch1Active, s.touch1Id,
                       s.touch1X, s.touch1Y);
    s.touchClick = (buf[7] & 0x02) != 0;
    s.touchValid = true;
}

bool decodeDualShock4(const uint8_t* buf, size_t len, DeviceState& s, const PsImuCalib* calib) {
    if (len < 10) return false;
    if (buf[0] != 0x01) return false;

    decodePsSticks(buf, s);
    s.wButtons = decodePsButtons(buf[5], buf[6]);
    s.bLT = buf[8];
    s.bRT = buf[9];

    // gyro pitch/yaw/roll at 13/15/17, accel x/y/z at 19/21/23 (int16 LE). Axis signs are an
    // unflipped straight map, still unverified on hardware like the Switch IMU.
    const bool hasMotion = calib != nullptr && calib->valid && len >= 25;
    if (hasMotion) decodePsMotion(buf, 13, 19, *calib, s);

    decodeDs4Touch(buf, len, s);
    if (len >= 31) decodeDs4Battery(buf[30], s);
    return true;
}

// Two 4-byte points at 33/37 in every report (no DS4-style frame bundling); the click rides
// button byte 10 bit 1. A taller surface than the DS4, hence its own Y maximum.
void decodeDualSenseTouch(const uint8_t* buf, const size_t len, DeviceState& s) {
    if (len < 41) return;
    decodePsTouchPoint(buf + 33, kDualSenseTouchMaxX, kDualSenseTouchMaxY, s.touch0Active,
                       s.touch0Id, s.touch0X, s.touch0Y);
    decodePsTouchPoint(buf + 37, kDualSenseTouchMaxX, kDualSenseTouchMaxY, s.touch1Active,
                       s.touch1Id, s.touch1X, s.touch1Y);
    s.touchClick = (buf[10] & 0x02) != 0;
    s.touchValid = true;
}

// DualSense USB report 0x01. Same axis conventions as DS4 but the byte layout shifts: triggers
// move to bytes 5/6 and the button bytes are at 8/9/10.
//
// Takes the whole ParserState rather than just the IMU calibration because the mic-mute button
// needs a latch across reports; a null one decodes everything except the mute state, which is the
// honest answer for a caller that kept no per-device memory.
bool decodeDualSense(const uint8_t* buf, size_t len, DeviceState& s, ParserState* sticks) {
    if (len < 11) return false;
    if (buf[0] != 0x01) return false;
    const PsImuCalib* calib = sticks != nullptr ? &sticks->psImu : nullptr;

    decodePsSticks(buf, s);
    s.bLT = buf[5];
    s.bRT = buf[6];

    uint16_t buttons = decodePsButtons(buf[8], buf[9]);
    // Mic-mute lives beside the touchpad click in button byte 10 (0x04 next to 0x02).
    if (sticks != nullptr) applyMicMuteLatch(buf[10], *sticks, buttons);
    s.wButtons = buttons;

    // gyro at 16/18/20, accel at 22/24/26 (int16 LE); same calibration as DS4, signs unverified.
    const bool hasMotion = calib != nullptr && calib->valid && len >= 28;
    if (hasMotion) decodePsMotion(buf, 16, 22, *calib, s);

    decodeDualSenseTouch(buf, len, s);
    if (len >= 54) decodeDualSenseBattery(buf[53], s);
    return true;
}

// Switch Pro standard full input report 0x30 over USB. Buttons are split across three bytes
// (right/shared/left) and sticks are packed 12-bit values. The XUSB mapping matches by physical
// position rather than label, the same convention used for DualShock 4: Switch A (right face) →
// XUSB_B (right face), Switch B (bottom) → XUSB_A (bottom), Switch X (top) → XUSB_Y, Switch Y
// (left) → XUSB_X. This is what PC games and ViGEm expect.
uint16_t decodeSwitchProButtons(const uint8_t right, const uint8_t shared, const uint8_t left) {
    uint16_t buttons = 0;
    if (right & 0x01) buttons |= XUSB_X;
    if (right & 0x02) buttons |= XUSB_Y;
    if (right & 0x04) buttons |= XUSB_A;
    if (right & 0x08) buttons |= XUSB_B;
    if (right & 0x40) buttons |= XUSB_RB;
    if (shared & 0x01) buttons |= XUSB_BACK;
    if (shared & 0x02) buttons |= XUSB_START;
    if (shared & 0x04) buttons |= XUSB_THUMB_R;
    if (shared & 0x08) buttons |= XUSB_THUMB_L;
    if (left & 0x01) buttons |= XUSB_DPAD_DOWN;
    if (left & 0x02) buttons |= XUSB_DPAD_UP;
    if (left & 0x04) buttons |= XUSB_DPAD_RIGHT;
    if (left & 0x08) buttons |= XUSB_DPAD_LEFT;
    if (left & 0x40) buttons |= XUSB_LB;
    return buttons;
}

// Sticks are packed 12-bit values, two per three bytes.
void decodeSwitchProSticks(const uint8_t* buf, ParserState& sticks, DeviceState& s) {
    const uint16_t lx = (uint16_t)buf[6] | (((uint16_t)buf[7] & 0x0F) << 8);
    const uint16_t ly = ((uint16_t)buf[7] >> 4) | ((uint16_t)buf[8] << 4);
    const uint16_t rx = (uint16_t)buf[9] | (((uint16_t)buf[10] & 0x0F) << 8);
    const uint16_t ry = ((uint16_t)buf[10] >> 4) | ((uint16_t)buf[11] << 4);
    s.sLX = scaleSwitchStickAuto(lx, sticks.lx);
    s.sLY = scaleSwitchStickAuto(ly, sticks.ly);
    s.sRX = scaleSwitchStickAuto(rx, sticks.rx);
    s.sRY = scaleSwitchStickAuto(ry, sticks.ry);
}

// hid-nintendo's bat_con: bits 7..5 the charge in five steps (empty, critical, low, medium,
// full), bit 4 charging, bit 0 host-powered. The percent is the step's midpoint, the same coarse
// number the framework shows for this pad.
void decodeSwitchProBattery(const uint8_t batCon, DeviceState& s) {
    static constexpr uint8_t kStepPercent[] = {5, 25, 50, 75, 100};
    const uint8_t step = (uint8_t)(batCon >> 5);
    const bool charging = (batCon & 0x10) != 0;
    const bool hostPowered = (batCon & 0x01) != 0;
    const bool stepIsKnown = step <= 4;
    s.batteryValid = true;
    s.batteryLevel = stepIsKnown ? kStepPercent[step] : PAD_BATTERY_LEVEL_UNKNOWN;
    if (charging) {
        s.batteryStatus = PAD_BATTERY_STATUS_CHARGING;
        return;
    }
    const bool toppedOffOnTheCable = hostPowered && step == 4;
    s.batteryStatus =
        toppedOffOnTheCable ? PAD_BATTERY_STATUS_FULL : PAD_BATTERY_STATUS_DISCHARGING;
}

struct SwitchImuSum {
    int32_t ax, ay, az, gx, gy, gz;
    int32_t frames;
};

// The pad packs up to three ~5ms samples per report; one 12-byte frame is accel int16 LE x3 then
// gyro x3, the first at byte 13.
SwitchImuSum sumSwitchImuFrames(const uint8_t* buf, const size_t len) {
    SwitchImuSum sum = {0, 0, 0, 0, 0, 0, 0};
    const size_t available = len >= 13 ? (len - 13) / 12 : 0;
    const size_t frames = available > 3 ? 3 : available;
    for (size_t f = 0; f < frames; f++) {
        const int off = 13 + 12 * (int)f;
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

int16_t clampToInt16(const int32_t v) { return v > 32767 ? (int16_t)32767 : (int16_t)v; }

// Rotate the Switch IMU frame onto the DS4 wire convention (wire gyro X=pitch, Y=yaw, Z=roll); the
// pad reports those on raw gyro Y/Z/X. Pitch and roll are negated to match the DS4 sign
// convention. Hardware testing confirmed pitch and yaw; roll's sign and the accel signs are
// unverified.
void applySwitchProMotion(const SwitchImuSum& sum, DeviceState& s) {
    const int32_t n = sum.frames;
    s.gyroX = switchGyroToWire(clampToInt16(-(sum.gy / n)));
    s.gyroY = switchGyroToWire((int16_t)(sum.gz / n));
    s.gyroZ = switchGyroToWire(clampToInt16(-(sum.gx / n)));
    s.accelX = switchAccelToWire((int16_t)(sum.ay / n));
    s.accelY = switchAccelToWire((int16_t)(sum.az / n));
    s.accelZ = switchAccelToWire((int16_t)(sum.ax / n));
    s.motionValid = true;
}

bool decodeSwitchProUsb(const uint8_t* buf, size_t len, DeviceState& s, ParserState& sticks) {
    if (len < 12) return false;
    if (buf[0] != 0x30) return false;

    const uint8_t rightByte = buf[3];
    const uint8_t sharedByte = buf[4];
    const uint8_t leftByte = buf[5];

    s.wButtons = decodeSwitchProButtons(rightByte, sharedByte, leftByte);

    // ZL/ZR are digital on the Pro, so triggers are either fully pressed or released.
    s.bLT = (leftByte & 0x80) ? 255 : 0;
    s.bRT = (rightByte & 0x80) ? 255 : 0;

    decodeSwitchProSticks(buf, sticks, s);
    decodeSwitchProBattery(buf[2], s);

    const SwitchImuSum imu = sumSwitchImuFrames(buf, len);
    const bool hasMotion = imu.frames > 0;
    if (hasMotion) applySwitchProMotion(imu, s);
    return true;
}

uint16_t decodeStadiaButtons(const uint8_t faceByte, const uint8_t systemByte, const uint8_t hat) {
    uint16_t buttons = 0;
    if (faceByte & 0x40) buttons |= XUSB_A;
    if (faceByte & 0x20) buttons |= XUSB_B;
    if (faceByte & 0x10) buttons |= XUSB_X;
    if (faceByte & 0x08) buttons |= XUSB_Y;
    if (faceByte & 0x04) buttons |= XUSB_LB;
    if (faceByte & 0x02) buttons |= XUSB_RB;
    if (systemByte & 0x80) buttons |= XUSB_START;
    if (systemByte & 0x40) buttons |= XUSB_BACK;
    if (systemByte & 0x20) buttons |= XUSB_THUMB_L;
    if (systemByte & 0x10) buttons |= XUSB_THUMB_R;
    return setDpadFromHat(buttons, (uint8_t)(hat & 0x0F));
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
    s.wButtons = decodeStadiaButtons(buf[8], buf[9], buf[7]);
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

constexpr size_t kSteamStateLen = 48;
constexpr uint8_t kSteamStateType = 0x01;
constexpr uint8_t kSteamWirelessType = 0x03;
constexpr uint8_t kSteamWirelessDisconnect = 0x01;
constexpr uint8_t kSteamWirelessConnect = 0x02;
constexpr int32_t kSteamTriggerMaxAnalog = 26000;
// 15 degrees in Q16; the pads sit rotated on the shell and SDL applies the same correction.
constexpr int32_t kSteamPadCos = 63303;
constexpr int32_t kSteamPadSin = 16962;

int16_t steamClampI16(int64_t v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (int16_t)v;
}

uint8_t steamTriggerToWire(uint8_t raw) {
    int32_t v = ((int32_t)raw << 7) | raw;
    if (v > kSteamTriggerMaxAnalog) v = kSteamTriggerMaxAnalog;
    return (uint8_t)((v * 255) / kSteamTriggerMaxAnalog);
}

// SDL adds a further +1000 to each axis while a finger is down. That is harmless for a touch
// surface but would park a stick off centre, so it is deliberately not carried over.
void steamRotatePad(int32_t x, int32_t y, int16_t& outX, int16_t& outY) {
    outX = steamClampI16(((int64_t)kSteamPadCos * x - (int64_t)kSteamPadSin * y) / 65536);
    outY = steamClampI16(((int64_t)kSteamPadSin * x + (int64_t)kSteamPadCos * y) / 65536);
}

// Steam reports gyro over +-2000 deg/s and accel over +-2g; the wire wants deg/s / 2000 and g / 4.
int16_t steamGyroToWire(int32_t raw) { return steamClampI16((int64_t)raw * 32767 / 32768); }

int16_t steamAccelToWire(int32_t raw) { return steamClampI16((int64_t)raw * 32767 / 65536); }

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
        st.steamStickX = rdLe16(buf, 16);
        st.steamStickY = rdLe16(buf, 18);
        // With no live pad the firmware reports a stick click as a left-pad click.
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
    steamRotatePad(rdLe16(buf, 20), rdLe16(buf, 22), s.sRX, s.sRY);
}

void decodeSteamMotion(const uint8_t* buf, DeviceState& s) {
    const int16_t ax = rdLe16(buf, 28);
    const int16_t ay = rdLe16(buf, 30);
    const int16_t az = rdLe16(buf, 32);
    const int16_t gx = rdLe16(buf, 34);
    const int16_t gy = rdLe16(buf, 36);
    const int16_t gz = rdLe16(buf, 38);
    // An all-zero block means the IMU setting never took; publishing it would stream a dead sensor.
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

bool decodeSteamController(const uint8_t* buf, size_t len, DeviceState& s, ParserState& st) {
    if (len < kSteamStateLen) return false;
    if (buf[0] != 0x01 || buf[1] != 0x00) return false;
    if (buf[2] != kSteamStateType) return false;

    const uint32_t btn = (uint32_t)buf[8] | ((uint32_t)buf[9] << 8) | ((uint32_t)buf[10] << 16);

    uint16_t buttons = decodeSteamButtons(btn);
    s.bLT = steamTriggerToWire(buf[11]);
    s.bRT = steamTriggerToWire(buf[12]);

    trackSteamLeftStick(buf, btn, st, buttons);
    s.wButtons = buttons;
    s.sLX = st.steamStickX;
    s.sLY = st.steamStickY;

    decodeSteamRightPad(buf, btn, s);
    decodeSteamMotion(buf, s);
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

bool parsePsCalibration(const uint8_t* buf, size_t len, PsImuCalib& out) {
    out = PsImuCalib{};
    if (len < 35) return false; // gyro/accel calibration occupies bytes 1..34
    if (!parsePsGyroCalibration(buf, out)) return false;
    if (!parsePsAccelCalibration(buf, out)) return false;
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
        return decodeDualSense(buf, len, s, sticks);
    case Parser::SWITCH_PRO_USB:
        return sticks != nullptr && decodeSwitchProUsb(buf, len, s, *sticks);
    case Parser::STADIA:
        return decodeStadia(buf, len, s);
    case Parser::STEAM_CONTROLLER:
        return sticks != nullptr && decodeSteamController(buf, len, s, *sticks);
    case Parser::GENERIC_HID_GAMEPAD:
        return sticks != nullptr && sticks->hidLayout.valid
                   ? usbhid::decodeFromLayout(buf, len, s, sticks->hidLayout)
                   : decodeGenericHidGamepad(buf, len, s);
    case Parser::NONE:
        return false;
    }
    return false;
}

// Event framing and the one-byte payload values follow the Linux hid-steam driver
// (steam_raw_event, ID_CONTROLLER_WIRELESS).
WirelessEvent checkWirelessEvent(Parser p, const uint8_t* buf, size_t len) {
    if (p != Parser::STEAM_CONTROLLER) return WirelessEvent::NONE;
    if (len < 5) return WirelessEvent::NONE;
    if (buf[0] != 0x01 || buf[1] != 0x00) return WirelessEvent::NONE;
    if (buf[2] != kSteamWirelessType || buf[3] != 0x01) return WirelessEvent::NONE;
    switch (buf[4]) {
    case kSteamWirelessDisconnect:
        return WirelessEvent::DISCONNECT;
    case kSteamWirelessConnect:
        return WirelessEvent::CONNECT;
    default:
        return WirelessEvent::NONE;
    }
}

uint16_t decodeGenericHidButtons(const uint8_t btnLo, const uint8_t btnHi) {
    uint16_t buttons = 0;
    if (btnLo & 0x10) buttons |= XUSB_A;
    if (btnLo & 0x20) buttons |= XUSB_B;
    if (btnLo & 0x40) buttons |= XUSB_X;
    if (btnLo & 0x80) buttons |= XUSB_Y;
    if (btnHi & 0x01) buttons |= XUSB_LB;
    if (btnHi & 0x02) buttons |= XUSB_RB;
    if (btnHi & 0x04) buttons |= XUSB_BACK;
    if (btnHi & 0x08) buttons |= XUSB_START;
    if (btnHi & 0x10) buttons |= XUSB_THUMB_L;
    if (btnHi & 0x20) buttons |= XUSB_THUMB_R;
    return setDpadFromHat(buttons, (uint8_t)(btnLo & 0x0F));
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

    const uint8_t btnLo = buf[4];
    const uint8_t btnHi = len > 5 ? buf[5] : 0;
    s.wButtons = decodeGenericHidButtons(btnLo, btnHi);
    s.bLT = (btnHi & 0x40) ? 255 : 0;
    s.bRT = (btnHi & 0x80) ? 255 : 0;
    return true;
}

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

size_t buildGipInitPacket(InitKind init, int index, uint8_t seq, uint8_t* out, size_t outCap) {
    const size_t len = copyInitPacket(gipSequenceFor(init), index, out, outCap);
    if (len == 0) return 0;
    out[2] = seq;
    return len;
}

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

size_t buildSteamConfigPacket(SteamConfig stage, int index, uint8_t* out, size_t outCap) {
    return copyInitPacket(steamSequenceFor(stage), index, out, outCap);
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
    case Parser::STEAM_CONTROLLER:
    case Parser::STADIA:
    case Parser::GENERIC_HID_GAMEPAD:
    case Parser::NONE:
        return 0;
    }
    return 0;
}

bool parserHasLightbar(Parser p) { return p == Parser::DUALSHOCK4 || p == Parser::DUALSENSE; }

bool parserHasPlayerLeds(Parser p) { return p == Parser::DUALSENSE || p == Parser::SWITCH_PRO_USB; }

bool parserHasTriggerEffects(Parser p) { return p == Parser::DUALSENSE; }

bool parserHasHapticLanes(Parser p) { return p == Parser::DUALSENSE; }

bool parserHasTriggerRumble(Parser p) { return p == Parser::XBOX_ONE_GIP; }

bool parserHasMicMuteLed(Parser p) { return p == Parser::DUALSENSE; }

// DualSense output report 0x02 field offsets and flag bits, in this file's convention: the report
// id lives at out[0], so these are hid-playstation's RID-stripped 8 and 9 plus one, the same shift
// the player-LED byte carries (out[44] against stripped 43).
static constexpr size_t kDs5MicMuteLedByte = 9;
static constexpr size_t kDs5PowerSaveByte = 10;
static constexpr uint8_t kDs5ValidFlag1MicMuteLed = 0x01;
static constexpr uint8_t kDs5ValidFlag1PowerSave = 0x02;
static constexpr uint8_t kDs5PowerSaveMicMute = 0x10;

// Stamp the shadowed lamp onto a DualSense 0x02 report that was built for something else. Every
// builder here memsets a fresh report, and the firmware applies whatever the valid flags claim, so
// without this a colour or player-LED write would be a lamp write too: flags set, field zero.
// A pad whose lamp the host never drove is left alone, so nothing changes for hosts that do not
// use it.
//
// The power-save bit rides along because the lamp and the microphone amplifier are one thing on
// this pad: a lit mute lamp over a live microphone is the one failure this whole feature exists to
// prevent. Pulse counts as lit for the same reason; it is the rarer state and the honest reading of
// a lamp the user can see.
static void reassertDs5MicMuteLed(const FeedbackState& st, uint8_t* out) {
    if (!st.ds5MicMuteLedSet) return;
    out[2] |= kDs5ValidFlag1MicMuteLed | kDs5ValidFlag1PowerSave;
    out[kDs5MicMuteLedByte] = st.ds5MicMuteLed;
    out[kDs5PowerSaveByte] = st.ds5MicMuteLed == MIC_MUTE_LED_OFF ? 0x00 : kDs5PowerSaveMicMute;
}

size_t buildMergedRumbleReport(Parser p, FeedbackState& st, uint8_t seq, uint8_t* out,
                               size_t outCap) {
    if (p != Parser::XBOX_ONE_GIP) {
        size_t n = buildRumbleReport(p, st.strong, st.weak, seq, out, outCap);
        if (n != 0 && p == Parser::DUALSENSE) reassertDs5MicMuteLed(st, out);
        return n;
    }
    // GIP: one report drives all four motors (mask 0x0F), so the merged state rides every write;
    // a trigger-only change must not zero the main motors and vice versa.
    if (outCap < 13) return 0;
    out[0] = 0x09;
    out[1] = 0x00;
    out[2] = seq;
    out[3] = 0x09;
    out[4] = 0x00;
    out[5] = 0x0F;
    out[6] = (uint8_t)(st.leftTrigger / 512);
    out[7] = (uint8_t)(st.rightTrigger / 512);
    out[8] = (uint8_t)(st.strong / 512);
    out[9] = (uint8_t)(st.weak / 512);
    out[10] = 0xFF;
    out[11] = 0x00;
    out[12] = 0xFF;
    return 13;
}

size_t buildLightbarReport(Parser p, FeedbackState& st, uint8_t r, uint8_t g, uint8_t b,
                           uint8_t* out, size_t outCap) {
    switch (p) {
    case Parser::DUALSHOCK4:
        // Flag 0x02 alone: the motor fields are marked invalid, so a colour write never stomps a
        // live rumble.
        if (outCap < 32) return 0;
        memset(out, 0, 32);
        out[0] = 0x05;
        out[1] = 0x02;
        out[6] = r;
        out[7] = g;
        out[8] = b;
        return 32;
    case Parser::DUALSENSE:
        if (outCap < 63) return 0;
        memset(out, 0, 63);
        out[0] = 0x02;
        out[2] = 0x04; // valid_flag1 LIGHTBAR_CONTROL_ENABLE
        if (!st.ds5LightbarSetupSent) {
            // One-time handoff (hid-playstation does the same at probe): LIGHTBAR_SETUP light-out
            // stops the firmware's own blue glow so the host colour actually shows.
            out[39] = 0x02; // valid_flag2 LIGHTBAR_SETUP_CONTROL_ENABLE
            out[42] = 0x02; // lightbar_setup = LIGHT_OUT
            st.ds5LightbarSetupSent = true;
        }
        out[45] = r;
        out[46] = g;
        out[47] = b;
        reassertDs5MicMuteLed(st, out);
        return 63;
    default:
        return 0;
    }
}

size_t buildPlayerLedsReport(Parser p, const FeedbackState& st, uint8_t ledMask, uint8_t seq,
                             uint8_t* out, size_t outCap) {
    switch (p) {
    case Parser::DUALSENSE:
        if (outCap < 63) return 0;
        memset(out, 0, 63);
        out[0] = 0x02;
        out[2] = 0x10; // valid_flag1 PLAYER_INDICATOR_CONTROL_ENABLE
        out[44] = (uint8_t)(ledMask & 0x1F);
        reassertDs5MicMuteLed(st, out);
        return 63;
    case Parser::SWITCH_PRO_USB:
        // Subcommand 0x30 rides the 0x01 rumble+subcommand report; neutral HD-rumble blocks keep
        // the motors untouched.
        if (outCap < 12) return 0;
        out[0] = 0x01;
        out[1] = (uint8_t)(seq & 0x0F);
        switchEncodeMotor(&out[2], 0);
        switchEncodeMotor(&out[6], 0);
        out[10] = 0x30;
        out[11] = (uint8_t)(ledMask & 0x0F);
        return 12;
    default:
        return 0;
    }
}

size_t buildTriggerEffectsReport(Parser p, const FeedbackState& st,
                                 const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                                 const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN], uint8_t* out,
                                 size_t outCap) {
    if (p != Parser::DUALSENSE) return 0;
    if (outCap < 63) return 0;
    memset(out, 0, 63);
    out[0] = 0x02;
    out[1] = 0x04 | 0x08; // valid_flag0: right + left trigger-effect blocks
    memcpy(out + 11, right, TRIGGER_EFFECT_BLOCK_LEN);
    memcpy(out + 22, left, TRIGGER_EFFECT_BLOCK_LEN);
    reassertDs5MicMuteLed(st, out);
    return 63;
}

size_t buildMicMuteLedReport(Parser p, FeedbackState& st, uint8_t state, uint8_t* out,
                             size_t outCap) {
    if (p != Parser::DUALSENSE) return 0;
    // A state past pulse can only come from a host speaking something newer than this client, and
    // the JNI receive arm already dropped it; refusing again here keeps the builder honest for any
    // other caller rather than inventing a lamp the pad would show.
    if (state > MIC_MUTE_LED_PULSE) return 0;
    if (outCap < 63) return 0;
    st.ds5MicMuteLed = state;
    st.ds5MicMuteLedSet = true;
    memset(out, 0, 63);
    out[0] = 0x02;
    // The whole report body is the re-assert: one code path writes the lamp, whichever builder
    // asked for it.
    reassertDs5MicMuteLed(st, out);
    return 63;
}

#ifdef __ANDROID__
namespace {

bool runSteamQuietInit(const int fd, const int interfaceNumber) {
    uint8_t buf[16];
    for (int i = 0;; i++) {
        const size_t n = buildSteamConfigPacket(SteamConfig::QUIET, i, buf, sizeof(buf));
        if (n == 0) break;
        if (!sendFeatureReport(fd, interfaceNumber, buf, n)) {
            LOGE("Steam Controller: quiet-mode packet %d failed", i);
            return false;
        }
    }
    LOGI("Steam Controller quiet-mode sequence sent");
    return true;
}

// GIP init: power-on tells the pad to start sending input reports; the rest of the sequence (LED,
// auth-done, and the S set-mode) starts the models the lone power-on left silent.
bool runGipInit(const int fd, const uint8_t epOut, const InitKind init) {
    uint8_t buf[16];
    for (int i = 0;; i++) {
        const size_t n = buildGipInitPacket(init, i, (uint8_t)i, buf, sizeof(buf));
        if (n == 0) break;
        const bool ok = bulkWrite(fd, epOut, buf, n, 200);
        const bool powerOnFailed = i == 0 && !ok;
        if (powerOnFailed) {
            LOGE("Xbox One power-on write failed");
            return false;
        }
        usleep(10000);
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
    {kSwitchStatus, sizeof(kSwitchStatus), 100, kSwitchInitSettleUs, true, "status request"},
    {kSwitchDisableTimeout, sizeof(kSwitchDisableTimeout), 100, kSwitchInitSettleUs, false,
     "disable-timeout write"},
    {kSwitchSetReportMode, sizeof(kSwitchSetReportMode), 200, kSwitchInitSettleUs, true,
     "set-report-mode write"},
    {kSwitchEnableVibration, sizeof(kSwitchEnableVibration), 200, 0, false,
     "enable-vibration write"},
};

bool runSwitchInitStep(const int fd, const uint8_t epOut, const SwitchInitStep& step) {
    const bool sent = bulkWrite(fd, epOut, step.packet, step.length, step.timeoutMs);
    if (!sent && step.isFatal) {
        LOGE("Switch Pro: %s failed", step.what);
        return false;
    }
    if (!sent) LOGI("Switch Pro: %s failed (non-fatal)", step.what);
    if (step.settleUs != 0) usleep(step.settleUs);
    return true;
}

bool runSwitchProHandshake(const int fd, const uint8_t epOut) {
    if (epOut == 0) {
        LOGE("Switch Pro: no OUT endpoint, cannot init");
        return false;
    }
    for (const SwitchInitStep& step : kSwitchInitSequence) {
        if (!runSwitchInitStep(fd, epOut, step)) return false;
    }
    LOGI("Switch Pro USB init sequence sent");
    return true;
}

} // namespace

bool runInit(int fd, int interfaceNumber, uint8_t epOut, InitKind init) {
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

void runTeardown(int fd, int interfaceNumber, Parser p) {
    if (p != Parser::STEAM_CONTROLLER) return;
    uint8_t buf[16];
    for (int i = 0;; i++) {
        size_t n = buildSteamConfigPacket(SteamConfig::RESTORE, i, buf, sizeof(buf));
        if (n == 0) break;
        sendFeatureReport(fd, interfaceNumber, buf, n);
    }
    LOGI("Steam Controller restored to stand-alone mode");
}

bool runRumble(int fd, uint8_t epOut, Parser p, uint16_t strong, uint16_t weak, uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildRumbleReport(p, strong, weak, seq, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}

bool runMergedRumble(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildMergedRumbleReport(p, st, seq, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}

bool runLightbar(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t r, uint8_t g,
                 uint8_t b) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildLightbarReport(p, st, r, g, b, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}

bool runPlayerLeds(int fd, uint8_t epOut, Parser p, const FeedbackState& st, uint8_t ledMask,
                   uint8_t seq) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildPlayerLedsReport(p, st, ledMask, seq, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}

bool runTriggerEffects(int fd, uint8_t epOut, Parser p, const FeedbackState& st,
                       const uint8_t left[TRIGGER_EFFECT_BLOCK_LEN],
                       const uint8_t right[TRIGGER_EFFECT_BLOCK_LEN]) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildTriggerEffectsReport(p, st, left, right, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}

bool runMicMuteLed(int fd, uint8_t epOut, Parser p, FeedbackState& st, uint8_t state) {
    if (epOut == 0) return false;
    uint8_t buf[64];
    size_t n = buildMicMuteLedReport(p, st, state, buf, sizeof(buf));
    if (n == 0) return false;
    return bulkWrite(fd, epOut, buf, n, 100);
}
#endif

} // namespace usbparsers

// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_hid_descriptor.h"

namespace usbhid {

using gamepad::DeviceState;

namespace {

constexpr size_t kMaxUsages = 16;
constexpr uint8_t kMaxButtons = 16;

int32_t signExtend(uint32_t v, uint8_t bytes) {
    if (bytes == 0 || bytes >= 4) return (int32_t)v;
    uint32_t bits = bytes * 8u;
    uint32_t signBit = 1u << (bits - 1);
    if (v & signBit) return (int32_t)(v | ~((1u << bits) - 1u));
    return (int32_t)v;
}

uint32_t extractBits(const uint8_t* d, size_t dlen, uint32_t bitOff, uint8_t bits) {
    uint32_t v = 0;
    for (uint8_t i = 0; i < bits && i < 32; i++) {
        uint32_t bi = bitOff + i;
        if ((size_t)(bi >> 3) >= dlen) break;
        if ((d[bi >> 3] >> (bi & 7u)) & 1u) v |= (1u << i);
    }
    return v;
}

int32_t toSigned(uint32_t raw, uint8_t bits, int32_t logicalMin) {
    if (logicalMin < 0 && bits > 0 && bits < 32) {
        uint32_t signBit = 1u << (bits - 1);
        if (raw & signBit) return (int32_t)(raw | ~((1u << bits) - 1u));
    }
    return (int32_t)raw;
}

int16_t scaleAxis16(uint32_t raw, const HidAxis& a, bool invert) {
    int32_t v = toSigned(raw, a.bitSize, a.logicalMin);
    int32_t center = (a.logicalMin + a.logicalMax) / 2;
    int32_t half = (a.logicalMax - a.logicalMin) / 2;
    if (half <= 0) return 0;
    int32_t scaled = (int32_t)((int64_t)(v - center) * 32767 / half);
    if (invert) scaled = -scaled;
    if (scaled > 32767) scaled = 32767;
    if (scaled < -32768) scaled = -32768;
    return (int16_t)scaled;
}

uint8_t scaleTrig8(uint32_t raw, const HidAxis& a) {
    int32_t v = toSigned(raw, a.bitSize, a.logicalMin);
    int32_t span = a.logicalMax - a.logicalMin;
    if (span <= 0) return 0;
    int32_t scaled = (int32_t)((int64_t)(v - a.logicalMin) * 255 / span);
    if (scaled < 0) scaled = 0;
    if (scaled > 255) scaled = 255;
    return (uint8_t)scaled;
}

uint16_t buttonBit(uint8_t idx) {
    using namespace gamepad;
    switch (idx) {
    case 0:
        return XUSB_A;
    case 1:
        return XUSB_B;
    case 2:
        return XUSB_X;
    case 3:
        return XUSB_Y;
    case 4:
        return XUSB_LB;
    case 5:
        return XUSB_RB;
    case 6:
        return XUSB_BACK;
    case 7:
        return XUSB_START;
    case 8:
        return XUSB_THUMB_L;
    case 9:
        return XUSB_THUMB_R;
    case 10:
        return XUSB_GUIDE;
    default:
        return 0;
    }
}

// Switch-order HID pads declare buttons in usage row Y B A X L R ZL ZR Minus Plus L3 R3 Home
// Capture; remap by position to match decodeSwitchProUsb. ZL/ZR (indices 6/7) fold into the
// triggers in decodeFromLayout instead of mapping here.
uint16_t switchOrderButtonBit(uint8_t idx) {
    using namespace gamepad;
    switch (idx) {
    case 0:
        return XUSB_X;
    case 1:
        return XUSB_A;
    case 2:
        return XUSB_B;
    case 3:
        return XUSB_Y;
    case 4:
        return XUSB_LB;
    case 5:
        return XUSB_RB;
    case 8:
        return XUSB_BACK;
    case 9:
        return XUSB_START;
    case 10:
        return XUSB_THUMB_L;
    case 11:
        return XUSB_THUMB_R;
    case 12:
        return XUSB_GUIDE;
    default:
        return 0;
    }
}

uint16_t dpadBitsForDir(int dir) {
    using namespace gamepad;
    switch (dir) {
    case 0:
        return XUSB_DPAD_UP;
    case 1:
        return (uint16_t)(XUSB_DPAD_UP | XUSB_DPAD_RIGHT);
    case 2:
        return XUSB_DPAD_RIGHT;
    case 3:
        return (uint16_t)(XUSB_DPAD_DOWN | XUSB_DPAD_RIGHT);
    case 4:
        return XUSB_DPAD_DOWN;
    case 5:
        return (uint16_t)(XUSB_DPAD_DOWN | XUSB_DPAD_LEFT);
    case 6:
        return XUSB_DPAD_LEFT;
    case 7:
        return (uint16_t)(XUSB_DPAD_UP | XUSB_DPAD_LEFT);
    default:
        return 0;
    }
}

void setAxis(HidAxis& a, uint32_t bit, uint32_t size, int32_t lo, int32_t hi) {
    if (a.present) return; // first declaration of an axis wins
    a.present = true;
    a.bitOffset = (uint16_t)bit;
    a.bitSize = (uint8_t)size;
    a.logicalMin = lo;
    a.logicalMax = hi;
}

// Generic Desktop right stick is Z/Rz and triggers are Rx/Ry, matching the convention the
// fixed-offset fallback already assumes; Simulation Brake/Accelerator also map to the triggers.
void assignUsage(HidLayout& out, uint32_t page, uint32_t usage, uint32_t bit, uint32_t size,
                 int32_t lo, int32_t hi) {
    if (page == 0x01) {
        switch (usage) {
        case 0x30:
            setAxis(out.lx, bit, size, lo, hi);
            break;
        case 0x31:
            setAxis(out.ly, bit, size, lo, hi);
            break;
        case 0x32:
            setAxis(out.rx, bit, size, lo, hi);
            break;
        case 0x35:
            setAxis(out.ry, bit, size, lo, hi);
            break;
        case 0x33:
            setAxis(out.lt, bit, size, lo, hi);
            break;
        case 0x34:
            setAxis(out.rt, bit, size, lo, hi);
            break;
        case 0x39:
            if (!out.hasHat) {
                out.hasHat = true;
                out.hatBitOffset = (uint16_t)bit;
                out.hatBitSize = (uint8_t)size;
                out.hatLogicalMin = lo;
                out.hatLogicalMax = hi;
            }
            break;
        default:
            break;
        }
    } else if (page == 0x02) {
        if (usage == 0xC5)
            setAxis(out.lt, bit, size, lo, hi);
        else if (usage == 0xC4)
            setAxis(out.rt, bit, size, lo, hi);
    }
}

// One item off the descriptor stream. A long item (prefix 0xFE) carries no data this parser
// understands, so it is reported with `skip` set and its payload stepped over.
struct HidItem {
    uint8_t type;
    uint8_t tag;
    uint32_t data;
    uint8_t dataLen;
    bool skip;
    bool truncated;
};

HidItem readHidItem(const uint8_t* desc, const size_t len, size_t& i) {
    HidItem item = {0, 0, 0, 0, false, false};
    const uint8_t prefix = desc[i++];

    const bool isLongItem = prefix == 0xFE;
    if (isLongItem) {
        if (i >= len) {
            item.truncated = true;
            return item;
        }
        const uint8_t payload = desc[i];
        i += 2u + payload;
        item.skip = true;
        return item;
    }

    const uint8_t bSize = prefix & 0x03u;
    item.dataLen = bSize == 3 ? 4 : bSize;
    item.type = (prefix >> 2) & 0x03u;
    item.tag = (prefix >> 4) & 0x0Fu;
    if (i + item.dataLen > len) {
        item.truncated = true;
        return item;
    }
    for (uint8_t k = 0; k < item.dataLen; k++) item.data |= (uint32_t)desc[i + k] << (8u * k);
    i += item.dataLen;
    return item;
}

// The global and local item state the stream accumulates until a Main item consumes it.
struct HidParseState {
    uint32_t usagePage;
    uint32_t reportSize;
    uint32_t reportCount;
    int32_t logMin;
    int32_t logMax;
    uint8_t currentReportId;
    uint32_t bitCursor;
    bool locked;
    uint8_t lockedReportId;
    uint32_t usages[kMaxUsages];
    size_t usageCount;
    uint32_t usageMin;
    bool haveRange;
};

void applyGlobalItem(const HidItem& item, HidParseState& st) {
    switch (item.tag) {
    case 0x0:
        st.usagePage = item.data;
        break;
    case 0x1:
        st.logMin = signExtend(item.data, item.dataLen);
        break;
    case 0x2:
        st.logMax = signExtend(item.data, item.dataLen);
        break;
    case 0x7:
        st.reportSize = item.data;
        break;
    case 0x8:
        st.currentReportId = (uint8_t)item.data;
        st.bitCursor = 0;
        break;
    case 0x9:
        st.reportCount = item.data;
        break;
    default:
        break;
    }
}

void applyLocalItem(const HidItem& item, HidParseState& st) {
    switch (item.tag) {
    case 0x0:
        if (st.usageCount < kMaxUsages) st.usages[st.usageCount++] = item.data;
        break;
    case 0x1:
        st.usageMin = item.data;
        st.haveRange = true;
        break;
    case 0x2:
        st.haveRange = true;
        break;
    default:
        break;
    }
}

void takeButtonField(const HidParseState& st, const uint32_t startBit, HidLayout& out) {
    const bool alreadyTaken = out.buttonCount != 0;
    if (alreadyTaken) return;
    out.buttonBitOffset = (uint16_t)startBit;
    const uint32_t count = st.reportCount > kMaxButtons ? kMaxButtons : st.reportCount;
    out.buttonCount = (uint8_t)count;
}

void takeAxisFields(const HidParseState& st, const uint32_t startBit, HidLayout& out) {
    for (uint32_t f = 0; f < st.reportCount; f++) {
        uint32_t usage;
        if (st.haveRange) {
            usage = st.usageMin + f;
        } else if (st.usageCount == 0) {
            break;
        } else {
            usage = st.usages[f < st.usageCount ? f : st.usageCount - 1];
        }
        assignUsage(out, st.usagePage, usage, startBit + f * st.reportSize, st.reportSize,
                    st.logMin, st.logMax);
    }
}

// The first non-constant Input item locks the report id this layout describes; later items from a
// different report are the device's other interfaces and are not ours to read.
void applyInputItem(const HidItem& item, HidParseState& st, HidLayout& out) {
    const uint32_t startBit = st.bitCursor;
    st.bitCursor += st.reportSize * st.reportCount;

    const bool isConstantPadding = (item.data & 0x01u) != 0;
    const bool carriesFields = st.reportSize > 0 && st.reportCount > 0;
    if (isConstantPadding || !carriesFields) return;

    if (!st.locked) {
        st.locked = true;
        st.lockedReportId = st.currentReportId;
        out.reportId = st.currentReportId;
    }
    const bool isTheLockedReport = st.currentReportId == st.lockedReportId;
    if (!isTheLockedReport) return;

    const bool isButtonPage = st.usagePage == 0x09;
    if (isButtonPage) {
        takeButtonField(st, startBit, out);
        return;
    }
    const bool isAxisPage = st.usagePage == 0x01 || st.usagePage == 0x02;
    if (isAxisPage) takeAxisFields(st, startBit, out);
}

void clearLocalItems(HidParseState& st) {
    st.usageCount = 0;
    st.haveRange = false;
    st.usageMin = 0;
}

// The report-id prefix byte, when the layout says the device sends one.
bool skipReportIdPrefix(const uint8_t* buf, const size_t len, const HidLayout& L,
                        size_t& dataStart) {
    const bool sendsAPrefix = L.reportId != 0;
    if (!sendsAPrefix) {
        dataStart = 0;
        return true;
    }
    const bool isOurReport = len >= 1 && buf[0] == L.reportId;
    if (!isOurReport) return false;
    dataStart = 1;
    return true;
}

void decodeLayoutAxes(const uint8_t* d, const size_t dlen, const HidLayout& L, DeviceState& s) {
    if (L.lx.present)
        s.sLX = scaleAxis16(extractBits(d, dlen, L.lx.bitOffset, L.lx.bitSize), L.lx, false);
    if (L.ly.present)
        s.sLY = scaleAxis16(extractBits(d, dlen, L.ly.bitOffset, L.ly.bitSize), L.ly, true);
    if (L.rx.present)
        s.sRX = scaleAxis16(extractBits(d, dlen, L.rx.bitOffset, L.rx.bitSize), L.rx, false);
    if (L.ry.present)
        s.sRY = scaleAxis16(extractBits(d, dlen, L.ry.bitOffset, L.ry.bitSize), L.ry, true);
    if (L.lt.present) s.bLT = scaleTrig8(extractBits(d, dlen, L.lt.bitOffset, L.lt.bitSize), L.lt);
    if (L.rt.present) s.bRT = scaleTrig8(extractBits(d, dlen, L.rt.bitOffset, L.rt.bitSize), L.rt);
}

uint16_t decodeLayoutHat(const uint8_t* d, const size_t dlen, const HidLayout& L) {
    if (!L.hasHat) return 0;
    const uint32_t raw = extractBits(d, dlen, L.hatBitOffset, L.hatBitSize);
    const int dir = (int)raw - (int)L.hatLogicalMin;
    const int range = (int)L.hatLogicalMax - (int)L.hatLogicalMin;
    const bool isADirection = dir >= 0 && dir <= range && dir <= 7;
    if (!isADirection) return 0;
    return dpadBitsForDir(dir);
}

bool layoutButtonIsDown(const uint8_t* d, const size_t dlen, const HidLayout& L, const uint8_t i) {
    return extractBits(d, dlen, (uint32_t)L.buttonBitOffset + i, 1) != 0;
}

// A Switch-order pad carries ZL and ZR in the button block; they drive the triggers, not buttons.
uint16_t decodeSwitchOrderButtons(const uint8_t* d, const size_t dlen, const HidLayout& L,
                                  DeviceState& s) {
    uint16_t buttons = 0;
    bool zl = false;
    bool zr = false;
    for (uint8_t i = 0; i < L.buttonCount; i++) {
        if (!layoutButtonIsDown(d, dlen, L, i)) continue;
        if (i == 6) {
            zl = true;
        } else if (i == 7) {
            zr = true;
        } else {
            buttons = (uint16_t)(buttons | switchOrderButtonBit(i));
        }
    }
    s.bLT = zl ? 255 : 0;
    s.bRT = zr ? 255 : 0;
    return buttons;
}

uint16_t decodeStandardButtons(const uint8_t* d, const size_t dlen, const HidLayout& L) {
    uint16_t buttons = 0;
    for (uint8_t i = 0; i < L.buttonCount; i++) {
        if (layoutButtonIsDown(d, dlen, L, i)) buttons = (uint16_t)(buttons | buttonBit(i));
    }
    return buttons;
}

} // namespace

bool parseReportDescriptor(const uint8_t* desc, size_t len, HidLayout& out) {
    out = HidLayout{};
    HidParseState st = {};

    size_t i = 0;
    while (i < len) {
        const HidItem item = readHidItem(desc, len, i);
        if (item.truncated) break;
        if (item.skip) continue;

        const bool isMainItem = item.type == 0;
        const bool isGlobalItem = item.type == 1;
        const bool isLocalItem = item.type == 2;
        if (isMainItem) {
            const bool isInputItem = item.tag == 0x8;
            if (isInputItem) applyInputItem(item, st, out);
            clearLocalItems(st);
        } else if (isGlobalItem) {
            applyGlobalItem(item, st);
        } else if (isLocalItem) {
            applyLocalItem(item, st);
        }
    }

    out.valid = out.lx.present || out.ly.present || out.buttonCount > 0 || out.hasHat;
    return out.valid;
}

bool decodeFromLayout(const uint8_t* buf, size_t len, DeviceState& s, const HidLayout& L) {
    if (!L.valid) return false;
    size_t dataStart = 0;
    if (!skipReportIdPrefix(buf, len, L, dataStart)) return false;

    const uint8_t* d = buf + dataStart;
    const size_t dlen = len - dataStart;

    decodeLayoutAxes(d, dlen, L, s);

    const uint16_t hatBits = decodeLayoutHat(d, dlen, L);
    const uint16_t buttonBits = L.switchOrderButtons ? decodeSwitchOrderButtons(d, dlen, L, s)
                                                     : decodeStandardButtons(d, dlen, L);
    s.wButtons = (uint16_t)(hatBits | buttonBits);
    return true;
}

} // namespace usbhid

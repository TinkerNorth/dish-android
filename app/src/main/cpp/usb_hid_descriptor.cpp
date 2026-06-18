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

} // namespace

bool parseReportDescriptor(const uint8_t* desc, size_t len, HidLayout& out) {
    out = HidLayout{};

    uint32_t usagePage = 0, reportSize = 0, reportCount = 0;
    int32_t logMin = 0, logMax = 0;
    uint8_t currentReportId = 0;
    uint32_t bitCursor = 0;
    bool locked = false;
    uint8_t lockedReportId = 0;

    uint32_t usages[kMaxUsages];
    size_t usageCount = 0;
    uint32_t usageMin = 0;
    bool haveRange = false;

    size_t i = 0;
    while (i < len) {
        uint8_t prefix = desc[i++];
        if (prefix == 0xFE) { // long item: 1 size byte + 1 tag byte + payload
            if (i >= len) break;
            uint8_t payload = desc[i];
            i += 2u + payload;
            continue;
        }
        uint8_t bSize = prefix & 0x03u;
        uint8_t dataLen = bSize == 3 ? 4 : bSize;
        uint8_t bType = (prefix >> 2) & 0x03u;
        uint8_t bTag = (prefix >> 4) & 0x0Fu;
        if (i + dataLen > len) break;
        uint32_t data = 0;
        for (uint8_t k = 0; k < dataLen; k++) data |= (uint32_t)desc[i + k] << (8u * k);
        i += dataLen;

        if (bType == 0) {      // Main
            if (bTag == 0x8) { // Input
                uint32_t startBit = bitCursor;
                bitCursor += reportSize * reportCount;
                bool isConst = (data & 0x01u) != 0;
                if (!isConst && reportSize > 0 && reportCount > 0) {
                    if (!locked) {
                        locked = true;
                        lockedReportId = currentReportId;
                        out.reportId = currentReportId;
                    }
                    if (currentReportId == lockedReportId) {
                        if (usagePage == 0x09) {
                            if (out.buttonCount == 0) {
                                out.buttonBitOffset = (uint16_t)startBit;
                                uint32_t cnt =
                                    reportCount > kMaxButtons ? kMaxButtons : reportCount;
                                out.buttonCount = (uint8_t)cnt;
                            }
                        } else if (usagePage == 0x01 || usagePage == 0x02) {
                            for (uint32_t f = 0; f < reportCount; f++) {
                                uint32_t usage;
                                if (haveRange) {
                                    usage = usageMin + f;
                                } else if (usageCount == 0) {
                                    break;
                                } else {
                                    usage = usages[f < usageCount ? f : usageCount - 1];
                                }
                                assignUsage(out, usagePage, usage, startBit + f * reportSize,
                                            reportSize, logMin, logMax);
                            }
                        }
                    }
                }
            }
            usageCount = 0;
            haveRange = false;
            usageMin = 0;
        } else if (bType == 1) { // Global
            switch (bTag) {
            case 0x0:
                usagePage = data;
                break;
            case 0x1:
                logMin = signExtend(data, dataLen);
                break;
            case 0x2:
                logMax = signExtend(data, dataLen);
                break;
            case 0x7:
                reportSize = data;
                break;
            case 0x8:
                currentReportId = (uint8_t)data;
                bitCursor = 0;
                break;
            case 0x9:
                reportCount = data;
                break;
            default:
                break;
            }
        } else if (bType == 2) { // Local
            switch (bTag) {
            case 0x0:
                if (usageCount < kMaxUsages) usages[usageCount++] = data;
                break;
            case 0x1:
                usageMin = data;
                haveRange = true;
                break;
            case 0x2:
                haveRange = true;
                break;
            default:
                break;
            }
        }
    }

    out.valid = out.lx.present || out.ly.present || out.buttonCount > 0 || out.hasHat;
    return out.valid;
}

bool decodeFromLayout(const uint8_t* buf, size_t len, DeviceState& s, const HidLayout& L) {
    if (!L.valid) return false;
    size_t dataStart = 0;
    if (L.reportId != 0) {
        if (len < 1 || buf[0] != L.reportId) return false;
        dataStart = 1;
    }
    const uint8_t* d = buf + dataStart;
    size_t dlen = len - dataStart;

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

    uint16_t b = 0;
    if (L.hasHat) {
        uint32_t raw = extractBits(d, dlen, L.hatBitOffset, L.hatBitSize);
        int dir = (int)raw - (int)L.hatLogicalMin;
        int range = (int)L.hatLogicalMax - (int)L.hatLogicalMin;
        if (dir >= 0 && dir <= range && dir <= 7) b = (uint16_t)(b | dpadBitsForDir(dir));
    }
    for (uint8_t i = 0; i < L.buttonCount; i++) {
        if (extractBits(d, dlen, (uint32_t)L.buttonBitOffset + i, 1)) {
            b = (uint16_t)(b | buttonBit(i));
        }
    }
    s.wButtons = b;
    return true;
}

} // namespace usbhid

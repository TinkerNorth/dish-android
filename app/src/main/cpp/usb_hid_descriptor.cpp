// SPDX-License-Identifier: LGPL-3.0-or-later

#include "usb_hid_descriptor.h"

#include <algorithm>
#include <cstddef>

namespace usbhid {

using gamepad::DeviceState;

namespace {

constexpr size_t kMaxUsages = 16;
constexpr uint8_t kMaxButtons = 16;

constexpr int64_t kAxisMax = 32767;
constexpr int64_t kAxisMin = -32768;
constexpr int64_t kTriggerMax = 255;
constexpr uint8_t kTriggerFull = 255;
constexpr uint8_t kBitsPerByte = 8;
constexpr uint8_t kBitsPerU32 = 32;
constexpr uint8_t kBytesPerU32 = 4;
// Report ID is a one-byte item, so every id has a slot; 0 is the device that sends no id at all.
constexpr size_t kReportIdCount = 256;

// The usage pages and usages this parser maps (USB HID Usage Tables 1.12 §4, §5, §12).
constexpr uint32_t kUsagePageGenericDesktop = 0x01;
constexpr uint32_t kUsagePageSimulation = 0x02;
constexpr uint32_t kUsagePageButton = 0x09;
constexpr uint32_t kUsageX = 0x30;
constexpr uint32_t kUsageY = 0x31;
constexpr uint32_t kUsageZ = 0x32;
constexpr uint32_t kUsageRx = 0x33;
constexpr uint32_t kUsageRy = 0x34;
constexpr uint32_t kUsageRz = 0x35;
constexpr uint32_t kUsageHatSwitch = 0x39;
constexpr uint32_t kUsageAccelerator = 0xC4;
constexpr uint32_t kUsageBrake = 0xC5;

// The item stream (HID 1.11 §6.2.2): a short item's prefix byte packs bSize, bType and bTag; a
// long item is the 0xFE prefix, a data length, a tag, then that many data bytes.
constexpr uint8_t kLongItemPrefix = 0xFE;
constexpr size_t kLongItemHeaderBytes = 2;
constexpr uint8_t kItemSizeMask = 0x03;
constexpr uint8_t kItemSizeFourBytes = 3;
constexpr uint8_t kItemTypeShift = 2;
constexpr uint8_t kItemTypeMask = 0x03;
constexpr uint8_t kItemTagShift = 4;
constexpr uint8_t kItemTagMask = 0x0F;
constexpr uint8_t kItemTypeMain = 0;
constexpr uint8_t kItemTypeGlobal = 1;
constexpr uint8_t kItemTypeLocal = 2;
constexpr uint8_t kMainInput = 0x8;
constexpr uint32_t kInputConstantBit = 0x01;
constexpr uint8_t kGlobalUsagePage = 0x0;
constexpr uint8_t kGlobalLogicalMin = 0x1;
constexpr uint8_t kGlobalLogicalMax = 0x2;
constexpr uint8_t kGlobalReportSize = 0x7;
constexpr uint8_t kGlobalReportId = 0x8;
constexpr uint8_t kGlobalReportCount = 0x9;
constexpr uint8_t kGlobalPush = 0xA;
constexpr uint8_t kGlobalPop = 0xB;
constexpr uint8_t kLocalUsage = 0x0;
constexpr uint8_t kLocalUsageMin = 0x1;
constexpr uint8_t kLocalUsageMax = 0x2;
// An extended usage packs its page into the high half and its id into the low half.
constexpr uint32_t kUsagePageShift = 16;
constexpr uint32_t kUsageIdMask = 0xFFFF;

int32_t signExtend(const uint32_t v, const uint8_t bytes) {
    const bool isAlreadyFullWidth = bytes == 0 || bytes >= kBytesPerU32;
    if (isAlreadyFullWidth) return (int32_t)v;
    const uint32_t bits = bytes * kBitsPerByte;
    const uint32_t signBit = 1u << (bits - 1);
    const bool isNegative = (v & signBit) != 0;
    if (isNegative) return (int32_t)(v | ~((1u << bits) - 1u));
    return (int32_t)v;
}

uint32_t extractBits(const uint8_t* d, const size_t dlen, const uint32_t bitOff,
                     const uint8_t bits) {
    uint32_t v = 0;
    for (uint8_t i = 0; i < bits && i < kBitsPerU32; i++) {
        const uint32_t bi = bitOff + i;
        const size_t byteIndex = bi / kBitsPerByte;
        if (byteIndex >= dlen) break;
        const bool isSet = ((d[byteIndex] >> (bi % kBitsPerByte)) & 1u) != 0;
        if (isSet) v |= (1u << i);
    }
    return v;
}

// HID 1.11 §6.2.2.7 reads a field as unsigned when both logical bounds are non-negative. Like
// Linux hid-core, this decides on the minimum alone (fieldLogicalMax says why). An unsigned field
// keeps all 32 bits as magnitude, so a value above its range stays above it.
int64_t toSigned(const uint32_t raw, const uint8_t bits, const int32_t logicalMin) {
    const bool isAnUnsignedField = logicalMin >= 0;
    if (isAnUnsignedField) return raw;
    const bool isFullWidth = bits == 0 || bits >= kBitsPerU32;
    if (isFullWidth) return (int32_t)raw;
    const uint32_t signBit = 1u << (bits - 1);
    const bool isNegative = (raw & signBit) != 0;
    if (isNegative) return (int32_t)(raw | ~((1u << bits) - 1u));
    return (int32_t)raw;
}

int16_t scaleAxis16(const uint32_t raw, const HidAxis& a, const bool invert) {
    const int64_t v = toSigned(raw, a.bitSize, a.logicalMin);
    const int64_t center = ((int64_t)a.logicalMin + a.logicalMax) / 2;
    const int64_t half = ((int64_t)a.logicalMax - a.logicalMin) / 2;
    if (half <= 0) return 0;
    const int64_t scaled = (v - center) * kAxisMax / half;
    const int64_t oriented = invert ? -scaled : scaled;
    return (int16_t)std::clamp(oriented, kAxisMin, kAxisMax);
}

uint8_t scaleTrig8(const uint32_t raw, const HidAxis& a) {
    const int64_t v = toSigned(raw, a.bitSize, a.logicalMin);
    const int64_t span = (int64_t)a.logicalMax - a.logicalMin;
    if (span <= 0) return 0;
    const int64_t scaled = (v - a.logicalMin) * kTriggerMax / span;
    return (uint8_t)std::clamp<int64_t>(scaled, 0, kTriggerMax);
}

struct ButtonMapping {
    uint8_t declaredIndex;
    uint16_t xusbBit;
};

// A descriptor's buttons in declaration order.
constexpr ButtonMapping STANDARD_BUTTON_MAP[] = {
    {0, gamepad::XUSB_A},       {1, gamepad::XUSB_B},      {2, gamepad::XUSB_X},
    {3, gamepad::XUSB_Y},       {4, gamepad::XUSB_LB},     {5, gamepad::XUSB_RB},
    {6, gamepad::XUSB_BACK},    {7, gamepad::XUSB_START},  {8, gamepad::XUSB_THUMB_L},
    {9, gamepad::XUSB_THUMB_R}, {10, gamepad::XUSB_GUIDE},
};

// Switch-order HID pads declare buttons in usage row Y B A X L R ZL ZR Minus Plus L3 R3 Home
// Capture, so the mapping is by position. ZL and ZR are absent here: they fold into the triggers
// in decodeFromLayout instead.
constexpr uint8_t kSwitchOrderZlIndex = 6;
constexpr uint8_t kSwitchOrderZrIndex = 7;
constexpr ButtonMapping SWITCH_ORDER_BUTTON_MAP[] = {
    {0, gamepad::XUSB_X},        {1, gamepad::XUSB_A},      {2, gamepad::XUSB_B},
    {3, gamepad::XUSB_Y},        {4, gamepad::XUSB_LB},     {5, gamepad::XUSB_RB},
    {8, gamepad::XUSB_BACK},     {9, gamepad::XUSB_START},  {10, gamepad::XUSB_THUMB_L},
    {11, gamepad::XUSB_THUMB_R}, {12, gamepad::XUSB_GUIDE},
};

template <size_t N>
uint16_t bitForDeclaredIndex(const ButtonMapping (&mappings)[N], const uint8_t idx) {
    for (const ButtonMapping& mapping : mappings) {
        const bool isTheIndex = mapping.declaredIndex == idx;
        if (isTheIndex) return mapping.xusbBit;
    }
    return 0;
}

uint16_t buttonBit(const uint8_t idx) { return bitForDeclaredIndex(STANDARD_BUTTON_MAP, idx); }

uint16_t switchOrderButtonBit(const uint8_t idx) {
    return bitForDeclaredIndex(SWITCH_ORDER_BUTTON_MAP, idx);
}

void setAxis(HidAxis& a, const uint32_t bit, const uint32_t size, const int32_t lo,
             const int64_t hi) {
    if (a.present) return;
    a.present = true;
    a.bitOffset = (uint16_t)bit;
    a.bitSize = (uint8_t)size;
    a.logicalMin = lo;
    a.logicalMax = hi;
}

void setHat(HidLayout& out, const uint32_t bit, const uint32_t size, const int32_t lo,
            const int64_t hi) {
    if (out.hasHat) return;
    out.hasHat = true;
    out.hatBitOffset = (uint16_t)bit;
    out.hatBitSize = (uint8_t)size;
    out.hatLogicalMin = lo;
    out.hatLogicalMax = hi;
}

// Generic Desktop: X/Y the left stick, Z/Rz the right stick and Rx/Ry the triggers, matching the
// convention the fixed-offset fallback assumes.
void assignGenericDesktopUsage(HidLayout& out, const uint32_t usage, const uint32_t bit,
                               const uint32_t size, const int32_t lo, const int64_t hi) {
    switch (usage) {
    case kUsageX:
        setAxis(out.lx, bit, size, lo, hi);
        break;
    case kUsageY:
        setAxis(out.ly, bit, size, lo, hi);
        break;
    case kUsageZ:
        setAxis(out.rx, bit, size, lo, hi);
        break;
    case kUsageRz:
        setAxis(out.ry, bit, size, lo, hi);
        break;
    case kUsageRx:
        setAxis(out.lt, bit, size, lo, hi);
        break;
    case kUsageRy:
        setAxis(out.rt, bit, size, lo, hi);
        break;
    case kUsageHatSwitch:
        setHat(out, bit, size, lo, hi);
        break;
    default:
        break;
    }
}

void assignSimulationUsage(HidLayout& out, const uint32_t usage, const uint32_t bit,
                           const uint32_t size, const int32_t lo, const int64_t hi) {
    switch (usage) {
    case kUsageBrake:
        setAxis(out.lt, bit, size, lo, hi);
        break;
    case kUsageAccelerator:
        setAxis(out.rt, bit, size, lo, hi);
        break;
    default:
        break;
    }
}

void assignUsage(HidLayout& out, const uint32_t page, const uint32_t usage, const uint32_t bit,
                 const uint32_t size, const int32_t lo, const int64_t hi) {
    switch (page) {
    case kUsagePageGenericDesktop:
        assignGenericDesktopUsage(out, usage, bit, size, lo, hi);
        break;
    case kUsagePageSimulation:
        assignSimulationUsage(out, usage, bit, size, lo, hi);
        break;
    default:
        break;
    }
}

// One item off the descriptor stream. A long item carries no data this parser understands, so it
// is reported with `skip` set and its payload stepped over.
struct HidItem {
    uint8_t type;
    uint8_t tag;
    uint32_t data;
    uint8_t dataLen;
    bool skip;
    bool truncated;
};

HidItem readLongItem(const uint8_t* desc, const size_t len, size_t& i) {
    HidItem item = {0, 0, 0, 0, false, false};
    if (i >= len) {
        item.truncated = true;
        return item;
    }
    const uint8_t payload = desc[i];
    i += kLongItemHeaderBytes + payload;
    item.skip = true;
    return item;
}

// bSize 3 means 4 bytes, the one size that is not its own encoding.
HidItem readShortItem(const uint8_t* desc, const size_t len, const uint8_t prefix, size_t& i) {
    HidItem item = {0, 0, 0, 0, false, false};
    const uint8_t bSize = prefix & kItemSizeMask;
    item.dataLen = bSize == kItemSizeFourBytes ? kBytesPerU32 : bSize;
    item.type = (prefix >> kItemTypeShift) & kItemTypeMask;
    item.tag = (prefix >> kItemTagShift) & kItemTagMask;
    if (i + item.dataLen > len) {
        item.truncated = true;
        return item;
    }
    for (uint8_t k = 0; k < item.dataLen; k++) {
        item.data |= (uint32_t)desc[i + k] << (kBitsPerByte * k);
    }
    i += item.dataLen;
    return item;
}

HidItem readHidItem(const uint8_t* desc, const size_t len, size_t& i) {
    const uint8_t prefix = desc[i++];
    const bool isLongItem = prefix == kLongItemPrefix;
    if (isLongItem) return readLongItem(desc, len, i);
    return readShortItem(desc, len, prefix, i);
}

// A Usage (or Usage Minimum) item as declared. Four data bytes make it an extended usage that
// carries its own page; any other size names an id on whichever Usage Page is current when the
// Main item arrives (HID 1.11 §6.2.2.8).
struct DeclaredUsage {
    uint32_t value;
    bool isExtended;
};

DeclaredUsage declareUsage(const HidItem& item) {
    const bool isExtended = item.dataLen == kBytesPerU32;
    return DeclaredUsage{item.data, isExtended};
}

struct Usage {
    uint32_t page;
    uint32_t id;
};

Usage resolveUsage(const DeclaredUsage& declared, const uint32_t currentPage) {
    if (declared.isExtended) {
        return Usage{declared.value >> kUsagePageShift, declared.value & kUsageIdMask};
    }
    return Usage{currentPage, declared.value};
}

// The global item state table: what Push saves and Pop restores (HID 1.11 §6.2.2.7).
struct HidGlobals {
    uint32_t usagePage;
    uint32_t reportSize;
    uint32_t reportCount;
    int32_t logMin;
    // The Logical Maximum as its item's bytes, zero-extended: fieldLogicalMax reads its sign.
    uint32_t logMaxData;
    uint8_t logMaxBytes;
    uint8_t currentReportId;
};

// The global and local item state the stream accumulates until a Main item consumes it.
struct HidParseState {
    HidGlobals globals;
    HidGlobals pushedGlobals[HID_GLOBAL_STACK_DEPTH];
    size_t pushedCount;
    // A field belongs to the report its Report ID names (HID 1.11 §6.2.2.7), so an id that comes
    // back continues its report where that report's last field ended.
    uint32_t bitCursorByReportId[kReportIdCount];
    bool locked;
    uint8_t lockedReportId;
    DeclaredUsage usages[kMaxUsages];
    size_t usageCount;
    DeclaredUsage usageMin;
    bool haveRange;
};

// A Push past the stack's depth has nowhere to go, so the stream is no longer one this parser can
// follow.
bool pushGlobals(HidParseState& st) {
    const bool isFull = st.pushedCount == HID_GLOBAL_STACK_DEPTH;
    if (isFull) return false;
    st.pushedGlobals[st.pushedCount++] = st.globals;
    return true;
}

// A Pop with nothing pushed is a malformed stream.
bool popGlobals(HidParseState& st) {
    const bool isEmpty = st.pushedCount == 0;
    if (isEmpty) return false;
    st.globals = st.pushedGlobals[--st.pushedCount];
    return true;
}

// False when the item leaves the stream malformed and the parse has to end.
bool applyGlobalItem(const HidItem& item, HidParseState& st) {
    HidGlobals& g = st.globals;
    switch (item.tag) {
    case kGlobalUsagePage:
        g.usagePage = item.data;
        return true;
    case kGlobalLogicalMin:
        g.logMin = signExtend(item.data, item.dataLen);
        return true;
    case kGlobalLogicalMax:
        g.logMaxData = item.data;
        g.logMaxBytes = item.dataLen;
        return true;
    case kGlobalReportSize:
        g.reportSize = item.data;
        return true;
    case kGlobalReportId:
        g.currentReportId = (uint8_t)item.data;
        return true;
    case kGlobalReportCount:
        g.reportCount = item.data;
        return true;
    case kGlobalPush:
        return pushGlobals(st);
    case kGlobalPop:
        return popGlobals(st);
    default:
        return true;
    }
}

void applyLocalItem(const HidItem& item, HidParseState& st) {
    switch (item.tag) {
    case kLocalUsage:
        if (st.usageCount < kMaxUsages) st.usages[st.usageCount++] = declareUsage(item);
        break;
    case kLocalUsageMin:
        st.usageMin = declareUsage(item);
        st.haveRange = true;
        break;
    case kLocalUsageMax:
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
    const uint32_t count = std::min<uint32_t>(st.globals.reportCount, kMaxButtons);
    out.buttonCount = (uint8_t)count;
}

bool namesAUsage(const HidParseState& st) { return st.haveRange || st.usageCount > 0; }

// The usage field f of an Input names, when the Input names any: a usage range counts up from its
// minimum on the minimum's page; a usage list names the first fields and its last entry stands for
// the rest.
Usage fieldUsage(const HidParseState& st, const uint32_t f) {
    const uint32_t currentPage = st.globals.usagePage;
    if (st.haveRange) {
        const Usage first = resolveUsage(st.usageMin, currentPage);
        return Usage{first.page, first.id + f};
    }
    const size_t listed = std::min<size_t>(f, st.usageCount - 1);
    return resolveUsage(st.usages[listed], currentPage);
}

// An Input that names no usage has its fields on the current page.
uint32_t firstFieldPage(const HidParseState& st) {
    if (!namesAUsage(st)) return st.globals.usagePage;
    return fieldUsage(st, 0).page;
}

// A field whose minimum is non-negative is unsigned, and so is its maximum: a common descriptor bug
// writes Logical Maximum 255 as the one-byte 0x25 0xFF, which sign-extends to -1. Linux reads the
// maximum the same way (hid-core.c hid_parser_global: item_udata unless logical_minimum < 0), but
// at the Logical Maximum item; this reads it against the minimum in force at the Input.
int64_t fieldLogicalMax(const HidGlobals& g) {
    const bool isAnUnsignedField = g.logMin >= 0;
    if (isAnUnsignedField) return g.logMaxData;
    return signExtend(g.logMaxData, g.logMaxBytes);
}

void takeAxisFields(const HidParseState& st, const uint32_t startBit, HidLayout& out) {
    if (!namesAUsage(st)) return;
    const HidGlobals& g = st.globals;
    const int64_t logMax = fieldLogicalMax(g);
    for (uint32_t f = 0; f < g.reportCount; f++) {
        const Usage usage = fieldUsage(st, f);
        assignUsage(out, usage.page, usage.id, startBit + f * g.reportSize, g.reportSize, g.logMin,
                    logMax);
    }
}

// The first non-constant Input item locks the report id this layout describes.
void applyInputItem(const HidItem& item, HidParseState& st, HidLayout& out) {
    const HidGlobals& g = st.globals;
    uint32_t& bitCursor = st.bitCursorByReportId[g.currentReportId];
    const uint32_t startBit = bitCursor;
    bitCursor += g.reportSize * g.reportCount;

    const bool isConstantPadding = (item.data & kInputConstantBit) != 0;
    const bool carriesFields = g.reportSize > 0 && g.reportCount > 0;
    if (isConstantPadding || !carriesFields) return;

    if (!st.locked) {
        st.locked = true;
        st.lockedReportId = g.currentReportId;
        out.reportId = g.currentReportId;
    }
    const bool isTheLockedReport = g.currentReportId == st.lockedReportId;
    if (!isTheLockedReport) return;

    const bool isButtonPage = firstFieldPage(st) == kUsagePageButton;
    if (isButtonPage) {
        takeButtonField(st, startBit, out);
        return;
    }
    takeAxisFields(st, startBit, out);
}

void clearLocalItems(HidParseState& st) {
    st.usageCount = 0;
    st.haveRange = false;
    st.usageMin = DeclaredUsage{0, false};
}

// A Main item consumes whatever the Global and Local items have accumulated and then clears the
// Local ones; only an Input main item carries fields this parser wants. False when the item leaves
// the stream malformed and the parse has to end.
bool applyItem(const HidItem& item, HidParseState& st, HidLayout& out) {
    const bool isMainItem = item.type == kItemTypeMain;
    if (isMainItem) {
        const bool isInputItem = item.tag == kMainInput;
        if (isInputItem) applyInputItem(item, st, out);
        clearLocalItems(st);
        return true;
    }
    const bool isGlobalItem = item.type == kItemTypeGlobal;
    if (isGlobalItem) return applyGlobalItem(item, st);
    const bool isLocalItem = item.type == kItemTypeLocal;
    if (isLocalItem) applyLocalItem(item, st);
    return true;
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
    const int64_t dir = (int64_t)raw - L.hatLogicalMin;
    const int64_t range = L.hatLogicalMax - L.hatLogicalMin;
    const bool isInsideTheDeclaredRange = dir >= 0 && dir <= range;
    if (!isInsideTheDeclaredRange) return 0;
    return gamepad::hatDirectionBits(dir);
}

bool layoutButtonIsDown(const uint8_t* d, const size_t dlen, const HidLayout& L, const uint8_t i) {
    return extractBits(d, dlen, (uint32_t)L.buttonBitOffset + i, 1) != 0;
}

uint16_t decodeSwitchOrderButtons(const uint8_t* d, const size_t dlen, const HidLayout& L,
                                  DeviceState& s) {
    uint16_t buttons = 0;
    bool zl = false;
    bool zr = false;
    for (uint8_t i = 0; i < L.buttonCount; i++) {
        if (!layoutButtonIsDown(d, dlen, L, i)) continue;
        if (i == kSwitchOrderZlIndex) {
            zl = true;
        } else if (i == kSwitchOrderZrIndex) {
            zr = true;
        } else {
            buttons = (uint16_t)(buttons | switchOrderButtonBit(i));
        }
    }
    s.bLT = zl ? kTriggerFull : 0;
    s.bRT = zr ? kTriggerFull : 0;
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

bool parseReportDescriptor(const uint8_t* desc, const size_t len, HidLayout& out) {
    out = HidLayout{};
    HidParseState st = {};

    size_t i = 0;
    while (i < len) {
        const HidItem item = readHidItem(desc, len, i);
        if (item.truncated) break;
        if (item.skip) continue;
        const bool isStillWellFormed = applyItem(item, st, out);
        if (!isStillWellFormed) break;
    }

    const bool hasAStick = out.lx.present || out.ly.present;
    const bool hasButtons = out.buttonCount > 0;
    out.valid = hasAStick || hasButtons || out.hasHat;
    return out.valid;
}

bool decodeFromLayout(const uint8_t* buf, const size_t len, DeviceState& s, const HidLayout& L) {
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

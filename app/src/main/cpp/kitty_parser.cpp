// kitty_parser.cpp
// State-machine implementation separating text from Kitty graphics protocol payloads.

#include "kitty_parser.h"

#include <android/log.h>
#include <sstream>
#include <unordered_map>

#define LOG_TAG "KittyParser"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ── Base64 decode table ─────────────────────────────────────────────────────
// All 256 entries are explicit.
// -1  = not a valid base64 character (skip)
// '=' = padding — must be -1, not 0 (otherwise padded strings get a corrupt trailing byte)
// Entries 128–255 are -1 (non-ASCII bytes are never valid base64).

static const int8_t kB64Table[256] = {
    // 0x00–0x1F (control characters)
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    // 0x20–0x2F  ' '  !   "   #   $   %   &   '   (   )   *   +   ,   -   .   /
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
    // 0x30–0x3F  0–9   :   ;   <   =   >   ?
    52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-1,-1,-1,
    // 0x40–0x4F  @   A–N
    -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
    // 0x50–0x5F  O–Z   [   \   ]   ^   _
    15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
    // 0x60–0x6F  `   a–n
    -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
    // 0x70–0x7F  o–z   {   |   }   ~  DEL
    41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
    // 0x80–0xFF  (non-ASCII — all invalid)
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
    -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
};

static std::vector<uint8_t> base64_decode(const std::vector<uint8_t>& in) {
    std::vector<uint8_t> out;
    out.reserve(in.size() * 3 / 4);
    int val = 0, bits = -8;
    for (uint8_t c : in) {
        int d = (c < 256) ? kB64Table[c] : -1;
        if (d == -1) continue;
        val = (val << 6) + d;
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<uint8_t>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

// ── KittyParser ────────────────────────────────────────────────────────────

KittyParser::KittyParser(MediaFrameCallback media_cb, TextCallback text_cb)
    : m_media_cb(std::move(media_cb))
    , m_text_cb(std::move(text_cb))
{}

void KittyParser::reset() {
    m_state = State::Normal;
    m_text_buf.clear();
    m_header_buf.clear();
    m_payload_buf.clear();
}

void KittyParser::feed(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; ++i) {
        uint8_t byte = data[i];

        switch (m_state) {

        case State::Normal:
            if (byte == 0x1B) {
                flush_text();
                m_state = State::Escape;
            } else {
                m_text_buf.push_back(byte);
            }
            break;

        case State::Escape:
            if (byte == '_') {
                m_state = State::APC_Start;
            } else {
                // Not a Kitty APC — emit ESC + this byte as plain text
                m_text_buf.push_back(0x1B);
                m_text_buf.push_back(byte);
                m_state = State::Normal;
            }
            break;

        case State::APC_Start:
            if (byte == 'G') {
                m_header_buf.clear();
                m_payload_buf.clear();
                m_state = State::APC_G;
            } else {
                // Not a Kitty sequence — discard
                m_state = State::Normal;
            }
            break;

        case State::APC_G:
            // Accumulate key=value header until ';'
            if (byte == ';') {
                m_state = State::APC_Data;
            } else {
                m_header_buf += static_cast<char>(byte);
            }
            break;

        case State::APC_Data:
            if (byte == 0x1B) {
                m_state = State::APC_ST_ESC;
            } else {
                m_payload_buf.push_back(byte);
            }
            break;

        case State::APC_ST_ESC:
            if (byte == '\\') {
                // String Terminator received — complete APC sequence
                parse_header_and_emit();
                m_state = State::Normal;
            } else {
                // False alarm — add ESC + byte to payload
                m_payload_buf.push_back(0x1B);
                m_payload_buf.push_back(byte);
                m_state = State::APC_Data;
            }
            break;

        default:
            m_state = State::Normal;
            break;
        }
    }

    // Flush any remaining plain text
    if (m_state == State::Normal) flush_text();
}

void KittyParser::flush_text() {
    if (!m_text_buf.empty()) {
        m_text_cb(m_text_buf.data(), m_text_buf.size());
        m_text_buf.clear();
    }
}

void KittyParser::parse_header_and_emit() {
    KittyPayload payload = parse_header(m_header_buf);
    payload.data = base64_decode(m_payload_buf);
    LOGD("Kitty frame: id=%d fmt=%d cols=%d rows=%d data=%zu bytes",
         payload.image_id, payload.format,
         payload.cols, payload.rows, payload.data.size());
    m_media_cb(payload);
}

KittyPayload KittyParser::parse_header(const std::string& header) {
    KittyPayload p;
    // Header format: "a=t,f=32,i=1,c=80,r=24"
    std::unordered_map<char, int> kv;
    std::istringstream ss(header);
    std::string token;
    while (std::getline(ss, token, ',')) {
        if (token.size() >= 3 && token[1] == '=') {
            try { kv[token[0]] = std::stoi(token.substr(2)); }
            catch (...) {}
        }
    }
    auto get = [&](char k) { auto it = kv.find(k); return it != kv.end() ? it->second : 0; };
    p.action       = get('a');
    p.format       = get('f');
    p.medium       = get('m');
    p.image_id     = get('i');
    p.placement_id = get('q');
    p.cols         = get('c');
    p.rows         = get('r');
    p.x_offset     = get('X');
    p.y_offset     = get('Y');
    return p;
}

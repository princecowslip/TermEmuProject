// kitty_parser.h
// State-tracking structures and interface for the Kitty Graphics Protocol parser.

#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

/**
 * KittyPayload
 *
 * Populated by the parser whenever a complete Kitty APC sequence is decoded.
 *
 * Fields map to the Kitty graphics protocol specification:
 *   https://sw.kovidgoyal.net/kitty/graphics-protocol/
 */
struct KittyPayload {
    // Control keys from the APC header
    int    action      = 0;   // 'a': t=transmit, p=put, q=query, d=delete
    int    format      = 0;   // 'f': 32=RGBA, 24=RGB, 100=PNG
    int    medium      = 0;   // 'm': more chunks follow if 1
    int    image_id    = 0;   // 'i': unique image identifier
    int    placement_id= 0;   // 'q': placement identifier
    int    cols        = 0;   // 'c': display width in cells
    int    rows        = 0;   // 'r': display height in cells
    int    x_offset    = 0;   // 'X': pixel x offset within source image
    int    y_offset    = 0;   // 'Y': pixel y offset within source image

    // Raw base64-encoded pixel payload
    std::vector<uint8_t> data;
};

/** Callback types */
using MediaFrameCallback = std::function<void(const KittyPayload&)>;
using TextCallback       = std::function<void(const uint8_t*, size_t)>;

/**
 * KittyParser
 *
 * Byte-by-byte state machine that reads a mixed terminal stream and separates:
 *  - Plain text / ANSI escape sequences  → delivered via text_cb
 *  - Kitty APC graphics payloads         → decoded and delivered via media_cb
 *
 * The APC escape begins with ESC_G (\033_G) and is terminated by ST (\033\\).
 */
class KittyParser {
public:
    KittyParser(MediaFrameCallback media_cb, TextCallback text_cb);

    /**
     * Feed raw bytes from the PTY stream into the parser.
     * Thread-safe if called from a single reader thread.
     */
    void feed(const uint8_t* data, size_t len);

    /** Reset parser state (e.g. on session restart). */
    void reset();

private:
    enum class State {
        Normal,       // Passing through plain text
        Escape,       // Received ESC (0x1B)
        APC_Start,    // Received ESC + '_'
        APC_G,        // Received ESC + '_' + 'G' — accumulates key=value header until ';'
        APC_Data,     // Accumulating base64 payload
        APC_ST_ESC,   // Received ESC inside APC (possible ST start)
    };

    State               m_state{State::Normal};
    std::vector<uint8_t> m_text_buf;
    std::string          m_header_buf;
    std::vector<uint8_t> m_payload_buf;

    MediaFrameCallback m_media_cb;
    TextCallback       m_text_cb;

    void flush_text();
    void parse_header_and_emit();
    KittyPayload parse_header(const std::string& header);
};

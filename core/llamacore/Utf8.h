//
// UTF-8 helpers for text streamed out of the engine.
//

#ifndef LMPLAYGROUND_UTF8_H
#define LMPLAYGROUND_UTF8_H

#include <string>

// Drop a trailing incomplete UTF-8 sequence. Streamed text can end mid
// character; both bridges reject invalid UTF-8 (JNI NewStringUTF crashes on
// it, NSString returns nil), so the partial bytes wait for the next callback.
inline void utf8TrimIncompleteTail(std::string &s) {
    while (!s.empty()) {
        unsigned char last = s.back();
        if ((last & 0x80) == 0) break;       // ASCII — complete
        if ((last & 0xC0) == 0xC0) {          // Start byte without continuation
            s.pop_back();
            break;
        }
        // Continuation byte — count how many
        size_t pos = s.size() - 1;
        while (pos > 0 && (((unsigned char)s[pos]) & 0xC0) == 0x80) pos--;
        unsigned char start = s[pos];
        int expected;
        if ((start & 0xE0) == 0xC0) expected = 2;
        else if ((start & 0xF0) == 0xE0) expected = 3;
        else if ((start & 0xF8) == 0xF0) expected = 4;
        else { s.resize(pos); break; }
        int actual = (int)(s.size() - pos);
        if (actual < expected) s.resize(pos);
        break;
    }
}

#endif // LMPLAYGROUND_UTF8_H

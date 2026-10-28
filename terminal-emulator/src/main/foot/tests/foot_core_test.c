// Standalone smoke test for the headless foot adapter.
//
// Build with -DTERMUX_FOOT_BUILD_TESTS=ON using the Android NDK toolchain and
// run the resulting executable inside Termux (or on a compatible device). It
// exercises the same code the JNI library uses, without needing Android's JVM.

#include "headless.h"

#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures;

#define CHECK(condition)                                                        \
    do {                                                                        \
        if (!(condition)) {                                                     \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
            failures++;                                                         \
        }                                                                       \
    } while (0)

static int32_t palette[259];

static void
init_palette(void)
{
    static const int32_t base[16] = {
        0xff000000, 0xffcd0000, 0xff00cd00, 0xffcdcd00,
        0xff0000ee, 0xffcd00cd, 0xff00cdcd, 0xffe5e5e5,
        0xff7f7f7f, 0xffff0000, 0xff00ff00, 0xffffff00,
        0xff5c5cff, 0xffff00ff, 0xff00ffff, 0xffffffff,
    };
    for (int i = 0; i < 16; i++)
        palette[i] = base[i];
    for (int i = 16; i < 232; i++) {
        int r = (i - 16) / 36;
        int g = ((i - 16) / 6) % 6;
        int b = (i - 16) % 6;
        palette[i] = (int32_t)(0xff000000u |
            ((r ? r * 40 + 55 : 0) << 16) |
            ((g ? g * 40 + 55 : 0) << 8) |
            (b ? b * 40 + 55 : 0));
    }
    for (int i = 232; i < 256; i++) {
        int value = (i - 232) * 10 + 8;
        palette[i] = (int32_t)(0xff000000u | (value << 16) | (value << 8) | value);
    }
    palette[256] = 0xffd0d0d0;
    palette[257] = 0xff101010;
    palette[258] = 0xff00ff00;
}

static int
feed(struct headless_terminal *ht, const char *text)
{
    int dirty = 0;
    int *rows = headless_append(ht, (const uint8_t *)text, strlen(text), &dirty);
    free(rows);
    return dirty;
}

static int32_t *
row(struct headless_terminal *ht, int external_row, int *count)
{
    return headless_row(ht, external_row, count);
}

static char *
row_utf8(struct headless_terminal *ht, int external_row)
{
    int count = 0;
    int32_t *encoded = row(ht, external_row, &count);
    if (encoded == NULL || count < 3) {
        free(encoded);
        return strdup("");
    }
    const int units = encoded[2];
    char *out = malloc((size_t)units * 4 + 1);
    if (out == NULL) {
        free(encoded);
        return strdup("");
    }
    size_t o = 0;
    for (int i = 0; i < units; i++) {
        uint32_t cp = (uint32_t)encoded[3 + i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < units) {
            uint32_t low = (uint32_t)encoded[4 + i];
            if (low >= 0xdc00 && low <= 0xdfff) {
                cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
                i++;
            }
        }
        if (cp < 0x80) {
            out[o++] = (char)cp;
        } else if (cp < 0x800) {
            out[o++] = (char)(0xc0 | (cp >> 6));
            out[o++] = (char)(0x80 | (cp & 0x3f));
        } else if (cp < 0x10000) {
            out[o++] = (char)(0xe0 | (cp >> 12));
            out[o++] = (char)(0x80 | ((cp >> 6) & 0x3f));
            out[o++] = (char)(0x80 | (cp & 0x3f));
        } else {
            out[o++] = (char)(0xf0 | (cp >> 18));
            out[o++] = (char)(0x80 | ((cp >> 12) & 0x3f));
            out[o++] = (char)(0x80 | ((cp >> 6) & 0x3f));
            out[o++] = (char)(0x80 | (cp & 0x3f));
        }
    }
    out[o] = '\0';
    free(encoded);
    return out;
}

static bool
events_contain(struct headless_terminal *ht, const char *needle)
{
    char *events = headless_drain_events(ht);
    bool found = events != NULL && strstr(events, needle) != NULL;
    free(events);
    return found;
}

static bool
has_text_fg(const int32_t *commands, int count, int wanted)
{
    int cursor = 0;
    while (cursor < count) {
        const int op = commands[cursor++];
        if (op == 0)
            break;
        if (op == 1) {
            cursor++;
        } else if (op == 2) {
        } else if (op == 3 || op == 5) {
            cursor += 4;
        } else if (op == 4) {
            cursor += 4; /* start char, count, start col, width */
            const int fg = commands[cursor++];
            cursor += 2; /* background and effects */
            if (fg == wanted)
                return true;
        } else {
            break;
        }
    }
    return false;
}

int
main(void)
{
    init_palette();
    struct headless_terminal *ht = headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;

    /* Wide characters and their column map. A row snapshot always covers the
     * full width, so trailing blank cells show up as spaces. */
    feed(ht, "A\xe7\x95\x8c" "B");
    int count = 0;
    int32_t *encoded = row(ht, 0, &count);
    CHECK(encoded != NULL && encoded[2] == 9); /* A, wide char, B + 6 blanks */
    if (encoded != NULL && encoded[2] == 9) {
        const int units = encoded[2];
        CHECK(count == 3 + units + 11 + 10 * 2);
        CHECK(encoded[3] == 'A');
        CHECK(encoded[4] == 0x754c);
        CHECK(encoded[5] == 'B');
        CHECK(encoded[6] == ' ' && encoded[11] == ' ');
        CHECK(encoded[3 + units + 0] == 0); /* column 0 */
        CHECK(encoded[3 + units + 1] == 1); /* column 1 */
        CHECK(encoded[3 + units + 2] == 1); /* spacer maps to the wide glyph */
        CHECK(encoded[3 + units + 3] == 2); /* column 3, B */
        CHECK(encoded[3 + units + 3 + 7] == 9); /* sentinel after the last column */
        const int style_offset = 3 + units + 11;
        CHECK(encoded[style_offset] == (int32_t)0x01010000u); /* default bg 257 */
        CHECK(encoded[style_offset + 1] == 256 << 8); /* default fg */
        CHECK(encoded[style_offset + 5] == 256 << 8); /* wide glyph's second column */
    }
    free(encoded);
    CHECK(headless_cursor_col(ht) == 4);

    /* Combining character is stored as composed text in one cell. */
    feed(ht, "\r\ne\xcc\x81");
    char *text = row_utf8(ht, 1);
    CHECK(strncmp(text, "e\xcc\x81", 3) == 0);
    free(text);

    /* Emoji is a surrogate pair in the row snapshot. */
    feed(ht, "\r\n\xf0\x9f\x98\x80");
    encoded = row(ht, 2, &count);
    CHECK(encoded != NULL && encoded[2] == 10); /* surrogate pair + 8 blanks */
    if (encoded != NULL && encoded[2] == 10) {
        const int units = encoded[2];
        CHECK(encoded[3] == 0xd83d && encoded[4] == 0xde00);
        CHECK(encoded[3 + units + 0] == 0);
        CHECK(encoded[3 + units + 1] == 0); /* spacer maps to the emoji */
        CHECK(encoded[3 + units + 2] == 2); /* following blank column */
    }
    free(encoded);

    /* SGR colours, bold brightening and the render ABI. */
    feed(ht, "\r\n\x1b[31mR\x1b[0m");
    int command_count = 0;
    int32_t *commands = headless_render(
        ht, 0, -1, -1, 0, 0, true, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL);
    CHECK(has_text_fg(commands, command_count, palette[1]));
    free(commands);
    encoded = row(ht, 3, &count);
    CHECK(encoded != NULL);
    if (encoded != NULL) {
        const int style_offset = 3 + encoded[2] + 11;
        CHECK(encoded[style_offset] == (int32_t)0x01010000u);
        CHECK(encoded[style_offset + 1] == 1 << 8);
    }
    free(encoded);

    feed(ht, " \x1b[1;31mB\x1b[0m");
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, true, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL);
    CHECK(has_text_fg(commands, command_count, palette[9]));
    free(commands);
    encoded = row(ht, 3, &count);
    CHECK(encoded != NULL);
    if (encoded != NULL) {
        const int style_offset = 3 + encoded[2] + 11;
        CHECK((encoded[style_offset + 4] & 1) != 0); /* bold */
        CHECK(encoded[style_offset + 5] == 1 << 8);  /* red; bold brightens at render time */
    }
    free(encoded);

    /* Selection and transcript extraction. */
    char *selected = headless_selected_text(ht, 0, 0, 9, 0, true, false);
    CHECK(selected != NULL && strstr(selected, "A\xe7\x95\x8c" "B") != NULL);
    free(selected);
    char *transcript = headless_transcript_text(ht, 0);
    CHECK(transcript != NULL && strstr(transcript, "A\xe7\x95\x8c" "B") != NULL);
    free(transcript);

    /* OSC title, OSC 52 clipboard copy, bell and a DSR response. */
    feed(ht, "\x1b]0;hello\x07\x1b]52;c;SGVsbG8=\x07\x07\x1b[6n");
    char *events = headless_drain_events(ht);
    CHECK(events != NULL && strstr(events, "title\taGVsbG8=") != NULL);
    CHECK(events != NULL && strstr(events, "clipboard_store\tSGVsbG8=") != NULL);
    CHECK(events != NULL && strstr(events, "bell") != NULL);
    CHECK(events != NULL && strstr(events, "write\t") != NULL);
    free(events);

    /* Kitty notification completion: notify_free() must leave standard
     * descriptors alone even though an unused icon_fd defaults to zero. */
    const int stdin_flags = fcntl(0, F_GETFD);
    feed(ht, "\x1b]99;i=test:d=1;Hello\x07");
    events = headless_drain_events(ht);
    CHECK(events != NULL && strstr(events, "notification\t") != NULL);
    CHECK(fcntl(0, F_GETFD) == stdin_flags);
    free(events);

    /* Mode bits and cursor style. */
    feed(ht, "\x1b[?1h\x1b[?1000h\x1b[?1006h\x1b[?2004h\x1b[4 q");
    const int modes = headless_mode_bits(ht);
    CHECK((modes & (1 << 0)) != 0);
    CHECK((modes & (1 << 6)) != 0);
    CHECK((modes & (1 << 9)) != 0);
    CHECK((modes & (1 << 10)) != 0);
    CHECK(headless_cursor_style(ht) == 1);

    /* Alternate screen restores the main grid. */
    feed(ht, "\x1b[?1049h");
    CHECK(headless_is_alternate(ht));
    CHECK(headless_history_size(ht) == 0);
    feed(ht, "\x1b[H" "ALT");
    text = row_utf8(ht, 0);
    CHECK(strncmp(text, "ALT", 3) == 0);
    free(text);
    feed(ht, "\x1b[?1049l");
    CHECK(!headless_is_alternate(ht));
    text = row_utf8(ht, 0);
    CHECK(strncmp(text, "A\xe7\x95\x8c" "B", 5) == 0);
    free(text);

    /* OSC 4 dynamic colours and OSC 104 reset. */
    feed(ht, "\x1b]4;1;rgb:12/34/56\x07");
    int32_t current[259];
    headless_palette(ht, current);
    CHECK(current[1] == 0xff123456);
    CHECK(events_contain(ht, "colors"));
    feed(ht, "\x1b]104\x07");
    headless_palette(ht, current);
    CHECK(current[1] == palette[1]);

    /* Scrollback, anchors, and history snapshot. */
    char lines[4096];
    lines[0] = '\0';
    for (int i = 0; i < 80; i++)
        strcat(lines, "line\r\n");
    feed(ht, lines);
    const int history = headless_history_size(ht);
    CHECK(history > 0 && history <= 20);
    CHECK(headless_scroll_counter(ht) > 0);
    const int anchor = headless_anchor_row(ht, 0);
    CHECK(anchor == (int)headless_absolute_scroll_count(ht));
    headless_clear_scroll_counter(ht);
    CHECK(headless_scroll_counter(ht) == 0);
    text = row_utf8(ht, -1);
    CHECK(text != NULL);
    free(text);

    /* Reflow preserves text and keeps the grid consistent. */
    headless_resize(ht, 20, 5, 8, 16);
    CHECK(headless_columns(ht) == 20 && headless_rows(ht) == 5);
    const int resized_history = headless_history_size(ht);
    CHECK(resized_history >= 0 && resized_history <= 20);
    text = row_utf8(ht, 0);
    CHECK(text != NULL);
    free(text);
    headless_resize(ht, 10, 5, 8, 16);
    CHECK(headless_columns(ht) == 10);

    headless_destroy(ht);

    /* Row snapshots and selection around wide glyphs (the Java facade uses
     * findStartOfColumn() on these tables while dragging selection cursors). */
    ht = headless_create(10, 3, 6, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;

    feed(ht, "A\xe7\x95\x8c" "B");
    selected = headless_selected_text(ht, 2, 0, 2, 0, true, false);
    CHECK(selected != NULL && strcmp(selected, "\xe7\x95\x8c") == 0);
    free(selected);
    selected = headless_selected_text(ht, 1, 0, 2, 0, true, false);
    CHECK(selected != NULL && strcmp(selected, "\xe7\x95\x8c") == 0);
    free(selected);
    selected = headless_selected_text(ht, 0, 0, 2, 0, true, false);
    CHECK(selected != NULL && strcmp(selected, "A\xe7\x95\x8c") == 0);
    free(selected);

    /* Bold brightening applies to palette indexes 0..7 only, not to arbitrary
     * 256-colour indexes that happen to have the same low nibble. */
    feed(ht, "\r\n\x1b[1;38;5;16mX");
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3, &command_count);
    CHECK(commands != NULL);
    CHECK(has_text_fg(commands, command_count, palette[16]));
    CHECK(!has_text_fg(commands, command_count, palette[24]));
    free(commands);

    /* A blank row still has the identity column map. */
    encoded = row(ht, -1, &count);
    CHECK(encoded != NULL && encoded[2] == 10);
    if (encoded != NULL && encoded[2] == 10) {
        CHECK(encoded[3] == ' ');
        CHECK(encoded[3 + 10 + 0] == 0);
        CHECK(encoded[3 + 10 + 9] == 9);
        CHECK(encoded[3 + 10 + 10] == 10);
    }
    free(encoded);

    /* The display list references the UTF-16 payload within bounds. */
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3, &command_count);
    CHECK(commands != NULL);
    int render_text_length = 0;
    int32_t *render_text = headless_render_text(ht, &render_text_length);
    CHECK(render_text != NULL && render_text_length >= 3);
    if (commands != NULL && render_text != NULL) {
        int cursor = 0;
        bool saw_a = false, saw_wide = false, saw_b = false;
        bool structure_ok = true;
        int row_begins = 0;
        while (cursor < command_count) {
            const int op = commands[cursor++];
            if (op == 0)
                break;
            if (op == 1) {
                row_begins++;
                cursor += 1;
            } else if (op == 2) {
            } else if (op == 3) {
                cursor += 4;
            } else if (op == 5) {
                cursor += 4;
            } else if (op == 4) {
                const int start_char = commands[cursor++];
                const int char_count = commands[cursor++];
                const int start_column = commands[cursor++];
                const int width_columns = commands[cursor++];
                cursor += 3; /* fg, bg, effects */
                if (start_char < 0 || char_count <= 0 ||
                    start_char + char_count > render_text_length ||
                    start_column < 0 || start_column + width_columns > 10) {
                    structure_ok = false;
                }
                for (int i = 0; i < char_count; i++) {
                    const int32_t unit = render_text[start_char + i];
                    if (unit == 'A') saw_a = true;
                    else if (unit == 0x754c) saw_wide = true;
                    else if (unit == 'B') saw_b = true;
                }
            } else {
                structure_ok = false;
                break;
            }
        }
        CHECK(structure_ok);
        CHECK(row_begins == 3);
        CHECK(saw_a && saw_wide && saw_b);
    }
    free(render_text);
    free(commands);

    /* Word lookup follows the Rust implementation (space delimited). */
    headless_resize(ht, 20, 3, 8, 16);
    feed(ht, "\x1b[2J\x1b[H" "hello world");
    char *word = headless_word_at_location(ht, 1, 0);
    CHECK(word != NULL && strcmp(word, "hello") == 0);
    free(word);
    word = headless_word_at_location(ht, 7, 0);
    CHECK(word != NULL && strcmp(word, "world") == 0);
    free(word);
    word = headless_word_at_location(ht, 5, 0);
    CHECK(word != NULL && strcmp(word, "hello") == 0);
    free(word);

    /* Transcript joins rows with newlines and trims the tail. */
    feed(ht, "\x1b[2J\x1b[H" "hello\r\nworld");
    transcript = headless_transcript_text(ht, 1);
    CHECK(transcript != NULL && strcmp(transcript, "hello\nworld") == 0);
    free(transcript);

    headless_destroy(ht);

    /* Wrapped rows: line_wrap is true only on the row that continues, and the
     * join flags in selected_text()/transcript mode must honour it. */
    ht = headless_create(5, 3, 6, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    feed(ht, "abcdefgh");
    encoded = row(ht, 0, &count);
    CHECK(encoded != NULL && encoded[1] == 1);
    free(encoded);
    encoded = row(ht, 1, &count);
    CHECK(encoded != NULL && encoded[1] == 0);
    free(encoded);
    selected = headless_selected_text(ht, 0, 0, 4, 1, true, false);
    CHECK(selected != NULL && strcmp(selected, "abcdefgh") == 0);
    free(selected);
    selected = headless_selected_text(ht, 0, 0, 4, 1, false, false);
    CHECK(selected != NULL && strcmp(selected, "abcde\nfgh") == 0);
    free(selected);
    transcript = headless_transcript_text(ht, 0);
    CHECK(transcript != NULL && strcmp(transcript, "abcdefgh") == 0);
    free(transcript);
    transcript = headless_transcript_text(ht, 1);
    CHECK(transcript != NULL && strcmp(transcript, "abcde\nfgh") == 0);
    free(transcript);
    headless_destroy(ht);

    /* Termux's transcript setting is the total ring height. foot rounds the
     * physical grid up, but only (total - screen rows) may be exposed. */
    ht = headless_create(10, 2, 5, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    for (int i = 0; i < 40; i++)
        feed(ht, "x\r\n");
    CHECK(headless_history_size(ht) == 3);
    text = row_utf8(ht, -3);
    CHECK(text != NULL && text[0] == 'x');
    free(text);
    text = row_utf8(ht, -4);
    CHECK(text != NULL && strncmp(text, "          ", 10) == 0);
    free(text);
    headless_destroy(ht);

    /* PTY replies larger than the response pipe must not be lost. foot queues
     * whatever it cannot write immediately; the adapter is responsible for
     * flushing that queue because it has no FDM loop. */
    ht = headless_create(10, 3, 13, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    const int query_count = 20000;
    char *queries = malloc((size_t)query_count * 4);
    CHECK(queries != NULL);
    if (queries == NULL) {
        headless_destroy(ht);
        return 1;
    }
    for (int i = 0; i < query_count; i++)
        memcpy(queries + i * 4, "\x1b[6n", 4);
    int dirty_count = 0;
    int *dirty = headless_append(ht, (const uint8_t *)queries, (size_t)query_count * 4, &dirty_count);
    free(dirty);
    free(queries);
    events = headless_drain_events(ht);
    CHECK(events != NULL);
    size_t write_events = 0;
    size_t base64_chars = 0;
    size_t padding = 0;
    for (char *line = events; line != NULL && *line != '\0'; ) {
        char *next_line = strchr(line, '\n');
        if (next_line != NULL)
            *next_line++ = '\0';
        if (strncmp(line, "write\t", 6) == 0) {
            const char *payload = line + 6;
            const size_t length = strlen(payload);
            write_events++;
            base64_chars += length;
            if (length > 0 && payload[length - 1] == '=')
                padding++;
            if (length > 1 && payload[length - 2] == '=')
                padding++;
        }
        line = next_line;
    }
    CHECK(write_events > 0);
    CHECK(base64_chars / 4 * 3 - padding == (size_t)query_count * 6);
    free(events);
    headless_destroy(ht);

    /* Combining marks use foot's composed-glyph table; ensure the row snapshot
     * exposes every UTF-16 unit and that the composed table is released. */
    ht = headless_create(10, 3, 6, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    feed(ht, "e\xcc\x81" "X");
    encoded = row(ht, 0, &count);
    CHECK(encoded != NULL && encoded[2] == 11);
    if (encoded != NULL && encoded[2] == 11) {
        CHECK(encoded[3] == 'e');
        CHECK(encoded[4] == 0x0301);
        CHECK(encoded[5] == 'X');
        CHECK(encoded[3 + 11 + 0] == 0);
        CHECK(encoded[3 + 11 + 1] == 2);
        CHECK(encoded[3 + 11 + 10] == 11);
    }
    free(encoded);
    selected = headless_selected_text(ht, 0, 0, 0, 0, true, false);
    CHECK(selected != NULL && strcmp(selected, "e\xcc\x81") == 0);
    free(selected);
    headless_destroy(ht);

    /* Scroll counter parity: Termux counts every line scrolled up through a
     * scroll region (and in the alternate screen), but never reverse scrolls
     * or resizes. A hard reset moves the baseline without clearing the count. */
    ht = headless_create(10, 5, 10, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    headless_clear_scroll_counter(ht);
    feed(ht, "\x1b[2;4r\x1b[4;1H\n");
    CHECK(headless_scroll_counter(ht) == 1);
    feed(ht, "\x1b[2;1H\x1bM");
    CHECK(headless_scroll_counter(ht) == 1);
    feed(ht, "\x1b[r\x1b[2S");
    CHECK(headless_scroll_counter(ht) == 3);
    feed(ht, "\x1b[?1049h\x1b[H\n\n\n\n\n\n");
    CHECK(headless_scroll_counter(ht) == 5);
    feed(ht, "\x1b[?1049l");
    CHECK(headless_scroll_counter(ht) == 5);
    headless_reset(ht);
    CHECK(headless_scroll_counter(ht) == 0);
    feed(ht, "\n\n\n\n\n\n");
    CHECK(headless_scroll_counter(ht) == 2);
    headless_destroy(ht);

    /* Pre-filter contract. foot only understands one colour per OSC 10/11/12,
     * so the adapter expands xterm's list form before handing it over. */
    ht = headless_create(10, 3, 6, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    feed(ht, "\x1b]10;rgb:11/22/33;rgb:44/55/66;rgb:77/88/99\x07");
    headless_palette(ht, current);
    CHECK(current[256] == (int32_t)0xff112233);
    CHECK(current[257] == (int32_t)0xff445566);
    CHECK(current[258] == (int32_t)0xff778899);

    /* DECCOLM is a no-op in foot; the adapter has to reset the scroll region,
     * clear the grid and home the cursor itself. */
    feed(ht, "\x1b[2;3r\x1b[H" "abc");
    feed(ht, "\x1b[?3h");
    CHECK(headless_cursor_row(ht) == 0 && headless_cursor_col(ht) == 0);
    text = row_utf8(ht, 0);
    CHECK(text != NULL && strncmp(text, "          ", 10) == 0);
    free(text);

    /* A combining mark at the start of a line gets a space base, matching the
     * Termux contract instead of being dropped by foot. */
    feed(ht, "\r\xcc\x81X");
    text = row_utf8(ht, 0);
    CHECK(text != NULL && strncmp(text, " \xcc\x81X", 4) == 0);
    free(text);

    headless_destroy(ht);
    ht = headless_create(10, 3, 6, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;

    /* OSC 99 chunk assembly, base64 payloads and metadata. */
    feed(ht, "\x1b]99;i=chunk:d=0:e=1:p=title;SGVsbG8=\x07");
    feed(ht, "\x1b]99;i=chunk:d=0:e=1:p=body;V29y\x07");
    feed(ht, "\x1b]99;i=chunk:e=1:p=body;bGQ=\x07");
    events = headless_drain_events(ht);
    CHECK(events != NULL && strstr(events, "notification\tY2h1bms=\tSGVsbG8=\tV29ybGQ=") != NULL);
    free(events);

    feed(ht,
         "\x1b]99;i=meta:d=1:o=unfocused:s=silent:a=report:p=body;Hi\x07");
    events = headless_drain_events(ht);
    CHECK(events != NULL && strstr(events, "\t2\t1\t1\t1") != NULL);
    free(events);

    /* A capability query is answered through the PTY response stream. */
    feed(ht, "\x1b]99;i=query:p=?;\x07");
    events = headless_drain_events(ht);
    CHECK(events != NULL && strstr(events, "write\t") != NULL);
    free(events);

    /* Focus reports are owned by the Java facade and must not be echoed. */
    feed(ht, "\x1b[?1004h\x1b[I\x1b[O");
    events = headless_drain_events(ht);
    CHECK(events == NULL || strstr(events, "write\t") == NULL);
    free(events);
    headless_destroy(ht);

    /* Deterministic byte stress: PTY output is arbitrary bytes, and the parser
     * must never abort. Periodically query the snapshot/selection/render paths
     * and resize the grid so reflow and scrollback are exercised too. */
    ht = headless_create(20, 6, 70, 20, 8, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return 1;
    uint32_t rng = 0x12345678u;
    for (int i = 0; i < 4000; i++) {
        uint8_t bytes[32];
        const int n = 1 + (int)(rng % sizeof(bytes));
        for (int j = 0; j < n; j++) {
            rng = rng * 1664525u + 1013904223u;
            bytes[j] = (uint8_t)(rng >> 24);
        }
        int dirty_count = 0;
        int *dirty = headless_append(ht, bytes, (size_t)n, &dirty_count);
        free(dirty);

        if ((i & 63) == 0) {
            const int columns = headless_columns(ht);
            const int rows = headless_rows(ht);
            text = headless_transcript_text(ht, (i / 64) % 3);
            CHECK(text != NULL);
            free(text);
            selected = headless_selected_text(ht, 0, 0, columns - 1, rows - 1, true, false);
            CHECK(selected != NULL);
            free(selected);
            encoded = row(ht, rows - 1, &count);
            CHECK(encoded != NULL);
            free(encoded);
            commands = headless_render(
                ht, -headless_history_size(ht), -1, -1, 0, 0, false, 0, 255,
                8.0f, 16, 12, columns, rows, &command_count);
            CHECK(commands != NULL);
            free(commands);
            char *events = headless_drain_events(ht);
            free(events);
        }
        if (i > 0 && i % 997 == 0)
            headless_resize(ht, 10 + i % 15, 2 + i % 8, 20, 8);
    }
    headless_destroy(ht);

    if (failures != 0) {
        fprintf(stderr, "%d test(s) failed\n", failures);
        return 1;
    }
    printf("foot core smoke test: ok\n");
    return 0;
}

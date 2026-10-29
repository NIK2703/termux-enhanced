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
#include <uchar.h>

enum {
    OP_END = 0,
    OP_ROW_BEGIN = 1,
    OP_ROW_END = 2,
    OP_FRAME_BEGIN = 6,
    OP_DAMAGE = 7,
    OP_FRAME_END = 8,
    OP_FILL_RECT = 9,
    OP_GLYPH = 10,
    OP_FILL_TRAPEZOID = 11,
    OP_IMAGE = 12,
};

static int failures;

#define CHECK(condition)                                                        \
    do {                                                                        \
        if (!(condition)) {                                                     \
            fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); \
            failures++;                                                         \
        }                                                                       \
    } while (0)

static bool
payload_has_frame_end(const int32_t *commands, int command_count)
{
    int cursor = 0;
    bool saw_frame_end = false;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END)
            break;
        switch (op) {
        case OP_ROW_BEGIN:
            cursor += 1;
            break;
        case OP_ROW_END:
            break;
        case OP_FILL_RECT:
            cursor += 5;
            break;
        case OP_GLYPH:
            cursor += 7;
            break;
        case OP_FILL_TRAPEZOID:
            cursor += 9;
            break;
        case OP_IMAGE:
            cursor += 8;
            break;
        case OP_FRAME_BEGIN:
            if (cursor + 8 > command_count)
                return saw_frame_end;
            cursor += 8 + commands[cursor + 7] * 3;
            break;
        case OP_DAMAGE:
            cursor += 2;
            break;
        case OP_FRAME_END:
            saw_frame_end = true;
            break;
        default:
            return saw_frame_end;
        }
    }
    return saw_frame_end;
}

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
    headless_append(ht, (const uint8_t *)text, strlen(text), &dirty);
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
        } else if (op == 9) {
            cursor += 5; /* x, y, width, height, colour */
        } else if (op == 10) {
            cursor += 5; /* start char, count, x, baseline, width */
            const int fg = commands[cursor++];
            cursor++; /* effects */
            if (fg == wanted)
                return true;
        } else if (op == 11) {
            cursor += 9;
        } else if (op == 12) {
            cursor += 8;
        } else if (op == 6) {
            /* frame header: 8 fields, then OP_DAMAGE triples */
            if (cursor + 8 > count)
                break;
            const int damage_count = commands[cursor + 7];
            cursor += 8 + damage_count * 3;
        } else if (op == 7) {
            cursor += 2;
        } else if (op == 8) {
        } else {
            break;
        }
    }
    return false;
}

static int *append_dirty(
    struct headless_terminal *ht, const char *text, int *dirty_count);
static void commit_dirty(
    struct headless_terminal *ht, int *dirty, int count);

static int *
append_dirty(struct headless_terminal *ht, const char *text, int *dirty_count)
{
    return headless_append(ht, (const uint8_t *)text, strlen(text), dirty_count);
}

static bool
dirty_has(const int *dirty, int count, int row)
{
    for (int i = 0; i < count; i++) {
        if (dirty[i] == row)
            return true;
    }
    return false;
}

static void
commit_dirty(struct headless_terminal *ht, int *dirty, int count)
{
    headless_commit_rows(ht, dirty, count);
}

static void
dirty_tracking_tests(void)
{
    struct headless_terminal *ht = headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    int dirty_count = 0;
    int *dirty = append_dirty(ht, "A", &dirty_count); /* drain the initial reset damage */
    commit_dirty(ht, dirty, dirty_count);

    dirty = append_dirty(ht, "B", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 0));
    commit_dirty(ht, dirty, dirty_count);

    dirty = append_dirty(ht, "\x1b[2;1H", &dirty_count);
    CHECK(dirty_count == 0);
    dirty = append_dirty(ht, "\x1b[2K", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 1));
    commit_dirty(ht, dirty, dirty_count);

    dirty = append_dirty(ht, "\x1b[5;1H", &dirty_count);
    CHECK(dirty_count == 0);
    dirty = append_dirty(ht, "\n", &dirty_count);
    CHECK(dirty_count == 5);
    for (int row = 0; row < 5; row++)
        CHECK(dirty_has(dirty, dirty_count, row));
    commit_dirty(ht, dirty, dirty_count);

    /* Switching to the alternate screen changes every visible cell. */
    dirty = append_dirty(ht, "\x1b[?1049h", &dirty_count);
    CHECK(dirty_count == 5);
    commit_dirty(ht, dirty, dirty_count);
    dirty = append_dirty(ht, "\x1b[?1049l", &dirty_count);
    CHECK(dirty_count == 5);
    commit_dirty(ht, dirty, dirty_count);
    headless_destroy(ht);

    /* OSC 4 only dirties rows that actually use the changed palette entry. */
    ht = headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;
    dirty = append_dirty(ht, "\x1b[31mA\x1b[0m", &dirty_count);
    commit_dirty(ht, dirty, dirty_count);
    dirty = append_dirty(ht, "\x1b]4;1;rgb:11/22/33\x07", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 0));
    commit_dirty(ht, dirty, dirty_count);
    headless_destroy(ht);
}

static bool
frame_row_damaged(const int32_t *commands, int count, int row)
{
    if (commands == NULL || count < 9 || commands[0] != 6)
        return false;
    if (commands[3] != 0)
        return true;
    const int ranges = commands[8];
    int cursor = 9;
    for (int i = 0; i < ranges; i++) {
        if (cursor + 2 >= count || commands[cursor] != 7)
            return false;
        if (row >= commands[cursor + 1] && row <= commands[cursor + 2])
            return true;
        cursor += 3;
    }
    return false;
}

static void
frame_header_tests(void)
{
    struct headless_terminal *ht = headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    int dirty_count = 0;
    int *dirty = append_dirty(ht, "A", &dirty_count);
    commit_dirty(ht, dirty, dirty_count);

    int command_count = 0;
    int32_t *commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL && command_count > 9);
    if (commands != NULL) {
        CHECK(commands[0] == 6); /* OP_FRAME_BEGIN */
        CHECK(commands[2] == 0); /* view top */
        CHECK(commands[3] == 0); /* full damage */
        CHECK(commands[4] == 10); /* terminal columns */
        CHECK(commands[5] == 5); /* terminal rows */
        CHECK(commands[6] == 0); /* render top */
        CHECK(commands[7] == 5); /* render rows */
        CHECK(commands[8] == 0); /* no damage ranges after commit */
        CHECK(!frame_row_damaged(commands, command_count, 0));
    }
    const int first_frame_id = commands != NULL ? commands[1] : -1;

    dirty = append_dirty(ht, "\x1b[3;1HB", &dirty_count);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL);
    if (commands != NULL) {
        CHECK(commands[1] == first_frame_id + 1);
        CHECK(commands[3] == 0);
        CHECK(commands[8] == 1);
        CHECK(frame_row_damaged(commands, command_count, 2));
        CHECK(!frame_row_damaged(commands, command_count, 1));
    }

    /* The damage list is scoped to the requested render band. */
    commands = headless_render(
        ht, 1, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 1, &command_count);
    CHECK(commands != NULL);
    if (commands != NULL) {
        CHECK(commands[6] == 1 && commands[7] == 1);
        CHECK(commands[8] == 0);
        CHECK(!frame_row_damaged(commands, command_count, 2));
    }
    commands = headless_render(
        ht, 2, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 1, &command_count);
    CHECK(commands != NULL);
    if (commands != NULL) {
        CHECK(commands[6] == 2 && commands[7] == 1);
        CHECK(commands[8] == 1);
        CHECK(frame_row_damaged(commands, command_count, 2));
    }

    int row = 2;
    headless_commit_rows(ht, &row, 1);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL && commands[8] == 0);

    /* A viewport change is full damage, and the header carries the moved view. */
    dirty = append_dirty(ht, "\x1b[1;1H\n\n\n\n\n\n", &dirty_count);
    commit_dirty(ht, dirty, dirty_count);
    headless_set_view(ht, -2);
    commands = headless_render(
        ht, -2, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL);
    if (commands != NULL) {
        CHECK(commands[2] == -2);
        CHECK(commands[3] == 1);
        CHECK(commands[6] == -2 && commands[7] == 5);
        CHECK(frame_row_damaged(commands, command_count, -2));
        CHECK(frame_row_damaged(commands, command_count, 2));
    }

    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, -2, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5, &command_count);
    CHECK(commands != NULL && commands[2] == -2 && commands[3] == 0 && commands[8] == 0);

    headless_destroy(ht);
}

static void
render_state_tests(void)
{
    struct headless_terminal *ht = headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    int dirty_count = 0;
    int *dirty = append_dirty(ht, "A", &dirty_count);
    commit_dirty(ht, dirty, dirty_count);
    CHECK(headless_view_top(ht) == 0);

    /* Dirty bits survive appends until the frame commits them. */
    dirty = append_dirty(ht, "B", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 0));
    dirty = append_dirty(ht, "\x1b[2;1H", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 0));
    commit_dirty(ht, dirty, dirty_count);

    dirty = append_dirty(ht, "\x1b[3;1HC", &dirty_count);
    CHECK(dirty_count == 1 && dirty_has(dirty, dirty_count, 2));
    int only_row = 2;
    headless_commit_rows(ht, &only_row, 1);

    /* A partial commit must not clear a different row's pending damage. */
    dirty = append_dirty(ht, "\x1b[4;1HD", &dirty_count);
    CHECK(dirty_has(dirty, dirty_count, 3));

    headless_commit_all_rows(ht);
    dirty = append_dirty(ht, "\x1b[0m", &dirty_count);
    CHECK(dirty_count == 0);

    /* Fill history, then make the UI viewport authoritative. */
    dirty = append_dirty(ht, "\x1b[1;1H\n\n\n\n\n\n", &dirty_count);
    commit_dirty(ht, dirty, dirty_count);
    int history = headless_history_size(ht);
    CHECK(history > 0);
    headless_set_view(ht, -2);
    CHECK(headless_view_top(ht) == -2);
    dirty = append_dirty(ht, "\x1b[0m", &dirty_count);
    CHECK(dirty_count == 5);
    CHECK(dirty_has(dirty, dirty_count, -2));
    CHECK(dirty_has(dirty, dirty_count, 2));

    /* A partial commit must not retire full viewport damage. */
    headless_commit_rows(ht, &dirty[0], 1);
    dirty = append_dirty(ht, "\x1b[0m", &dirty_count);
    CHECK(dirty_count == 5);
    commit_dirty(ht, dirty, dirty_count);

    /* Output at the bottom follows the live screen (top stays 0). */
    headless_set_view(ht, 0);
    CHECK(headless_view_top(ht) == 0);
    (void)append_dirty(ht, "\n", &dirty_count);
    CHECK(headless_view_top(ht) == 0);
    headless_commit_all_rows(ht);

    /* Output while scrolled up keeps the content anchor, so the external
     * viewport moves one row deeper into history. */
    history = headless_history_size(ht);
    CHECK(history >= 2);
    headless_set_view(ht, -2);
    (void)append_dirty(ht, "\n", &dirty_count);
    CHECK(headless_view_top(ht) == -3);
    headless_commit_all_rows(ht);

    history = headless_history_size(ht);
    headless_set_view(ht, -1000000);
    CHECK(headless_view_top(ht) == -history);
    headless_set_view(ht, 1000000);
    CHECK(headless_view_top(ht) == 0);

    headless_destroy(ht);
}

static void
scrolled_view_scroll_tests(void)
{
    struct headless_terminal *ht =
        headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    for (int i = 0; i < 12; i++)
        feed(ht, "line\r\n");

    headless_set_view(ht, -3);
    headless_commit_all_rows(ht);
    headless_clear_scroll_counter(ht);

    /* A full-screen scroll while the user is scrolled up changes the grid ring
     * address, but not the content of any visible slot. It must still advance
     * the scroll counter so the UI can keep its anchor. */
    int dirty_count = 0;
    int *dirty = append_dirty(ht, "\n\n", &dirty_count);
    CHECK(headless_view_top(ht) == -5);
    CHECK(headless_scroll_counter(ht) == 2);
    CHECK(dirty_count == 0);

    /* A partial scroll region moves only the rows in its region. The pointer
     * comparison above the cell damage must mark exactly those visible slots,
     * not the whole viewport. */
    headless_set_view(ht, -1);
    headless_commit_all_rows(ht);
    headless_clear_scroll_counter(ht);

    dirty = append_dirty(ht, "\x1b[1;2r\x1b[2;1H\n", &dirty_count);
    CHECK(headless_view_top(ht) == -2);
    CHECK(headless_scroll_counter(ht) == 1);
    CHECK(dirty_count == 2);
    CHECK(dirty_has(dirty, dirty_count, 1));
    CHECK(dirty_has(dirty, dirty_count, 2));

    headless_destroy(ht);
}

static void
scrolled_cursor_tests(void)
{
    struct headless_terminal *ht =
        headless_create(10, 3, 8, 16, 12, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    /* Force a non-zero grid->offset, then put the cursor at the top of the
     * live screen. The renderer must map cursor.point.row (a ring index) to
     * the external viewport address; comparing those numbers directly hides
     * or misplaces the cursor after scrollback starts. */
    feed(ht, "one\r\ntwo\r\nthree\r\nfour\r\n");
    CHECK(headless_history_size(ht) > 0);
    headless_set_view(ht, 0);
    feed(ht, "\x1b[1;1H");
    headless_commit_all_rows(ht);

    int command_count = 0;
    int32_t *commands = headless_render(
        ht, 0, -1, -1, 0, 0, true, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);

    int cursor_fills = 0;
    int cursor_y = -1;
    int cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            const int x = commands[cursor++];
            const int y = commands[cursor++];
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const int32_t color = commands[cursor++];
            if (x == 0 && w == 8 && h == 16 && color == palette[258]) {
                cursor_fills++;
                cursor_y = y;
            }
        } else if (op == OP_GLYPH) cursor += 7;
        else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else if (op == OP_IMAGE) cursor += 8;
        else break;
    }
    CHECK(cursor_fills == 1);
    CHECK(cursor_y == 0);

    headless_destroy(ht);
}

static void
sixel_render_tests(void)
{
    struct headless_terminal *ht =
        headless_create(10, 5, 25, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;

    /* One red sixel column (six pixels, 2:1 aspect ratio). */
    feed(ht, "\x1bPq#0;2;100;0;0#0~\x1b\\");

    int command_count = 0;
    int32_t *commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5,
        &command_count);
    CHECK(commands != NULL);

    int image_count = 0;
    int image_x = -1;
    int image_y = -1;
    int image_width = 0;
    int image_height = 0;
    uint64_t image_id = 0;
    uint32_t image_revision = 0;
    int cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) {
            cursor++;
        } else if (op == OP_ROW_END || op == OP_FRAME_END) {
        } else if (op == OP_FILL_RECT) {
            cursor += 5;
        } else if (op == OP_GLYPH) {
            cursor += 7;
        } else if (op == OP_FILL_TRAPEZOID) {
            cursor += 9;
        } else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) {
            cursor += 2;
        } else if (op == OP_IMAGE) {
            if (cursor + 8 > command_count) break;
            image_x = commands[cursor++];
            image_y = commands[cursor++];
            image_width = commands[cursor++];
            image_height = commands[cursor++];
            const uint32_t image_id_lo = (uint32_t)commands[cursor++];
            const uint32_t image_id_hi = (uint32_t)commands[cursor++];
            image_id = ((uint64_t)image_id_hi << 32) | image_id_lo;
            image_revision = (uint32_t)commands[cursor++];
            cursor++; /* opaque */
            image_count++;
        } else {
            break;
        }
    }

    CHECK(image_count == 1);
    CHECK(image_x == 0 && image_y == 0);
    CHECK(image_width == 1);
    CHECK(image_height > 0 && image_height <= 16);
    CHECK(image_id != 0);

    int pixel_width = 0;
    int pixel_height = 0;
    uint32_t pixel_revision = 0;
    uint32_t *pixels = headless_sixel_pixels(
        ht, image_id, image_width, image_height, image_revision,
        &pixel_width, &pixel_height, &pixel_revision);
    CHECK(pixels != NULL);
    CHECK(pixel_width == image_width && pixel_height == image_height);
    CHECK(pixel_revision == image_revision);
    if (pixels != NULL)
        CHECK((pixels[0] & 0x00ffffffu) == 0x00ff0000u);

    /* Clean frames do not need to resend an unchanged image. */
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5,
        &command_count);
    CHECK(commands != NULL);
    bool saw_image = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END)
            break;
        if (op == OP_IMAGE) {
            saw_image = true;
            break;
        }
        if (op == OP_ROW_BEGIN)
            cursor++;
        else if (op == OP_ROW_END || op == OP_FRAME_END)
            ;
        else if (op == OP_FILL_RECT)
            cursor += 5;
        else if (op == OP_GLYPH)
            cursor += 7;
        else if (op == OP_FILL_TRAPEZOID)
            cursor += 9;
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) {
            cursor += 2;
        } else {
            break;
        }
    }
    CHECK(!saw_image);

    /* Text overwrites the left edge of a wider image. Upstream crops the
     * surface through a pixman region subtraction; exercise that path before
     * the renderer hands the remaining chunks back to Java. */
    feed(ht,
         "\x1b[2J\x1b[1;1H"
         "\x1bPq#0;2;100;0;0#0~~~~~~~~~~~~~~~~~~~~\x1b\\X");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 5,
        &command_count);
    CHECK(commands != NULL);
    CHECK(payload_has_frame_end(commands, command_count));

    headless_destroy(ht);
}

static void
canvas_render_tests(void)
{
    struct headless_terminal *ht =
        (headless_create)(10, 3, 6, 8, 16, palette);
    CHECK(ht != NULL);
    if (ht == NULL)
        return;
    feed(ht, "abc");
    headless_commit_all_rows(ht);
    int command_count = 0;
    int32_t *commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    /* The command buffer grows while render_cell() emits glyphs. Make sure the
     * frame terminator is still present after that reallocation. */
    CHECK(payload_has_frame_end(commands, command_count));
    int glyph_start = -1;
    int glyph_count = 0;
    int glyph_x = -1;
    int glyph_width = -1;
    int glyph_baseline = -1;
    int32_t glyph_color = 0;
    int glyph_effects = -1;
    int cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) {
            break;
        } else if (op == OP_ROW_BEGIN || op == OP_ROW_END) {
            if (op == OP_ROW_BEGIN) cursor++;
        } else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            const int damage = commands[cursor + 7];
            cursor += 8 + damage * 3;
        } else if (op == OP_DAMAGE) {
            cursor += 2;
        } else if (op == OP_FILL_RECT) {
            cursor += 5;
        } else if (op == OP_GLYPH) {
            glyph_start = commands[cursor++];
            glyph_count = commands[cursor++];
            glyph_x = commands[cursor++];
            glyph_baseline = commands[cursor++];
            glyph_width = commands[cursor++];
            glyph_color = commands[cursor++];
            glyph_effects = commands[cursor++];
            break;
        } else if (op == OP_FILL_TRAPEZOID) {
            cursor += 9;
        } else {
            break;
        }
    }
    int first_text_length = 0;
    int32_t *first_text = headless_render_text(ht, &first_text_length);
    CHECK(first_text != NULL && first_text_length > 0 &&
        first_text[0] == 'a');
    free(first_text);
    /* LTR batching must collapse the three adjacent ASCII cells into one run;
     * otherwise every scroll frame pays three Canvas drawTextRun() calls. */
    CHECK(glyph_start >= 0 && glyph_count == 3 && glyph_x == 0);
    CHECK(glyph_width == 3);
    CHECK(glyph_baseline == 12);
    CHECK(glyph_color == palette[256]);
    CHECK(glyph_effects == 0);

    /* The block cursor is an upstream fill rectangle; the glyph is repainted
     * with the contrast colour selected by cursor_colors_for_cell(). */
    feed(ht, "\x1b[1;1H");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, true, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    bool saw_cursor_fill = false;
    bool saw_contrast_glyph = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            const int x = commands[cursor++];
            const int y = commands[cursor++];
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const int32_t color = commands[cursor++];
            if (x == 0 && y == 0 && w == 8 && h == 16 &&
                color == palette[258])
                saw_cursor_fill = true;
        } else if (op == OP_GLYPH) {
            cursor += 2; /* text index/count */
            const int x = commands[cursor++];
            cursor++; /* baseline */
            cursor++; /* cell width */
            const int32_t color = commands[cursor++];
            cursor++; /* effects */
            if (x == 0 && color == palette[257])
                saw_contrast_glyph = true;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(saw_cursor_fill);
    CHECK(saw_contrast_glyph);

    /* Selection swaps foreground/background through upstream render_cell(). */
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, 0, 0, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    bool saw_selection_fill = false;
    bool saw_selection_glyph = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            const int x = commands[cursor++];
            const int y = commands[cursor++];
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const int32_t color = commands[cursor++];
            if (x == 0 && y == 0 && w == 8 && h == 16 &&
                color == palette[256])
                saw_selection_fill = true;
        } else if (op == OP_GLYPH) {
            cursor += 2;
            const int x = commands[cursor++];
            cursor++; /* baseline */
            cursor++; /* cell width */
            const int32_t color = commands[cursor++];
            cursor++; /* effects */
            if (x == 0 && color == palette[257])
                saw_selection_glyph = true;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(saw_selection_fill);
    CHECK(saw_selection_glyph);

    /* Wide glyphs keep upstream cell advances: B starts at column 2. */
    feed(ht, "\x1b[2J\x1b[H\xe7\x95\x8c" "B");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    int render_text_length = 0;
    int32_t *render_text = headless_render_text(ht, &render_text_length);
    CHECK(render_text != NULL);
    bool saw_wide = false;
    bool saw_b = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) cursor += 5;
        else if (op == OP_GLYPH) {
            char32_t cp = 0;
            const int start = commands[cursor];
            const int count = commands[cursor + 1];
            const int x = commands[cursor + 2];
            cursor += 7;
            if (render_text != NULL && start >= 0 && count > 0 &&
                start + count <= render_text_length)
            {
                cp = (char32_t)render_text[start];
            }
            if (x == 0 && cp == 0x754c)
                saw_wide = true;
            if (x == 16 && cp == 'B')
                saw_b = true;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(saw_wide);
    CHECK(saw_b);
    free(render_text);

    /* OSC-8 links are underlined by upstream render_cell() when the URL
     * attribute is present. Android has no URL-mode UI, so the bridge enables
     * the always-on OSC-8 underline; this also exercises the URL branch of the
     * reused upstream renderer rather than a separate decoration pass. */
    feed(ht, "\x1b[2J\x1b[H\x1b]8;;https://example.com\x1b\\X\x1b]8;;\x1b\\");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    bool saw_url_underline = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            const int x = commands[cursor++];
            cursor++; /* y */
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const int32_t color = commands[cursor++];
            if (x == 0 && w == 8 && h > 0 && h < 16 &&
                color == palette[3])
                saw_url_underline = true;
        } else if (op == OP_GLYPH) {
            cursor += 7;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(saw_url_underline);

    /* Adjacent cells sharing an explicit background must not break the text
     * run. The fills are coalesced into one rectangle and the glyphs into one
     * Canvas drawTextRun(), which is important for coloured status lines and
     * reverse-video screens while scrolling. */
    feed(ht, "\x1b[2J\x1b[H\x1b[41mabc");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    int merged_fill_count = 0;
    int merged_glyph_runs = 0;
    int merged_glyph_width = 0;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            const int x = commands[cursor++];
            const int y = commands[cursor++];
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const int32_t color = commands[cursor++];
            if (x == 0 && y == 0 && w == 24 && h == 16 &&
                color == palette[1])
                merged_fill_count++;
        } else if (op == OP_GLYPH) {
            const int count = commands[cursor + 1];
            const int width = commands[cursor + 4];
            cursor += 7;
            merged_glyph_runs++;
            merged_glyph_width = width;
            if (count != 3)
                merged_glyph_runs = -1000;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(merged_fill_count == 1);
    CHECK(merged_glyph_runs == 1);
    CHECK(merged_glyph_width == 3);

    /* Block-art rows are batched across skipped blank cells. The run carries
     * the cell span and the inserted space explicitly, which is the shape the
     * Canvas renderer must split into per-cell clusters when the font's block
     * advance differs from the primary space advance. */
    feed(ht, "\x1b[2J\x1b[H\x1b[38;2;138;138;138m\xe2\x96\x88 \xe2\x96\x88");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    int block_start = -1;
    int block_count = 0;
    int block_width = 0;
    int block_effects = 0;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) cursor += 5;
        else if (op == OP_GLYPH) {
            block_start = commands[cursor];
            block_count = commands[cursor + 1];
            block_width = commands[cursor + 4];
            block_effects = commands[cursor + 6];
            cursor += 7;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    int block_text_length = 0;
    int32_t *block_text = headless_render_text(ht, &block_text_length);
    CHECK(block_text != NULL);
    CHECK(block_start >= 0 && block_count == 3 && block_width == 3);
    CHECK((block_effects & (1 << 7)) != 0);
    if (block_text != NULL && block_start >= 0 && block_count == 3 &&
        block_start + block_count <= block_text_length)
    {
        CHECK(block_text[block_start] == 0x2588);
        CHECK(block_text[block_start + 1] == ' ');
        CHECK(block_text[block_start + 2] == 0x2588);
    }
    free(block_text);

    /* The block-fill effect bit must not spill over into neighbouring text
     * runs: TerminalRenderer uses SRC_ATOP for those glyphs to preserve the
     * terminal background alpha, and that transfer mode must never be applied
     * to ordinary text. */
    feed(ht, "\x1b[2J\x1b[H\x1b[38;2;138;138;138mA\xe2\x96\x88" "B");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 128, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    int mixed_glyph_runs = 0;
    bool mixed_block_effect = false;
    bool mixed_text_effect = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) cursor += 5;
        else if (op == OP_GLYPH) {
            const int effects = commands[cursor + 6];
            mixed_glyph_runs++;
            if ((effects & (1 << 7)) != 0)
                mixed_block_effect = true;
            else
                mixed_text_effect = true;
            cursor += 7;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(mixed_glyph_runs == 3);
    CHECK(mixed_block_effect);
    CHECK(mixed_text_effect);

    /* Transparency: Termux applies the configured alpha to painted cell
     * backgrounds too, not just to the default background. */
    feed(ht, "\x1b[2J\x1b[H\x1b[41mX");
    headless_commit_all_rows(ht);
    commands = headless_render(
        ht, 0, -1, -1, 0, 0, false, 0, 128, 8.0f, 16, 12, 10, 3,
        &command_count);
    CHECK(commands != NULL);
    bool saw_translucent_fill = false;
    bool saw_opaque_cell_fill = false;
    cursor = 0;
    while (commands != NULL && cursor < command_count) {
        const int op = commands[cursor++];
        if (op == OP_END) break;
        if (op == OP_ROW_BEGIN) cursor++;
        else if (op == OP_ROW_END) {}
        else if (op == OP_FRAME_BEGIN) {
            if (cursor + 8 > command_count) break;
            cursor += 8 + commands[cursor + 7] * 3;
        } else if (op == OP_DAMAGE) cursor += 2;
        else if (op == OP_FILL_RECT) {
            cursor++; /* x */
            cursor++; /* y */
            const int w = commands[cursor++];
            const int h = commands[cursor++];
            const uint32_t color = (uint32_t)commands[cursor++];
            if (w >= 8 && h >= 16) {
                if ((color >> 24) == 128) saw_translucent_fill = true;
                if ((color >> 24) == 255) saw_opaque_cell_fill = true;
            }
        } else if (op == OP_GLYPH) {
            cursor += 7;
        } else if (op == OP_FILL_TRAPEZOID) cursor += 9;
        else break;
    }
    CHECK(saw_translucent_fill);
    CHECK(!saw_opaque_cell_fill);

    headless_destroy(ht);
}

int
main(void)
{
    init_palette();
    sixel_render_tests();
    canvas_render_tests();
    scrolled_cursor_tests();
    dirty_tracking_tests();
    frame_header_tests();
    render_state_tests();
    scrolled_view_scroll_tests();
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
        bool saw_frame_end = false;
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
            } else if (op == 6) {
                if (cursor + 8 > command_count) {
                    structure_ok = false;
                    break;
                }
                CHECK(commands[cursor + 0] >= 0); /* frame id */
                CHECK(commands[cursor + 2] == 0 || commands[cursor + 2] == 1);
                CHECK(commands[cursor + 3] == 10); /* terminal columns */
                CHECK(commands[cursor + 5] == 0); /* render top */
                CHECK(commands[cursor + 6] == 3); /* render rows */
                const int damage_ranges = commands[cursor + 7];
                cursor += 8 + damage_ranges * 3;
            } else if (op == 7) {
                cursor += 2;
            } else if (op == 8) {
                saw_frame_end = true;
            } else if (op == 9) {
                cursor += 5; /* x, y, width, height, colour */
            } else if (op == 10) {
                const int start_char = commands[cursor++];
                const int char_count = commands[cursor++];
                const int x = commands[cursor++];
                cursor += 2; /* baseline, cell width */
                cursor += 2; /* fg, effects */
                if (start_char < 0 || char_count <= 0 ||
                    start_char + char_count > render_text_length ||
                    x < 0 || x > 10 * 8) {
                    structure_ok = false;
                }
                for (int i = 0; i < char_count; i++) {
                    const int32_t unit = render_text[start_char + i];
                    if (unit == 'A') saw_a = true;
                    else if (unit == 0x754c) saw_wide = true;
                    else if (unit == 'B') saw_b = true;
                }
            } else if (op == 11) {
                cursor += 9;
            } else {
                structure_ok = false;
                break;
            }
        }
        CHECK(structure_ok);
        CHECK(saw_frame_end);
        CHECK(row_begins == 3);
        CHECK(saw_a && saw_wide && saw_b);
    }
    free(render_text);

    /* The packed payload after OP_END mirrors headless_render_text(), and a
     * partial render keeps absolute row numbers while starting text at zero. */
    commands = headless_render(
        ht, 1, -1, -1, 0, 0, false, 0, 255, 8.0f, 16, 12, 10, 1, &command_count);
    CHECK(commands != NULL);
    render_text_length = 0;
    render_text = headless_render_text(ht, &render_text_length);
    CHECK(render_text != NULL);
    if (commands != NULL && render_text != NULL) {
        int cursor = 0;
        int packed_offset = -1;
        int row_begin = -1;
        int text_units = 0;
        while (cursor < command_count) {
            const int op = commands[cursor++];
            if (op == 0) {
                packed_offset = cursor;
                break;
            }
            if (op == 1) {
                row_begin = commands[cursor++];
            } else if (op == 2) {
            } else if (op == 3 || op == 5) {
                cursor += 4;
            } else if (op == 9) {
                cursor += 5;
            } else if (op == 10) {
                cursor += 7;
            } else if (op == 11) {
                cursor += 9;
            } else if (op == 6) {
                if (cursor + 8 > command_count)
                    break;
                const int damage_count = commands[cursor + 7];
                cursor += 8 + damage_count * 3;
            } else if (op == 7) {
                cursor += 2;
            } else if (op == 8) {
            } else {
                break;
            }
        }
        CHECK(row_begin == 1);
        CHECK(packed_offset >= 0 && packed_offset + 1 < command_count);
        if (packed_offset >= 0 && packed_offset + 1 < command_count) {
            text_units = commands[packed_offset];
            CHECK(text_units == render_text_length);
            CHECK(packed_offset + 1 + text_units <= command_count);
            for (int i = 0; i < text_units; i++)
                CHECK(commands[packed_offset + 1 + i] == render_text[i]);
        }
    }
    free(render_text);

    /* Word lookup is space delimited. */
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
    headless_append(ht, (const uint8_t *)queries, (size_t)query_count * 4, &dirty_count);
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

    /* 0x9d is also a UTF-8 continuation byte; it must not be mistaken for a
     * standalone C1 OSC introducer. */
    feed(ht, "\r\x1b[2K\xe6\x9e\x9dX");
    text = row_utf8(ht, 0);
    CHECK(text != NULL && strncmp(text, "\xe6\x9e\x9dX", 4) == 0);
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
        headless_append(ht, bytes, (size_t)n, &dirty_count);

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

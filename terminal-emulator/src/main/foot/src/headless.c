// Headless adapter around foot's terminal core.
//
// The VT parser, grid, reflow, scrollback, OSC/DCS handlers and selection
// primitives are upstream foot sources. This file only owns the Android/JNI
// facing state: a palette mirror, a response pipe, event queue, row snapshots
// and the render command planner.

#define LOG_MODULE "headless"
#define LOG_ENABLE_DBG 0
#include "log.h"

#include "headless.h"
#include "ht_internal.h"
#include "render_model.h"
#include "foot_canvas_backend.h"
#include "foot_grid_scroll.h"

#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "base64.h"
#include "composed.h"
#include "grid.h"
#include "macros.h"
#include "misc.h"
#include "notify.h"
#include "selection.h"
#include "sixel.h"
#include "terminal.h"
#include "url-mode.h"
#include "util.h"
#include "vt.h"
#include "vt_filter.h"
#include "xmalloc.h"

struct event_record {
    char *text;
    struct event_record *next;
};

struct headless_terminal {
    struct terminal term;
    struct config conf;
    struct wayland wl;
    struct wl_window window;
    struct seat seat;

    int ptmx_read;
    int cell_width;
    int cell_height;
    int transcript_rows;

    int32_t palette[FOOT_RENDER_PALETTE_SIZE];
    int32_t defaults[FOOT_RENDER_PALETTE_SIZE];

    uint64_t scroll_count;
    uint64_t scroll_base;
    int last_append_flags;

    struct vt_filter *filter;

    /* Viewport and pending-damage policy. Rows themselves are still owned by
     * foot's grid; the model only decides which visible rows are pending and
     * clears them after the Canvas frame has committed. */
    struct foot_render_model *render_model;

    struct event_record *events_head;
    struct event_record *events_tail;

    /* Scratch buffers reused across appends. They are borrowed by the caller
     * until the next call on the same terminal and must not be freed. */
    int *append_dirty;
    size_t append_dirty_cap;

    /* Content identity of each visible viewport slot before an append. Foot
     * moves row *pointers* for scrolls without setting row->dirty (the pixel
     * renderer moves pixels instead), so the Canvas adapter has to compare the
     * slots and damage the rows whose content actually moved. */
    struct row **viewport_before;
    size_t viewport_before_cap;
};

static int
next_pow2(int value)
{
    int result = 1;
    while (result < value)
        result <<= 1;
    return result;
}

static int
total_grid_rows(int screen_rows, int transcript_rows)
{
    /* Termux's "transcript rows" setting is the total ring-buffer height,
     * screen rows included. */
    int requested = transcript_rows > 0 ? transcript_rows : screen_rows;
    if (requested < screen_rows)
        requested = screen_rows;
    return next_pow2(requested);
}

static int
history_limit(const struct headless_terminal *ht)
{
    const int history = ht->transcript_rows - ht->term.rows;
    return history > 0 ? history : 0;
}

static void
event_push(struct headless_terminal *ht, char *record)
{
    if (record == NULL)
        return;

    struct event_record *node = calloc(1, sizeof(*node));
    if (node == NULL) {
        free(record);
        return;
    }
    node->text = record;
    if (ht->events_tail != NULL)
        ht->events_tail->next = node;
    else
        ht->events_head = node;
    ht->events_tail = node;
}

static void
event_push_format(struct headless_terminal *ht, const char *format, ...)
{
    va_list ap;
    va_start(ap, format);
    char *record = NULL;
    if (vasprintf(&record, format, ap) < 0)
        record = NULL;
    va_end(ap);
    event_push(ht, record);
}

static char *
base64_dup(const void *data, size_t length)
{
    static const char alphabet[] =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const uint8_t *bytes = data;
    size_t output_length = (length + 2) / 3 * 4;
    char *out = malloc(output_length + 1);
    if (out == NULL)
        return NULL;

    size_t o = 0;
    for (size_t i = 0; i < length; i += 3) {
        uint32_t v = (uint32_t)bytes[i] << 16;
        if (i + 1 < length) v |= (uint32_t)bytes[i + 1] << 8;
        if (i + 2 < length) v |= bytes[i + 2];
        out[o++] = alphabet[(v >> 18) & 0x3f];
        out[o++] = alphabet[(v >> 12) & 0x3f];
        out[o++] = i + 1 < length ? alphabet[(v >> 6) & 0x3f] : '=';
        out[o++] = i + 2 < length ? alphabet[v & 0x3f] : '=';
    }
    out[o] = '\0';
    return out;
}

static void
emit_notification_fields(
    struct headless_terminal *ht, const char *id, const char *title,
    const char *body, int occasions, bool silent, bool report, bool focus)
{
    char *id64 = base64_dup(id != NULL ? id : "", strlen(id != NULL ? id : ""));
    char *title64 = base64_dup(
        title != NULL ? title : "", strlen(title != NULL ? title : ""));
    char *body64 = base64_dup(
        body != NULL ? body : "", strlen(body != NULL ? body : ""));
    if (id64 == NULL || title64 == NULL || body64 == NULL) {
        free(id64);
        free(title64);
        free(body64);
        return;
    }

    event_push_format(
        ht, "notification\t%s\t%s\t%s\t%d\t%d\t%d\t%d",
        id64, title64, body64, occasions, silent ? 1 : 0,
        report ? 1 : 0, focus ? 1 : 0);

    free(id64);
    free(title64);
    free(body64);
}

static void
filter_write(void *user, const void *data, size_t length)
{
    struct headless_terminal *ht = user;
    headless_emit_bytes(&ht->term, data, length);
}

static void
filter_notification(
    void *user, const char *id, const char *title, const char *body,
    int occasions, bool silent, bool report, bool focus)
{
    emit_notification_fields(
        user, id, title, body, occasions, silent, report, focus);
}

void
headless_emit_bytes(struct terminal *term, const void *data, size_t length)
{
    struct headless_terminal *ht = (struct headless_terminal *)term;

    /* Termux reports focus changes from the Java facade, which is the only
     * side that knows Android's real window focus. foot's automatic DECSET
     * 1004 reply would otherwise be sent as well, producing a duplicate. */
    if (length == 3) {
        const uint8_t *bytes = data;
        if (bytes[0] == 0x1b && bytes[1] == '[' &&
            (bytes[2] == 'I' || bytes[2] == 'O'))
        {
            return;
        }
    }

    char *encoded = base64_dup(data, length);
    if (encoded == NULL)
        return;
    event_push_format(ht, "write\t%s", encoded);
    free(encoded);
}

void
headless_emit_title(struct terminal *term)
{
    struct headless_terminal *ht = (struct headless_terminal *)term;
    const char *title = term->window_title;
    if (title == NULL || *title == '\0') {
        event_push_format(ht, "reset_title");
        return;
    }

    char *encoded = base64_dup(title, strlen(title));
    if (encoded == NULL)
        return;
    event_push_format(ht, "title\t%s", encoded);
    free(encoded);
}

void
headless_emit_clipboard_store(struct terminal *term, const void *data, size_t length)
{
    struct headless_terminal *ht = (struct headless_terminal *)term;
    char *encoded = base64_dup(data, length);
    if (encoded == NULL)
        return;
    event_push_format(ht, "clipboard_store\t%s", encoded);
    free(encoded);
}

void
headless_emit_bell(struct terminal *term)
{
    struct headless_terminal *ht = (struct headless_terminal *)term;
    event_push_format(ht, "bell");
}

void
headless_emit_notification(
    struct terminal *term, const struct notification *notification)
{
    struct headless_terminal *ht = (struct headless_terminal *)term;

    int occasions = 8; /* OCCASION_ALWAYS */
    switch (notification->when) {
    case NOTIFY_UNFOCUSED: occasions = 2; break;  /* OCCASION_UNFOCUSED */
    case NOTIFY_INVISIBLE: occasions = 4; break;  /* OCCASION_INVISIBLE */
    case NOTIFY_ALWAYS:
    default: occasions = 8; break;
    }

    emit_notification_fields(
        ht, notification->id, notification->title, notification->body,
        occasions, notification->muted,
        notification->report_activated || notification->report_closed,
        notification->focus);
}

static void
drain_ptmx(struct headless_terminal *ht)
{
    uint8_t buffer[4096];
    for (;;) {
        ssize_t n = read(ht->ptmx_read, buffer, sizeof(buffer));
        if (n > 0) {
            headless_emit_bytes(&ht->term, buffer, (size_t)n);
            continue;
        }
        if (n < 0 && errno == EINTR)
            continue;
        break;
    }
}

/*
 * foot queues data when its non-blocking write to the slave returns EAGAIN
 * and relies on its FDM loop to flush the queue later. We have no FDM loop,
 * so flush the queue ourselves, draining the response pipe as we go. Without
 * this a large burst of replies (e.g. thousands of DSR requests in one PTY
 * read) would silently lose everything that did not fit into the pipe.
 */
static void
flush_ptmx_buffer_list(
    struct headless_terminal *ht, ptmx_buffer_list_t *buffers)
{
    struct terminal *term = &ht->term;

    tll_foreach(*buffers, it) {
        struct ptmx_buffer *buffer = &it->item;
        while (buffer->idx < buffer->len) {
            ssize_t n = write(
                term->ptmx,
                (const uint8_t *)buffer->data + buffer->idx,
                buffer->len - buffer->idx);
            if (n > 0) {
                buffer->idx += (size_t)n;
                continue;
            }
            if (n < 0 && errno == EINTR)
                continue;
            if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                drain_ptmx(ht);
                continue;
            }
            /* Reader gone or pipe broken: discard the rest. */
            buffer->idx = buffer->len;
        }
    }
    tll_foreach(*buffers, it)
        free(it->item.data);
    tll_free(*buffers);
}

static void
flush_ptmx_buffers(struct headless_terminal *ht)
{
    drain_ptmx(ht);
    if (ht->term.ptmx < 0)
        return;
    flush_ptmx_buffer_list(ht, &ht->term.ptmx_paste_buffers);
    flush_ptmx_buffer_list(ht, &ht->term.ptmx_buffers);
    drain_ptmx(ht);
}

static int
history_size(const struct headless_terminal *ht)
{
    const struct terminal *term = &ht->term;
    const struct grid *grid = term->grid;

    if (grid == &term->alt)
        return 0;

    const int oldest = grid_sb_start_ignore_uninitialized(grid, term->rows);
    int history = (grid->offset - oldest) & (grid->num_rows - 1);
    if (history < 0)
        history += grid->num_rows;
    const int limit = history_limit(ht);
    if (history > limit)
        history = limit;
    return history;
}

static struct row *
row_at(const struct headless_terminal *ht, int external_row)
{
    const struct terminal *term = &ht->term;
    const struct grid *grid = term->grid;

    if (external_row >= term->rows)
        return NULL;
    if (external_row < 0) {
        if (grid == &term->alt || external_row < -history_size(ht))
            return NULL;
    }

    const int index = (grid->offset + external_row) & (grid->num_rows - 1);
    if (index < 0 || index >= grid->num_rows)
        return NULL;
    return grid->rows[index];
}

static void
utf16_push(char32_t **buffer, size_t *length, size_t *capacity, char32_t cp)
{
    if (*length + 2 > *capacity) {
        size_t new_capacity = *capacity == 0 ? 64 : *capacity * 2;
        while (new_capacity < *length + 2)
            new_capacity *= 2;
        char32_t *resized = realloc(*buffer, new_capacity * sizeof((*buffer)[0]));
        if (resized == NULL)
            return;
        *buffer = resized;
        *capacity = new_capacity;
    }

    if (cp <= 0xffff) {
        (*buffer)[(*length)++] = cp;
    } else {
        cp -= 0x10000;
        (*buffer)[(*length)++] = 0xd800 + (cp >> 10);
        (*buffer)[(*length)++] = 0xdc00 + (cp & 0x3ff);
    }
}

struct row_text {
    char32_t *chars;
    size_t char_count;
    int32_t *starts;
    uint64_t *styles;
    int columns;
    int line_wrap;
};

#define STYLE_DEFAULT \
    ((UINT64_C(256) << 40) | (UINT64_C(257) << 16))

static uint64_t
cell_style(const struct cell *cell)
{
    const struct attributes *attrs = &cell->attrs;
    uint64_t style = 0;

    if (attrs->bold)
        style |= UINT64_C(1) << 0;
    if (attrs->italic)
        style |= UINT64_C(1) << 1;
    if (attrs->underline)
        style |= UINT64_C(1) << 2;
    if (attrs->blink)
        style |= UINT64_C(1) << 3;
    if (attrs->reverse)
        style |= UINT64_C(1) << 4;
    if (attrs->conceal)
        style |= UINT64_C(1) << 5;
    if (attrs->strikethrough)
        style |= UINT64_C(1) << 6;
    if (attrs->dim)
        style |= UINT64_C(1) << 8;

    uint32_t fg;
    switch (attrs->fg_src) {
    case COLOR_RGB:
        fg = 0xff000000u | attrs->fg;
        break;
    case COLOR_DEFAULT:
        fg = 256;
        break;
    default:
        fg = attrs->fg;
        break;
    }

    uint32_t bg;
    switch (attrs->bg_src) {
    case COLOR_RGB:
        bg = 0xff000000u | attrs->bg;
        break;
    case COLOR_DEFAULT:
        bg = 257;
        break;
    default:
        bg = attrs->bg;
        break;
    }

    /* Mirrors TextStyle.encode(): truecolor carries a flag plus 24 bits,
     * indexed colours carry the low 9 bits. */
    if ((fg & 0xff000000u) == 0xff000000u)
        style |= (UINT64_C(1) << 9) | ((uint64_t)(fg & 0xffffffu) << 40);
    else
        style |= (uint64_t)(fg & 0x1ffu) << 40;

    if ((bg & 0xff000000u) == 0xff000000u)
        style |= (UINT64_C(1) << 10) | ((uint64_t)(bg & 0xffffffu) << 16);
    else
        style |= (uint64_t)(bg & 0x1ffu) << 16;

    return style;
}

static void
row_text_init_blank(const struct headless_terminal *ht, struct row_text *out)
{
    const int columns = ht->term.cols;
    out->chars = malloc((size_t)columns * sizeof(out->chars[0]));
    out->starts = malloc((size_t)(columns + 1) * sizeof(out->starts[0]));
    out->styles = malloc((size_t)columns * sizeof(out->styles[0]));
    out->char_count = (size_t)columns;
    out->columns = columns;
    out->line_wrap = 0;
    if (out->chars != NULL) {
        for (int i = 0; i < columns; i++)
            out->chars[i] = ' ';
    }
    if (out->starts != NULL) {
        for (int i = 0; i <= columns; i++)
            out->starts[i] = i;
    }
    if (out->styles != NULL) {
        for (int i = 0; i < columns; i++)
            out->styles[i] = STYLE_DEFAULT;
    }
}

static void
row_text_free(struct row_text *text)
{
    free(text->chars);
    free(text->starts);
    free(text->styles);
    memset(text, 0, sizeof(*text));
}

static void
row_text_init(
    const struct headless_terminal *ht, int external_row, struct row_text *out)
{
    memset(out, 0, sizeof(*out));
    const int columns = ht->term.cols;
    out->columns = columns;
    const struct row *row = row_at(ht, external_row);

    if (row == NULL) {
        row_text_init_blank(ht, out);
        return;
    }

    out->starts = malloc((size_t)(columns + 1) * sizeof(out->starts[0]));
    out->styles = malloc((size_t)columns * sizeof(out->styles[0]));
    if (out->starts == NULL || out->styles == NULL) {
        free(out->starts);
        free(out->styles);
        out->starts = NULL;
        out->styles = NULL;
        return;
    }

    size_t capacity = (size_t)columns + 16;
    out->chars = malloc(capacity * sizeof(out->chars[0]));
    if (out->chars == NULL) {
        free(out->starts);
        free(out->styles);
        out->starts = NULL;
        out->styles = NULL;
        return;
    }

    size_t base_start = 0;
    uint64_t base_style = STYLE_DEFAULT;
    for (int column = 0; column < columns; column++) {
        const struct cell *cell = &row->cells[column];
        char32_t wc = cell->wc;

        if (wc >= CELL_SPACER) {
            if (column == 0) {
                /* Leading continuation without its base (a wide glyph split by
                 * the left edge): treat it as a real blank cell so the cell
                 * map stays stable. */
                out->starts[column] = (int32_t)out->char_count;
                base_start = out->char_count;
                base_style = cell_style(cell);
                out->styles[column] = base_style;
                utf16_push(&out->chars, &out->char_count, &capacity, ' ');
                continue;
            }
            /* Second and later columns of a wide/multi-column glyph: every
             * such column maps to the base glyph, like TerminalRow's
             * findStartOfColumn() does. */
            out->starts[column] = (int32_t)base_start;
            out->styles[column] = base_style;
            continue;
        }

        out->starts[column] = (int32_t)out->char_count;
        base_start = out->char_count;
        base_style = cell_style(cell);
        out->styles[column] = base_style;

        if (wc >= CELL_COMB_CHARS_LO && wc <= CELL_COMB_CHARS_HI) {
            const struct composed *composed =
                composed_lookup(ht->term.composed, wc - CELL_COMB_CHARS_LO);
            if (composed != NULL && composed->chars != NULL) {
                for (size_t i = 0; i < composed->count; i++)
                    utf16_push(&out->chars, &out->char_count, &capacity, composed->chars[i]);
                continue;
            }
            wc = ' ';
        } else if (wc == 0) {
            wc = ' ';
        }

        utf16_push(&out->chars, &out->char_count, &capacity, wc);
    }

    out->starts[columns] = (int32_t)out->char_count;
    out->line_wrap = !row->linebreak;
}

static size_t
find_start(const struct row_text *text, int column)
{
    if (column < 0)
        return 0;
    if (text->starts == NULL)
        return 0;
    /* starts is columns+1 long; the caller passes the column count too. */
    if (text->chars == NULL)
        return 0;
    if (column > text->columns)
        return text->char_count;
    return (size_t)text->starts[column];
}

static void
utf16_append(char32_t **buffer, size_t *length, size_t *capacity, const char32_t *chars, size_t count)
{
    for (size_t i = 0; i < count; i++)
        utf16_push(buffer, length, capacity, chars[i]);
}

static char *
utf16_to_utf8(const char32_t *units, size_t length)
{
    char *out = malloc(length * 4 + 1);
    if (out == NULL)
        return NULL;

    size_t o = 0;
    for (size_t i = 0; i < length; i++) {
        uint32_t cp = (uint32_t)units[i];
        if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < length) {
            uint32_t low = (uint32_t)units[i + 1];
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
    return out;
}

static char *
selected_text(
    struct headless_terminal *ht,
    int x1, int y1, int x2, int y2,
    bool join_back_lines, bool join_full_lines)
{
    const int columns = ht->term.cols;
    const int screen_rows = ht->term.rows;
    const int active_history = history_size(ht);

    if (y1 < -active_history)
        y1 = -active_history;
    if (y2 > screen_rows - 1)
        y2 = screen_rows - 1;
    if (y1 > y2)
        return strdup("");

    char32_t *result = NULL;
    size_t result_len = 0;
    size_t result_cap = 0;

    for (int row = y1; row <= y2; row++) {
        struct row_text line;
        row_text_init(ht, row, &line);
        if (line.chars == NULL || line.starts == NULL) {
            row_text_free(&line);
            continue;
        }

        int line_x1 = row == y1 ? x1 : 0;
        int line_x2 = row == y2 ? x2 + 1 : columns;
        if (line_x1 < 0) line_x1 = 0;
        if (line_x2 > columns) line_x2 = columns;
        if (line_x1 > columns) line_x1 = columns;
        if (line_x2 < line_x1) line_x2 = line_x1;

        const size_t x1_index = find_start(&line, line_x1);
        size_t x2_index = line_x2 < columns
            ? find_start(&line, line_x2)
            : line.char_count;
        if (x2_index == x1_index)
            x2_index = find_start(&line, line_x2 + 1);
        if (x2_index > line.char_count)
            x2_index = line.char_count;

        int64_t last_printing = -1;
        if (line.line_wrap && line_x2 == columns) {
            last_printing = (int64_t)x2_index - 1;
        } else {
            for (size_t i = x1_index; i < x2_index && i < line.char_count; i++) {
                if (line.chars[i] != ' ')
                    last_printing = (int64_t)i;
            }
        }

        if (last_printing >= 0 && (size_t)last_printing + 1 > x1_index) {
            size_t end = (size_t)last_printing + 1;
            if (end > line.char_count)
                end = line.char_count;
            if (end > x1_index)
                utf16_append(&result, &result_len, &result_cap, line.chars + x1_index, end - x1_index);
        }

        const bool line_fills_width = last_printing == (int64_t)x2_index - 1;
        if ((!join_back_lines || !line.line_wrap) &&
            (!join_full_lines || !line_fills_width) &&
            row < y2 && row < screen_rows - 1)
        {
            utf16_push(&result, &result_len, &result_cap, '\n');
        }

        row_text_free(&line);
    }

    char *utf8 = utf16_to_utf8(result, result_len);
    free(result);
    return utf8 != NULL ? utf8 : strdup("");
}

static int
selection_text_start_col(
    const struct headless_terminal *ht, int external_row, int col)
{
    const int columns = ht->term.cols;
    if (col < 0)
        col = 0;
    if (col >= columns)
        col = columns - 1;

    const struct row *row = row_at(ht, external_row);
    if (row == NULL)
        return col;

    /* A selection endpoint on the spacer half of a wide character starts at
     * the base cell. This mirrors upstream selection_modify()'s endpoint
     * adjustment and keeps copy text identical for either half. */
    while (col > 0 && row->cells[col].wc >= CELL_SPACER)
        col--;
    return col;
}

static int
selection_text_end_col(
    const struct headless_terminal *ht, int external_row, int col)
{
    const int columns = ht->term.cols;
    if (col < 0)
        col = 0;
    if (col >= columns)
        col = columns - 1;

    const struct row *row = row_at(ht, external_row);
    if (row == NULL)
        return col;

    while (col + 1 < columns && row->cells[col + 1].wc >= CELL_SPACER)
        col++;
    return col;
}

static void
trim_selection_trailing_spaces(char *text)
{
    if (text == NULL)
        return;

    char *read = text;
    char *write = text;

    while (*read != '\0') {
        if (*read == '\n') {
            while (write > text && write[-1] == ' ')
                write--;
            *write++ = '\n';
            read++;
        } else {
            *write++ = *read++;
        }
    }

    while (write > text && write[-1] == ' ')
        write--;
    *write = '\0';
}

static char *
selected_text_upstream(
    struct headless_terminal *ht, int x1, int y1, int x2, int y2)
{
    const int columns = ht->term.cols;
    const int screen_rows = ht->term.rows;
    const int active_history = history_size(ht);

    if (y1 < -active_history)
        y1 = -active_history;
    if (y2 > screen_rows - 1)
        y2 = screen_rows - 1;
    if (y1 > y2)
        return strdup("");

    if (x1 < 0)
        x1 = 0;
    if (x2 < 0)
        x2 = 0;
    if (x1 >= columns)
        x1 = columns - 1;
    if (x2 >= columns)
        x2 = columns - 1;
    if (y1 == y2 && x2 < x1) {
        const int tmp = x1;
        x1 = x2;
        x2 = tmp;
    }

    x1 = selection_text_start_col(ht, y1, x1);
    x2 = selection_text_end_col(ht, y2, x2);

    struct terminal *term = &ht->term;
    const struct range saved_coords = term->selection.coords;
    const enum selection_kind saved_kind = term->selection.kind;

    term->selection.kind = SELECTION_CHAR_WISE;
    term->selection.coords.start = (struct coord){
        .col = x1,
        .row = (term->grid->offset + y1) & (term->grid->num_rows - 1),
    };
    term->selection.coords.end = (struct coord){
        .col = x2,
        .row = (term->grid->offset + y2) & (term->grid->num_rows - 1),
    };

    char *text = selection_to_text(term);

    term->selection.coords = saved_coords;
    term->selection.kind = saved_kind;

    if (text == NULL)
        return strdup("");

    /* Termux has always trimmed printed spaces at a hard line end; upstream
     * extract.c only strips never-written cells. Keep the Android copy
     * contract without changing the extracted selection walker. */
    trim_selection_trailing_spaces(text);
    return text;
}

int
headless_history_size(const struct headless_terminal *ht)
{
    return history_size(ht);
}

int
headless_rows(const struct headless_terminal *ht)
{
    return ht->term.rows;
}

int
headless_columns(const struct headless_terminal *ht)
{
    return ht->term.cols;
}

int
headless_cursor_row(const struct headless_terminal *ht)
{
    return ht->term.grid->cursor.point.row;
}

int
headless_cursor_col(const struct headless_terminal *ht)
{
    return ht->term.grid->cursor.point.col;
}

int
headless_mode_bits(const struct headless_terminal *ht)
{
    const struct terminal *term = &ht->term;
    int bits = 0;
    if (term->cursor_keys_mode == CURSOR_KEYS_APPLICATION)
        bits |= 1;
    if (term->reverse)
        bits |= 1 << 1;
    if (term->origin == ORIGIN_RELATIVE)
        bits |= 1 << 2;
    if (term->auto_margin)
        bits |= 1 << 3;
    if (!term->hide_cursor)
        bits |= 1 << 4;
    if (term->keypad_keys_mode == KEYPAD_APPLICATION)
        bits |= 1 << 5;
    if (term->mouse_tracking == MOUSE_CLICK)
        bits |= 1 << 6;
    if (term->mouse_tracking == MOUSE_DRAG || term->mouse_tracking == MOUSE_MOTION)
        bits |= 1 << 7;
    if (term->focus_events)
        bits |= 1 << 8;
    if (term->mouse_reporting == MOUSE_SGR)
        bits |= 1 << 9;
    if (term->bracketed_paste)
        bits |= 1 << 10;
    return bits;
}

int
headless_scroll_counter(const struct headless_terminal *ht)
{
    uint64_t value = ht->scroll_count > ht->scroll_base
        ? ht->scroll_count - ht->scroll_base : 0;
    if (value > INT32_MAX) value = INT32_MAX;
    return (int)value;
}

int
headless_last_append_flags(const struct headless_terminal *ht)
{
    return ht->last_append_flags;
}

void
headless_clear_scroll_counter(struct headless_terminal *ht)
{
    ht->scroll_base = ht->scroll_count;
}

int
headless_anchor_row(const struct headless_terminal *ht, int external_row)
{
    /* Return the low 32 bits of scroll_count + external_row. Java stores the
     * anchor in an int, and wrapping keeps every anchor distinct for the
     * practical lifetime of a session. */
    const uint64_t value = ht->scroll_count + (int64_t)external_row;
    return (int)(uint32_t)value;
}

int64_t
headless_absolute_scroll_count(const struct headless_terminal *ht)
{
    return (int64_t)ht->scroll_count;
}

bool
headless_is_alternate(const struct headless_terminal *ht)
{
    return ht->term.grid == &ht->term.alt;
}

int
headless_cursor_style(const struct headless_terminal *ht)
{
    switch (ht->term.cursor_style) {
    case CURSOR_UNDERLINE: return 1;
    case CURSOR_BEAM: return 2;
    case CURSOR_BLOCK:
    case CURSOR_HOLLOW:
    default: return 0;
    }
}

void
headless_set_cursor_style(struct headless_terminal *ht, int shape)
{
    enum cursor_style style = CURSOR_BLOCK;
    if (shape == 1)
        style = CURSOR_UNDERLINE;
    else if (shape == 2)
        style = CURSOR_BEAM;
    ht->term.cursor_style = style;
    ((struct config *)ht->term.conf)->cursor.style = style;
}

int32_t *
headless_row(const struct headless_terminal *ht, int external_row, int *out_count)
{
    struct row_text line;
    row_text_init(ht, external_row, &line);
    const int columns = ht->term.cols;
    if (line.chars == NULL || line.starts == NULL) {
        row_text_free(&line);
        *out_count = 0;
        return NULL;
    }

    const int style_offset = 3 + (int)line.char_count + columns + 1;
    const int count = style_offset + columns * 2;
    int32_t *out = malloc((size_t)count * sizeof(out[0]));
    if (out == NULL) {
        row_text_free(&line);
        *out_count = 0;
        return NULL;
    }

    out[0] = (int32_t)line.char_count;
    out[1] = line.line_wrap ? 1 : 0;
    out[2] = (int32_t)line.char_count;
    for (size_t i = 0; i < line.char_count; i++)
        out[3 + i] = (int32_t)line.chars[i];
    for (int i = 0; i <= columns; i++)
        out[3 + line.char_count + i] = line.starts[i];
    for (int i = 0; i < columns; i++) {
        const uint64_t style =
            line.styles != NULL ? line.styles[i] : STYLE_DEFAULT;
        out[style_offset + i * 2] = (int32_t)(uint32_t)style;
        out[style_offset + i * 2 + 1] = (int32_t)(uint32_t)(style >> 32);
    }

    *out_count = count;
    row_text_free(&line);
    return out;
}

char *
headless_selected_text(
    struct headless_terminal *ht, int x1, int y1, int x2, int y2,
    bool join_back_lines, bool join_full_lines)
{
    /* Normal selection copy matches upstream extract.c exactly: wrapped rows
     * are joined, hard linebreaks are preserved, and trailing empty cells are
     * stripped. The legacy walker remains for Termux's transcript modes,
     * which also support joining full (hard-broken) lines. */
    if (join_back_lines && !join_full_lines)
        return selected_text_upstream(ht, x1, y1, x2, y2);

    return selected_text(ht, x1, y1, x2, y2, join_back_lines, join_full_lines);
}

char *
headless_transcript_text(struct headless_terminal *ht, int mode)
{
    const int history = history_size(ht);
    char *text = selected_text(
        ht, 0, -history, ht->term.cols, ht->term.rows - 1,
        mode != 1, mode == 2);
    if (text == NULL)
        return strdup("");

    /* Transcript extraction trims leading/trailing whitespace. */
    char *start = text;
    while (*start == ' ' || *start == '\n' || *start == '\r' || *start == '\t')
        start++;
    size_t length = strlen(start);
    while (length > 0) {
        char c = start[length - 1];
        if (c != ' ' && c != '\n' && c != '\r' && c != '\t')
            break;
        length--;
    }
    char *trimmed = malloc(length + 1);
    if (trimmed != NULL) {
        memcpy(trimmed, start, length);
        trimmed[length] = '\0';
    }
    free(text);
    return trimmed != NULL ? trimmed : strdup("");
}

static bool
contains_newline(const char *text)
{
    return text != NULL && strchr(text, '\n') != NULL;
}

static char32_t *
utf8_to_utf16_units(const char *text, size_t *out_length)
{
    const size_t length = strlen(text);
    char32_t *units = malloc((length + 1) * sizeof(units[0]));
    if (units == NULL)
        return NULL;

    size_t i = 0;
    size_t o = 0;
    while (i < length) {
        uint32_t cp = (uint8_t)text[i++];
        if (cp < 0x80) {
        } else if ((cp & 0xe0) == 0xc0 && i < length) {
            cp = ((cp & 0x1f) << 6) | ((uint8_t)text[i++] & 0x3f);
        } else if ((cp & 0xf0) == 0xe0 && i + 1 < length) {
            cp = ((cp & 0x0f) << 12) |
                (((uint8_t)text[i] & 0x3f) << 6) |
                ((uint8_t)text[i + 1] & 0x3f);
            i += 2;
        } else if ((cp & 0xf8) == 0xf0 && i + 2 < length) {
            cp = ((cp & 0x07) << 18) |
                (((uint8_t)text[i] & 0x3f) << 12) |
                (((uint8_t)text[i + 1] & 0x3f) << 6) |
                ((uint8_t)text[i + 2] & 0x3f);
            i += 3;
        } else {
            cp = 0xfffd;
        }

        if (cp <= 0xffff) {
            units[o++] = cp;
        } else {
            cp -= 0x10000;
            units[o++] = 0xd800 + (cp >> 10);
            units[o++] = 0xdc00 + (cp & 0x3ff);
        }
    }
    *out_length = o;
    return units;
}

char *
headless_word_at_location(struct headless_terminal *ht, int x, int y)
{
    const int screen_rows = ht->term.rows;
    const int columns = ht->term.cols;
    int y1 = y;
    int y2 = y;

    while (y1 > 0) {
        char *probe = selected_text(ht, 0, y1 - 1, columns - 1, y, true, true);
        bool newline = contains_newline(probe);
        free(probe);
        if (newline)
            break;
        y1--;
    }
    while (y2 < screen_rows) {
        char *probe = selected_text(ht, 0, y, columns - 1, y2 + 1, true, true);
        bool newline = contains_newline(probe);
        free(probe);
        if (newline)
            break;
        y2++;
    }

    char *text = selected_text(ht, 0, y1, columns - 1, y2, true, true);
    if (text == NULL)
        return strdup("");

    size_t text_len = 0;
    char32_t *units = utf8_to_utf16_units(text, &text_len);
    free(text);
    if (units == NULL)
        return strdup("");

    int text_offset = (y - y1) * columns + x;
    if (text_offset < 0 || (size_t)text_offset >= text_len) {
        free(units);
        return strdup("");
    }

    int x1 = text_offset;
    while (x1 > 0 && units[x1 - 1] != ' ')
        x1--;
    int x2 = text_offset;
    while (x2 < (int)text_len && units[x2] != ' ')
        x2++;

    char *result = utf16_to_utf8(units + x1, (size_t)(x2 - x1));
    free(units);
    return result != NULL ? result : strdup("");
}

static bool
scroll_damage_applies(const struct grid *grid, const struct damage *damage)
{
    switch (damage->type) {
    case DAMAGE_SCROLL:
    case DAMAGE_SCROLL_REVERSE:
        return grid->view == grid->offset;
    case DAMAGE_SCROLL_IN_VIEW:
    case DAMAGE_SCROLL_REVERSE_IN_VIEW:
        return true;
    }
    return false;
}

static bool
has_applicable_scroll_damage(const struct grid *grid)
{
    tll_foreach(grid->scroll_damage, it) {
        if (scroll_damage_applies(grid, &it->item))
            return true;
    }
    return false;
}

static int
scroll_damage_lines(const struct grid *grid)
{
    int lines = 0;
    tll_foreach(grid->scroll_damage, it) {
        const struct damage *damage = &it->item;
        if (damage->type != DAMAGE_SCROLL)
            continue;
        if (lines > INT32_MAX - damage->lines)
            lines = INT32_MAX;
        else
            lines += damage->lines;
    }
    return lines;
}

/*
 * Forward movement of the grid ring during one append. foot only records
 * DAMAGE_SCROLL when the viewport follows the live screen (the pixel backend
 * would otherwise not move pixels), but Termux's scroll counter must also see
 * scrolls that happened while the user is scrolled into history. The ring
 * offset advances for every line scrolled up, partial scroll regions included,
 * and moves backwards for reverse scrolls (which the counter ignores).
 */
static int
forward_offset_delta(const struct grid *grid, int offset_before)
{
    int delta = grid->offset - offset_before;
    if (delta < 0)
        delta += grid->num_rows;
    if (delta > grid->num_rows / 2)
        return 0; /* Net reverse movement, or an unrealistic full-ring wrap. */
    return delta;
}

/*
 * Increment the monotonic scroll counter for every scroll_up(), including
 * partial scroll regions and the alternate screen. Reverse scrolls (RI, IL) do
 * not touch it.
 *
 * DAMAGE_SCROLL is authoritative when it exists (and does not lose wrap counts
 * during very large appends). The ring-offset delta covers the scrolled-up
 * case, where foot cannot record scroll damage because the viewport does not
 * follow the live screen.
 */
static void
count_scroll_movement(
    uint64_t *counter, const struct grid *grid, int offset_before)
{
    int lines = scroll_damage_lines(grid);
    const int offset_lines = forward_offset_delta(grid, offset_before);
    if (offset_lines > lines)
        lines = offset_lines;

    if (UINT64_MAX - *counter < (uint64_t)lines)
        *counter = UINT64_MAX;
    else
        *counter += (uint64_t)lines;
}

static struct row **
reserve_viewport_before(struct headless_terminal *ht, int rows)
{
    if (rows <= 0)
        return NULL;
    if ((size_t)rows > ht->viewport_before_cap) {
        struct row **resized = realloc(
            ht->viewport_before, (size_t)rows * sizeof(resized[0]));
        if (resized == NULL)
            return NULL;
        ht->viewport_before = resized;
        ht->viewport_before_cap = (size_t)rows;
    }
    return ht->viewport_before;
}

static void
snapshot_viewport(
    const struct grid *grid, struct row **out, int rows)
{
    for (int i = 0; i < rows; i++) {
        const int index = (grid->view + i) & (grid->num_rows - 1);
        out[i] = grid->rows[index];
    }
}

/*
 * Detect content that moved without a cell-level dirty bit. Foot's scroll
 * engine swaps row pointers; at the Canvas layer that is a moved row, not a
 * no-op. Compare the viewport slots by content identity after the append. If a
 * slot becomes an unallocated row, request a full repaint because there is no
 * row object on which to set ->dirty.
 */
static bool
mark_moved_viewport_rows(
    struct grid *grid, struct row *const *before, int rows)
{
    bool needs_full = false;
    for (int i = 0; i < rows; i++) {
        const int index = (grid->view + i) & (grid->num_rows - 1);
        struct row *row = grid->rows[index];
        if (row == before[i])
            continue;
        if (row == NULL) {
            needs_full = true;
            continue;
        }
        row->dirty = true;
    }
    return needs_full;
}

static void
process_scroll_damage(
    struct headless_terminal *ht,
    int normal_offset_before, int alt_offset_before)
{
    struct terminal *term = &ht->term;

    /* Keep the scroll damage list until the next render: the Android
     * renderer replays it as a pixel blit on its retained surface. Native row
     * dirty bits stay reserved for newly exposed/changed content. */
    count_scroll_movement(
        &ht->scroll_count, &term->normal, normal_offset_before);
    count_scroll_movement(
        &ht->scroll_count, &term->alt, alt_offset_before);

    if (has_applicable_scroll_damage(term->grid))
        foot_render_model_queue_scroll(ht->render_model, term->grid);

    tll_free(term->normal.scroll_damage);
    tll_free(term->alt.scroll_damage);
}

static int *
reserve_append_dirty(struct headless_terminal *ht, int count)
{
    if (count <= 0)
        return NULL;
    if ((size_t)count > ht->append_dirty_cap) {
        int *resized = realloc(ht->append_dirty, (size_t)count * sizeof(resized[0]));
        if (resized == NULL)
            return NULL;
        ht->append_dirty = resized;
        ht->append_dirty_cap = (size_t)count;
    }
    return ht->append_dirty;
}

int *
headless_append(
    struct headless_terminal *ht, const uint8_t *data, size_t length,
    int *dirty_count)
{
    *dirty_count = 0;
    ht->last_append_flags = 0;
    if (length == 0)
        return NULL;

    const int rows = ht->term.rows;
    const int history_before = history_size(ht);
    const int mode_before = headless_mode_bits(ht);
    const bool alternate_before = headless_is_alternate(ht);
    const uint64_t scroll_before = ht->scroll_count;
    struct grid *active_before = ht->term.grid;
    const int normal_offset_before = ht->term.normal.offset;
    const int alt_offset_before = ht->term.alt.offset;
    struct row **viewport_before = reserve_viewport_before(ht, rows);
    const bool viewport_snapshot_failed =
        rows > 0 && viewport_before == NULL;
    if (viewport_before != NULL)
        snapshot_viewport(active_before, viewport_before, rows);

    uint8_t *filtered = NULL;
    size_t filtered_length = 0;
    bool filtered_owned = false;
    if (ht->filter != NULL) {
        vt_filter_feed(
            ht->filter, data, length, &filtered, &filtered_length,
            &filtered_owned);
    } else {
        filtered = (uint8_t *)data;
        filtered_length = length;
    }
    if (filtered_length > 0)
        vt_from_slave(&ht->term, filtered, filtered_length);
    if (filtered_owned)
        free(filtered);
    flush_ptmx_buffers(ht);
    process_scroll_damage(ht, normal_offset_before, alt_offset_before);

    bool viewport_lost_row = false;
    if (viewport_before != NULL && ht->term.grid == active_before &&
        ht->term.rows == rows &&
        !foot_render_model_has_pending_scroll(ht->render_model))
    {
        viewport_lost_row = mark_moved_viewport_rows(
            ht->term.grid, viewport_before, rows);
    }

    const int history_after = history_size(ht);
    const int mode_after = headless_mode_bits(ht);
    const bool alternate_after = headless_is_alternate(ht);

    /* The UI viewport is an address; following output can move the screen
     * under it. Re-read foot's grid->view after the ring has advanced. */
    foot_render_model_sync_view(ht->render_model, &ht->term, history_after);

    /* A chunk that produced no output cannot have changed the terminal palette. */
    if (filtered_length > 0) {
        int32_t current_palette[FOOT_RENDER_PALETTE_SIZE];
        headless_palette(ht, current_palette);
        bool palette_changed = memcmp(current_palette, ht->palette, sizeof(current_palette)) != 0;
        if (palette_changed) {
            memcpy(ht->palette, current_palette, sizeof(current_palette));
            event_push_format(ht, "colors");
        }
    }

    const bool all_dirty =
        viewport_snapshot_failed ||
        viewport_lost_row ||
        alternate_before != alternate_after ||
        history_after < history_before ||
        mode_before != mode_after;
    if (all_dirty)
        foot_render_model_damage_all(ht->render_model);

    int *dirty = reserve_append_dirty(ht, rows);
    if (dirty == NULL)
        return NULL;
    int count = 0;

    /* Report the visible viewport, not just the live screen rows. When the
     * user is scrolled into history, output can damage transcript rows through
     * OSC 4 or reflow; those rows must reach the Java dirty set too. Native
     * row->dirty bits stay set until the Canvas frame commits them. */
    const int view_top = foot_render_model_view_top(ht->render_model);
    const bool full_damage =
        foot_render_model_is_full_damage(ht->render_model);
    for (int i = 0; i < rows; i++) {
        const int external = view_top + i;
        struct row *row = row_at(ht, external);
        if (full_damage || (row != NULL && row->dirty))
            dirty[count++] = external;
    }

    /* Scroll-damaged rows are not dirty: the retained surface is moved by
     * OP_SCROLL instead of being redrawn. The view still invalidates the whole
     * scroll band through APPEND_FLAG_PARTIAL_SCROLL so the moved pixels reach
     * the screen, but keeping them out of the dirty set is what lets the
     * renderer skip their row commands. */

    if (full_damage)
        ht->last_append_flags |= HEADLESS_APPEND_FULL_DAMAGE;
    if (!all_dirty && count < rows && ht->scroll_count > scroll_before)
        ht->last_append_flags |= HEADLESS_APPEND_PARTIAL_SCROLL;

    if (alternate_before != alternate_after)
        event_push_format(ht, "colors");

    *dirty_count = count;
    return dirty;
}

void
headless_set_view(struct headless_terminal *ht, int top_row)
{
    if (ht == NULL)
        return;
    foot_render_model_set_view(
        ht->render_model, &ht->term, top_row, history_size(ht));
}

int
headless_view_top(const struct headless_terminal *ht)
{
    return ht != NULL ? foot_render_model_view_top(ht->render_model) : 0;
}

int
headless_consume_viewport_shift(struct headless_terminal *ht)
{
    return ht != NULL
        ? foot_render_model_consume_viewport_shift(ht->render_model) : 0;
}

void
headless_damage_rows(
    struct headless_terminal *ht, const int *rows, int count)
{
    if (ht == NULL || rows == NULL)
        return;

    for (int i = 0; i < count; i++) {
        struct row *row = row_at(ht, rows[i]);
        if (row != NULL)
            row->dirty = true;
    }
}

bool
headless_scroll_surface_rows(
    struct headless_terminal *ht, void *pixels, int width, int height,
    int stride, int start, int end, int lines, bool reverse)
{
    if (ht == NULL)
        return false;
    return foot_grid_scroll_apply_rows(
        &ht->term, pixels, width, height, stride, start, end, lines, reverse);
}

void
headless_commit_rows(
    struct headless_terminal *ht, const int *rows, int count)
{
    if (ht == NULL)
        return;
    foot_render_model_commit_rows(
        ht->render_model, &ht->term, rows, count, history_size(ht));
}

void
headless_commit_all_rows(struct headless_terminal *ht)
{
    if (ht == NULL)
        return;
    foot_render_model_commit_all(
        ht->render_model, &ht->term, history_size(ht));
}

static const uint32_t default_sixel_colors[16] = {
    0xff000000, 0xff3333cc, 0xffcc2121, 0xff33cc33,
    0xffcc33cc, 0xff33cccc, 0xffcccc33, 0xff878787,
    0xff424242, 0xff545499, 0xff994242, 0xff549954,
    0xff995499, 0xff549999, 0xff999954, 0xffcccccc,
};

static void
set_color_theme(
    struct color_theme *theme, const int32_t palette[FOOT_RENDER_PALETTE_SIZE])
{
    memset(theme, 0, sizeof(*theme));
    theme->alpha = 0xffff;
    theme->fg = (uint32_t)palette[FOOT_RENDER_COLOR_FOREGROUND] & 0xffffff;
    theme->bg = (uint32_t)palette[FOOT_RENDER_COLOR_BACKGROUND] & 0xffffff;
    theme->selection_fg = theme->fg;
    theme->selection_bg = theme->bg;
    theme->cursor.text = theme->fg;
    theme->cursor.cursor = (uint32_t)palette[FOOT_RENDER_COLOR_CURSOR] & 0xffffff;
    theme->use_custom.cursor = true;
    memcpy(theme->sixel, default_sixel_colors, sizeof(theme->sixel));
    for (int i = 0; i < 256; i++)
        theme->table[i] = (uint32_t)palette[i] & 0xffffff;
}

int
headless_palette(struct headless_terminal *ht, int32_t out[FOOT_RENDER_PALETTE_SIZE])
{
    const struct colors *colors = &ht->term.colors;
    for (int i = 0; i < 256; i++)
        out[i] = (int32_t)(0xff000000u | (colors->table[i] & 0xffffffu));
    out[FOOT_RENDER_COLOR_FOREGROUND] = (int32_t)(0xff000000u | (colors->fg & 0xffffffu));
    out[FOOT_RENDER_COLOR_BACKGROUND] = (int32_t)(0xff000000u | (colors->bg & 0xffffffu));
    out[FOOT_RENDER_COLOR_CURSOR] = (int32_t)(0xff000000u | (colors->cursor_bg & 0xffffffu));
    return FOOT_RENDER_PALETTE_SIZE;
}

bool
headless_set_palette(
    struct headless_terminal *ht,
    const int32_t palette[FOOT_RENDER_PALETTE_SIZE], const int32_t defaults[FOOT_RENDER_PALETTE_SIZE])
{
    int32_t current[FOOT_RENDER_PALETTE_SIZE];
    headless_palette(ht, current);
    const bool changed = memcmp(current, palette, sizeof(current)) != 0;

    struct colors *colors = &ht->term.colors;
    for (int i = 0; i < 256; i++)
        colors->table[i] = (uint32_t)palette[i] & 0xffffff;
    colors->fg = (uint32_t)palette[FOOT_RENDER_COLOR_FOREGROUND] & 0xffffff;
    colors->bg = (uint32_t)palette[FOOT_RENDER_COLOR_BACKGROUND] & 0xffffff;
    colors->cursor_fg = colors->fg;
    colors->cursor_bg = (uint32_t)palette[FOOT_RENDER_COLOR_CURSOR] & 0xffffff;
    colors->alpha = 0xffff;
    colors->active_theme = COLOR_THEME_DARK;

    struct config *conf = (struct config *)ht->term.conf;
    set_color_theme(&conf->colors_dark, defaults);
    set_color_theme(&conf->colors_light, defaults);
    memcpy(ht->palette, palette, sizeof(ht->palette));
    memcpy(ht->defaults, defaults, sizeof(ht->defaults));
    if (changed)
        foot_render_model_damage_all(ht->render_model);
    return changed;
}

static void
init_grid(struct grid *grid, int rows, int columns)
{
    memset(grid, 0, sizeof(*grid));
    grid->num_rows = rows;
    grid->num_cols = columns;
    grid->offset = 0;
    grid->view = 0;
    grid->rows = calloc((size_t)rows, sizeof(grid->rows[0]));
    grid->cursor.point = (struct coord){0, 0};
    grid->saved_cursor.point = (struct coord){0, 0};
}

struct headless_terminal *
headless_create(
    int columns, int rows, int transcript_rows,
    int cell_width, int cell_height, const int32_t *palette)
{
    if (columns < 2) columns = 2;
    if (rows < 2) rows = 2;
    if (transcript_rows < 0) transcript_rows = 0;
    if (cell_width < 1) cell_width = 1;
    if (cell_height < 1) cell_height = 1;

    struct headless_terminal *ht = calloc(1, sizeof(*ht));
    if (ht == NULL)
        return NULL;

    ht->render_model = foot_render_model_create();
    if (ht->render_model == NULL) {
        free(ht);
        return NULL;
    }

    ht->cell_width = cell_width;
    ht->cell_height = cell_height;
    ht->transcript_rows = transcript_rows;
    ht->ptmx_read = -1;
    ht->filter = vt_filter_create(filter_write, filter_notification, ht);

    struct terminal *term = &ht->term;
    struct config *conf = &ht->conf;

    term->conf = conf;
    term->wl = &ht->wl;
    term->window = &ht->window;
    ht->window.term = term;
    ht->window.scale = 1;
    ht->window.preferred_buffer_scale = 1;
    ht->window.is_configured = true;

    ht->seat.kbd_focus = term;
    ht->seat.kbd.serial = 1;
    tll_push_back(ht->wl.seats, ht->seat);
    /* Fix the focus pointer after the list copied the seat. */
    tll_front(ht->wl.seats).kbd_focus = term;

    int pipe_fds[2] = {-1, -1};
    if (pipe2(pipe_fds, O_CLOEXEC | O_NONBLOCK) == 0) {
        ht->ptmx_read = pipe_fds[0];
        term->ptmx = pipe_fds[1];
    } else {
        term->ptmx = -1;
    }

    term->cols = columns;
    term->rows = rows;
    term->cell_width = cell_width;
    term->cell_height = cell_height;
    term->width = columns * cell_width;
    term->height = rows * cell_height;
    term->scale = 1.0f;
    term->visual_focus = false;
    term->kbd_focus = false;
    term->blink.fd = -1;
    term->flash.fd = -1;
    term->cursor_blink.fd = -1;
    term->selection.auto_scroll.fd = -1;
    term->scroll_region.start = 0;
    term->scroll_region.end = rows;
    term->normal.cur_row = NULL;
    term->alt.cur_row = NULL;

    const int normal_rows = total_grid_rows(rows, transcript_rows);
    init_grid(&term->normal, normal_rows, columns);
    init_grid(&term->alt, next_pow2(rows), columns);
    term->grid = &term->normal;

    for (int i = 0; i < columns; i += 8)
        tll_push_back(term->tab_stops, i);

    conf->title = strdup("");
    conf->term = strdup("xterm-256color");
    conf->scrollback.lines = (uint32_t)history_limit(ht);
    conf->scrollback.multiplier = 1.0f;
    conf->security.osc52 = OSC52_COPY_ENABLED;
    conf->cursor.style = CURSOR_BLOCK;
    conf->cursor.unfocused_style = CURSOR_UNFOCUSED_UNCHANGED;
    conf->cursor.blink.enabled = false;
    conf->bold_in_bright.enabled = true;
    conf->bold_in_bright.palette_based = true;
    conf->initial_color_theme = COLOR_THEME_DARK;
    conf->tweak.grapheme_width_method = GRAPHEME_WIDTH_DOUBLE;
    conf->tweak.grapheme_shaping = false;
    conf->tweak.sixel = true;
    conf->tweak.surface_bit_depth = SHM_BITS_8;
    /* Android has no URL-mode UI. Keep OSC-8 links visible by reusing the
     * upstream underline pass instead of disabling the URL attribute. */
    conf->url.osc8_underline = OSC8_UNDERLINE_ALWAYS;
    conf->url.style = UNDERLINE_SINGLE;
    conf->bell.system_bell = true;
    conf->bell.urgent = false;
    conf->bell.notify = false;
    conf->bell.flash = false;
    conf->hold_at_exit = false;
    conf->render_worker_count = 0;

    int32_t default_palette[FOOT_RENDER_PALETTE_SIZE];
    if (palette != NULL)
        memcpy(default_palette, palette, sizeof(default_palette));
    else
        memset(default_palette, 0, sizeof(default_palette));
    memcpy(ht->palette, default_palette, sizeof(ht->palette));
    memcpy(ht->defaults, default_palette, sizeof(ht->defaults));
    set_color_theme(&conf->colors_dark, default_palette);
    set_color_theme(&conf->colors_light, default_palette);
    headless_set_palette(ht, default_palette, default_palette);

    term_reset(term, true);
    foot_render_model_reset(ht->render_model, term, history_size(ht));
    tll_free(term->normal.scroll_damage);
    tll_free(term->alt.scroll_damage);
    ht->scroll_count = 0;
    ht->scroll_base = 0;
    ht->last_append_flags = 0;
    return ht;
}

void
headless_reset(struct headless_terminal *ht)
{
    if (ht == NULL)
        return;
    struct terminal *term = &ht->term;
    struct config *conf = (struct config *)term->conf;
    vt_filter_reset(ht->filter);
    set_color_theme(&conf->colors_dark, ht->defaults);
    set_color_theme(&conf->colors_light, ht->defaults);
    term_reset(term, true);
    foot_render_model_reset(ht->render_model, term, history_size(ht));
    headless_palette(ht, ht->palette);
    /* The monotonic counter survives a reset, while the baseline is moved so
     * the next viewport query starts from zero. */
    tll_free(term->normal.scroll_damage);
    tll_free(term->alt.scroll_damage);
    ht->scroll_base = ht->scroll_count;
    ht->last_append_flags = 0;
    flush_ptmx_buffers(ht);
}

void
headless_resize(
    struct headless_terminal *ht, int columns, int rows,
    int cell_width, int cell_height)
{
    if (ht == NULL)
        return;
    if (columns < 2) columns = 2;
    if (rows < 2) rows = 2;
    if (cell_width < 1) cell_width = 1;
    if (cell_height < 1) cell_height = 1;

    struct terminal *term = &ht->term;
    const bool grid_changed = term->cols != columns || term->rows != rows;
    const bool cell_size_changed =
        term->cell_width != cell_width || term->cell_height != cell_height;
    if (!grid_changed) {
        /* Font/cell metrics only. Reflowing the grid here would be wasteful
         * and could churn scrollback/line-break state for no reason. */
        ht->cell_width = cell_width;
        ht->cell_height = cell_height;
        term->cell_width = cell_width;
        term->cell_height = cell_height;
        term->width = columns * cell_width;
        term->height = rows * cell_height;
        if (cell_size_changed)
            sixel_cell_size_changed(term);
        return;
    }
    const int old_rows = term->rows;
    const int old_columns = term->cols;
    const int old_cursor_row = term->normal.cursor.point.row;
    const int old_saved_cursor_row = term->normal.saved_cursor.point.row;
    const int new_normal_rows = total_grid_rows(rows, ht->transcript_rows);
    const int new_alt_rows = next_pow2(rows);

    /*
     * foot tracks the cursor's cell as content while reflowing. When the cursor
     * sits one cell past the last glyph on a wrapped line, that blank cell can
     * make a line exactly fill the new width and spill into an extra row. Pull
     * the tracked cell back onto the glyph; the next-write position is restored
     * below.
     */
    bool restore_cursor_after_glyph = false;
    if (columns > old_columns && term->normal.cursor.point.col > 0) {
        struct row *row = grid_row(&term->normal, term->normal.cursor.point.row);
        if (row != NULL) {
            const int cursor_col = term->normal.cursor.point.col;
            const struct cell *cell = &row->cells[cursor_col];
            if (cursor_col < old_columns &&
                (cell->wc == 0 || cell->wc == CELL_SPACER))
            {
                for (int c = cursor_col - 1; c >= 0; c--) {
                    const char32_t wc = row->cells[c].wc;
                    if (wc != 0 && wc != CELL_SPACER) {
                        if (cursor_col == c + 1) {
                            term->normal.cursor.point.col = c;
                            restore_cursor_after_glyph = true;
                        }
                        break;
                    }
                }
            }
        }
    }

    struct coord *const no_tracking_points[1] = {NULL};
    grid_resize_and_reflow(
        &term->normal, term, new_normal_rows, columns, old_rows, rows, 0,
        no_tracking_points);

    if (restore_cursor_after_glyph) {
        if (term->normal.cursor.point.col + 1 < columns) {
            term->normal.cursor.point.col++;
            term->normal.cursor.lcf = false;
        } else {
            term->normal.cursor.point.col = columns - 1;
            term->normal.cursor.lcf = true;
        }
    }

    /*
     * When the screen does not grow, keep the cursor on the same visual row.
     * foot intentionally anchors reflow to the last row, which is right when
     * pulling lines out of scrollback, but it means shrinking height pushes
     * still-visible top rows into history. Termux keeps the cursor's row and
     * discards blank rows below it instead.
     */
    if (rows <= old_rows) {
        const int rows_per_grid = term->normal.num_rows;
        const int cursor_abs =
            (term->normal.offset + term->normal.cursor.point.row) & (rows_per_grid - 1);
        const int desired_row = old_cursor_row < rows ? old_cursor_row : rows - 1;
        int new_offset = cursor_abs - desired_row;
        while (new_offset < 0)
            new_offset += rows_per_grid;
        new_offset &= rows_per_grid - 1;

        int index = new_offset;
        while (term->normal.rows[index] == NULL) {
            index = (index + 1) & (rows_per_grid - 1);
            if (index == new_offset)
                break;
            new_offset = index;
        }

        const int old_offset = term->normal.offset;
        const int old_history = history_size(ht);
        const bool view_follows = term->normal.view == old_offset;
        int view_delta = new_offset - old_offset;
        if (view_delta < 0)
            view_delta += rows_per_grid;

        int shift_back = old_offset - new_offset;
        if (shift_back < 0)
            shift_back += rows_per_grid;
        int new_history = old_history - shift_back;
        if (new_history < 0)
            new_history = 0;

        /* Reflow normally leaves no rows after the visible screen. Moving the
         * viewport back can expose those stale rows, which the ring arithmetic
         * would then mistake for scrollback at the opposite end. */
        const int unused_rows = rows_per_grid - rows - new_history;
        for (int i = 0; i < unused_rows; i++) {
            const int row_index = (new_offset + rows + i) & (rows_per_grid - 1);
            if (term->normal.rows[row_index] != NULL) {
                grid_row_free(term->normal.rows[row_index]);
                term->normal.rows[row_index] = NULL;
            }
        }

        term->normal.offset = new_offset;
        term->normal.view = view_follows
            ? new_offset
            : (term->normal.view + view_delta) & (rows_per_grid - 1);
        term->normal.cursor.point.row = desired_row;
        term->normal.saved_cursor.point.row =
            old_saved_cursor_row < rows ? old_saved_cursor_row : rows - 1;
        term->normal.cur_row =
            term->normal.rows[(new_offset + desired_row) & (rows_per_grid - 1)];

        for (int r = 0; r < rows; r++) {
            const int row_index = (new_offset + r) & (rows_per_grid - 1);
            if (term->normal.rows[row_index] == NULL) {
                term->normal.rows[row_index] = grid_row_alloc(columns, true);
            }
        }
    }

    const int old_alt_cursor_row = term->alt.cursor.point.row;
    grid_resize_without_reflow(&term->alt, new_alt_rows, columns, old_rows, rows);
    if (rows > old_rows && old_alt_cursor_row < rows) {
        /* With no scrollback, growing the alt screen adds space below the
         * cursor instead of moving the cursor to the new bottom row. */
        term->alt.cursor.point.row = old_alt_cursor_row;
        term->alt.cur_row = grid_row(&term->alt, old_alt_cursor_row);
    }

    term->cols = columns;
    term->rows = rows;
    term->scroll_region.start = 0;
    term->scroll_region.end = rows;
    term->width = columns * cell_width;
    term->height = rows * cell_height;
    term->cell_width = cell_width;
    term->cell_height = cell_height;
    ((struct config *)term->conf)->scrollback.lines = (uint32_t)history_limit(ht);
    ht->cell_width = cell_width;
    ht->cell_height = cell_height;
    if (cell_size_changed)
        sixel_cell_size_changed(term);
    sixel_reflow(term);
    tll_free(term->tab_stops);
    for (int i = 0; i < columns; i += 8)
        tll_push_back(term->tab_stops, i);

    if (term->normal.cursor.point.col >= columns)
        term->normal.cursor.point.col = columns - 1;
    if (term->alt.cursor.point.col >= columns)
        term->alt.cursor.point.col = columns - 1;
    if (term->normal.cursor.point.row >= rows)
        term->normal.cursor.point.row = rows - 1;
    if (term->alt.cursor.point.row >= rows)
        term->alt.cursor.point.row = rows - 1;

    selection_cancel(term);
    /* Reflow may leave damage records behind, but a resize is not a scroll and
     * must not shift the viewport anchor. */
    tll_free(term->normal.scroll_damage);
    tll_free(term->alt.scroll_damage);
    foot_render_model_resize(ht->render_model, term, history_size(ht));
}

char *
headless_drain_events(struct headless_terminal *ht)
{
    flush_ptmx_buffers(ht);
    if (ht->events_head == NULL)
        return NULL;

    size_t length = 0;
    for (struct event_record *node = ht->events_head; node != NULL; node = node->next)
        length += strlen(node->text) + 1;

    char *result = malloc(length + 1);
    if (result == NULL) {
        while (ht->events_head != NULL) {
            struct event_record *next = ht->events_head->next;
            free(ht->events_head->text);
            free(ht->events_head);
            ht->events_head = next;
        }
        ht->events_tail = NULL;
        return NULL;
    }

    size_t offset = 0;
    while (ht->events_head != NULL) {
        struct event_record *next = ht->events_head->next;
        size_t len = strlen(ht->events_head->text);
        memcpy(result + offset, ht->events_head->text, len);
        offset += len;
        result[offset++] = '\n';
        free(ht->events_head->text);
        free(ht->events_head);
        ht->events_head = next;
    }
    result[offset > 0 ? offset - 1 : 0] = '\0';
    ht->events_tail = NULL;
    return result;
}

static int32_t *
headless_render_impl(
    struct headless_terminal *ht, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    float font_width, int font_line_spacing, int font_ascent,
    int columns, int rows, bool force_all_rows, bool force_all_sixels,
    int *out_count)
{
    int cell_width = (int)(font_width + 0.5f);
    if (cell_width < 1)
        cell_width = ht->cell_width;
    int cell_height = font_line_spacing > 0
        ? font_line_spacing : ht->cell_height;
    int baseline = font_ascent < 0 ? -font_ascent : font_ascent;
    if (baseline <= 0)
        baseline = cell_height * 4 / 5;

    const struct foot_canvas_metrics metrics = {
        .cell_width = cell_width,
        .cell_height = cell_height,
        .font_baseline = baseline,
        .background_alpha = background_alpha,
        .cursor_shape = cursor_shape,
        .cursor_visible = cursor_visible,
    };
    foot_canvas_prepare_frame(&ht->term, &metrics);

    return foot_render_model_render_frame(
        ht->render_model, &ht->term, ht->palette, history_size(ht), top_row,
        selection_y1, selection_y2, selection_x1, selection_x2,
        cursor_visible, cursor_shape, background_alpha,
        columns, rows, force_all_rows, force_all_sixels, out_count);
}

int32_t *
headless_render_filtered(
    struct headless_terminal *ht, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    float font_width, int font_line_spacing, int font_ascent,
    int columns, int rows, bool force_all_rows, int *out_count)
{
    return headless_render_impl(
        ht, top_row, selection_y1, selection_y2, selection_x1, selection_x2,
        cursor_visible, cursor_shape, background_alpha, font_width,
        font_line_spacing, font_ascent, columns, rows, force_all_rows,
        force_all_rows, out_count);
}

int32_t *
headless_render(
    struct headless_terminal *ht, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    float font_width, int font_line_spacing, int font_ascent,
    int columns, int rows, int *out_count)
{
    /* Legacy ABI: every row is emitted, but damage ranges and sixel visibility
     * keep their historical meaning. New callers use headless_render_filtered(). */
    return headless_render_impl(
        ht, top_row, selection_y1, selection_y2, selection_x1, selection_x2,
        cursor_visible, cursor_shape, background_alpha, font_width,
        font_line_spacing, font_ascent, columns, rows, true, false, out_count);
}

int32_t *
headless_render_text(struct headless_terminal *ht, int *out_count)
{
    return foot_render_model_render_text(ht->render_model, out_count);
}

uint32_t *
headless_sixel_pixels(
    struct headless_terminal *ht, uint64_t image_id,
    int expected_width, int expected_height, uint32_t expected_revision,
    int *out_width, int *out_height, uint32_t *out_revision)
{
    if (out_width != NULL)
        *out_width = 0;
    if (out_height != NULL)
        *out_height = 0;
    if (out_revision != NULL)
        *out_revision = 0;
    if (ht == NULL || image_id == 0)
        return NULL;

    tll_foreach(ht->term.grid->sixel_images, it) {
        struct sixel *six = &it->item;
        if (six->pix == NULL ||
            foot_canvas_image_id(six->pix) != image_id)
        {
            continue;
        }

        const uint32_t revision = foot_canvas_image_revision(six->pix);
        if (six->width != expected_width ||
            six->height != expected_height ||
            revision != expected_revision)
        {
            return NULL;
        }

        uint32_t *pixels = pixman_image_get_data(six->pix);
        if (pixels == NULL)
            return NULL;

        if (out_width != NULL)
            *out_width = six->width;
        if (out_height != NULL)
            *out_height = six->height;
        if (out_revision != NULL)
            *out_revision = revision;
        return pixels;
    }

    return NULL;
}

void
headless_destroy(struct headless_terminal *ht)
{
    if (ht == NULL)
        return;
    struct terminal *term = &ht->term;

    if (term->ptmx >= 0)
        close(term->ptmx);
    if (ht->ptmx_read >= 0)
        close(ht->ptmx_read);

    urls_reset(term);

    grid_free(&term->normal);
    grid_free(&term->alt);
    composed_free(term->composed);
    tll_free(term->tab_stops);
    tll_free_and_free(term->window_title_stack, free);
    free(term->color_stack.stack);
    notify_free(term, &term->kitty_notification);
    tll_foreach(term->active_notifications, it) {
        notify_free(term, &it->item);
        tll_remove(term->active_notifications, it);
    }
    for (size_t i = 0; i < ALEN(term->notification_icons); i++)
        notify_icon_free(&term->notification_icons[i]);
    sixel_destroy_all(term);
    sixel_fini(term);
    tll_free(term->normal.sixel_images);
    tll_free(term->alt.sixel_images);
    tll_free(term->normal.scroll_damage);
    tll_free(term->alt.scroll_damage);
    tll_foreach(term->ptmx_buffers, it)
        free(it->item.data);
    tll_free(term->ptmx_buffers);
    tll_foreach(term->ptmx_paste_buffers, it)
        free(it->item.data);
    tll_free(term->ptmx_paste_buffers);
    tll_free(ht->wl.seats);
    free(term->window_title);
    free(term->app_id);
    free(term->mouse_user_cursor);
    free(term->vt.osc.data);
    free(term->vt.osc8.uri);
    free(term->vt.dcs.data);
    free(term->sixel.private_palette);
    free(term->sixel.shared_palette);

    struct config *conf = (struct config *)term->conf;
    free(conf->title);
    free(conf->term);

    while (ht->events_head != NULL) {
        struct event_record *next = ht->events_head->next;
        free(ht->events_head->text);
        free(ht->events_head);
        ht->events_head = next;
    }

    free(ht->append_dirty);
    free(ht->viewport_before);
    foot_render_model_destroy(ht->render_model);
    foot_canvas_cleanup_term(term);
    vt_filter_destroy(ht->filter);
    free(ht);
}

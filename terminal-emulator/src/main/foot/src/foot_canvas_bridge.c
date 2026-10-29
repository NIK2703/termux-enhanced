/*
 * Upstream foot cell renderer bridged to the Canvas command stream.
 *
 * render_cell() and render_row() are extracted verbatim from the pinned
 * third_party/foot/render.c (see foot-render-cell.inc). Only the pixman/fcft
 * shim and this thin adapter are Android-specific.
 */

#include "foot_canvas_backend.h"

#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "char32.h"
#include "config.h"
#include "grid.h"
#include "macros.h"
#include "terminal.h"
#include "util.h"
#include "xmalloc.h"

/* Declared by the shim. */
uint16_t srgb_decode_8_to_16(uint8_t value);
struct fcft_glyph *box_drawing(const struct terminal *term, char32_t wc);

#include "foot-render-cell.inc"

static int
clamp_int(int value, int min_value, int max_value)
{
    if (value < min_value)
        return min_value;
    if (value > max_value)
        return max_value;
    return value;
}

static void
destroy_fonts(struct terminal *term)
{
    for (size_t i = 0; i < ALEN(term->fonts); i++) {
        if (term->fonts[i] != NULL) {
            fcft_destroy(term->fonts[i]);
            term->fonts[i] = NULL;
        }
    }
}

static bool
fonts_match(
    const struct terminal *term, int cell_width, int cell_height,
    int baseline)
{
    const struct fcft_font *font = term->fonts[0];
    return font != NULL &&
        font->max_advance.x == cell_width &&
        font->height == cell_height &&
        font->ascent == baseline;
}

static bool
create_fonts(
    struct terminal *term, int cell_width, int cell_height, int baseline)
{
    destroy_fonts(term);
    for (int i = 0; i < 4; i++) {
        term->fonts[i] =
            foot_canvas_font_create(cell_width, cell_height, baseline, i);
        if (term->fonts[i] == NULL) {
            destroy_fonts(term);
            return false;
        }
    }
    return true;
}

bool
foot_canvas_prepare_frame(
    struct terminal *term, const struct foot_canvas_metrics *metrics)
{
    if (term == NULL || metrics == NULL)
        return false;

    const int cell_width = metrics->cell_width > 0
        ? metrics->cell_width : 1;
    const int cell_height = metrics->cell_height > 0
        ? metrics->cell_height : 1;
    int baseline = metrics->font_baseline;
    if (baseline <= 0)
        baseline = cell_height * 4 / 5;
    baseline = clamp_int(baseline, 1, cell_height - 1);

    if (!fonts_match(term, cell_width, cell_height, baseline) &&
        !create_fonts(term, cell_width, cell_height, baseline))
    {
        return false;
    }

    term->cell_width = cell_width;
    term->cell_height = cell_height;
    term->font_baseline = baseline;
    term->font_subpixel = FCFT_SUBPIXEL_NONE;
    term->font_x_ofs = 0;
    term->font_y_ofs = 0;
    term->width = term->cols * cell_width;
    term->height = term->rows * cell_height;
    term->font_line_height.px = (int16_t)cell_height;

    struct config *conf = (struct config *)term->conf;
    if (conf != NULL) {
        conf->box_drawings_uses_font_glyphs = true;
        conf->can_shape_grapheme = false;
        conf->tweak.grapheme_shaping = false;
        conf->tweak.overflowing_glyphs = false;
        conf->url.style = UNDERLINE_NONE;
        conf->use_custom_underline_offset = false;
        conf->underline_thickness = (struct pt_or_px){.px = -1, .pt = 0};
        conf->strikeout_thickness = (struct pt_or_px){.px = -1, .pt = 0};
        conf->cursor.beam_thickness = (struct pt_or_px){.px = -1, .pt = 0};
        conf->cursor.underline_thickness =
            (struct pt_or_px){.px = -1, .pt = 0};
        conf->cursor.unfocused_style = CURSOR_UNFOCUSED_UNCHANGED;
        conf->colors_dark.alpha_mode = ALPHA_MODE_DEFAULT;

        /* foot's config default is 1.5 in color_blend_towards(), i.e. a
         * one-third blend towards black. The headless config is calloc'd, so
         * set it explicitly to keep SGR dim matching the legacy planner. */
        conf->dim.amount = 1.5f;
    }

    int shape = metrics->cursor_shape;
    if (shape < 0 || shape > 3)
        shape = 0;
    term->cursor_style = shape == 1
        ? CURSOR_UNDERLINE
        : shape == 2
            ? CURSOR_BEAM
            : shape == 3
                ? CURSOR_HOLLOW
                : CURSOR_BLOCK;
    if (conf != NULL)
        conf->cursor.style = term->cursor_style;

    term->cursor_blink.fd = -1;
    term->cursor_blink.state = CURSOR_BLINK_ON;
    term->blink.fd = 0; /* non-negative: never arm a real timer */
    term->blink.state = BLINK_ON;

    /* Cursor visibility is carried by the per-row cursor column, not by the
     * terminal's focus state. Leave kbd_focus alone here: the VT core uses it
     * for focus reporting, and the renderer temporarily forces the focused
     * cursor style only while render_row() runs. */

    const int alpha = clamp_int(metrics->background_alpha, 0, 255);
    term->colors.alpha = (uint16_t)(alpha * 0x101);

    /* Upstream treats selection colours without an alpha byte as custom
     * colours. Setting the high byte forces the built-in invert-selection
     * behaviour that matches the existing Android renderer. */
    term->colors.selection_fg = 0x80000000u;
    term->colors.selection_bg = 0x80000000u;

    /* cursor_colors_for_cell() uses the high bit as "custom cursor colour". */
    const uint32_t cursor_color =
        term->colors.cursor_bg & 0x00ffffffu;
    term->colors.cursor_bg = 0x80000000u | cursor_color;
    term->colors.cursor_fg = 0x80000000u | (term->colors.bg & 0x00ffffffu);

    term->window->is_fullscreen = false;
    return true;
}

static void
cell_span(
    const struct row *row, int columns, int col,
    int *span_start, int *span_end)
{
    const char32_t wc = row->cells[col].wc;

    if (wc >= CELL_SPACER) {
        /* A spacer cell stores its width minus one in the marker value. */
        int width_minus_one = (int)(wc - CELL_SPACER);
        if (width_minus_one < 0)
            width_minus_one = 0;
        *span_start = col - width_minus_one;
        *span_end = col;
    } else if (col + 1 < columns && row->cells[col + 1].wc >= CELL_SPACER) {
        int width_minus_one = (int)(row->cells[col + 1].wc - CELL_SPACER);
        if (width_minus_one < 0)
            width_minus_one = 0;
        *span_start = col;
        *span_end = col + width_minus_one;
    } else {
        *span_start = col;
        *span_end = col;
    }

    if (*span_start < 0)
        *span_start = 0;
    if (*span_end >= columns)
        *span_end = columns - 1;
}

void
foot_canvas_prepare_row(
    struct terminal *term, struct row *row,
    int selection_start, int selection_end)
{
    if (term == NULL || row == NULL || row->cells == NULL)
        return;

    for (int col = 0; col < term->cols; col++) {
        row->cells[col].attrs.clean = false;
        row->cells[col].attrs.selected = false;
    }

    if (selection_start < 0 || selection_end < selection_start)
        return;

    /* A selection endpoint may land on either half of a wide character. Mark
     * the whole grapheme, matching the old planner and foot's cell model. */
    for (int col = 0; col < term->cols; col++) {
        int span_start;
        int span_end;
        cell_span(row, term->cols, col, &span_start, &span_end);
        if (span_end < selection_start || span_start > selection_end)
            continue;
        for (int selected = span_start; selected <= span_end; selected++)
            row->cells[selected].attrs.selected = true;
    }
}

static bool
cell_needs_canvas_render(const struct cell *cell, bool has_cursor)
{
    if (has_cursor || cell->attrs.selected || cell->attrs.reverse)
        return true;

    /* A non-default background or a text decoration is visible even when the
     * cell itself is blank. Foreground-only attributes on a blank cell are
     * not. */
    if (cell->attrs.bg_src != COLOR_DEFAULT ||
        cell->attrs.underline ||
        cell->attrs.strikethrough ||
        cell->attrs.url)
    {
        return true;
    }

    return cell->wc != 0 &&
        cell->wc != U' ' &&
        cell->wc != U'\t' &&
        cell->wc < CELL_SPACER;
}

void
foot_canvas_render_row(
    struct terminal *term, struct row *row, int row_no, int cursor_col,
    const struct foot_canvas_sink *sink)
{
    if (term == NULL || row == NULL || sink == NULL)
        return;

    struct pixman_image *target =
        foot_canvas_target_create(sink, row_no, term->cell_height);
    if (target == NULL)
        return;

    /* has_cursor already encodes whether the cursor is currently visible.
     * Force the focused style for this synchronous render so a blink-off frame
     * does not switch to the unfocused cursor style or leak a transient focus
     * state into the VT core. */
    const bool kbd_focus = term->kbd_focus;
    term->kbd_focus = true;

    /* Upstream render_row() walks right-to-left. That order is fine for a
     * pixel backend, but Canvas text runs must be emitted in logical
     * left-to-right order so adjacent cells can be coalesced into one
     * drawTextRun(). The per-cell renderer itself is still the verbatim
     * upstream render_cell(); only the trivial loop direction is adapted. */
    int first_col = 0;
    int last_col = term->cols - 1;

    /* A fully default-background row is already painted by Java's row base
     * fill. Avoid walking every blank cell through render_cell(); that is
     * pure overhead during scrolling. Rows with a cursor, selection, explicit
     * background, reverse video or styled underline ranges keep the full
     * walk. */
    if (cursor_col < 0 && !term->reverse && row->extra == NULL) {
        first_col = term->cols;
        last_col = -1;
        for (int col = 0; col < term->cols; col++) {
            if (!cell_needs_canvas_render(&row->cells[col], false))
                continue;
            if (first_col == term->cols)
                first_col = col;
            last_col = col;
        }
        if (last_col < first_col) {
            term->kbd_focus = kbd_focus;
            foot_canvas_target_destroy(target);
            return;
        }
    }

    for (int col = first_col; col <= last_col; col++)
        render_cell(term, target, NULL, row, row_no, col, cursor_col == col);

    term->kbd_focus = kbd_focus;
    foot_canvas_target_destroy(target);
}

void
foot_canvas_cleanup_term(struct terminal *term)
{
    if (term == NULL)
        return;
    destroy_fonts(term);
}

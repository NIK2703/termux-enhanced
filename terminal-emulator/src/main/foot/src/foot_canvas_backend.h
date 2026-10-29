#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <uchar.h>

#include <pixman.h>

struct terminal;
struct row;
struct fcft_font;

/*
 * Canvas compatibility backend for upstream foot render_cell().
 *
 * render_row() is part of the extracted, hash-checked upstream block, but the
 * bridge calls render_cell() directly in left-to-right order so Canvas text
 * runs stay in logical order. The callbacks receive row-relative pixel
 * coordinates. The render model turns them into packed render commands;
 * TerminalRenderer executes those commands on Android's Canvas.
 */
struct foot_canvas_sink {
    void *data;
    void (*fill_rect)(
        void *data, int x, int y, int width, int height, uint32_t argb);
    void (*draw_glyph)(
        void *data, char32_t codepoint, int x, int baseline, uint32_t argb,
        int font_index);
    void (*fill_trapezoid)(
        void *data,
        int x1, int y1, int x2, int y2,
        int x3, int y3, int x4, int y4,
        uint32_t argb);
};

struct foot_canvas_metrics {
    int cell_width;
    int cell_height;
    int font_baseline;
    int background_alpha;
    int cursor_shape;
    bool cursor_visible;
};

/* Install synthetic fcft metrics and the Canvas-specific config. Safe to call
 * once per frame; fonts are only recreated when the metrics change. */
bool foot_canvas_prepare_frame(
    struct terminal *term, const struct foot_canvas_metrics *metrics);

/* Mark the selection and clear attrs.clean before calling render_row(). */
void foot_canvas_prepare_row(
    struct terminal *term, struct row *row,
    int selection_start, int selection_end);

/* Run upstream render_cell() left-to-right for one row with the Canvas sink. */
void foot_canvas_render_row(
    struct terminal *term, struct row *row, int row_no, int cursor_col,
    const struct foot_canvas_sink *sink);

void foot_canvas_cleanup_term(struct terminal *term);

/* Synthetic font constructor used by the shim. */
struct fcft_font *foot_canvas_font_create(
    int cell_width, int cell_height, int ascent, int font_index);

/* Opaque target image used to run upstream render_row(). */
pixman_image_t *foot_canvas_target_create(
    const struct foot_canvas_sink *sink, int row, int cell_height);
void foot_canvas_target_destroy(pixman_image_t *target);

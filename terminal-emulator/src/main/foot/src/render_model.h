#pragma once

#include <stdbool.h>
#include <stdint.h>

struct terminal;
struct foot_render_model;

/*
 * Palette layout shared by the adapter and the render planner. Entries 0..255
 * are the terminal palette, followed by the three effective UI colours.
 */
enum foot_render_palette {
    FOOT_RENDER_PALETTE_SIZE = 259,
    FOOT_RENDER_COLOR_FOREGROUND = 256,
    FOOT_RENDER_COLOR_BACKGROUND = 257,
    FOOT_RENDER_COLOR_CURSOR = 258,
};

/*
 * Native viewport, damage and frame planning for the Android renderer.
 *
 * This is the part of foot's render policy that can be shared across backends:
 * it keeps the viewport in sync with foot's grid (grid->view), tracks
 * full-frame damage, turns a committed Canvas frame into cleared native dirty
 * bits, and plans the frame/row/cell command stream from the grid.
 *
 * Pixel work (fonts, shaping, Canvas, clip, transparency) stays in Java.
 */

struct foot_render_model *foot_render_model_create(void);
void foot_render_model_destroy(struct foot_render_model *model);

/* Reset after headless_create/headless_reset. The viewport follows the live
 * bottom and every visible row is considered damaged. */
void foot_render_model_reset(
    struct foot_render_model *model, struct terminal *term, int history);

/* Resize: keep the viewport address, but repaint the new visible set. */
void foot_render_model_resize(
    struct foot_render_model *model, struct terminal *term, int history);

/* The UI selected a new viewport (mTopRow). This is an address relative to
 * the live screen top and is clamped to [-history, 0]. Changing it damages
 * every visible row because the content under each address changes. */
void foot_render_model_set_view(
    struct foot_render_model *model, struct terminal *term,
    int top_row, int history);

/* Output has moved the ring while the viewport followed it (or stayed on an
 * absolute content anchor). Re-read foot's grid->view without treating the
 * address change as a user scroll. */
void foot_render_model_sync_view(
    struct foot_render_model *model, struct terminal *term, int history);

int foot_render_model_view_top(const struct foot_render_model *model);

/* Request a complete repaint of the current visible set. */
void foot_render_model_damage_all(struct foot_render_model *model);
bool foot_render_model_is_full_damage(const struct foot_render_model *model);

/* True when this visible external row still needs painting. */
bool foot_render_model_row_is_damaged(
    const struct foot_render_model *model, struct terminal *term,
    int external_row);

/* The renderer has put these visible rows on the Canvas. Clear their native
 * dirty bits. Full damage is only retired once every visible row has been
 * committed, so a partial frame cannot lose damage. */
void foot_render_model_commit_rows(
    struct foot_render_model *model, struct terminal *term,
    const int *rows, int count, int history);

/* A full-clip frame was painted. */
void foot_render_model_commit_all(
    struct foot_render_model *model, struct terminal *term, int history);

/*
 * Plan one packed frame:
 *
 *   OP_FRAME_BEGIN, OP_DAMAGE...,
 *   OP_ROW_BEGIN, OP_RECT/OP_CURSOR/OP_FILL_RECT/OP_GLYPH..., OP_ROW_END,
 *   ...,
 *   OP_FRAME_END, OP_END, text length, UTF-16 units
 *
 * The returned buffer is borrowed until the next render_frame() call on the
 * same model. `top_row` is the first row of the requested band, not the
 * viewport top; partial frames start at an arbitrary row inside the viewport.
 */
int32_t *foot_render_model_render_frame(
    struct foot_render_model *model, struct terminal *term,
    const int32_t *palette, int history, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    int columns, int rows, int *out_count);

/* Copy of the UTF-16 payload referenced by the most recent frame. Caller owns
 * the returned array. */
int32_t *foot_render_model_render_text(
    struct foot_render_model *model, int *out_count);

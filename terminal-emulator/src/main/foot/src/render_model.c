#include "render_model.h"

#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "composed.h"
#include "foot_canvas_backend.h"
#include "grid.h"
#include "terminal.h"

/* Render command opcodes. Kept in sync with TerminalRenderer.java. */
enum render_opcode {
    OP_END = 0,
    OP_ROW_BEGIN = 1,
    OP_ROW_END = 2,
    OP_RECT = 3,
    OP_TEXT = 4,
    OP_CURSOR = 5,
    OP_FRAME_BEGIN = 6,
    OP_DAMAGE = 7,
    OP_FRAME_END = 8,
    OP_FILL_RECT = 9,
    OP_GLYPH = 10,
    OP_FILL_TRAPEZOID = 11,
};

/* Effect bits shared with TerminalRenderer.java. */
enum render_effect {
    EFFECT_BLOCK_FILL = 1 << 7,
};

struct foot_render_model {
    /* Viewport top as an external row address: 0 is the live screen top,
     * negative values are transcript rows above it. */
    int view_top;
    int rows;
    bool full_damage;

    /* Rows painted since the last full-damage request. Only used while
     * full_damage is set, to avoid retiring it after a partial frame. */
    unsigned char *committed;
    int committed_cap;
    int committed_count;

    /* Reused frame command/text buffers. The command buffer is borrowed by
     * JNI until the next frame; the text buffer only lives for one frame. */
    int32_t *commands;
    size_t commands_cap;
    int32_t *text;
    size_t text_len;
    size_t text_cap;
    uint32_t frame_id;

    /* Upstream render_cell()/render_row() through the Canvas shim. The legacy
     * planner remains available as a temporary fallback for A/B testing. */
    bool use_canvas;
};

static int
clamp_top(int top, int history)
{
    if (history < 0)
        history = 0;
    if (top > 0)
        top = 0;
    if (top < -history)
        top = -history;
    return top;
}

static bool
ensure_committed(struct foot_render_model *model)
{
    if (model->rows <= 0)
        return false;
    if (model->committed_cap >= model->rows)
        return true;

    unsigned char *resized = realloc(
        model->committed, (size_t)model->rows);
    if (resized == NULL)
        return false;
    model->committed = resized;
    model->committed_cap = model->rows;
    return true;
}

static void
reset_committed(struct foot_render_model *model)
{
    model->committed_count = 0;
    if (model->committed != NULL && model->rows > 0)
        memset(model->committed, 0, (size_t)model->rows);
}

static int
grid_view_top(const struct terminal *term)
{
    const struct grid *grid = term->grid;
    int top = grid->view - grid->offset;
    if (top > 0)
        top -= grid->num_rows;
    return top;
}

static int
external_row_index(const struct terminal *term, int external_row)
{
    const struct grid *grid = term->grid;
    return (grid->offset + external_row) & (grid->num_rows - 1);
}

static struct row *
row_for_external(const struct terminal *term, int external_row)
{
    const struct grid *grid = term->grid;
    const int index = external_row_index(term, external_row);
    if (index < 0 || index >= grid->num_rows)
        return NULL;
    return grid->rows[index];
}

static bool
row_is_visible(const struct foot_render_model *model, int external_row)
{
    return external_row >= model->view_top &&
           external_row < model->view_top + model->rows;
}

struct foot_render_model *
foot_render_model_create(void)
{
    struct foot_render_model *model = calloc(1, sizeof(struct foot_render_model));
    if (model != NULL)
        model->use_canvas = true;
    return model;
}

void
foot_render_model_destroy(struct foot_render_model *model)
{
    if (model == NULL)
        return;
    free(model->committed);
    free(model->commands);
    free(model->text);
    free(model);
}

void
foot_render_model_reset(
    struct foot_render_model *model, struct terminal *term, int history)
{
    if (model == NULL || term == NULL)
        return;

    model->rows = term->rows;
    model->view_top = 0;
    term->grid->view = term->grid->offset;
    model->full_damage = true;
    (void)history;
    if (ensure_committed(model))
        reset_committed(model);
}

void
foot_render_model_resize(
    struct foot_render_model *model, struct terminal *term, int history)
{
    if (model == NULL || term == NULL)
        return;

    model->rows = term->rows;
    model->full_damage = true;
    if (ensure_committed(model))
        reset_committed(model);

    /* Reflow may have moved the ring and clamped the viewport. Re-read the
     * address but do not acquire the user-scroll full-damage path twice. */
    model->view_top = clamp_top(grid_view_top(term), history);
    if (term->grid->view !=
        (int)((term->grid->offset + model->view_top) &
              (term->grid->num_rows - 1)))
    {
        term->grid->view =
            (term->grid->offset + model->view_top) & (term->grid->num_rows - 1);
    }
}

void
foot_render_model_set_view(
    struct foot_render_model *model, struct terminal *term,
    int top_row, int history)
{
    if (model == NULL || term == NULL)
        return;

    const int top = clamp_top(top_row, history);
    const int view = (term->grid->offset + top) & (term->grid->num_rows - 1);
    if (top == model->view_top && view == term->grid->view) {
        model->rows = term->rows;
        return;
    }

    term->grid->view = view;
    model->view_top = top;
    model->rows = term->rows;

    /* The same addresses now show different content. */
    model->full_damage = true;
    if (ensure_committed(model))
        reset_committed(model);
}

void
foot_render_model_sync_view(
    struct foot_render_model *model, struct terminal *term, int history)
{
    if (model == NULL || term == NULL)
        return;

    model->rows = term->rows;
    const int top = clamp_top(grid_view_top(term), history);
    if (top == model->view_top)
        return;

    /* A downward move means foot could not keep the content under the old
     * address (the viewport was at the scrollback top and output wrapped the
     * ring). Every visible slot may now show different content, so commit
     * bookkeeping from the old address is meaningless. */
    const bool moved_down = top > model->view_top;

    /* Following output shifted the address but kept the content under the
     * viewport. This is not a user scroll and must not request a full frame
     * unless the address actually moved down. */
    model->view_top = top;
    if (grid_view_top(term) != top) {
        term->grid->view =
            (term->grid->offset + top) & (term->grid->num_rows - 1);
    }

    if (moved_down)
        model->full_damage = true;

    /* If full damage is already pending, rows committed at the old address no
     * longer identify the same viewport slots. Keep the request, start over. */
    if (model->full_damage && ensure_committed(model))
        reset_committed(model);
}

int
foot_render_model_view_top(const struct foot_render_model *model)
{
    return model != NULL ? model->view_top : 0;
}

void
foot_render_model_set_use_canvas(
    struct foot_render_model *model, bool use_canvas)
{
    if (model != NULL)
        model->use_canvas = use_canvas;
}

void
foot_render_model_damage_all(struct foot_render_model *model)
{
    if (model == NULL)
        return;
    model->full_damage = true;
    if (ensure_committed(model))
        reset_committed(model);
}

bool
foot_render_model_is_full_damage(const struct foot_render_model *model)
{
    return model != NULL && model->full_damage;
}

bool
foot_render_model_row_is_damaged(
    const struct foot_render_model *model, struct terminal *term,
    int external_row)
{
    if (model == NULL || term == NULL || !row_is_visible(model, external_row))
        return false;
    if (model->full_damage)
        return true;

    const struct row *row = row_for_external(term, external_row);
    return row != NULL && row->dirty;
}

void
foot_render_model_commit_rows(
    struct foot_render_model *model, struct terminal *term,
    const int *rows, int count, int history)
{
    if (model == NULL || term == NULL || rows == NULL || count <= 0)
        return;

    if (history < 0)
        history = 0;

    bool full = model->full_damage;
    if (full && !ensure_committed(model))
        full = false;

    for (int i = 0; i < count; i++) {
        const int external = rows[i];
        if (!row_is_visible(model, external) ||
            external < -history || external >= term->rows)
        {
            continue;
        }

        struct row *row = row_for_external(term, external);
        if (row != NULL)
            row->dirty = false;

        if (full) {
            const int slot = external - model->view_top;
            if (slot >= 0 && slot < model->rows &&
                !model->committed[slot])
            {
                model->committed[slot] = 1;
                model->committed_count++;
            }
        }
    }

    if (full && model->committed_count >= model->rows) {
        model->full_damage = false;
        reset_committed(model);
    }
}

void
foot_render_model_commit_all(
    struct foot_render_model *model, struct terminal *term, int history)
{
    if (model == NULL || term == NULL)
        return;

    if (history < 0)
        history = 0;

    for (int i = 0; i < model->rows; i++) {
        const int external = model->view_top + i;
        if (external < -history || external >= term->rows)
            continue;

        struct row *row = row_for_external(term, external);
        if (row != NULL)
            row->dirty = false;
    }

    model->full_damage = false;
    reset_committed(model);
}

static int32_t
color_argb(
    const int32_t *palette, enum color_source source,
    uint32_t value, int default_index)
{
    switch (source) {
    case COLOR_DEFAULT:
        return palette[default_index];
    case COLOR_BASE16:
    case COLOR_BASE256:
        return palette[value & 0xff];
    case COLOR_RGB:
        return (int32_t)(0xff000000u | (value & 0xffffffu));
    }
    return palette[default_index];
}

static int32_t
with_alpha(int32_t color, int alpha)
{
    if (alpha >= 255)
        return color;
    if (alpha < 0)
        alpha = 0;
    return (color & 0x00ffffff) | (alpha << 24);
}

static int32_t
without_lightness(int32_t color)
{
    /* Keep the alpha channel, invert only the visible RGB colour. */
    const uint32_t rgb = ((uint32_t)color & 0x00ffffffu) ^ 0x00ffffffu;
    return (int32_t)(((uint32_t)color & 0xff000000u) | rgb);
}

static int
cell_width(const struct terminal *term, const struct row *row, int column)
{
    if (row == NULL || column + 1 >= term->cols)
        return 1;
    const char32_t next = row->cells[column + 1].wc;
    if (next >= CELL_SPACER)
        return (int)(next - CELL_SPACER) + 1;
    return 1;
}

static bool
cell_intersects_selection(
    bool in_selection, int selection_start, int selection_end,
    int column, int width)
{
    return in_selection &&
        column <= selection_end &&
        column + width - 1 >= selection_start;
}

static int
effects_for_cell(const struct cell *cell)
{
    int effects = 0;
    if (cell->attrs.bold) effects |= 1;
    if (cell->attrs.italic) effects |= 2;
    if (cell->attrs.underline) effects |= 4;
    if (cell->attrs.blink) effects |= 8;
    if (cell->attrs.reverse) effects |= 16;
    if (cell->attrs.conceal) effects |= 32;
    if (cell->attrs.strikethrough) effects |= 64;
    if (cell->wc >= 0x2580 && cell->wc <= 0x259f)
        effects |= EFFECT_BLOCK_FILL;
    if (cell->attrs.dim) effects |= 256;
    return effects;
}

static bool
reserve_commands(struct foot_render_model *model, size_t needed)
{
    if (needed <= model->commands_cap)
        return true;

    size_t new_capacity = model->commands_cap > 0 ? model->commands_cap : 8;
    while (new_capacity < needed)
        new_capacity *= 2;

    int32_t *resized = realloc(
        model->commands, new_capacity * sizeof(resized[0]));
    if (resized == NULL)
        return false;

    model->commands = resized;
    model->commands_cap = new_capacity;
    return true;
}

static bool
render_text_append(
    struct foot_render_model *model, struct terminal *term, char32_t wc)
{
    if (wc >= CELL_COMB_CHARS_LO && wc <= CELL_COMB_CHARS_HI) {
        const struct composed *composed =
            composed_lookup(term->composed, wc - CELL_COMB_CHARS_LO);
        if (composed != NULL && composed->chars != NULL) {
            for (size_t i = 0; i < composed->count; i++) {
                if (model->text_len + 2 > model->text_cap) {
                    size_t cap = model->text_cap == 0 ? 256 : model->text_cap * 2;
                    while (cap < model->text_len + 2) cap *= 2;
                    int32_t *resized = realloc(
                        model->text, cap * sizeof(resized[0]));
                    if (resized == NULL)
                        return false;
                    model->text = resized;
                    model->text_cap = cap;
                }
                char32_t cp = composed->chars[i];
                if (cp <= 0xffff) {
                    model->text[model->text_len++] = (int32_t)cp;
                } else {
                    cp -= 0x10000;
                    model->text[model->text_len++] = 0xd800 + (cp >> 10);
                    model->text[model->text_len++] = 0xdc00 + (cp & 0x3ff);
                }
            }
            return true;
        }
        wc = ' ';
    }

    if (wc == 0 || wc >= CELL_SPACER)
        wc = ' ';

    if (model->text_len + 2 > model->text_cap) {
        size_t cap = model->text_cap == 0 ? 256 : model->text_cap * 2;
        while (cap < model->text_len + 2) cap *= 2;
        int32_t *resized = realloc(model->text, cap * sizeof(resized[0]));
        if (resized == NULL)
            return false;
        model->text = resized;
        model->text_cap = cap;
    }

    if (wc <= 0xffff) {
        model->text[model->text_len++] = (int32_t)wc;
    } else {
        wc -= 0x10000;
        model->text[model->text_len++] = 0xd800 + (wc >> 10);
        model->text[model->text_len++] = 0xdc00 + (wc & 0x3ff);
    }
    return true;
}

struct canvas_sink_ctx {
    struct foot_render_model *model;
    struct terminal *term;
    int *count;
    int columns;
    int32_t base_bg;
    int last_glyph_cmd;
    int last_glyph_x;
    int last_glyph_width;
    int last_glyph_cols;
    int last_glyph_baseline;
    uint32_t last_glyph_argb;
    int last_glyph_effects;
    int last_full_fill_cmd;
    int last_full_fill_x;
    int last_full_fill_y;
    int last_full_fill_width;
    uint32_t last_full_fill_argb;
    bool failed;
};

static void
canvas_reset_glyph_run(struct canvas_sink_ctx *ctx)
{
    ctx->last_glyph_cmd = -1;
    ctx->last_glyph_x = 0;
    ctx->last_glyph_width = 0;
    ctx->last_glyph_cols = 0;
}

static int32_t *
canvas_append(struct canvas_sink_ctx *ctx, size_t fields)
{
    if (ctx->failed)
        return NULL;
    if (!reserve_commands(ctx->model, (size_t)*ctx->count + fields)) {
        ctx->failed = true;
        return NULL;
    }
    int32_t *out = ctx->model->commands + *ctx->count;
    *ctx->count += (int)fields;
    return out;
}

static void
canvas_fill_rect(
    void *data, int x, int y, int width, int height, uint32_t argb)
{
    struct canvas_sink_ctx *ctx = data;
    if (width <= 0 || height <= 0)
        return;

    /* Termux applies the configured background alpha to every painted
     * surface, not only to COLOR_DEFAULT cells. Upstream's ALPHA_MODE_DEFAULT
     * leaves explicit/reverse/selection/cursor fills opaque, while the legacy
     * planner used with_alpha() on all of them. A full-cell fill is a
     * background or a block cursor; text decorations are thinner and keep
     * their opaque foreground. */
    if ((argb >> 24) == 0xFF && height == ctx->term->cell_height) {
        const uint32_t alpha = (uint32_t)ctx->base_bg >> 24;
        if (alpha < 0xFF)
            argb = (argb & 0x00FFFFFFu) | (alpha << 24);
    }

    /* The default background is painted by Java for the whole row. Do not
     * break a text run for a fill that is skipped here. */
    if ((int32_t)argb == ctx->base_bg)
        return;

    /* Coalesce adjacent full-cell fills with the same colour. This is the
     * common case for selection, reverse video and coloured status lines:
     * without it every cell emits its own fill and breaks the glyph run.
     * Extending the earlier fill is safe because it was emitted before the
     * glyphs that follow it; those glyphs then remain eligible for batching. */
    const bool full_cell_fill = y == 0 && height == ctx->term->cell_height;
    if (full_cell_fill &&
        ctx->last_full_fill_cmd >= 0 &&
        ctx->last_full_fill_argb == argb &&
        ctx->last_full_fill_y == y &&
        ctx->last_full_fill_x + ctx->last_full_fill_width == x)
    {
        ctx->model->commands[ctx->last_full_fill_cmd + 3] += width;
        ctx->last_full_fill_width += width;
        return;
    }

    canvas_reset_glyph_run(ctx);
    int32_t *cmd = canvas_append(ctx, 6);
    if (cmd == NULL)
        return;
    cmd[0] = OP_FILL_RECT;
    cmd[1] = x;
    cmd[2] = y;
    cmd[3] = width;
    cmd[4] = height;
    cmd[5] = (int32_t)argb;

    if (full_cell_fill) {
        ctx->last_full_fill_cmd = *ctx->count - 6;
        ctx->last_full_fill_x = x;
        ctx->last_full_fill_y = y;
        ctx->last_full_fill_width = width;
        ctx->last_full_fill_argb = argb;
    }
}

static void
canvas_draw_glyph(
    void *data, char32_t codepoint, int x, int baseline, uint32_t argb,
    int font_index)
{
    struct canvas_sink_ctx *ctx = data;
    if (ctx->failed || codepoint == 0 || (argb >> 24) == 0)
        return;
    if (codepoint >= CELL_COMB_CHARS_LO && codepoint <= CELL_COMB_CHARS_HI)
        return;

    int cols = wcwidth((wchar_t)codepoint);
    if (cols < 0)
        cols = 1;
    if (cols > 2)
        cols = 2;

    /*
     * render_cell() emits the base glyph followed by its combining marks.
     * Merge zero-width marks into the preceding Canvas text run so Android
     * shapes them together instead of drawing a standalone mark.
     */
    if (cols == 0 && ctx->last_glyph_cmd >= 0) {
        const int start_char = (int)ctx->model->text_len;
        if (!render_text_append(ctx->model, ctx->term, codepoint)) {
            ctx->failed = true;
            return;
        }
        const int added = (int)ctx->model->text_len - start_char;
        ctx->model->commands[ctx->last_glyph_cmd + 2] += added;
        return;
    }

    int effects =
        ((font_index & 1) ? 1 : 0) | ((font_index & 2) ? 2 : 0);

    /* The Canvas backend paints a whole glyph via drawTextRun(); unlike the
     * upstream underline/strike passes it has no separate fill for block
     * elements. Mark those runs so TerminalRenderer can keep their alpha
     * stable with SRC_ATOP instead of punching an opaque foreground hole
     * through a translucent terminal background. This bit also splits a
     * block-art run from neighbouring text, because the transfer mode must
     * not affect ordinary glyphs. */
    if (codepoint >= 0x2580 && codepoint <= 0x259f)
        effects |= EFFECT_BLOCK_FILL;

    /* A space has no ink, so its transfer mode is irrelevant. Keep the
     * previous block-fill flag on it so block art separated by blank cells
     * still coalesces into one Canvas run (and the wordmark remains one
     * drawTextRun instead of three). */
    if (codepoint == U' ' && ctx->last_glyph_cmd >= 0)
        effects |= ctx->last_glyph_effects & EFFECT_BLOCK_FILL;

    const int cell_width = ctx->term->cell_width;

    /*
     * Upstream paint_*() backends draw one glyph at a time. Android's Canvas
     * has a comparatively high cost per drawTextRun() call, so coalesce
     * adjacent cells with identical style into one text run. This is the same
     * run shape the legacy planner emitted (OP_TEXT), just built from the
     * upstream per-cell renderer instead of a second cell walk.
     */
    if (ctx->last_glyph_cmd >= 0 &&
        ctx->last_glyph_baseline == baseline &&
        ctx->last_glyph_argb == argb &&
        ctx->last_glyph_effects == effects &&
        ctx->last_glyph_cols == cols)
    {
        const int expected_x =
            ctx->last_glyph_x + ctx->last_glyph_width * cell_width;

        int gap = 0;
        if (x > expected_x && (x - expected_x) % cell_width == 0)
            gap = (x - expected_x) / cell_width;

        /* Spaces are skipped by render_cell(); put them back into the run so a
         * word gap does not split an otherwise contiguous row. Wide glyph
         * gaps are left alone to keep shaping clusters predictable. */
        if (x == expected_x || (cols == 1 && gap > 0 && gap <= 256)) {
            const int start_char = (int)ctx->model->text_len;
            for (int i = 0; i < gap; i++) {
                if (!render_text_append(ctx->model, ctx->term, U' ')) {
                    ctx->failed = true;
                    return;
                }
            }
            if (!render_text_append(ctx->model, ctx->term, codepoint)) {
                ctx->failed = true;
                return;
            }
            const int added = (int)ctx->model->text_len - start_char;
            ctx->model->commands[ctx->last_glyph_cmd + 2] += added;
            ctx->model->commands[ctx->last_glyph_cmd + 5] += gap + cols;
            ctx->last_glyph_width += gap + cols;
            return;
        }
    }

    /* A style/colour/width change, or a non-contiguous position, starts a new
     * run. Combining marks can still attach to it afterwards. */
    canvas_reset_glyph_run(ctx);

    const int start_char = (int)ctx->model->text_len;
    if (!render_text_append(ctx->model, ctx->term, codepoint)) {
        ctx->failed = true;
        return;
    }
    const int char_count = (int)ctx->model->text_len - start_char;
    if (char_count <= 0)
        return;

    int32_t *cmd = canvas_append(ctx, 8);
    if (cmd == NULL)
        return;
    cmd[0] = OP_GLYPH;
    cmd[1] = start_char;
    cmd[2] = char_count;
    cmd[3] = x;
    cmd[4] = baseline;
    cmd[5] = cols;
    cmd[6] = (int32_t)argb;
    cmd[7] = effects;
    ctx->last_glyph_cmd = *ctx->count - 8;
    ctx->last_glyph_x = x;
    ctx->last_glyph_width = cols;
    ctx->last_glyph_cols = cols;
    ctx->last_glyph_baseline = baseline;
    ctx->last_glyph_argb = argb;
    ctx->last_glyph_effects = effects;
}

static void
canvas_fill_trapezoid(
    void *data,
    int x1, int y1, int x2, int y2,
    int x3, int y3, int x4, int y4,
    uint32_t argb)
{
    struct canvas_sink_ctx *ctx = data;
    if ((int32_t)argb == ctx->base_bg)
        return;

    canvas_reset_glyph_run(ctx);
    int32_t *cmd = canvas_append(ctx, 10);
    if (cmd == NULL)
        return;
    cmd[0] = OP_FILL_TRAPEZOID;
    cmd[1] = x1;
    cmd[2] = y1;
    cmd[3] = x2;
    cmd[4] = y2;
    cmd[5] = x3;
    cmd[6] = y3;
    cmd[7] = x4;
    cmd[8] = y4;
    cmd[9] = (int32_t)argb;
}

static const struct foot_canvas_sink canvas_sink = {
    .fill_rect = canvas_fill_rect,
    .draw_glyph = canvas_draw_glyph,
    .fill_trapezoid = canvas_fill_trapezoid,
};

static bool
render_canvas_row(
    struct foot_render_model *model, struct terminal *term,
    struct row *native_row, int row, int cursor_col,
    int selection_start, int selection_end, int columns, int32_t base_bg,
    int *count)
{
    if (!reserve_commands(model, (size_t)*count + 2))
        return false;

    int32_t *commands = model->commands;
    commands[(*count)++] = OP_ROW_BEGIN;
    commands[(*count)++] = row;

    struct canvas_sink_ctx ctx = {
        .model = model,
        .term = term,
        .count = count,
        .columns = columns,
        .base_bg = base_bg,
        .last_glyph_cmd = -1,
        .last_full_fill_cmd = -1,
        .failed = false,
    };

    foot_canvas_prepare_row(
        term, native_row, selection_start, selection_end);
    struct foot_canvas_sink sink = canvas_sink;
    sink.data = &ctx;
    foot_canvas_render_row(
        term, native_row, row, cursor_col, &sink);
    if (ctx.failed)
        return false;

    if (!reserve_commands(model, (size_t)*count + 1))
        return false;
    model->commands[(*count)++] = OP_ROW_END;
    return true;
}

struct render_run {
    int start_column;
    int width_columns;
    int start_char;
    int char_count;
    int32_t fg;
    int32_t bg;
    int effects;
    bool cursor;
};

int32_t *
foot_render_model_render_frame(
    struct foot_render_model *model, struct terminal *term,
    const int32_t *palette, int history, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    int columns, int rows, int *out_count)
{
    if (model == NULL || term == NULL || palette == NULL || out_count == NULL) {
        if (out_count != NULL)
            *out_count = 0;
        return NULL;
    }

    *out_count = 0;

    const int term_columns = term->cols;
    const int screen_rows = term->rows;
    if (history < 0)
        history = 0;
    if (columns <= 0 || columns > term_columns)
        columns = term_columns;
    if (rows < 0)
        rows = 0;

    const int min_top = -history;
    if (top_row < min_top)
        top_row = min_top;
    if (top_row > screen_rows)
        top_row = screen_rows;

    int end_row = top_row + rows;
    if (end_row < top_row)
        end_row = screen_rows;
    if (end_row > screen_rows)
        end_row = screen_rows;

    const int cursor_row = term->grid->cursor.point.row;
    const int cursor_col = term->grid->cursor.point.col;
    const int32_t base_bg = with_alpha(
        term->reverse
            ? palette[FOOT_RENDER_COLOR_FOREGROUND]
            : palette[FOOT_RENDER_COLOR_BACKGROUND],
        background_alpha);
    const int32_t cursor_color = with_alpha(
        palette[FOOT_RENDER_COLOR_CURSOR], background_alpha);

    model->text_len = 0;

    const bool frame_full_damage =
        foot_render_model_is_full_damage(model);

    /* Coalesce consecutive damaged rows into ranges for the frame header. */
    int damage_ranges = 0;
    bool in_damage = false;
    for (int row = top_row; row < end_row; row++) {
        const bool damaged = foot_render_model_row_is_damaged(
            model, term, row);
        if (damaged && !in_damage) {
            damage_ranges++;
            in_damage = true;
        } else if (!damaged) {
            in_damage = false;
        }
    }

    const size_t header_size =
        (size_t)(rows + 1) * 32 + (size_t)damage_ranges * 3 + 32;
    if (!reserve_commands(model, header_size))
        return NULL;

    int32_t *commands = model->commands;
    int count = 0;

    /* Frame header: the renderer can make the per-row draw decision from the
     * native damage without consulting the Java mirror. Row coordinates are
     * external addresses, exactly like OP_ROW_BEGIN. */
    commands[count++] = OP_FRAME_BEGIN;
    commands[count++] = (int32_t)model->frame_id++;
    commands[count++] = foot_render_model_view_top(model);
    commands[count++] = frame_full_damage ? 1 : 0;
    commands[count++] = term_columns;
    commands[count++] = screen_rows;
    commands[count++] = top_row;
    commands[count++] = end_row - top_row;
    commands[count++] = damage_ranges;

    int range_first = 0;
    in_damage = false;
    for (int row = top_row; row < end_row; row++) {
        const bool damaged = foot_render_model_row_is_damaged(
            model, term, row);
        if (damaged && !in_damage) {
            range_first = row;
            in_damage = true;
        } else if (!damaged && in_damage) {
            commands[count++] = OP_DAMAGE;
            commands[count++] = range_first;
            commands[count++] = row - 1;
            in_damage = false;
        }
    }
    if (in_damage) {
        commands[count++] = OP_DAMAGE;
        commands[count++] = range_first;
        commands[count++] = end_row - 1;
    }

    /* A phone-sized terminal fits in the stack buffer; only very wide grids
     * pay for a heap allocation. The run table never escapes this function. */
    struct render_run stack_runs[256];
    struct render_run *runs = stack_runs;
    bool runs_heap = false;
    if ((size_t)(columns + 1) > sizeof(stack_runs) / sizeof(stack_runs[0])) {
        runs = malloc((size_t)(columns + 1) * sizeof(runs[0]));
        if (runs == NULL)
            goto fail;
        runs_heap = true;
    }

    for (int row = top_row; row < end_row; row++) {
        if (row < min_top || row >= screen_rows)
            continue;

        struct row *native_row = row_for_external(term, row);
        const bool is_cursor_row = cursor_visible && row == cursor_row;

        if (model->use_canvas && native_row != NULL &&
            term->fonts[0] != NULL) {
            const bool in_selection =
                row >= selection_y1 && row <= selection_y2;
            int selection_start =
                row == selection_y1 ? selection_x1 : 0;
            int selection_end =
                row == selection_y2 ? selection_x2 : columns - 1;
            if (!in_selection) {
                selection_start = -1;
                selection_end = -1;
            } else {
                if (selection_start < 0)
                    selection_start = 0;
                if (selection_end > columns - 1)
                    selection_end = columns - 1;
                if (selection_end < selection_start) {
                    selection_start = -1;
                    selection_end = -1;
                }
            }

            const int canvas_cursor_col =
                is_cursor_row ? cursor_col : -1;
            if (!render_canvas_row(
                    model, term, native_row, row, canvas_cursor_col,
                    selection_start, selection_end, columns, base_bg, &count))
            {
                goto fail;
            }
            continue;
        }

        if (native_row == NULL) {
            /* Still emit a row frame: TerminalRenderer fills the row with the
             * base colour and commits it on OP_ROW_END. Skipping a blank row
             * here would leave stale pixels in partial frames. */
            if (!reserve_commands(model, (size_t)count + 16))
                goto fail;
            commands = model->commands;

            const bool in_selection =
                row >= selection_y1 && row <= selection_y2;
            const int selection_start =
                row == selection_y1 ? selection_x1 : 0;
            const int selection_end =
                row == selection_y2 ? selection_x2 : columns - 1;

            commands[count++] = OP_ROW_BEGIN;
            commands[count++] = row;
            if (in_selection && selection_end >= selection_start) {
                int start = selection_start;
                int end = selection_end;
                if (start < 0) start = 0;
                if (end > columns - 1) end = columns - 1;
                if (end >= start) {
                    int32_t selection_bg = with_alpha(
                        term->reverse
                            ? palette[FOOT_RENDER_COLOR_BACKGROUND]
                            : palette[FOOT_RENDER_COLOR_FOREGROUND],
                        background_alpha);
                    if ((selection_bg & 0x00ffffff) ==
                        (base_bg & 0x00ffffff))
                    {
                        selection_bg = without_lightness(selection_bg);
                    }
                    if (selection_bg != base_bg) {
                        commands[count++] = OP_RECT;
                        commands[count++] = start;
                        commands[count++] = end + 1;
                        commands[count++] = selection_bg;
                        commands[count++] = 1; /* RECT_SPAN_VIEW */
                    }
                }
            }
            if (is_cursor_row && cursor_col >= 0 && cursor_col < columns) {
                commands[count++] = OP_CURSOR;
                commands[count++] = cursor_col;
                commands[count++] = 1;
                commands[count++] = cursor_color;
                commands[count++] = cursor_shape;
            }
            commands[count++] = OP_ROW_END;
            continue;
        }

        const bool in_selection =
            row >= selection_y1 && row <= selection_y2;
        const int selection_start =
            row == selection_y1 ? selection_x1 : 0;
        const int selection_end =
            row == selection_y2 ? selection_x2 : columns - 1;

        int run_count = 0;
        int cursor_run = -1;
        int column = 0;

        while (column < columns) {
            const struct cell *cell = &native_row->cells[column];
            if (cell->wc >= CELL_SPACER) {
                column++;
                continue;
            }

            int width = cell_width(term, native_row, column);
            if (width > columns - column)
                width = columns - column;

            const bool inside_cursor = is_cursor_row &&
                cursor_col >= column && cursor_col < column + width;
            const bool inside_selection = cell_intersects_selection(
                in_selection, selection_start, selection_end, column, width);
            const bool cursor_block = inside_cursor && cursor_shape == 0;

            int32_t fg = color_argb(
                palette, cell->attrs.fg_src, cell->attrs.fg,
                FOOT_RENDER_COLOR_FOREGROUND);
            int32_t bg = color_argb(
                palette, cell->attrs.bg_src, cell->attrs.bg,
                FOOT_RENDER_COLOR_BACKGROUND);

            /* Termux's long-standing bold-in-bright rule: only the first
             * eight palette entries are brightened, not arbitrary 256-colour
             * indexes that happen to share the same low bits. */
            if (cell->attrs.bold &&
                (cell->attrs.fg_src == COLOR_BASE16 ||
                 cell->attrs.fg_src == COLOR_BASE256) &&
                cell->attrs.fg < 8)
            {
                fg = palette[cell->attrs.fg + 8];
            }

            const bool reverse_here =
                term->reverse || inside_selection || cursor_block;
            if (reverse_here ^ cell->attrs.reverse) {
                int32_t swap = fg;
                fg = bg;
                bg = swap;
            }

            /* Selection must stay visible when a cell explicitly uses the
             * same foreground and background. foot does the same in
             * render_cell() by inverting the selected background. */
            if (inside_selection &&
                (fg & 0x00ffffff) == (bg & 0x00ffffff))
            {
                bg = without_lightness(bg);
            }
            bg = with_alpha(bg, background_alpha);

            const int start_char = (int)model->text_len;
            if (!render_text_append(model, term, cell->wc))
                goto fail;
            const int char_count = (int)model->text_len - start_char;
            const int effects = effects_for_cell(cell);

            struct render_run run = {
                .start_column = column,
                .width_columns = width,
                .start_char = start_char,
                .char_count = char_count,
                .fg = fg,
                .bg = bg,
                .effects = effects,
                .cursor = inside_cursor,
            };

            bool merged = false;
            if (run_count > 0) {
                struct render_run *last = &runs[run_count - 1];
                if (last->fg == run.fg && last->bg == run.bg &&
                    last->effects == run.effects &&
                    last->cursor == run.cursor &&
                    last->start_column + last->width_columns == run.start_column)
                {
                    last->width_columns += run.width_columns;
                    last->char_count =
                        run.start_char + run.char_count - last->start_char;
                    merged = true;
                }
            }
            if (!merged)
                runs[run_count++] = run;
            if (inside_cursor)
                cursor_run = run_count - 1;
            column += width;
        }

        if (!reserve_commands(model, (size_t)count + 8 +
                                     (size_t)run_count * 16))
        {
            goto fail;
        }
        commands = model->commands;

        commands[count++] = OP_ROW_BEGIN;
        commands[count++] = row;

        for (int i = 0; i < run_count; i++) {
            const struct render_run *run = &runs[i];
            if (run->bg == base_bg)
                continue;
            commands[count++] = OP_RECT;
            commands[count++] = run->start_column;
            commands[count++] = run->start_column + run->width_columns;
            commands[count++] = run->bg;
            commands[count++] = 1; /* RECT_SPAN_VIEW */
        }

        if (cursor_run >= 0) {
            const struct render_run *run = &runs[cursor_run];
            commands[count++] = OP_CURSOR;
            commands[count++] = run->start_column;
            commands[count++] = run->width_columns;
            commands[count++] = cursor_color;
            commands[count++] = cursor_shape;
        }

        for (int i = 0; i < run_count; i++) {
            const struct render_run *run = &runs[i];
            if (run->effects & 32)
                continue;

            bool has_content = false;
            for (int c = 0; c < run->char_count; c++) {
                if (run->start_char + c < (int)model->text_len &&
                    model->text[run->start_char + c] != ' ')
                {
                    has_content = true;
                    break;
                }
            }
            const bool decorated = (run->effects & (4 | 64)) != 0;
            if (!has_content && !decorated)
                continue;

            int char_count = run->char_count;
            if (!decorated) {
                while (char_count > 0 &&
                       run->start_char + char_count - 1 < (int)model->text_len &&
                       model->text[run->start_char + char_count - 1] == ' ')
                {
                    char_count--;
                }
            }
            if (char_count == 0)
                continue;

            commands[count++] = OP_TEXT;
            commands[count++] = run->start_char;
            commands[count++] = char_count;
            commands[count++] = run->start_column;
            commands[count++] = run->width_columns;
            commands[count++] = run->fg;
            commands[count++] = run->bg;
            commands[count++] = run->effects;
        }

        commands[count++] = OP_ROW_END;
    }

    /* The Canvas path appends commands from render_cell() through the sink,
     * which may have grown the command buffer while the rows were rendered. */
    commands = model->commands;
    commands[count++] = OP_FRAME_END;

    const size_t text_length = model->text_len;
    if (!reserve_commands(model, (size_t)count + 2 + text_length))
        goto fail;
    commands = model->commands;

    commands[count++] = OP_END;
    /* The UTF-16 payload travels in the same array as the commands, saving a
     * second JNI transition and a second array allocation per frame. */
    commands[count++] = (int32_t)text_length;
    for (size_t i = 0; i < text_length; i++)
        commands[count++] = model->text[i];

    if (runs_heap)
        free(runs);
    *out_count = count;
    return commands;

fail:
    if (runs_heap)
        free(runs);
    *out_count = 0;
    return NULL;
}

int32_t *
foot_render_model_render_text(struct foot_render_model *model, int *out_count)
{
    if (out_count != NULL)
        *out_count = 0;
    if (model == NULL || out_count == NULL || model->text_len == 0)
        return NULL;

    int32_t *copy = malloc(model->text_len * sizeof(copy[0]));
    if (copy == NULL)
        return NULL;
    memcpy(copy, model->text, model->text_len * sizeof(copy[0]));
    *out_count = (int)model->text_len;
    return copy;
}

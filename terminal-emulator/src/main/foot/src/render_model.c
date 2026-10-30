#include "render_model.h"

#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#include "composed.h"
#include "debug.h"
#include "foot_canvas_backend.h"
#include "grid.h"
#include "log.h"
#include "sixel.h"
#include "terminal.h"
#include "util.h"

/*
 * dirty_old_cursor()/dirty_cursor() are extracted verbatim from the pinned
 * upstream render.c. The adapter below only decides when the policy runs:
 * Android renders partial bands and defers row->dirty clearing until Java has
 * committed the Canvas frame, so calling upstream on every partial frame
 * would re-dirty the cursor row forever.
 */
#include "foot-render-damage.inc"

/*
 * pixman_region_for_coords*() and mark_selected_region() are extracted
 * verbatim from the pinned upstream selection.c. They provide foot's normal
 * selection geometry, its old/new region diff, and the selected-bit policy.
 * The adapter maps Android's transcript-relative coordinates onto ring rows
 * and applies the visible-selection policy per painted row.
 */
#include "foot-selection.inc"

/* Render command opcodes. Kept in sync with TerminalRenderer.java. */
enum render_opcode {
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
    OP_SCROLL = 13,
};

/* Effect bits shared with TerminalRenderer.java. */
enum render_effect {
    EFFECT_BLOCK_FILL = 1 << 7,
};

/*
 * Upstream render_row() paints cells right-to-left. That is correct for a
 * pixel backend, but Canvas text runs must be emitted in logical order so
 * adjacent cells can share one drawTextRun(). The sink buffers one row of
 * paint operations, then replays them left-to-right. Sorting by x and then by
 * arrival order also preserves the per-cell order (background, cursor, glyph,
 * decorations) that upstream render_cell() relies on.
 */
enum canvas_draw_kind {
    CANVAS_DRAW_RECT,
    CANVAS_DRAW_GLYPH,
    CANVAS_DRAW_TRAPEZOID,
};

struct canvas_draw_op {
    enum canvas_draw_kind kind;
    int sort_x;
    size_t sequence;
    union {
        struct {
            int x;
            int y;
            int width;
            int height;
            uint32_t argb;
        } rect;
        struct {
            char32_t codepoint;
            int x;
            int baseline;
            uint32_t argb;
            int font_index;
        } glyph;
        struct {
            int x1, y1, x2, y2, x3, y3, x4, y4;
            uint32_t argb;
        } trapezoid;
    } u;
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

    /* Scratch row operation list. Reused across rows and frames. */
    struct canvas_draw_op *draw_ops;
    size_t draw_ops_len;
    size_t draw_ops_cap;

    /*
     * Cursor and selection are overlays: they are not part of row content and
     * therefore do not set row->dirty in the VT core. Track their previous
     * frame state here so native damage remains the single source of truth for
     * what the Canvas has to repaint. Java still mirrors damage for
     * invalidation, but no longer decides which rows are drawn.
     */
    struct row *last_cursor_row;
    int last_cursor_row_index;
    int last_cursor_col;
    bool last_cursor_visible;
    int last_cursor_shape;
    bool cursor_tracking_valid;

    int last_selection_y1;
    int last_selection_y2;
    int last_selection_x1;
    int last_selection_x2;
    bool last_selection_active;
    bool selection_tracking_valid;

    /* Scroll damage queued by headless_append(). Kept until the next render so
     * the Android retained surface can replay it as a pixel blit. */
    tll(struct damage) pending_scroll;

    /* External-row delta accumulated by optimized viewport changes. Android
     * shifts the retained surface by this many rows instead of repainting the
     * whole viewport; consumed by the next successful frame. */
    int viewport_shift;
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

static void
mark_external_row_dirty(struct terminal *term, int external_row)
{
    struct row *row = row_for_external(term, external_row);
    if (row != NULL)
        row->dirty = true;
}

static bool
row_is_actually_damaged(
    const struct foot_render_model *model, struct terminal *term,
    int external_row)
{
    if (foot_render_model_row_is_damaged(model, term, external_row))
        return true;

    /* A missing row is painted as the base colour. There is no dirty bit to
     * consult, so keep emitting it rather than risk leaving stale pixels from
     * a previous buffer at that address. */
    return row_is_visible(model, external_row) &&
           row_for_external(term, external_row) == NULL;
}

static bool
row_needs_emit(
    const struct foot_render_model *model, struct terminal *term,
    int external_row, bool force_all_rows)
{
    return force_all_rows ||
           row_is_actually_damaged(model, term, external_row);
}

static bool
selection_is_active(int y1, int y2, int x1, int x2)
{
    /* Java uses all -1 for "no selection". A real selection may start in the
     * transcript (negative y), including row -1, so x decides the sentinel. */
    return x1 >= 0 && x2 >= 0 && y2 >= y1;
}

static int
selection_grapheme_start(
    const struct terminal *term, int external_row, int col)
{
    const struct row *row = row_for_external(term, external_row);
    if (row == NULL)
        return col;

    /* An endpoint on a spacer half belongs to the base cell, matching
     * upstream selection_modify(). */
    while (col > 0 && row->cells[col].wc >= CELL_SPACER)
        col--;
    return col;
}

static int
selection_grapheme_end(
    const struct terminal *term, int external_row, int col)
{
    const struct row *row = row_for_external(term, external_row);
    if (row == NULL)
        return col;

    while (col + 1 < term->cols && row->cells[col + 1].wc >= CELL_SPACER)
        col++;
    return col;
}

/*
 * Apply upstream selection_dirty_cells()'s visible-selection policy to one
 * row. foot calls that function for every grid_render() and relies on it
 * re-dirtying selected empty cells. Android commits row damage only after Java
 * has drawn the Canvas frame, so calling the whole-grid version every frame
 * would keep those rows dirty forever. Marking is therefore done only for rows
 * that are already being painted; the region geometry and selected-bit policy
 * still come from the extracted upstream selection code.
 */
static void
mark_row_selection(
    struct terminal *term, struct row *row, int external_row)
{
    if (term == NULL || row == NULL || row->cells == NULL)
        return;

    for (int col = 0; col < term->cols; col++)
        row->cells[col].attrs.selected = false;

    if (term->selection.coords.start.row < 0 ||
        term->selection.coords.end.row < 0)
    {
        return;
    }

    const int abs_row = external_row_index(term, external_row);
    const int rel_row = grid_row_abs_to_sb(term->grid, term->rows, abs_row);

    pixman_region32_t selection = pixman_region_for_coords(
        term, &term->selection.coords.start, &term->selection.coords.end);
    pixman_region32_t visible;
    pixman_region32_init(&visible);
    pixman_region32_intersect_rect(
        &visible, &selection, 0, rel_row, (unsigned)term->cols, 1);

    int count = 0;
    pixman_box32_t *boxes = pixman_region32_rectangles(&visible, &count);
    if (boxes != NULL && count > 0) {
        mark_selected_region(
            term, boxes, (size_t)count, MARK_SELECTION_MARK_FOR_RENDER);
    }

    pixman_region32_fini(&visible);
    pixman_region32_fini(&selection);
}

static int clamp_row(int row, int rows);
static bool scroll_damage_applies(
    const struct grid *grid, const struct damage *damage);

static void
apply_upstream_cursor_damage(struct terminal *term, bool visible)
{
    /*
     * dirty_cursor()/dirty_old_cursor() use term->hide_cursor, which is the
     * VT-owned DECTCEM state. Android's cursor_visible argument additionally
     * reflects focus and blink policy, so expose it only for this synchronous
     * damage-policy call and restore the terminal state afterwards.
     */
    const bool hide_cursor = term->hide_cursor;
    term->hide_cursor = !visible;
    dirty_old_cursor(term);
    dirty_cursor(term);
    term->hide_cursor = hide_cursor;
}

/*
 * Foot's grid_render() calls dirty_old_cursor()/dirty_cursor() before building
 * the damage region. The Canvas bridge bypasses grid_render(), so the adapter
 * detects overlay changes and lets the extracted upstream functions dirty the
 * old/new rows.
 */
static void
track_cursor_damage(
    struct foot_render_model *model, struct terminal *term,
    bool visible, int shape)
{
    const struct coord *cursor = &term->grid->cursor.point;
    const int row_index = cursor->row;
    struct row *row = grid_row(term->grid, row_index);
    const int col = cursor->col;

    const bool changed =
        !model->cursor_tracking_valid ||
        visible != model->last_cursor_visible ||
        shape != model->last_cursor_shape ||
        (visible &&
         (row != model->last_cursor_row || col != model->last_cursor_col));

    if (changed) {
        /* The ring index may have been recycled while the viewport was
         * following output. Invalidate upstream's remembered row instead of
         * dirtying a slot that now contains unrelated content. */
        if (term->render.last_cursor.row != NULL &&
            grid_row(term->grid, model->last_cursor_row_index) !=
                model->last_cursor_row)
        {
            term->render.last_cursor.row = NULL;
        }

        apply_upstream_cursor_damage(term, visible);
    }

    model->last_cursor_row = row;
    model->last_cursor_row_index = row_index;
    model->last_cursor_col = col;
    model->last_cursor_visible = visible;
    model->last_cursor_shape = shape;
    model->cursor_tracking_valid = true;
}

static void
mark_selection_region(
    struct terminal *term, const struct coord *start, const struct coord *end,
    enum mark_selection_variant variant)
{
    pixman_region32_t region = pixman_region_for_coords(term, start, end);

    int count = 0;
    pixman_box32_t *boxes = pixman_region32_rectangles(&region, &count);
    if (boxes != NULL && count > 0)
        mark_selected_region(term, boxes, (size_t)count, variant);

    pixman_region32_fini(&region);
}

/*
 * Android selection coordinates are transcript-relative (the same addressing
 * Java uses for copying text). Convert them to absolute ring rows, then let
 * the extracted upstream region code diff the old and new selections exactly
 * like selection_modify() does: rows in both regions are dirtied, and cells
 * are marked/unmarked before the Canvas bridge repaints them.
 */
static void
sync_native_selection(
    struct foot_render_model *model, struct terminal *term,
    int y1, int y2, int x1, int x2)
{
    const int requested_x1 = x1;
    const int requested_x2 = x2;
    const bool active = selection_is_active(y1, y2, x1, x2);
    const bool changed =
        !model->selection_tracking_valid ||
        active != model->last_selection_active ||
        (active &&
         (y1 != model->last_selection_y1 ||
          y2 != model->last_selection_y2 ||
          x1 != model->last_selection_x1 ||
          x2 != model->last_selection_x2));

    if (!changed)
        return;

    term->selection.kind = SELECTION_CHAR_WISE;

    const bool old_active =
        term->selection.coords.start.row >= 0 &&
        term->selection.coords.end.row >= 0;
    if (old_active) {
        mark_selection_region(
            term, &term->selection.coords.start, &term->selection.coords.end,
            MARK_SELECTION_UNMARK_AND_DIRTY);
    }

    if (active) {
        if (x1 < 0)
            x1 = 0;
        if (x2 < 0)
            x2 = 0;
        if (x1 >= term->cols)
            x1 = term->cols - 1;
        if (x2 >= term->cols)
            x2 = term->cols - 1;
        if (y1 == y2 && x2 < x1) {
            const int tmp = x1;
            x1 = x2;
            x2 = tmp;
        }

        x1 = selection_grapheme_start(term, y1, x1);
        x2 = selection_grapheme_end(term, y2, x2);

        term->selection.coords.start = (struct coord){
            .col = x1, .row = external_row_index(term, y1)};
        term->selection.coords.end = (struct coord){
            .col = x2, .row = external_row_index(term, y2)};

        mark_selection_region(
            term, &term->selection.coords.start, &term->selection.coords.end,
            MARK_SELECTION_MARK_AND_DIRTY);
    } else {
        term->selection.coords.start = (struct coord){-1, -1};
        term->selection.coords.end = (struct coord){-1, -1};
    }

    model->last_selection_y1 = y1;
    model->last_selection_y2 = y2;
    model->last_selection_x1 = active ? requested_x1 : -1;
    model->last_selection_x2 = active ? requested_x2 : -1;
    model->last_selection_active = active;
    model->selection_tracking_valid = true;
}

static void
track_scroll_replacement_damage(
    struct foot_render_model *model, struct terminal *term)
{
    if (model->full_damage)
        return;

    tll_foreach(model->pending_scroll, it) {
        const struct damage *damage = &it->item;
        if (!scroll_damage_applies(term->grid, damage))
            continue;

        int start = clamp_row(damage->region.start, term->rows);
        int end = clamp_row(damage->region.end, term->rows);
        if (damage->lines < end - start)
            continue;

        /* No pixel blit can preserve any row in a fully replaced region. */
        for (int r = start; r < end; r++)
            mark_external_row_dirty(term, model->view_top + r);
    }
}

static void
reset_overlay_tracking(
    struct foot_render_model *model, struct terminal *term)
{
    if (term != NULL) {
        /* Do not let the upstream cursor-damage policy touch a row that was
         * remembered before a reset, resize or viewport change. */
        term->render.last_cursor.row = NULL;
        term->render.last_cursor.hidden = true;

        /* A reset always requests full damage, so every visible row is
         * repainted (and its selected bits are rebuilt) from the Java
         * selection coordinates. Drop the old absolute selection first. */
        term->selection.kind = SELECTION_CHAR_WISE;
        term->selection.coords.start = (struct coord){-1, -1};
        term->selection.coords.end = (struct coord){-1, -1};
    }

    model->last_cursor_row = NULL;
    model->last_cursor_row_index = 0;
    model->last_cursor_col = 0;
    model->last_cursor_visible = false;
    model->last_cursor_shape = 0;
    model->cursor_tracking_valid = false;

    model->last_selection_y1 = -1;
    model->last_selection_y2 = -1;
    model->last_selection_x1 = -1;
    model->last_selection_x2 = -1;
    model->last_selection_active = false;
    model->selection_tracking_valid = false;
}

static bool
scroll_damage_applies(const struct grid *grid, const struct damage *damage)
{
    switch (damage->type) {
    case DAMAGE_SCROLL:
    case DAMAGE_SCROLL_REVERSE:
        /* foot only records these when the viewport follows the live screen;
         * the pixel backend must not move a viewport the user scrolled away. */
        return grid->view == grid->offset;
    case DAMAGE_SCROLL_IN_VIEW:
    case DAMAGE_SCROLL_REVERSE_IN_VIEW:
        return true;
    }
    return false;
}

static bool
scroll_damage_is_reverse(const struct damage *damage)
{
    return damage->type == DAMAGE_SCROLL_REVERSE ||
           damage->type == DAMAGE_SCROLL_REVERSE_IN_VIEW;
}

static void
discard_pending_scroll(struct foot_render_model *model)
{
    tll_free(model->pending_scroll);
}

static void
discard_scroll_damage(struct foot_render_model *model, struct terminal *term)
{
    if (term != NULL) {
        tll_free(term->normal.scroll_damage);
        tll_free(term->alt.scroll_damage);
    }
    discard_pending_scroll(model);
}

void
foot_render_model_queue_scroll(
    struct foot_render_model *model, const struct grid *grid)
{
    if (model == NULL || grid == NULL)
        return;
    tll_foreach(grid->scroll_damage, it)
        tll_push_back(model->pending_scroll, it->item);
}

bool
foot_render_model_has_pending_scroll(const struct foot_render_model *model)
{
    return model != NULL && tll_length(model->pending_scroll) > 0;
}

bool
foot_render_model_scroll_covers_row(
    const struct foot_render_model *model, const struct terminal *term,
    int external_row)
{
    if (model == NULL || term == NULL || model->full_damage)
        return false;

    const int view_row = external_row - model->view_top;
    if (view_row < 0 || view_row >= model->rows)
        return false;

    tll_foreach(model->pending_scroll, it) {
        const struct damage *damage = &it->item;
        if (!scroll_damage_applies(term->grid, damage))
            continue;
        if (view_row >= damage->region.start && view_row < damage->region.end)
            return true;
    }
    return false;
}

static int
clamp_row(int row, int rows)
{
    if (row < 0)
        return 0;
    if (row > rows)
        return rows;
    return row;
}

struct foot_render_model *
foot_render_model_create(void)
{
    struct foot_render_model *model = calloc(1, sizeof(*model));
    return model;
}

void
foot_render_model_destroy(struct foot_render_model *model)
{
    if (model == NULL)
        return;
    tll_free(model->pending_scroll);
    free(model->committed);
    free(model->commands);
    free(model->text);
    free(model->draw_ops);
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
    model->viewport_shift = 0;
    reset_overlay_tracking(model, term);
    discard_scroll_damage(model, term);
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
    model->viewport_shift = 0;
    reset_overlay_tracking(model, term);
    discard_scroll_damage(model, term);
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

    const int old_top = model->view_top;
    const int rows = term->rows;
    const int delta = top - old_top;

    /*
     * A neighboring viewport shares rows with the retained Android surface.
     * Move those pixels on the Java side and regenerate only the rows that
     * were outside the old viewport. Overlays (cursor, selection) are row
     * content in foot's renderer, so they move with the pixels; the newly
     * exposed rows are marked dirty and therefore painted with current state.
     *
     * Any pending output scroll means the surface has not yet replayed the
     * last movement, so refuse the optimization and take the full path.
     */
    const bool can_blit =
        delta != 0 && abs(delta) < rows &&
        !model->full_damage &&
        !foot_render_model_has_pending_scroll(model);

    if (can_blit) {
        term->grid->view = view;
        model->view_top = top;
        model->rows = rows;

        if (delta < 0) {
            for (int external = top; external < old_top; external++)
                mark_external_row_dirty(term, external);
        } else {
            for (int external = old_top + rows;
                 external < top + rows; external++)
                mark_external_row_dirty(term, external);
        }

        model->viewport_shift += delta;
        if (ensure_committed(model))
            reset_committed(model);
        return;
    }

    term->grid->view = view;
    model->view_top = top;
    model->rows = rows;

    /* The same addresses now show different content. */
    model->full_damage = true;
    model->viewport_shift = 0;
    reset_overlay_tracking(model, term);
    discard_scroll_damage(model, term);
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
    const bool pending_shift = model->viewport_shift != 0;

    /* Following output shifted the address but kept the content under the
     * viewport. This is not a user scroll and must not request a full frame
     * unless the address actually moved down, or a not-yet-rendered viewport
     * shift would now be applied to a different address space. */
    model->view_top = top;
    if (grid_view_top(term) != top) {
        term->grid->view =
            (term->grid->offset + top) & (term->grid->num_rows - 1);
    }

    /* Selection endpoints are viewport addresses. Following output can move
     * the viewport without changing the content under it, but the addresses
     * still have to be re-evaluated before dirtying old selection rows. */
    if (model->selection_tracking_valid)
        model->selection_tracking_valid = false;

    if (moved_down || pending_shift) {
        model->full_damage = true;
        model->viewport_shift = 0;
        reset_overlay_tracking(model, term);
        discard_scroll_damage(model, term);
    }

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

int
foot_render_model_consume_viewport_shift(struct foot_render_model *model)
{
    if (model == NULL)
        return 0;
    const int shift = model->viewport_shift;
    model->viewport_shift = 0;
    return shift;
}

void
foot_render_model_damage_all(struct foot_render_model *model)
{
    if (model == NULL)
        return;
    model->full_damage = true;
    model->viewport_shift = 0;
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
    model->viewport_shift = 0;
    discard_pending_scroll(model);
    reset_committed(model);
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
    /* Index into model->draw_ops of the last non-combining glyph. */
    int last_glyph_op;
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

static bool
canvas_record_op(struct canvas_sink_ctx *ctx, struct canvas_draw_op op)
{
    struct foot_render_model *model = ctx->model;
    if (ctx->failed)
        return false;

    if (model->draw_ops_len >= model->draw_ops_cap) {
        size_t capacity = model->draw_ops_cap > 0
            ? model->draw_ops_cap * 2 : 64;
        struct canvas_draw_op *resized = realloc(
            model->draw_ops, capacity * sizeof(resized[0]));
        if (resized == NULL) {
            ctx->failed = true;
            return false;
        }
        model->draw_ops = resized;
        model->draw_ops_cap = capacity;
    }

    op.sequence = model->draw_ops_len;
    model->draw_ops[model->draw_ops_len++] = op;
    return true;
}

static int
canvas_op_compare(const void *lhs_ptr, const void *rhs_ptr)
{
    const struct canvas_draw_op *lhs = lhs_ptr;
    const struct canvas_draw_op *rhs = rhs_ptr;

    if (lhs->sort_x != rhs->sort_x)
        return lhs->sort_x < rhs->sort_x ? -1 : 1;
    if (lhs->sequence != rhs->sequence)
        return lhs->sequence < rhs->sequence ? -1 : 1;
    return 0;
}

static void
canvas_collect_fill_rect(
    void *data, int x, int y, int width, int height, uint32_t argb)
{
    struct canvas_sink_ctx *ctx = data;
    if (width <= 0 || height <= 0)
        return;

    struct canvas_draw_op op = {
        .kind = CANVAS_DRAW_RECT,
        .sort_x = x,
    };
    op.u.rect.x = x;
    op.u.rect.y = y;
    op.u.rect.width = width;
    op.u.rect.height = height;
    op.u.rect.argb = argb;
    canvas_record_op(ctx, op);
}

static void
canvas_collect_glyph(
    void *data, char32_t codepoint, int x, int baseline, uint32_t argb,
    int font_index)
{
    struct canvas_sink_ctx *ctx = data;
    if (ctx->failed || codepoint == 0 || (argb >> 24) == 0)
        return;
    if (codepoint >= CELL_COMB_CHARS_LO && codepoint <= CELL_COMB_CHARS_HI)
        return;

    const int cols = wcwidth((wchar_t)codepoint);
    const bool combining = cols == 0;

    int sort_x = x;
    if (combining &&
        ctx->last_glyph_op >= 0 &&
        (size_t)ctx->last_glyph_op < ctx->model->draw_ops_len)
    {
        sort_x = ctx->model->draw_ops[ctx->last_glyph_op].sort_x;
    }

    struct canvas_draw_op op = {
        .kind = CANVAS_DRAW_GLYPH,
        .sort_x = sort_x,
    };
    op.u.glyph.codepoint = codepoint;
    op.u.glyph.x = x;
    op.u.glyph.baseline = baseline;
    op.u.glyph.argb = argb;
    op.u.glyph.font_index = font_index;

    if (!canvas_record_op(ctx, op))
        return;
    if (!combining)
        ctx->last_glyph_op = (int)ctx->model->draw_ops_len - 1;
}

static int
min_int4(int a, int b, int c, int d)
{
    int result = a;
    if (b < result) result = b;
    if (c < result) result = c;
    if (d < result) result = d;
    return result;
}

static void
canvas_collect_fill_trapezoid(
    void *data,
    int x1, int y1, int x2, int y2,
    int x3, int y3, int x4, int y4,
    uint32_t argb)
{
    struct canvas_sink_ctx *ctx = data;

    struct canvas_draw_op op = {
        .kind = CANVAS_DRAW_TRAPEZOID,
        .sort_x = min_int4(x1, x2, x3, x4),
    };
    op.u.trapezoid.x1 = x1;
    op.u.trapezoid.y1 = y1;
    op.u.trapezoid.x2 = x2;
    op.u.trapezoid.y2 = y2;
    op.u.trapezoid.x3 = x3;
    op.u.trapezoid.y3 = y3;
    op.u.trapezoid.x4 = x4;
    op.u.trapezoid.y4 = y4;
    op.u.trapezoid.argb = argb;
    canvas_record_op(ctx, op);
}

static void
canvas_emit_fill_rect(
    struct canvas_sink_ctx *ctx,
    int x, int y, int width, int height, uint32_t argb)
{
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
canvas_emit_glyph(
    struct canvas_sink_ctx *ctx, char32_t codepoint, int x, int baseline,
    uint32_t argb, int font_index)
{
    if (ctx->failed)
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
     * run shape the Canvas sink expects, just built from the
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

        /* Empty cells (wc == 0) do not produce a glyph; put spaces back into
         * the run so a gap does not split an otherwise contiguous row. Wide
         * glyph gaps are left alone to keep shaping clusters predictable. */
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
canvas_emit_fill_trapezoid(
    struct canvas_sink_ctx *ctx,
    int x1, int y1, int x2, int y2,
    int x3, int y3, int x4, int y4,
    uint32_t argb)
{
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

static void
canvas_flush_ops(struct canvas_sink_ctx *ctx)
{
    struct foot_render_model *model = ctx->model;
    if (ctx->failed || model->draw_ops_len == 0) {
        model->draw_ops_len = 0;
        return;
    }

    qsort(model->draw_ops, model->draw_ops_len, sizeof(model->draw_ops[0]),
        canvas_op_compare);

    for (size_t i = 0; i < model->draw_ops_len && !ctx->failed; i++) {
        const struct canvas_draw_op *op = &model->draw_ops[i];
        switch (op->kind) {
        case CANVAS_DRAW_RECT:
            canvas_emit_fill_rect(ctx, op->u.rect.x, op->u.rect.y,
                op->u.rect.width, op->u.rect.height, op->u.rect.argb);
            break;
        case CANVAS_DRAW_GLYPH:
            canvas_emit_glyph(ctx, op->u.glyph.codepoint, op->u.glyph.x,
                op->u.glyph.baseline, op->u.glyph.argb,
                op->u.glyph.font_index);
            break;
        case CANVAS_DRAW_TRAPEZOID:
            canvas_emit_fill_trapezoid(ctx,
                op->u.trapezoid.x1, op->u.trapezoid.y1,
                op->u.trapezoid.x2, op->u.trapezoid.y2,
                op->u.trapezoid.x3, op->u.trapezoid.y3,
                op->u.trapezoid.x4, op->u.trapezoid.y4,
                op->u.trapezoid.argb);
            break;
        }
    }

    model->draw_ops_len = 0;
}

static const struct foot_canvas_sink canvas_sink = {
    .fill_rect = canvas_collect_fill_rect,
    .draw_glyph = canvas_collect_glyph,
    .fill_trapezoid = canvas_collect_fill_trapezoid,
};

static bool
render_canvas_row(
    struct foot_render_model *model, struct terminal *term,
    struct row *native_row, int row, int cursor_col,
    int columns, int32_t base_bg, int *count)
{
    if (!reserve_commands(model, (size_t)*count + 2))
        return false;

    int32_t *commands = model->commands;
    commands[(*count)++] = OP_ROW_BEGIN;
    commands[(*count)++] = row;

    model->draw_ops_len = 0;
    struct canvas_sink_ctx ctx = {
        .model = model,
        .term = term,
        .count = count,
        .columns = columns,
        .base_bg = base_bg,
        .last_glyph_cmd = -1,
        .last_full_fill_cmd = -1,
        .last_glyph_op = -1,
        .failed = false,
    };

    foot_canvas_prepare_row(term, native_row);
    mark_row_selection(term, native_row, row);
    struct foot_canvas_sink sink = canvas_sink;
    sink.data = &ctx;
    foot_canvas_render_row(
        term, native_row, row, cursor_col, &sink);
    canvas_flush_ops(&ctx);
    if (ctx.failed)
        return false;

    if (!reserve_commands(model, (size_t)*count + 1))
        return false;
    model->commands[(*count)++] = OP_ROW_END;
    return true;
}

static bool
render_canvas_sixel_images(
    struct foot_render_model *model, struct terminal *term,
    int top_row, int visible_rows, bool full_damage, bool force_all_sixels,
    int *count)
{
    struct grid *grid = term->grid;
    if (tll_length(grid->sixel_images) == 0)
        return true;

    const int cell_width = term->cell_width;
    const int cell_height = term->cell_height;
    if (cell_width <= 0 || cell_height <= 0)
        return true;

    tll_foreach(grid->sixel_images, it) {
        struct sixel *six = &it->item;

        /* A font/cell-size change invalidates the cached pixman surface. The
         * scaled copy is produced here, before it is handed to Java. */
        sixel_sync_cache(term, six);
        if (six->pix == NULL || six->width <= 0 || six->height <= 0)
            continue;

        /* six->pos.row is an absolute ring index. Convert it to the external
         * viewport address used by OP_ROW_BEGIN, then to pixels relative to
         * the top row of this render call. */
        int external = six->pos.row - grid->offset;
        if (external > grid->num_rows / 2)
            external -= grid->num_rows;
        else if (external < -grid->num_rows / 2)
            external += grid->num_rows;

        const int image_rows = (six->height + cell_height - 1) / cell_height;
        int first_external = external;
        int last_external = external + image_rows - 1;
        if (first_external < top_row)
            first_external = top_row;
        const int render_last = top_row + visible_rows - 1;
        if (last_external > render_last)
            last_external = render_last;
        if (first_external > last_external)
            continue;

        if (!full_damage && !force_all_sixels) {
            bool visible_damage = false;
            for (int row = first_external; row <= last_external; row++) {
                if (foot_render_model_row_is_damaged(model, term, row)) {
                    visible_damage = true;
                    break;
                }
            }
            if (!visible_damage)
                continue;
        }

        if (!reserve_commands(model, (size_t)*count + 9))
            return false;

        const uint64_t image_id = foot_canvas_image_id(six->pix);
        const uint32_t revision = foot_canvas_image_revision(six->pix);
        int32_t *cmd = model->commands + *count;
        *count += 9;
        cmd[0] = OP_IMAGE;
        cmd[1] = six->pos.col * cell_width;
        cmd[2] = (external - top_row) * cell_height;
        cmd[3] = six->width;
        cmd[4] = six->height;
        cmd[5] = (int32_t)(image_id & 0xffffffffu);
        cmd[6] = (int32_t)(image_id >> 32);
        cmd[7] = (int32_t)revision;
        cmd[8] = six->opaque ? 1 : 0;
    }

    return true;
}

int32_t *
foot_render_model_render_frame(
    struct foot_render_model *model, struct terminal *term,
    const int32_t *palette, int history, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    int columns, int rows, bool force_all_rows, bool force_all_sixels,
    int *out_count)
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
    struct row *cursor_native_row = grid_row(term->grid, cursor_row);
    const int32_t base_bg = with_alpha(
        term->reverse
            ? palette[FOOT_RENDER_COLOR_FOREGROUND]
            : palette[FOOT_RENDER_COLOR_BACKGROUND],
        background_alpha);

    /* Cursor and selection are overlays: mark the rows whose overlay state
     * changed before computing this frame's damage ranges. This is the part of
     * foot's grid_render() policy that the Canvas bridge has to reproduce. */
    track_cursor_damage(model, term, cursor_visible, cursor_shape);
    sync_native_selection(
        model, term, selection_y1, selection_y2, selection_x1, selection_x2);
    track_scroll_replacement_damage(model, term);

    model->text_len = 0;

    const bool frame_full_damage =
        foot_render_model_is_full_damage(model);

    /* A full repaint supersedes any pending scroll damage. */
    if (frame_full_damage)
        discard_scroll_damage(model, term);

    /* Coalesce consecutive damaged rows into ranges for the frame header. */
    int damage_ranges = 0;
    bool in_damage = false;
    for (int row = top_row; row < end_row; row++) {
        const bool damaged = row_is_actually_damaged(model, term, row);
        if (damaged && !in_damage) {
            damage_ranges++;
            in_damage = true;
        } else if (!damaged) {
            in_damage = false;
        }
    }

    const size_t header_size =
        (size_t)(rows + 1) * 32 + (size_t)damage_ranges * 3 +
        (size_t)tll_length(model->pending_scroll) * 5 + 32;
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
        const bool damaged = row_is_actually_damaged(model, term, row);
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

    /* Replay foot's scroll damage before the row commands. The Android
     * renderer applies these to its retained surface, then paints only the
     * newly exposed rows that foot marked dirty. */
    if (!frame_full_damage) {
        tll_foreach(model->pending_scroll, it) {
            const struct damage *damage = &it->item;
            if (!scroll_damage_applies(term->grid, damage))
                continue;

            int start = clamp_row(damage->region.start, screen_rows);
            int end = clamp_row(damage->region.end, screen_rows);
            const int lines = damage->lines;
            if (lines <= 0 || lines >= end - start)
                continue;

            commands[count++] = OP_SCROLL;
            commands[count++] = start;
            commands[count++] = end;
            commands[count++] = lines;
            commands[count++] = scroll_damage_is_reverse(damage) ? 1 : 0;
        }
    }

    for (int row = top_row; row < end_row; row++) {
        if (row < min_top || row >= screen_rows)
            continue;
        if (!row_needs_emit(model, term, row, force_all_rows))
            continue;

        struct row *native_row = row_for_external(term, row);
        /* cursor.point.row is a ring index, while row is an external viewport
         * address. Compare row pointers instead of numbers: with scrollback
         * history grid->offset is non-zero, so row == cursor_row highlights
         * the wrong row (or no row at all). */
        const bool is_cursor_row =
            cursor_visible && native_row != NULL &&
            native_row == cursor_native_row;

        if (native_row != NULL && term->fonts[0] != NULL) {
            const int canvas_cursor_col =
                is_cursor_row ? cursor_col : -1;
            if (!render_canvas_row(
                    model, term, native_row, row, canvas_cursor_col,
                    columns, base_bg, &count))
            {
                goto fail;
            }
            continue;
        }

        /* Still emit a row frame: TerminalRenderer fills the row with the
         * base colour and commits it on OP_ROW_END. Selection and cursor
         * policy live exclusively in upstream render_row(), which needs a
         * native row and prepared fonts; there is no parallel fallback. */
        if (!reserve_commands(model, (size_t)count + 3))
            goto fail;
        commands = model->commands;
        commands[count++] = OP_ROW_BEGIN;
        commands[count++] = row;
        commands[count++] = OP_ROW_END;
    }

    /* Sixel images are viewport-anchored surfaces, not part of a cell run.
     * Emit them after the row commands so a partial-row background repaint
     * cannot cover an image that is still valid. */
    if (!render_canvas_sixel_images(
            model, term, top_row, end_row - top_row, frame_full_damage,
            force_all_sixels, &count))
    {
        goto fail;
    }

    /* The Canvas path appends commands from render_cell() through the sink,
     * which may have grown the command buffer while the rows were rendered.
     * Reserve the whole tail before touching it: frame end, payload end, text
     * length and the UTF-16 units. */
    const size_t text_length = model->text_len;
    if (!reserve_commands(model, (size_t)count + 3 + text_length))
        goto fail;
    commands = model->commands;

    commands[count++] = OP_FRAME_END;
    commands[count++] = OP_END;
    /* The UTF-16 payload travels in the same array as the commands, saving a
     * second JNI transition and a second array allocation per frame. */
    commands[count++] = (int32_t)text_length;
    for (size_t i = 0; i < text_length; i++)
        commands[count++] = model->text[i];

    discard_scroll_damage(model, term);
    *out_count = count;
    return commands;

fail:
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

/* Generated from third_party/foot/selection.c (sha256 af300c0315bcd32bf19602f0b22147ddb2b52410fe04189df0b9ebb0199fd2a5).
 * Source lines: pixman_region_for_coords_normal() through
 * mark_selected_region(). Do not edit; see
 * docs/foot-renderer-migration.md and the extractor command there. */
static pixman_region32_t
pixman_region_for_coords_normal(const struct terminal *term,
                                const struct coord *start,
                                const struct coord *end)
{
    pixman_region32_t region;
    pixman_region32_init(&region);

    const int rel_start_row =
        grid_row_abs_to_sb(term->grid, term->rows, start->row);
    const int rel_end_row =
        grid_row_abs_to_sb(term->grid, term->rows, end->row);

    if (rel_start_row < rel_end_row) {
        /* First partial row (start ->)*/
        if (term->cols > start->col) {
            pixman_region32_union_rect(
                &region, &region,
                start->col, rel_start_row,
                term->cols - start->col, 1);
        }

        /* Full rows between start and end */
        if (rel_start_row + 1 < rel_end_row) {
            pixman_region32_union_rect(
                &region, &region,
                0, rel_start_row + 1,
                term->cols, rel_end_row - rel_start_row - 1);
        }

        /* Last partial row (-> end) */
        pixman_region32_union_rect(
            &region, &region,
            0, rel_end_row,
            end->col + 1, 1);

    } else if (rel_start_row > rel_end_row) {
        /* First partial row (end ->) */
        if (term->cols > end->col) {
            pixman_region32_union_rect(
                &region, &region,
                end->col, rel_end_row,
                term->cols - end->col, 1);
        }

        /* Full rows between end and start */
        if (rel_end_row + 1 < rel_start_row) {
            pixman_region32_union_rect(
                &region, &region,
                0, rel_end_row + 1,
                term->cols, rel_start_row - rel_end_row - 1);
        }

        /* Last partial row (-> start) */
        pixman_region32_union_rect(
            &region, &region,
            0, rel_start_row,
            start->col + 1, 1);
    } else {
        const int start_col = min(start->col, end->col);
        const int end_col = max(start->col, end->col);

        pixman_region32_union_rect(
            &region, &region,
            start_col, rel_start_row,
            end_col + 1 - start_col, 1);
    }

    return region;
}

static pixman_region32_t
pixman_region_for_coords_block(const struct terminal *term,
                               const struct coord *start, const struct coord *end)
{
    pixman_region32_t region;
    pixman_region32_init(&region);

    const int rel_start_row =
        grid_row_abs_to_sb(term->grid, term->rows, start->row);
    const int rel_end_row =
        grid_row_abs_to_sb(term->grid, term->rows, end->row);

    pixman_region32_union_rect(
        &region, &region,
        min(start->col, end->col), min(rel_start_row, rel_end_row),
        abs(start->col - end->col) + 1, abs(rel_start_row - rel_end_row) + 1);

    return region;
}

/* Returns a pixman region representing the selection between 'start'
 * and 'end' (given the current selection kind), in *scrollback
 * relative coordinates* */
static pixman_region32_t
pixman_region_for_coords(const struct terminal *term,
                         const struct coord *start, const struct coord *end)
{
    switch (term->selection.kind) {
    default:              return pixman_region_for_coords_normal(term, start, end);
    case SELECTION_BLOCK: return pixman_region_for_coords_block(term, start, end);
    }
}

enum mark_selection_variant {
    MARK_SELECTION_MARK_AND_DIRTY,
    MARK_SELECTION_UNMARK_AND_DIRTY,
    MARK_SELECTION_MARK_FOR_RENDER,
};

static void
mark_selected_region(struct terminal *term, pixman_box32_t *boxes,
                     size_t count, enum mark_selection_variant mark_variant)
{
    const bool selected =
        mark_variant == MARK_SELECTION_MARK_AND_DIRTY ||
        mark_variant == MARK_SELECTION_MARK_FOR_RENDER;
    const bool dirty_cells =
        mark_variant == MARK_SELECTION_MARK_AND_DIRTY ||
        mark_variant == MARK_SELECTION_UNMARK_AND_DIRTY;
    const bool highlight_empty =
        mark_variant != MARK_SELECTION_MARK_FOR_RENDER ||
        term->selection.kind == SELECTION_BLOCK;

    for (size_t i = 0; i < count; i++) {
        const pixman_box32_t *box = &boxes[i];

        LOG_DBG("%s selection in region: %dx%d - %dx%d",
                selected ? "marking" : "unmarking",
                box->x1, box->y1,
                box->x2, box->y2);

        int abs_row_start = grid_row_sb_to_abs(
            term->grid, term->rows, box->y1);

        for (int r = abs_row_start, rel_r = box->y1;
             rel_r < box->y2;
             r = (r + 1) & (term->grid->num_rows - 1), rel_r++)
        {
            struct row *row = term->grid->rows[r];
            xassert(row != NULL);

            if (dirty_cells)
                row->dirty = true;

            for (int c = box->x1, empty_count = 0; c < box->x2; c++) {
                struct cell *cell = &row->cells[c];

                if ((cell->wc == 0 || cell->wc == CELL_SPACER) &&
                    !highlight_empty)
                {
                    /*
                     * We used to highlight empty cells *if* they were
                     * followed by non-empty cell(s), since this
                     * corresponds to what gets extracted when the
                     * selection is copied (that is, empty cells
                     * "between" non-empty cells are converted to
                     * spaces).
                     *
                     * However, they way we handle selection updates
                     * (diffing the "old" selection area against the
                     * "new" one, using pixman regions), means we
                     * can't correctly update the state of empty
                     * cells. The result is "random" empty cells being
                     * rendered as selected when they shouldn't.
                     *
                     * "Fix" by *never* highlighting selected empty
                     * cells (they still get converted to spaces when
                     * copied, if followed by non-empty cells).
                     */
                    empty_count++;

                    /*
                     * When the selection is *modified*, empty cells
                     * are treated just like non-empty cells; they are
                     * marked as selected, and dirtied.
                     *
                     * This is due to how the algorithm for updating
                     * the selection works; it uses regions to
                     * calculate the difference between the "old" and
                     * the "new" selection. This makes it impossible
                     * to tell if an empty cell is a *trailing* empty
                     * cell (that should not be highlighted), or an
                     * empty cells between non-empty cells (that
                     * *should* be highlighted).
                     *
                     * Then, when a frame is rendered, we loop the
                     * *visibible* cells that belong to the
                     * selection. At this point, we *can* tell if an
                     * empty cell is trailing or not.
                     *
                     * So, what we need to do is check if a
                     * 'selected', and empty cell has been marked as
                     * selected, temporarily unmark (forcing it dirty,
                     * to ensure it gets re-rendered). If it is *not*
                     * a trailing empty cell, it will get re-tagged as
                     * selected in the for-loop below.
                     */
                    cell->attrs.clean = false;
                    cell->attrs.selected = false;
                    row->dirty = true;
                    continue;
                }

                for (int j = 0; j < empty_count + 1; j++) {
                    xassert(c - j >= 0);
                    struct cell *cell = &row->cells[c - j];

                    if (dirty_cells) {
                        cell->attrs.clean = false;
                        row->dirty = true;
                    }
                    cell->attrs.selected = selected;
                }

                empty_count = 0;
            }
        }
    }
}

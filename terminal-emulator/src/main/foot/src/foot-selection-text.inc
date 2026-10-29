/* Generated from third_party/foot/selection.c (sha256 af300c0315bcd32bf19602f0b22147ddb2b52410fe04189df0b9ebb0199fd2a5).
 * Source lines: static void foreach_selected_normal() through
 * selection_to_text().
 * Do not edit; see docs/foot-renderer-migration.md and the extractor
 * command there. */
static void
foreach_selected_normal(
    struct terminal *term, struct coord _start, struct coord _end,
    bool (*cb)(struct terminal *term, struct row *row, struct cell *cell,
               int row_no, int col, void *data),
    void *data)
{
    const struct coord *start = &_start;
    const struct coord *end = &_end;

    const int grid_rows = term->grid->num_rows;

    /* Start/end rows, relative to the scrollback start */
    /* Start/end rows, relative to the scrollback start */
    const int rel_start_row =
        grid_row_abs_to_sb(term->grid, term->rows, start->row);
    const int rel_end_row =
        grid_row_abs_to_sb(term->grid, term->rows, end->row);

    int start_row, end_row;
    int start_col, end_col;

    if (rel_start_row < rel_end_row) {
        start_row = start->row;
        start_col = start->col;
        end_row = end->row;
        end_col = end->col;
    } else if (rel_start_row > rel_end_row) {
        start_row = end->row;
        start_col = end->col;
        end_row = start->row;
        end_col = start->col;
    } else {
        start_row = end_row = start->row;
        start_col = min(start->col, end->col);
        end_col = max(start->col, end->col);
    }

    start_row &= (grid_rows - 1);
    end_row &= (grid_rows - 1);

    for (int r = start_row; r != end_row; r = (r + 1) & (grid_rows - 1)) {
        struct row *row = term->grid->rows[r];
        xassert(row != NULL);

        for (int c = start_col; c <= term->cols - 1; c++) {
            if (!cb(term, row, &row->cells[c], r, c, data))
                return;
        }

        start_col = 0;
    }

    /* Last, partial row */
    struct row *row = term->grid->rows[end_row];
    xassert(row != NULL);

    for (int c = start_col; c <= end_col; c++) {
        if (!cb(term, row, &row->cells[c], end_row, c, data))
            return;
    }
}

static void
foreach_selected_block(
    struct terminal *term, struct coord _start, struct coord _end,
    bool (*cb)(struct terminal *term, struct row *row, struct cell *cell,
               int row_no, int col, void *data),
    void *data)
{
    const struct coord *start = &_start;
    const struct coord *end = &_end;

    const int grid_rows = term->grid->num_rows;

    /* Start/end rows, relative to the scrollback start */
    const int rel_start_row =
        grid_row_abs_to_sb(term->grid, term->rows, start->row);
    const int rel_end_row =
        grid_row_abs_to_sb(term->grid, term->rows, end->row);

    struct coord top_left = {
        .row = (rel_start_row < rel_end_row
                ? start->row : end->row) & (grid_rows - 1),
        .col = min(start->col, end->col),
    };

    struct coord bottom_right = {
        .row = (rel_start_row > rel_end_row
                ? start->row : end->row) & (grid_rows - 1),
        .col = max(start->col, end->col),
    };

    int r = top_left.row;
    while (true) {
        struct row *row = term->grid->rows[r];
        xassert(row != NULL);

        for (int c = top_left.col; c <= bottom_right.col; c++) {
            if (!cb(term, row, &row->cells[c], r, c, data))
                return;
        }

        if (r == bottom_right.row)
            break;

        r++;
        r &= grid_rows - 1;
    }
}

static void
foreach_selected(
    struct terminal *term, struct coord start, struct coord end,
    bool (*cb)(struct terminal *term, struct row *row, struct cell *cell, int row_no, int col, void *data),
    void *data)
{
    switch (term->selection.kind) {
    case SELECTION_CHAR_WISE:
    case SELECTION_WORD_WISE:
    case SELECTION_QUOTE_WISE:
    case SELECTION_LINE_WISE:
        foreach_selected_normal(term, start, end, cb, data);
        return;

    case SELECTION_BLOCK:
        foreach_selected_block(term, start, end, cb, data);
        return;

    case SELECTION_NONE:
        break;
    }

    BUG("Invalid selection kind");
}

static bool
extract_one_const_wrapper(struct terminal *term,
                          struct row *row, struct cell *cell,
                          int row_no, int col, void *data)
{
    return extract_one(term, row, cell, col, data);
}

char *
selection_to_text(const struct terminal *term)
{
    if (term->selection.coords.end.row == -1)
        return NULL;

    struct extraction_context *ctx = extract_begin(term->selection.kind, true);
    if (ctx == NULL)
        return NULL;

    foreach_selected(
        (struct terminal *)term, term->selection.coords.start, term->selection.coords.end,
        &extract_one_const_wrapper, ctx);

    char *text;
    return extract_finish(ctx, &text, NULL) ? text : NULL;
}

/* Generated from third_party/foot/render.c (sha256 97280b358e859d2c4f40f7ddf0b55311d7d66f9a5d9fe6e2c221611c6e0187cb).
 * Source lines: dirty_old_cursor() through dirty_cursor(). Do not edit;
 * see docs/foot-renderer-migration.md and the extractor command there. */
static void
dirty_old_cursor(struct terminal *term)
{
    if (term->render.last_cursor.row != NULL && !term->render.last_cursor.hidden) {
        struct row *row = term->render.last_cursor.row;
        struct cell *cell = &row->cells[term->render.last_cursor.col];
        cell->attrs.clean = 0;
        row->dirty = true;
    }

    /* Remember current cursor position, for the next frame */
    term->render.last_cursor.row = grid_row(term->grid, term->grid->cursor.point.row);
    term->render.last_cursor.col = term->grid->cursor.point.col;
    term->render.last_cursor.hidden = term->hide_cursor;
}

static void
dirty_cursor(struct terminal *term)
{
    if (term->hide_cursor)
        return;

    const struct coord *cursor = &term->grid->cursor.point;

    struct row *row = grid_row(term->grid, cursor->row);
    struct cell *cell = &row->cells[cursor->col];
    cell->attrs.clean = 0;
    row->dirty = true;
}

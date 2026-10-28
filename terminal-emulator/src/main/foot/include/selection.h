#pragma once
#include <stdbool.h>
#include "terminal.h"
struct seat;
void selection_start(struct terminal *term, int col, int row, enum selection_kind new_kind, bool spaces_only);
void selection_update(struct terminal *term, int col, int row);
void selection_finalize(struct seat *seat, struct terminal *term, uint32_t serial);
void selection_dirty_cells(struct terminal *term);
void selection_cancel(struct terminal *term);
void selection_extend(struct seat *seat, struct terminal *term, int col, int row, enum selection_kind kind);
bool selection_on_rows(const struct terminal *term, int start, int end);
void selection_scroll_up(struct terminal *term, int rows);
void selection_scroll_down(struct terminal *term, int rows);
void selection_view_up(struct terminal *term, int new_view);
void selection_view_down(struct terminal *term, int new_view);
char *selection_to_text(const struct terminal *term);
void selection_clipboard_unset(struct seat *seat);
void selection_primary_unset(struct seat *seat);
bool selection_clipboard_has_data(const struct seat *seat);
bool selection_primary_has_data(const struct seat *seat);
void selection_to_clipboard(struct seat *seat, struct terminal *term, uint32_t serial);
void selection_from_clipboard(struct seat *seat, struct terminal *term, uint32_t serial);
void selection_to_primary(struct seat *seat, struct terminal *term, uint32_t serial);
void selection_from_primary(struct seat *seat, struct terminal *term);
bool text_to_clipboard(struct seat *seat, struct terminal *term, char *text, uint32_t serial);
bool text_to_primary(struct seat *seat, struct terminal *term, char *text, uint32_t serial);
void text_from_clipboard(struct seat *seat, struct terminal *term, bool no_strip, void (*cb)(char *data, size_t size, void *user), void (*done)(void *user), void *user);
void text_from_primary(struct seat *seat, struct terminal *term, bool no_strip, void (*cb)(char *data, size_t size, void *user), void (*done)(void *user), void *user);
void selection_start_scroll_timer(struct terminal *term, int interval_ns, enum selection_scroll_direction direction, int col);
void selection_stop_scroll_timer(struct terminal *term);
void selection_find_word_boundary_left(struct terminal *term, int *col, int *row);
void selection_find_word_boundary_right(struct terminal *term, int *col, int *row);
struct coord selection_get_start(const struct terminal *term);
struct coord selection_get_end(const struct terminal *term);

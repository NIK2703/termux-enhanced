#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

struct headless_terminal;

struct headless_terminal *headless_create(
    int columns, int rows, int transcript_rows,
    int cell_width, int cell_height, const int32_t *palette);

void headless_destroy(struct headless_terminal *ht);

void headless_reset(struct headless_terminal *ht);
void headless_resize(
    struct headless_terminal *ht, int columns, int rows,
    int cell_width, int cell_height);

/* Returns a borrowed array of dirty visible viewport rows, or NULL on failure.
 * The array may be non-NULL with *dirty_count == 0 when nothing changed. Row
 * addresses are external coordinates (0 is the live screen top, negative rows
 * are history) and stay valid until the next headless_append() on the same
 * terminal; the caller must not free it. Native dirty bits are only cleared by
 * headless_commit_rows()/headless_commit_all_rows() after the frame is on the
 * Canvas. */
int *headless_append(
    struct headless_terminal *ht, const uint8_t *data, size_t length,
    int *dirty_count);

/* Select the visible viewport and synchronize foot's grid->view. Called by the
 * UI before drawing; not by headless_render(), because a partial render starts
 * at an arbitrary row inside the viewport. */
void headless_set_view(struct headless_terminal *ht, int top_row);
int headless_view_top(const struct headless_terminal *ht);

/* Retire native damage after rows were painted. */
void headless_commit_rows(
    struct headless_terminal *ht, const int *rows, int count);
void headless_commit_all_rows(struct headless_terminal *ht);

/* Returns a malloc'ed newline-joined event string, or NULL when no events are
 * pending. The caller owns the returned string. */
char *headless_drain_events(struct headless_terminal *ht);

int headless_palette(struct headless_terminal *ht, int32_t out[259]);
bool headless_set_palette(
    struct headless_terminal *ht,
    const int32_t palette[259], const int32_t defaults[259]);

int headless_history_size(const struct headless_terminal *ht);
int headless_rows(const struct headless_terminal *ht);
int headless_columns(const struct headless_terminal *ht);
int headless_cursor_row(const struct headless_terminal *ht);
int headless_cursor_col(const struct headless_terminal *ht);
int headless_mode_bits(const struct headless_terminal *ht);
int headless_scroll_counter(const struct headless_terminal *ht);
void headless_clear_scroll_counter(struct headless_terminal *ht);
enum {
    HEADLESS_APPEND_PARTIAL_SCROLL = 1 << 0,
    /* The native model still has full-viewport damage pending. */
    HEADLESS_APPEND_FULL_DAMAGE = 1 << 1,
};
int headless_last_append_flags(const struct headless_terminal *ht);
int headless_anchor_row(const struct headless_terminal *ht, int external_row);
int64_t headless_absolute_scroll_count(const struct headless_terminal *ht);
bool headless_is_alternate(const struct headless_terminal *ht);
int headless_cursor_style(const struct headless_terminal *ht);
void headless_set_cursor_style(struct headless_terminal *ht, int shape);

int32_t *headless_row(
    const struct headless_terminal *ht, int external_row, int *out_count);

char *headless_selected_text(
    struct headless_terminal *ht, int x1, int y1, int x2, int y2,
    bool join_back_lines, bool join_full_lines);
char *headless_transcript_text(struct headless_terminal *ht, int mode);
char *headless_word_at_location(
    struct headless_terminal *ht, int x, int y);

/* Packed render payload: commands terminated by OP_END, followed by the
 * UTF-16 text length and the text units referenced by OP_GLYPH commands.
 * The buffer is reused and stays valid until the next headless_render() on the
 * same terminal; the caller must not free it. */
int32_t *headless_render(
    struct headless_terminal *ht, int top_row,
    int selection_y1, int selection_y2, int selection_x1, int selection_x2,
    bool cursor_visible, int cursor_shape, int background_alpha,
    float font_width, int font_line_spacing, int font_ascent,
    int columns, int rows, int *out_count);

int32_t *headless_render_text(
    struct headless_terminal *ht, int *out_count);

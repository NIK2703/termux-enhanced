#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/*
 * Streaming pre-filter for sequences handled by Termux rather than foot:
 *
 *  - OSC 99 notifications are assembled here so Termux keeps the exact
 *    chunking/query semantics exposed by the Rust backend.
 *  - OSC 10/11/12 accept the xterm list form (fg;bg;cursor), which foot only
 *    parses one colour at a time.
 *  - DECCOLM (CSI ? 3 h/l) is translated into the side effects Termux relies
 *    on (reset margins, clear the grid and home the cursor), since foot ignores
 *    the mode itself.
 *
 * All other bytes pass through unchanged.
 */

struct vt_filter;

typedef void (*vt_filter_write_fn)(
    void *user, const void *data, size_t length);
typedef void (*vt_filter_notification_fn)(
    void *user, const char *id, const char *title, const char *body,
    int occasions, bool silent, bool report, bool focus);

struct vt_filter *vt_filter_create(
    vt_filter_write_fn write_fn,
    vt_filter_notification_fn notification_fn, void *user);
void vt_filter_destroy(struct vt_filter *filter);

void vt_filter_reset(struct vt_filter *filter);

/*
 * Feed PTY bytes. Bytes intended for the terminal core are returned in
 * *out_data. When the chunk needs no rewriting, *out_data points at the input
 * and *out_owned is false; otherwise it points at a malloc()ed buffer and
 * *out_owned is true. *out_length is zero when the whole chunk was consumed by
 * the filter (a complete OSC 99, for example).
 */
void vt_filter_feed(
    struct vt_filter *filter, const uint8_t *data, size_t length,
    uint8_t **out_data, size_t *out_length, bool *out_owned);

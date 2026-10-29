#pragma once

#include <stdbool.h>
#include <stdint.h>

struct terminal;

/*
 * Replay a foot scroll-damage record on a retained ARGB surface.
 *
 * The pixel move is performed by the verbatim upstream
 * grid_render_scroll()/grid_render_scroll_reverse() block extracted into
 * foot-grid-scroll.inc. The surface must contain whole terminal rows starting
 * at term->margins.top and use the terminal's cell height.
 *
 * Returns false when the geometry does not describe a valid pixel band, so the
 * caller can fall back to a full row repaint. Returns true when the band is
 * already fully replaced (lines >= end-start); upstream leaves it untouched
 * and the newly exposed rows are redrawn by the normal frame.
 */
bool foot_grid_scroll_apply_rows(
    struct terminal *term, void *pixels, int width, int height, int stride,
    int start, int end, int lines, bool reverse);

/*
 * Upstream foot scroll-damage pixel movers for the retained Android surface.
 *
 * grid_render_scroll() and grid_render_scroll_reverse() are extracted verbatim
 * from the pinned third_party/foot/render.c. Upstream normally moves pixels in
 * a Wayland SHM buffer; this adapter points struct buffer at the locked
 * Android Bitmap instead. SHM is disabled by the headless stubs, so the
 * memmove fallback is the path that actually runs.
 */

#include "foot_grid_scroll.h"

#include <stdint.h>
#include <string.h>

#include "log.h"
#include "macros.h"
#include "pixman.h"
#include "shm.h"
#include "terminal.h"
#include "wayland.h"

/*
 * render.c defines render_margin() before the scroll functions. It is only
 * reached when shm_scroll() succeeds; the Android headless build always
 * reports that SHM is unavailable. Provide the declaration and an unreachable
 * implementation so the extracted block compiles without pulling in the
 * Wayland margin painter.
 */
static void
render_margin(struct terminal *term, struct buffer *buf,
              int start_line, int end_line, bool apply_damage)
{
    (void)term;
    (void)buf;
    (void)start_line;
    (void)end_line;
    (void)apply_damage;
}

#include "foot-grid-scroll.inc"

bool
foot_grid_scroll_apply_rows(
    struct terminal *term, void *pixels, int width, int height, int stride,
    int start, int end, int lines, bool reverse)
{
    if (term == NULL || pixels == NULL || term->window == NULL)
        return false;
    if (width <= 0 || height <= 0 || stride <= 0 || term->cell_height <= 0)
        return false;
    if (start < 0 || end <= start || end > term->rows)
        return false;
    if (term->margins.top < 0)
        return false;
    if (lines < 0 || lines > UINT16_MAX)
        return false;

    /*
     * Validate the band before upstream's memmove. The retained Bitmap is
     * view-sized and must contain every terminal row from margins.top through
     * end; otherwise the caller has to repaint instead of moving pixels.
     */
    const int64_t top = (int64_t)term->margins.top + (int64_t)start * term->cell_height;
    const int64_t bottom = (int64_t)term->margins.top + (int64_t)end * term->cell_height;
    if (top < 0 || bottom > height)
        return false;
    if ((int64_t)stride < (int64_t)width * 4)
        return false;

    if (lines >= end - start) {
        /* Upstream treats this as a fully replaced region and returns before
         * touching pixels. The newly exposed rows are normal frame damage. */
        return true;
    }

    pixman_region32_t dirty;
    pixman_region32_init(&dirty);

    struct buffer buf = {
        .width = width,
        .height = height,
        .stride = stride,
        .data = pixels,
        .dirty = &dirty,
    };
    struct damage dmg = {
        .type = reverse ? DAMAGE_SCROLL_REVERSE : DAMAGE_SCROLL,
        .region = {
            .start = start,
            .end = end,
        },
        .lines = (uint16_t)lines,
    };

    if (reverse)
        grid_render_scroll_reverse(term, &buf, &dmg);
    else
        grid_render_scroll(term, &buf, &dmg);

    pixman_region32_fini(&dirty);
    return true;
}

/* Generated from third_party/foot/render.c (sha256 97280b358e859d2c4f40f7ddf0b55311d7d66f9a5d9fe6e2c221611c6e0187cb).
 * Source lines: grid_render_scroll() through
 * grid_render_scroll_reverse(). Do not edit; see
 * docs/foot-renderer-migration.md and the extractor command there. */
static void
grid_render_scroll(struct terminal *term, struct buffer *buf,
                   const struct damage *dmg)
{
    LOG_DBG(
        "damage: SCROLL: %d-%d by %d lines",
        dmg->region.start, dmg->region.end, dmg->lines);

    const int region_size = dmg->region.end - dmg->region.start;

    if (dmg->lines >= region_size) {
        /* The entire scroll region will be scrolled out (i.e. replaced) */
        return;
    }

    const int height = (region_size - dmg->lines) * term->cell_height;
    xassert(height > 0);

#if TIME_SCROLL_DAMAGE
    struct timespec start_time;
    clock_gettime(CLOCK_MONOTONIC, &start_time);
#endif

    int dst_y = term->margins.top + (dmg->region.start + 0) * term->cell_height;
    int src_y = term->margins.top + (dmg->region.start + dmg->lines) * term->cell_height;

    /*
     * SHM scrolling can be *much* faster, but it depends on how many
     * lines we're scrolling, and how much repairing we need to do.
     *
     * In short, scrolling a *large* number of rows is faster with a
     * memmove, while scrolling a *small* number of lines is faster
     * with SHM scrolling.
     *
     * However, since we need to restore the scrolling regions when
     * SHM scrolling, we also need to take this into account.
     *
     * Finally, we also have to restore the window margins, and this
     * is a *huge* performance hit when scrolling a large number of
     * lines (in addition to the sloweness of SHM scrolling as
     * method).
     *
     * So, we need to figure out when to SHM scroll, and when to
     * memmove.
     *
     * For now, assume that the both methods perform roughly the same,
     * given an equal number of bytes to move/allocate, and use the
     * method that results in the least amount of bytes to touch.
     *
     * Since number of lines directly translates to bytes, we can
     * simply count lines.
     *
     * SHM scrolling needs to first "move" (punch hole + allocate)
     * dmg->lines number of lines, and then we need to restore
     * the bottom scroll region.
     *
     * If the total number of lines is less than half the screen - use
     * SHM. Otherwise use memmove.
     */
    bool try_shm_scroll =
        shm_can_scroll(buf) && (
            dmg->lines +
            dmg->region.start +
            (term->rows - dmg->region.end)) < term->rows / 2;

    bool did_shm_scroll = false;

    //try_shm_scroll = false;
    //try_shm_scroll = true;

    if (try_shm_scroll) {
        did_shm_scroll = shm_scroll(
            buf, dmg->lines * term->cell_height,
            term->margins.top, dmg->region.start * term->cell_height,
            term->margins.bottom, (term->rows - dmg->region.end) * term->cell_height);
    }

    if (did_shm_scroll) {
        /* Restore margins */
        render_margin(
            term, buf, dmg->region.end - dmg->lines, term->rows, false);
    } else {
        /* Fallback for when we either cannot do SHM scrolling, or it failed */
        uint8_t *raw = buf->data;
        memmove(raw + dst_y * buf->stride,
                raw + src_y * buf->stride,
                height * buf->stride);
    }

#if TIME_SCROLL_DAMAGE
    struct timespec end_time;
    clock_gettime(CLOCK_MONOTONIC, &end_time);

    struct timespec memmove_time;
    timespec_sub(&end_time, &start_time, &memmove_time);
    LOG_INFO("scrolled %dKB (%d lines) using %s in %lds %ldns",
             height * buf->stride / 1024, dmg->lines,
             did_shm_scroll ? "SHM" : try_shm_scroll ? "memmove (SHM failed)" :  "memmove",
             (long)memmove_time.tv_sec, memmove_time.tv_nsec);
#endif

    wl_surface_damage_buffer(
        term->window->surface.surf, term->margins.left, dst_y,
        term->width - term->margins.left - term->margins.right, height);

    /*
     * TODO: remove this if re-enabling scroll damage when re-applying
     * last frame's damage (see reapply_old_damage()
     */
    pixman_region32_union_rect(
        &buf->dirty[0], &buf->dirty[0], 0, dst_y, buf->width, height);
}

static void
grid_render_scroll_reverse(struct terminal *term, struct buffer *buf,
                           const struct damage *dmg)
{
    LOG_DBG(
        "damage: SCROLL REVERSE: %d-%d by %d lines",
        dmg->region.start, dmg->region.end, dmg->lines);

    const int region_size = dmg->region.end - dmg->region.start;

    if (dmg->lines >= region_size) {
        /* The entire scroll region will be scrolled out (i.e. replaced) */
        return;
    }

    const int height = (region_size - dmg->lines) * term->cell_height;
    xassert(height > 0);

#if TIME_SCROLL_DAMAGE
    struct timespec start_time;
    clock_gettime(CLOCK_MONOTONIC, &start_time);
#endif

    int src_y = term->margins.top + (dmg->region.start + 0) * term->cell_height;
    int dst_y = term->margins.top + (dmg->region.start + dmg->lines) * term->cell_height;

    bool try_shm_scroll =
        shm_can_scroll(buf) && (
            dmg->lines +
            dmg->region.start +
            (term->rows - dmg->region.end)) < term->rows / 2;

    bool did_shm_scroll = false;

    if (try_shm_scroll) {
        did_shm_scroll = shm_scroll(
            buf, -dmg->lines * term->cell_height,
            term->margins.top, dmg->region.start * term->cell_height,
            term->margins.bottom, (term->rows - dmg->region.end) * term->cell_height);
    }

    if (did_shm_scroll) {
        /* Restore margins */
        render_margin(
            term, buf, dmg->region.start, dmg->region.start + dmg->lines, false);
    } else {
        /* Fallback for when we either cannot do SHM scrolling, or it failed */
        uint8_t *raw = buf->data;
        memmove(raw + dst_y * buf->stride,
                raw + src_y * buf->stride,
                height * buf->stride);
    }

#if TIME_SCROLL_DAMAGE
    struct timespec end_time;
    clock_gettime(CLOCK_MONOTONIC, &end_time);

    struct timespec memmove_time;
    timespec_sub(&end_time, &start_time, &memmove_time);
    LOG_INFO("scrolled REVERSE %dKB (%d lines) using %s in %lds %ldns",
             height * buf->stride / 1024, dmg->lines,
             did_shm_scroll ? "SHM" : try_shm_scroll ? "memmove (SHM failed)" :  "memmove",
             (long)memmove_time.tv_sec, memmove_time.tv_nsec);
#endif

    wl_surface_damage_buffer(
        term->window->surface.surf, term->margins.left, dst_y,
        term->width - term->margins.left - term->margins.right, height);

    /*
     * TODO: remove this if re-enabling scroll damage when re-applying
     * last frame's damage (see reapply_old_damage()
     */
    pixman_region32_union_rect(
        &buf->dirty[0], &buf->dirty[0], 0, dst_y, buf->width, height);
}

// GUI/PTY/notification stubs for the headless foot build.
//
// None of these functions are part of the terminal state machine. They are the
// seams where foot normally talks to Wayland, the font rasterizer, the process
// reaper or the desktop notification daemon. Keeping them here means the
// upstream .c files can be compiled unmodified.

#define LOG_MODULE "stubs"
#define LOG_ENABLE_DBG 0
#include "log.h"

#include "headless.h"
#include "ht_internal.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "async.h"
#include "commands.h"
#include "extract.h"
#include "fdm.h"
#include "grid.h"
#include "ime.h"
#include "input.h"
#include "notify.h"
#include "pixman.h"
#include "quirks.h"
#include "reaper.h"
#include "render.h"
#include "selection.h"
#include "shm.h"
#include "sixel.h"
#include "spawn.h"
#include "terminal.h"
#include "url-mode.h"
#include "wayland.h"

/* -------------------------------------------------------------------------
 * FDM
 * ---------------------------------------------------------------------- */

struct fdm;
struct fdm *fdm_init(void) { return NULL; }
void fdm_destroy(struct fdm *fdm) { (void)fdm; }
bool fdm_add(struct fdm *fdm, int fd, int events, fdm_fd_handler_t handler, void *data)
{
    (void)fdm; (void)fd; (void)events; (void)handler; (void)data;
    return true;
}
bool fdm_del(struct fdm *fdm, int fd) { (void)fdm; (void)fd; return true; }
bool fdm_del_no_close(struct fdm *fdm, int fd) { (void)fdm; (void)fd; return true; }
bool fdm_event_add(struct fdm *fdm, int fd, int events) { (void)fdm; (void)fd; (void)events; return true; }
bool fdm_event_del(struct fdm *fdm, int fd, int events) { (void)fdm; (void)fd; (void)events; return true; }
bool fdm_hook_add(struct fdm *fdm, fdm_hook_t hook, void *data, enum fdm_hook_priority priority)
{
    (void)fdm; (void)hook; (void)data; (void)priority;
    return true;
}
bool fdm_hook_del(struct fdm *fdm, fdm_hook_t hook, enum fdm_hook_priority priority)
{
    (void)fdm; (void)hook; (void)priority;
    return true;
}
bool fdm_signal_add(struct fdm *fdm, int signo, fdm_signal_handler_t handler, void *data)
{
    (void)fdm; (void)signo; (void)handler; (void)data;
    return true;
}
bool fdm_signal_del(struct fdm *fdm, int signo) { (void)fdm; (void)signo; return true; }
bool fdm_poll(struct fdm *fdm) { (void)fdm; return true; }

/* -------------------------------------------------------------------------
 * Async writes (PTY responses)
 * ---------------------------------------------------------------------- */

enum async_write_status
async_write(int fd, const void *data, size_t len, size_t *idx)
{
    if (fd < 0)
        return ASYNC_WRITE_ERR;

    const uint8_t *bytes = data;
    while (*idx < len) {
        ssize_t n = write(fd, bytes + *idx, len - *idx);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            if (errno == EAGAIN || errno == EWOULDBLOCK)
                return ASYNC_WRITE_REMAIN;
            return ASYNC_WRITE_ERR;
        }
        if (n == 0)
            return ASYNC_WRITE_REMAIN;
        *idx += (size_t)n;
    }
    return ASYNC_WRITE_DONE;
}

/* -------------------------------------------------------------------------
 * Renderer / Wayland seams
 * ---------------------------------------------------------------------- */

void render_refresh(struct terminal *term) { (void)term; }
void render_refresh_app_id(struct terminal *term)
{
    (void)term;
}
void render_refresh_icon(struct terminal *term) { (void)term; }
void render_refresh_csd(struct terminal *term) { (void)term; }
void render_refresh_search(struct terminal *term) { (void)term; }
void render_refresh_urls(struct terminal *term) { (void)term; }
void render_refresh_title(struct terminal *term)
{
    headless_emit_title(term);
}
bool render_resize(struct terminal *term, int width, int height, uint8_t resize_options)
{
    (void)term; (void)width; (void)height; (void)resize_options;
    return true;
}
bool render_xcursor_set(struct seat *seat, struct terminal *term, enum cursor_shape shape)
{
    (void)seat; (void)term; (void)shape;
    return true;
}
bool render_xcursor_is_valid(const struct seat *seat, const char *cursor)
{
    (void)seat; (void)cursor;
    return true;
}
void render_overlay(struct terminal *term) { (void)term; }
int render_worker_thread(void *ctx) { (void)ctx; return 0; }
struct csd_data get_csd_data(const struct terminal *term, enum csd_surface surf_idx)
{
    (void)term; (void)surf_idx;
    return (struct csd_data){0};
}
void render_buffer_release_callback(struct buffer *buf, void *data)
{
    (void)buf; (void)data;
}
void render_wait_for_preapply_damage(struct terminal *term) { (void)term; }

/* -------------------------------------------------------------------------
 * Shared memory buffers (only referenced by the unused render setup paths)
 * ---------------------------------------------------------------------- */

void shm_fini(void) {}
void shm_set_max_pool_size(off_t max_pool_size) { (void)max_pool_size; }
void shm_set_min_stride_alignment(size_t min_stride_alignment) { (void)min_stride_alignment; }
struct buffer_chain *shm_chain_new(
    struct wayland *wayl, bool scrollable, size_t pix_instances,
    enum shm_bit_depth desired_bit_depth,
    void (*release_cb)(struct buffer *buf, void *data), void *cb_data)
{
    (void)wayl; (void)scrollable; (void)pix_instances;
    (void)desired_bit_depth; (void)release_cb; (void)cb_data;
    return NULL;
}
void shm_chain_free(struct buffer_chain *chain) { (void)chain; }
enum shm_bit_depth shm_chain_bit_depth(const struct buffer_chain *chain)
{
    (void)chain;
    return SHM_BITS_8;
}
struct buffer *shm_get_buffer(struct buffer_chain *chain, int width, int height)
{
    (void)chain; (void)width; (void)height;
    return NULL;
}
void shm_get_many(
    struct buffer_chain *chain, size_t count,
    int widths[static count], int heights[static count],
    struct buffer *bufs[static count])
{
    (void)chain; (void)widths; (void)heights; (void)bufs;
}
void shm_did_not_use_buf(struct buffer *buf) { (void)buf; }
bool shm_can_scroll(const struct buffer *buf) { (void)buf; return false; }
bool shm_scroll(struct buffer *buf, int rows, int top_margin, int top_keep_rows,
                int bottom_margin, int bottom_keep_rows)
{
    (void)buf; (void)rows; (void)top_margin; (void)top_keep_rows;
    (void)bottom_margin; (void)bottom_keep_rows;
    return false;
}
void shm_addref(struct buffer *buf) { (void)buf; }
void shm_unref(struct buffer *buf) { (void)buf; }
void shm_purge(struct buffer_chain *chain) { (void)chain; }

bool wayl_do_linear_blending(struct wayland *wayl, const struct config *conf)
{
    (void)wayl; (void)conf;
    return false;
}
struct wl_window *wayl_win_init(struct terminal *term, const char *token)
{
    (void)token;
    return term != NULL ? term->window : NULL;
}
void wayl_win_destroy(struct wl_window *win) { (void)win; }
void wayl_win_alpha_changed(struct wl_window *win) { (void)win; }
bool wayl_win_set_urgent(struct wl_window *win) { (void)win; return true; }
void wl_surface_commit(struct wl_surface *surface) { (void)surface; }
void xdg_toplevel_set_maximized(struct xdg_toplevel *toplevel) { (void)toplevel; }
void xdg_toplevel_set_fullscreen(struct xdg_toplevel *toplevel, void *output)
{
    (void)toplevel; (void)output;
}
bool wayl_win_ring_bell(const struct wl_window *win)
{
    if (win != NULL && win->term != NULL)
        headless_emit_bell(win->term);
    return true;
}

/* -------------------------------------------------------------------------
 * Key bindings / input
 * ---------------------------------------------------------------------- */

void key_binding_new_for_conf(
    struct key_binding_manager *mgr, struct wayland *wayl, const struct config *conf)
{
    (void)mgr; (void)wayl; (void)conf;
}
void key_binding_unref(struct key_binding_manager *mgr, const struct config *conf)
{
    (void)mgr; (void)conf;
}
const struct key_binding_set *
key_binding_for(
    struct key_binding_manager *mgr, const struct config *conf, const struct seat *seat)
{
    (void)mgr; (void)conf; (void)seat;
    return NULL;
}
void get_current_modifiers(
    const struct seat *seat, xkb_mod_mask_t *effective, xkb_mod_mask_t *consumed,
    uint32_t key, bool filter_locked)
{
    (void)seat; (void)key; (void)filter_locked;
    if (effective != NULL) *effective = 0;
    if (consumed != NULL) *consumed = 0;
}
void input_repeat(struct seat *seat, uint32_t key) { (void)seat; (void)key; }
void ime_enable(struct seat *seat) { (void)seat; }
void ime_disable(struct seat *seat) { (void)seat; }
void ime_update_cursor_rect(struct seat *seat) { (void)seat; }
void ime_reset_pending(struct seat *seat) { (void)seat; }
void ime_reset_preedit(struct seat *seat) { (void)seat; }

/* -------------------------------------------------------------------------
 * Process management
 * ---------------------------------------------------------------------- */

struct reaper *reaper_init(struct fdm *fdm) { (void)fdm; return NULL; }
void reaper_destroy(struct reaper *reaper) { (void)reaper; }
void reaper_add(struct reaper *reaper, pid_t pid, reaper_cb cb, void *cb_data)
{
    (void)reaper; (void)pid; (void)cb; (void)cb_data;
}
void reaper_del(struct reaper *reaper, pid_t pid) { (void)reaper; (void)pid; }

pid_t spawn(
    struct reaper *reaper, const char *cwd, char *const argv[],
    int stdin_fd, int stdout_fd, int stderr_fd, reaper_cb cb, void *cb_data,
    const char *xdg_activation_token)
{
    (void)reaper; (void)cwd; (void)argv; (void)stdin_fd; (void)stdout_fd;
    (void)stderr_fd; (void)cb; (void)cb_data; (void)xdg_activation_token;
    return -1;
}
bool spawn_expand_template(
    const struct config_spawn_template *template, size_t key_count,
    const char *key_names[], const char *key_values[],
    size_t *argc, char ***argv)
{
    (void)template; (void)key_count; (void)key_names; (void)key_values;
    (void)argc; (void)argv;
    return false;
}
pid_t slave_spawn(
    int ptmx, int argc, const char *cwd, char *const *argv,
    const char *const *envp, const env_var_list_t *extra_env_vars,
    const char *term_env, const char *conf_shell, bool login_shell,
    const user_notifications_t *notifications)
{
    (void)ptmx; (void)argc; (void)cwd; (void)argv; (void)envp;
    (void)extra_env_vars; (void)term_env; (void)conf_shell; (void)login_shell;
    (void)notifications;
    return -1;
}

/* -------------------------------------------------------------------------
 * Clipboard / selection
 * ---------------------------------------------------------------------- */

void selection_start(struct terminal *term, int col, int row, enum selection_kind kind, bool spaces_only)
{
    (void)term; (void)col; (void)row; (void)kind; (void)spaces_only;
}
void selection_update(struct terminal *term, int col, int row) { (void)term; (void)col; (void)row; }
void selection_finalize(struct seat *seat, struct terminal *term, uint32_t serial)
{
    (void)seat; (void)term; (void)serial;
}
void selection_dirty_cells(struct terminal *term) { (void)term; }
void selection_cancel(struct terminal *term)
{
    if (term != NULL)
        term->selection.coords.start.row = term->selection.coords.end.row = -1;
}
void selection_extend(struct seat *seat, struct terminal *term, int col, int row, enum selection_kind kind)
{
    (void)seat; (void)term; (void)col; (void)row; (void)kind;
}
bool selection_on_rows(const struct terminal *term, int start, int end)
{
    (void)term; (void)start; (void)end;
    return false;
}
void selection_scroll_up(struct terminal *term, int rows) { (void)term; (void)rows; }
void selection_scroll_down(struct terminal *term, int rows) { (void)term; (void)rows; }
void selection_view_up(struct terminal *term, int new_view) { (void)term; (void)new_view; }
void selection_view_down(struct terminal *term, int new_view) { (void)term; (void)new_view; }
char *selection_to_text(const struct terminal *term) { (void)term; return strdup(""); }
void selection_clipboard_unset(struct seat *seat) { (void)seat; }
void selection_primary_unset(struct seat *seat) { (void)seat; }
bool selection_clipboard_has_data(const struct seat *seat) { (void)seat; return false; }
bool selection_primary_has_data(const struct seat *seat) { (void)seat; return false; }
void selection_to_clipboard(struct seat *seat, struct terminal *term, uint32_t serial)
{
    (void)seat; (void)term; (void)serial;
}
void selection_from_clipboard(struct seat *seat, struct terminal *term, uint32_t serial)
{
    (void)seat; (void)term; (void)serial;
}
void selection_to_primary(struct seat *seat, struct terminal *term, uint32_t serial)
{
    (void)seat; (void)term; (void)serial;
}
void selection_from_primary(struct seat *seat, struct terminal *term)
{
    (void)seat; (void)term;
}
bool text_to_clipboard(struct seat *seat, struct terminal *term, char *text, uint32_t serial)
{
    (void)seat; (void)serial;
    if (text != NULL) {
        headless_emit_clipboard_store(term, text, strlen(text));
        free(text);
    }
    return true;
}
bool text_to_primary(struct seat *seat, struct terminal *term, char *text, uint32_t serial)
{
    (void)seat; (void)serial;
    if (text != NULL) {
        headless_emit_clipboard_store(term, text, strlen(text));
        free(text);
    }
    return true;
}
void text_from_clipboard(
    struct seat *seat, struct terminal *term, bool no_strip,
    void (*cb)(char *data, size_t size, void *user), void (*done)(void *user), void *user)
{
    (void)seat; (void)term; (void)no_strip; (void)cb;
    if (done != NULL) done(user);
}
void text_from_primary(
    struct seat *seat, struct terminal *term, bool no_strip,
    void (*cb)(char *data, size_t size, void *user), void (*done)(void *user), void *user)
{
    (void)seat; (void)term; (void)no_strip; (void)cb;
    if (done != NULL) done(user);
}
void selection_start_scroll_timer(
    struct terminal *term, int interval_ns, enum selection_scroll_direction direction, int col)
{
    (void)term; (void)interval_ns; (void)direction; (void)col;
}
void selection_stop_scroll_timer(struct terminal *term) { (void)term; }
void selection_find_word_boundary_left(struct terminal *term, int *col, int *row)
{
    (void)term; (void)col; (void)row;
}
void selection_find_word_boundary_right(struct terminal *term, int *col, int *row)
{
    (void)term; (void)col; (void)row;
}
struct coord selection_get_start(const struct terminal *term)
{
    (void)term;
    return (struct coord){0, 0};
}
struct coord selection_get_end(const struct terminal *term)
{
    (void)term;
    return (struct coord){0, 0};
}

/* -------------------------------------------------------------------------
 * Notifications
 * ---------------------------------------------------------------------- */

bool notify_notify(struct terminal *term, struct notification *notif)
{
    headless_emit_notification(term, notif);
    return true;
}
void notify_close(struct terminal *term, const char *id) { (void)term; (void)id; }
void notify_free(struct terminal *term, struct notification *notif)
{
    if (notif == NULL)
        return;
    if (notif->pid > 0)
        fdm_del(term->fdm, notif->stdout_fd);
    free(notif->id);
    free(notif->title);
    free(notif->body);
    free(notif->category);
    free(notif->app_id);
    free(notif->icon_cache_id);
    free(notif->icon_symbolic_name);
    free(notif->icon_data);
    free(notif->sound_name);
    free(notif->xdg_token);
    free(notif->stdout_data);
    tll_free_and_free(notif->actions, free);
    if (notif->icon_path != NULL) {
        unlink(notif->icon_path);
        free(notif->icon_path);
        if (notif->icon_fd >= 0)
            close(notif->icon_fd);
    }
    memset(notif, 0, sizeof(*notif));
}
void notify_icon_add(
    struct terminal *term, const char *id, const char *symbolic_name,
    const uint8_t *data, size_t data_sz)
{
    (void)term; (void)id; (void)symbolic_name; (void)data; (void)data_sz;
}
void notify_icon_del(struct terminal *term, const char *id) { (void)term; (void)id; }
void notify_icon_free(struct notification_icon *icon)
{
    if (icon == NULL)
        return;
    if (icon->tmp_file_name != NULL) {
        unlink(icon->tmp_file_name);
        if (icon->tmp_file_fd >= 0)
            close(icon->tmp_file_fd);
    }
    free(icon->id);
    free(icon->symbolic_name);
    free(icon->tmp_file_name);
    memset(icon, 0, sizeof(*icon));
    icon->tmp_file_fd = -1;
}

/* -------------------------------------------------------------------------
 * URL mode / extraction / scrollback commands
 * ---------------------------------------------------------------------- */

void urls_reset(struct terminal *term)
{
    if (term == NULL)
        return;

    if (term->url.grid_snapshot != NULL) {
        grid_free(term->url.grid_snapshot);
        free(term->url.grid_snapshot);
        term->url.grid_snapshot = NULL;
    }
    term->render.last_cursor.row = NULL;

    tll_foreach(term->url.list, it) {
        free(it->item.url);
        free(it->item.key);
        tll_remove(term->url.list, it);
    }

    term->url.show_uri_on_jump_label = false;
    memset(term->url.keys, 0, sizeof(term->url.keys));
    term->url.ime_reenable_after_url_mode = false;
}
struct extraction_context *extract_begin(enum selection_kind kind, bool strip_trailing_empty)
{
    (void)kind; (void)strip_trailing_empty;
    return NULL;
}
bool extract_one(
    const struct terminal *term, const struct row *row, const struct cell *cell,
    int col, void *context)
{
    (void)term; (void)row; (void)cell; (void)col; (void)context;
    return false;
}
bool extract_finish(struct extraction_context *context, char **text, size_t *len)
{
    (void)context;
    if (text != NULL) *text = NULL;
    if (len != NULL) *len = 0;
    return false;
}
bool extract_finish_wide(struct extraction_context *context, char32_t **text, size_t *len)
{
    (void)context;
    if (text != NULL) *text = NULL;
    if (len != NULL) *len = 0;
    return false;
}
void cmd_scrollback_up(struct terminal *term, int rows) { (void)term; (void)rows; }
void cmd_scrollback_down(struct terminal *term, int rows) { (void)term; (void)rows; }

/* -------------------------------------------------------------------------
 * Sixel (disabled on Android)
 * ---------------------------------------------------------------------- */

void sixel_fini(struct terminal *term)
{
    free(term->sixel.image.data);
    term->sixel.image.data = NULL;
    free(term->sixel.private_palette);
    term->sixel.private_palette = NULL;
    free(term->sixel.shared_palette);
    term->sixel.shared_palette = NULL;
}
sixel_put sixel_init(struct terminal *term, int p1, int p2, int p3)
{
    (void)term; (void)p1; (void)p2; (void)p3;
    return NULL;
}
void sixel_unhook(struct terminal *term) { (void)term; }
void sixel_destroy(struct sixel *sixel)
{
    if (sixel == NULL)
        return;
    /* No pixman surfaces are created in the headless build, but the image
     * buffers themselves are owned here. */
    free(sixel->original.data);
    free(sixel->scaled.data);
    sixel->original.data = NULL;
    sixel->scaled.data = NULL;
    sixel->pix = NULL;
    sixel->original.pix = NULL;
    sixel->scaled.pix = NULL;
}
void sixel_destroy_all(struct terminal *term)
{
    tll_foreach(term->normal.sixel_images, it)
        sixel_destroy(&it->item);
    tll_foreach(term->alt.sixel_images, it)
        sixel_destroy(&it->item);
    tll_free(term->normal.sixel_images);
    tll_free(term->alt.sixel_images);
}
void sixel_scroll_up(struct terminal *term, int rows) { (void)term; (void)rows; }
void sixel_scroll_down(struct terminal *term, int rows) { (void)term; (void)rows; }
void sixel_cell_size_changed(struct terminal *term) { (void)term; }
void sixel_sync_cache(const struct terminal *term, struct sixel *sixel)
{
    (void)term; (void)sixel;
}
void sixel_reflow_grid(struct terminal *term, struct grid *grid)
{
    (void)term; (void)grid;
}
void sixel_reflow(struct terminal *term) { (void)term; }
void sixel_overwrite_by_rectangle(
    struct terminal *term, int row, int col, int height, int width)
{
    (void)term; (void)row; (void)col; (void)height; (void)width;
}
void sixel_overwrite_by_row(struct terminal *term, int row, int col, int width)
{
    (void)term; (void)row; (void)col; (void)width;
}
void sixel_overwrite_at_cursor(struct terminal *term, int width)
{
    (void)term; (void)width;
}
void sixel_colors_report_current(struct terminal *term) { (void)term; }
void sixel_colors_reset(struct terminal *term) { (void)term; }
void sixel_colors_set(struct terminal *term, unsigned count) { (void)term; (void)count; }
void sixel_colors_report_max(struct terminal *term) { (void)term; }
void sixel_geometry_report_current(struct terminal *term) { (void)term; }
void sixel_geometry_reset(struct terminal *term) { (void)term; }
void sixel_geometry_set(struct terminal *term, unsigned width, unsigned height)
{
    (void)term; (void)width; (void)height;
}
void sixel_geometry_report_max(struct terminal *term) { (void)term; }

/* -------------------------------------------------------------------------
 * pixman (only used by sixel/glyph paths, which are disabled)
 * ---------------------------------------------------------------------- */

void *pixman_image_get_data(pixman_image_t *image) { (void)image; return NULL; }
pixman_format_code_t pixman_image_get_format(pixman_image_t *image)
{
    (void)image;
    return PIXMAN_FORMAT_A8R8G8B8;
}
void pixman_image_unref(pixman_image_t *image) { (void)image; }
pixman_image_t *pixman_image_create_bits(
    pixman_format_code_t format, int width, int height, uint32_t *bits, int stride)
{
    (void)format; (void)width; (void)height; (void)bits; (void)stride;
    return NULL;
}
pixman_image_t *pixman_image_create_bits_no_clear(
    pixman_format_code_t format, int width, int height, uint32_t *bits, int stride)
{
    (void)format; (void)width; (void)height; (void)bits; (void)stride;
    return NULL;
}
void pixman_image_set_transform(pixman_image_t *image, const pixman_transform_t *transform)
{
    (void)image; (void)transform;
}
void pixman_image_set_filter(pixman_image_t *image, pixman_filter_t filter, const void *params, int n_params)
{
    (void)image; (void)filter; (void)params; (void)n_params;
}
void pixman_image_composite32(
    pixman_op_t op, pixman_image_t *src, pixman_image_t *mask,
    pixman_image_t *dst, int src_x, int src_y, int mask_x, int mask_y,
    int dst_x, int dst_y, int width, int height)
{
    (void)op; (void)src; (void)mask; (void)dst; (void)src_x; (void)src_y;
    (void)mask_x; (void)mask_y; (void)dst_x; (void)dst_y; (void)width; (void)height;
}
void pixman_region32_init(pixman_region32_t *region) { (void)region; }
void pixman_region32_fini(pixman_region32_t *region) { (void)region; }
bool pixman_region32_empty(pixman_region32_t *region) { (void)region; return true; }
void pixman_region32_union(pixman_region32_t *new_region, pixman_region32_t *region1, pixman_region32_t *region2)
{
    (void)new_region; (void)region1; (void)region2;
}
void pixman_region32_union_rect(pixman_region32_t *region, pixman_region32_t *source, int x, int y, unsigned width, unsigned height)
{
    (void)region; (void)source; (void)x; (void)y; (void)width; (void)height;
}
void pixman_region32_subtract(pixman_region32_t *region, pixman_region32_t *region1, pixman_region32_t *region2)
{
    (void)region; (void)region1; (void)region2;
}
void pixman_region32_intersect(pixman_region32_t *new_region, pixman_region32_t *region1, pixman_region32_t *region2)
{
    (void)new_region; (void)region1; (void)region2;
}
void pixman_region32_intersect_rect(pixman_region32_t *region, pixman_region32_t *source, int x, int y, unsigned width, unsigned height)
{
    (void)region; (void)source; (void)x; (void)y; (void)width; (void)height;
}
bool pixman_region32_contains_point(pixman_region32_t *region, int x, int y, pixman_box32_t *box)
{
    (void)region; (void)x; (void)y;
    if (box != NULL) *box = (pixman_box32_t){0, 0, 0, 0};
    return false;
}
pixman_box32_t *pixman_region32_rectangles(pixman_region32_t *region, int *count)
{
    (void)region;
    if (count != NULL) *count = 0;
    return NULL;
}
void pixman_region32_translate(pixman_region32_t *region, int x, int y)
{
    (void)region; (void)x; (void)y;
}
void pixman_region32_clear(pixman_region32_t *region) { (void)region; }

/* -------------------------------------------------------------------------
 * fcft (font rasterizer)
 * ---------------------------------------------------------------------- */

struct fcft_font_options *fcft_font_options_create(void)
{
    return calloc(1, sizeof(struct fcft_font_options));
}
void fcft_font_options_destroy(struct fcft_font_options *options) { free(options); }
struct fcft_font *fcft_from_name2(
    size_t count, const char *names[static count], const char *attributes,
    const struct fcft_font_options *options)
{
    (void)count; (void)names; (void)attributes; (void)options;
    return NULL;
}
void fcft_destroy(struct fcft_font *font) { free(font); }
const struct fcft_glyph *fcft_rasterize_char_utf32(
    struct fcft_font *font, char32_t cp, enum fcft_subpixel subpixel)
{
    (void)font; (void)cp; (void)subpixel;
    return NULL;
}
char32_t fcft_precompose(
    const struct fcft_font *font, char32_t base, char32_t comb,
    bool *base_from_primary, bool *comb_from_primary, bool *pre_from_primary)
{
    (void)font; (void)comb;
    if (base_from_primary != NULL) *base_from_primary = true;
    if (comb_from_primary != NULL) *comb_from_primary = false;
    if (pre_from_primary != NULL) *pre_from_primary = false;
    return base;
}

enum cursor_shape xcursor_for_csd_border(struct terminal *term, int x, int y)
{
    (void)term; (void)x; (void)y;
    return CURSOR_SHAPE_LEFT_PTR;
}

int cursor_string_to_server_shape(const char *xcursor, int bound_version)
{
    (void)xcursor; (void)bound_version;
    return 0;
}

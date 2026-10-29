#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <time.h>
#include <pixman.h>
#include <tllist.h>
#include "cursor-shape.h"
struct wayland;
struct wl_surface;
struct wl_subsurface;
struct wl_buffer;
struct wl_keyboard;
struct wl_pointer;
struct wl_touch;
struct xdg_toplevel;
struct seat;
struct terminal;
struct monitor { struct { struct { int width, height; } px_real; } dim; struct { float physical, scaled; } dpi; int subpixel; float scale; };
struct wayl_surface { struct wl_surface *surf; };
struct wayl_sub_surface { struct wayl_surface surface; struct wl_subsurface *sub; };
enum csd_surface { CSD_SURF_TITLE, CSD_SURF_LEFT, CSD_SURF_RIGHT, CSD_SURF_TOP, CSD_SURF_BOTTOM, CSD_SURF_MINIMIZE, CSD_SURF_MAXIMIZE, CSD_SURF_CLOSE, CSD_SURF_COUNT };
struct csd { struct wayl_sub_surface surface[CSD_SURF_COUNT]; };
struct wl_window {
    struct terminal *term;
    tll(const struct monitor *) on_outputs;
    struct wayl_surface surface;
    struct csd csd;
    struct xdg_toplevel *xdg_toplevel;
    bool is_configured;
    bool is_suspended;
    bool is_fullscreen;
    int scale;
    int preferred_buffer_scale;
};
struct kbd { uint32_t serial; struct xkb_keymap *xkb_keymap; struct terminal *kbd_focus; };
struct key_binding_set { xkb_mod_mask_t selection_overrides; };
struct pointer { bool hidden; };
struct mouse { int x, y; };
struct seat { struct kbd kbd; struct pointer pointer; struct mouse mouse; struct terminal *kbd_focus; struct terminal *mouse_focus; struct terminal *ime_focus; };
struct wayland {
    struct key_binding_manager *key_binding_manager;
    tll(struct monitor) monitors;
    void *fractional_scale_manager;
    void *viewporter;
    int shape_manager_version;
    tll(struct terminal *) terms;
    tll(struct seat) seats;
    bool shm_have_argb2101010;
    bool shm_have_abgr2101010;
};
void wl_surface_commit(struct wl_surface *);
void wl_surface_damage_buffer(
    struct wl_surface *, int32_t x, int32_t y, int32_t width, int32_t height);
void xdg_toplevel_set_maximized(struct xdg_toplevel *);
void xdg_toplevel_set_fullscreen(struct xdg_toplevel *, void *);

enum wl_output_subpixel { WL_OUTPUT_SUBPIXEL_UNKNOWN, WL_OUTPUT_SUBPIXEL_NONE, WL_OUTPUT_SUBPIXEL_HORIZONTAL_RGB, WL_OUTPUT_SUBPIXEL_HORIZONTAL_BGR, WL_OUTPUT_SUBPIXEL_VERTICAL_RGB, WL_OUTPUT_SUBPIXEL_VERTICAL_BGR };

bool wayl_do_linear_blending(struct wayland *wayl, const struct config *conf);
void key_binding_new_for_conf(struct key_binding_manager *mgr, struct wayland *wayl, const struct config *conf);

struct wl_window *wayl_win_init(struct terminal *term, const char *token);
void wayl_win_destroy(struct wl_window *win);
void wayl_win_alpha_changed(struct wl_window *win);
void key_binding_unref(struct key_binding_manager *mgr, const struct config *conf);

const struct key_binding_set *key_binding_for(struct key_binding_manager *mgr, const struct config *conf, const struct seat *seat);
bool wayl_win_set_urgent(struct wl_window *win);
bool wayl_win_ring_bell(const struct wl_window *win);
int cursor_string_to_server_shape(const char *xcursor, int bound_version);

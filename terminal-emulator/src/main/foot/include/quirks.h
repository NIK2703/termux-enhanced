#pragma once
struct wl_subsurface;
struct terminal;
void quirk_weston_subsurface_desync_on(struct wl_subsurface *sub);
void quirk_weston_subsurface_desync_off(struct wl_subsurface *sub);
void quirk_weston_csd_on(struct terminal *term);
void quirk_weston_csd_off(struct terminal *term);

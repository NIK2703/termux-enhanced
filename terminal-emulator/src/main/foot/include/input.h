#pragma once
#include <stdint.h>
#include <stdbool.h>
#include "wayland.h"
#define BTN_WHEEL_BACK 0x11c
#define BTN_WHEEL_FORWARD 0x11d
#define BTN_WHEEL_LEFT 0x11e
#define BTN_WHEEL_RIGHT 0x11f
struct wl_keyboard_listener;
struct wl_pointer_listener;
struct wl_touch_listener;
struct terminal;
struct seat;
void input_repeat(struct seat *seat, uint32_t key);
enum cursor_shape xcursor_for_csd_border(struct terminal *term, int x, int y);
void mouse_coord_pixel_to_cell(struct seat *seat, const struct terminal *term, int x, int y);
#include <xkbcommon/xkbcommon.h>
void get_current_modifiers(const struct seat *seat, xkb_mod_mask_t *effective, xkb_mod_mask_t *consumed, uint32_t key, bool filter_locked);

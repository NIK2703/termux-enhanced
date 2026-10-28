#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <uchar.h>
#include <tllist.h>
#include <fcft/fcft.h>

struct pt_or_px { int16_t px; float pt; };
struct config_font { char *pattern; float pt_size; int px_size; };
struct config_spawn_template { char **args; };
enum cursor_style { CURSOR_BLOCK, CURSOR_UNDERLINE, CURSOR_BEAM, CURSOR_HOLLOW };
enum cursor_unfocused_style { CURSOR_UNFOCUSED_UNCHANGED, CURSOR_UNFOCUSED_HOLLOW, CURSOR_UNFOCUSED_NONE };
enum which_color_theme { COLOR_THEME_DARK, COLOR_THEME_LIGHT, COLOR_THEME_1, COLOR_THEME_2 };
enum underline_style { UNDERLINE_NONE, UNDERLINE_SINGLE, UNDERLINE_DOUBLE, UNDERLINE_CURLY, UNDERLINE_DOTTED, UNDERLINE_DASHED };
enum osc52 { OSC52_DISABLED, OSC52_ENABLED, OSC52_COPY_ENABLED, OSC52_PASTE_ENABLED };
enum grapheme_width_method { GRAPHEME_WIDTH_DOUBLE, GRAPHEME_WIDTH_MAX, GRAPHEME_WIDTH_WCSWIDTH, GRAPHEME_WIDTH_WCWC, GRAPHEME_WIDTH_ALWAYS_WIDE };

struct color_theme {
    uint32_t fg, bg, alpha;
    uint32_t table[256];
    struct { bool cursor; } use_custom;
    struct { uint32_t text, cursor; } cursor;
    uint32_t selection_fg, selection_bg;
};

struct config {
    char *app_id;
    char *title;
    bool grapheme_shaping;
    enum which_color_theme initial_color_theme;
    struct color_theme colors_dark;
    struct color_theme colors_light;
    struct {
        enum cursor_style style;
        enum cursor_unfocused_style unfocused_style;
        struct { bool enabled; uint32_t rate_ms; } blink;
        struct pt_or_px beam_thickness;
        struct pt_or_px underline_thickness;
    } cursor;
    struct { enum osc52 osc52; } security;
    struct {
        bool sixel;
        enum grapheme_width_method grapheme_width_method;
    } tweak;
    struct { bool inhibit_when_focused; } desktop_notifications;
    struct {
        struct { bool cursor; } use_custom;
    } colors_dark_compat;
    struct {
        enum { OSC8_UNDERLINE_URL_MODE, OSC8_UNDERLINE_ALWAYS } osc8_underline;
    } url;
};

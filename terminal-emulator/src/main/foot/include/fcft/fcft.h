#pragma once

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <uchar.h>

#include <pixman.h>

struct fcft_font;
struct fcft_text_run;

struct fcft_glyph {
    pixman_image_t *pix;
    int width, height;
    int x, y;
    struct { int x, y; } advance;
    uint32_t codepoint;
    int cols;
    bool is_color_glyph;
};

struct fcft_grapheme {
    const struct fcft_glyph **glyphs;
    size_t count;
};

enum fcft_subpixel {
    FCFT_SUBPIXEL_DEFAULT,
    FCFT_SUBPIXEL_NONE,
    FCFT_SUBPIXEL_HORIZONTAL_RGB,
    FCFT_SUBPIXEL_HORIZONTAL_BGR,
    FCFT_SUBPIXEL_VERTICAL_RGB,
    FCFT_SUBPIXEL_VERTICAL_BGR,
};

enum fcft_scaling_filter {
    FCFT_SCALING_FILTER_NONE,
    FCFT_SCALING_FILTER_NEAREST,
    FCFT_SCALING_FILTER_BILINEAR,
};

struct fcft_font_options {
    enum fcft_scaling_filter scaling_filter;
    struct {
        pixman_format_code_t format;
        bool srgb_decode;
    } color_glyphs;
};

struct fcft_font {
    int height, ascent, descent;
    struct { int x, y; } max_advance;
    struct { int position, thickness; } underline;
    struct { int position, thickness; } strikeout;

    /* Canvas backend state. Unused by the real fcft library. */
    int canvas_index;
    void *canvas_cache;
};

#define FCFT_CAPABILITY_TEXT_RUN_SHAPING (1ull << 0)

struct fcft_font_options *fcft_font_options_create(void);
void fcft_font_options_destroy(struct fcft_font_options *);
struct fcft_font *fcft_from_name2(
    size_t count, const char *names[static count], const char *attributes,
    const struct fcft_font_options *);
void fcft_destroy(struct fcft_font *);
const struct fcft_glyph *fcft_rasterize_char_utf32(
    struct fcft_font *, char32_t, enum fcft_subpixel);
const struct fcft_grapheme *fcft_rasterize_grapheme_utf32(
    struct fcft_font *, size_t, const char32_t *, enum fcft_subpixel);
struct fcft_text_run *fcft_rasterize_text_run_utf32(
    struct fcft_font *, size_t, const char32_t *, enum fcft_subpixel);
void fcft_text_run_destroy(struct fcft_text_run *);
uint64_t fcft_capabilities(void);
char32_t fcft_precompose(
    const struct fcft_font *font, char32_t base, char32_t comb,
    bool *base_from_primary, bool *comb_from_primary, bool *pre_from_primary);

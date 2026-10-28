#pragma once
#include <stdbool.h>
#include <stdint.h>
#include <uchar.h>
#include <pixman.h>
struct fcft_font;

struct fcft_glyph { pixman_image_t *pix; int width, height; int x, y; struct { int x, y; } advance; uint32_t codepoint; };
enum fcft_subpixel { FCFT_SUBPIXEL_DEFAULT, FCFT_SUBPIXEL_NONE, FCFT_SUBPIXEL_HORIZONTAL_RGB, FCFT_SUBPIXEL_HORIZONTAL_BGR, FCFT_SUBPIXEL_VERTICAL_RGB, FCFT_SUBPIXEL_VERTICAL_BGR };
enum fcft_scaling_filter { FCFT_SCALING_FILTER_NONE, FCFT_SCALING_FILTER_NEAREST, FCFT_SCALING_FILTER_BILINEAR };
struct fcft_font_options { enum fcft_scaling_filter scaling_filter; struct { pixman_format_code_t format; bool srgb_decode; } color_glyphs; };
struct fcft_font { int height, ascent, descent; struct { int x, y; } max_advance; };
struct fcft_font_options *fcft_font_options_create(void);
void fcft_font_options_destroy(struct fcft_font_options *);
struct fcft_font *fcft_from_name2(size_t count, const char *names[static count], const char *attributes, const struct fcft_font_options *);
void fcft_destroy(struct fcft_font *);
const struct fcft_glyph *fcft_rasterize_char_utf32(struct fcft_font *, char32_t, enum fcft_subpixel);
char32_t fcft_precompose(const struct fcft_font *font, char32_t base, char32_t comb, bool *base_from_primary, bool *comb_from_primary, bool *pre_from_primary);

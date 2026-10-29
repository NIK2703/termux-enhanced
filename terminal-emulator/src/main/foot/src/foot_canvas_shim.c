/*
 * Canvas compatibility backend for upstream foot render_cell().
 *
 * This file supplies the tiny subset of pixman and fcft that render_cell()
 * needs. It does not rasterize fonts: glyphs become one Canvas text operation
 * each. The real Android font stack still performs shaping and rasterization
 * in TerminalRenderer.
 */

#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <uchar.h>
#include <wchar.h>

#include <pixman.h>
#include <fcft/fcft.h>

#include "foot_canvas_backend.h"
#include "terminal.h"

enum image_kind {
    IMAGE_SOLID,
    IMAGE_BITS,
    IMAGE_GLYPH,
    IMAGE_TARGET,
};

struct foot_canvas {
    const struct foot_canvas_sink *sink;
    int row;
    int cell_height;
};

struct pixman_image {
    enum image_kind kind;
    uint32_t solid;
    uint32_t *bits;
    int width;
    int height;
    int stride;
    pixman_format_code_t format;

    char32_t glyph_cp;
    int glyph_y;
    int glyph_font_index;

    struct foot_canvas *canvas;
};

struct canvas_glyph_cache {
    char32_t cp;
    struct pixman_image image;
    struct fcft_glyph glyph;
    struct canvas_glyph_cache *next;
};

static uint8_t
pixman_channel_to_argb(uint16_t channel, uint16_t alpha)
{
    if (alpha == 0)
        return 0;
    const uint32_t value = ((uint32_t)channel * 255u + alpha / 2u) / alpha;
    return value > 255 ? 255 : (uint8_t)value;
}

static uint32_t
argb_from_pixman(const pixman_color_t *color)
{
    if (color == NULL)
        return 0;

    const uint16_t alpha = color->alpha;
    const uint8_t alpha8 = (uint8_t)(alpha >> 8);
    if (alpha8 == 0)
        return 0;

    /* foot's color_hex_to_pixman_with_alpha() stores premultiplied channels.
     * Android Paint colors are unpremultiplied and are premultiplied by the
     * Canvas when it blends, so undo the multiplication here to avoid applying
     * background alpha twice. */
    return ((uint32_t)alpha8 << 24) |
           ((uint32_t)pixman_channel_to_argb(color->red, alpha) << 16) |
           ((uint32_t)pixman_channel_to_argb(color->green, alpha) << 8) |
           ((uint32_t)pixman_channel_to_argb(color->blue, alpha));
}

static int
row_y(const struct foot_canvas *canvas, int y)
{
    return y - canvas->row * canvas->cell_height;
}

pixman_image_t *
foot_canvas_target_create(
    const struct foot_canvas_sink *sink, int row, int cell_height)
{
    struct foot_canvas *canvas = calloc(1, sizeof(*canvas));
    if (canvas == NULL)
        return NULL;
    *canvas = (struct foot_canvas){
        .sink = sink,
        .row = row,
        .cell_height = cell_height,
    };

    struct pixman_image *target = calloc(1, sizeof(*target));
    if (target == NULL) {
        free(canvas);
        return NULL;
    }
    *target = (struct pixman_image){
        .kind = IMAGE_TARGET,
        .format = PIXMAN_a8r8g8b8,
        .canvas = canvas,
    };
    return target;
}

void
foot_canvas_target_destroy(pixman_image_t *target)
{
    if (target == NULL)
        return;
    free(target->canvas);
    free(target);
}

void *
pixman_image_get_data(pixman_image_t *image)
{
    return image != NULL ? image->bits : NULL;
}

pixman_format_code_t
pixman_image_get_format(pixman_image_t *image)
{
    return image != NULL ? image->format : PIXMAN_FORMAT_A8R8G8B8;
}

pixman_image_t *
pixman_image_create_bits(
    pixman_format_code_t format, int width, int height, uint32_t *bits,
    int stride)
{
    struct pixman_image *image = calloc(1, sizeof(*image));
    if (image == NULL)
        return NULL;
    if (stride == 0)
        stride = width * 4;
    *image = (struct pixman_image){
        .kind = IMAGE_BITS,
        .bits = bits,
        .width = width,
        .height = height,
        .stride = stride,
        .format = format,
    };
    return image;
}

pixman_image_t *
pixman_image_create_bits_no_clear(
    pixman_format_code_t format, int width, int height, uint32_t *bits,
    int stride)
{
    return pixman_image_create_bits(format, width, height, bits, stride);
}

pixman_image_t *
pixman_image_create_solid_fill(const pixman_color_t *color)
{
    struct pixman_image *image = calloc(1, sizeof(*image));
    if (image == NULL)
        return NULL;
    *image = (struct pixman_image){
        .kind = IMAGE_SOLID,
        .solid = argb_from_pixman(color),
        .format = PIXMAN_a8r8g8b8,
    };
    return image;
}

void
pixman_image_unref(pixman_image_t *image)
{
    free(image);
}

void
pixman_image_set_transform(pixman_image_t *image, const pixman_transform_t *transform)
{
    (void)image;
    (void)transform;
}

void
pixman_image_set_filter(
    pixman_image_t *image, pixman_filter_t filter, const void *params,
    int n_params)
{
    (void)image;
    (void)filter;
    (void)params;
    (void)n_params;
}

void
pixman_image_set_clip_region32(pixman_image_t *image, pixman_region32_t *region)
{
    (void)image;
    (void)region;
}

void
pixman_image_fill_rectangles(
    pixman_op_t op, pixman_image_t *image, const pixman_color_t *color,
    int n_rects, const pixman_rectangle16_t *rects)
{
    (void)op;

    if (image == NULL || color == NULL || rects == NULL)
        return;

    if (image->kind == IMAGE_TARGET && image->canvas != NULL &&
        image->canvas->sink != NULL &&
        image->canvas->sink->fill_rect != NULL)
    {
        const uint32_t argb = argb_from_pixman(color);
        for (int i = 0; i < n_rects; i++) {
            const pixman_rectangle16_t *rect = &rects[i];
            if (rect->width == 0 || rect->height == 0)
                continue;
            image->canvas->sink->fill_rect(
                image->canvas->sink->data, rect->x,
                row_y(image->canvas, rect->y), rect->width, rect->height,
                argb);
        }
        return;
    }

    if (image->kind == IMAGE_BITS && image->bits != NULL) {
        const uint32_t argb = argb_from_pixman(color);
        for (int i = 0; i < n_rects; i++) {
            const pixman_rectangle16_t *rect = &rects[i];
            for (int y = rect->y; y < rect->y + rect->height; y++) {
                if (y < 0 || y >= image->height)
                    continue;
                uint32_t *row = (uint32_t *)((char *)image->bits +
                                             (size_t)y * image->stride);
                for (int x = rect->x; x < rect->x + rect->width; x++) {
                    if (x >= 0 && x < image->width)
                        row[x] = argb;
                }
            }
        }
    }
}

void
pixman_image_composite32(
    pixman_op_t op, pixman_image_t *src, pixman_image_t *mask,
    pixman_image_t *dst, int src_x, int src_y, int mask_x, int mask_y,
    int dst_x, int dst_y, int width, int height)
{
    (void)op;
    (void)src_x;
    (void)src_y;
    (void)mask_x;
    (void)mask_y;
    (void)width;
    (void)height;

    if (dst == NULL || dst->kind != IMAGE_TARGET || dst->canvas == NULL ||
        dst->canvas->sink == NULL)
    {
        return;
    }

    const struct foot_canvas *canvas = dst->canvas;
    struct pixman_image *glyph = NULL;
    if (mask != NULL && mask->kind == IMAGE_GLYPH)
        glyph = mask;
    else if (src != NULL && src->kind == IMAGE_GLYPH)
        glyph = src;

    if (glyph != NULL && canvas->sink->draw_glyph != NULL) {
        uint32_t argb = 0xffffffffu;
        if (src != NULL && src->kind == IMAGE_SOLID)
            argb = src->solid;
        canvas->sink->draw_glyph(
            canvas->sink->data, glyph->glyph_cp, dst_x,
            row_y(canvas, dst_y) + glyph->glyph_y,
            argb, glyph->glyph_font_index);
        return;
    }

    if (src != NULL && src->kind == IMAGE_SOLID &&
        canvas->sink->fill_rect != NULL)
    {
        canvas->sink->fill_rect(
            canvas->sink->data, dst_x, row_y(canvas, dst_y), width, height,
            src->solid);
    }
}

void
pixman_composite_trapezoids(
    pixman_op_t op, pixman_image_t *src, pixman_image_t *dst,
    pixman_format_code_t mask_format, int src_x, int src_y,
    int dst_x, int dst_y, int n_traps, const pixman_trapezoid_t *traps)
{
    (void)op;
    (void)mask_format;
    (void)src_x;
    (void)src_y;
    (void)dst_x;
    (void)dst_y;

    if (src == NULL || src->kind != IMAGE_SOLID || dst == NULL ||
        dst->kind != IMAGE_TARGET || dst->canvas == NULL ||
        dst->canvas->sink == NULL ||
        dst->canvas->sink->fill_trapezoid == NULL)
    {
        return;
    }

    const struct foot_canvas *canvas = dst->canvas;
    for (int i = 0; i < n_traps; i++) {
        const pixman_trapezoid_t *trap = &traps[i];
        canvas->sink->fill_trapezoid(
            canvas->sink->data,
            trap->left.p1.x >> 16, row_y(canvas, trap->left.p1.y >> 16),
            trap->left.p2.x >> 16, row_y(canvas, trap->left.p2.y >> 16),
            trap->right.p2.x >> 16, row_y(canvas, trap->right.p2.y >> 16),
            trap->right.p1.x >> 16, row_y(canvas, trap->right.p1.y >> 16),
            src->solid);
    }
}

void
pixman_composite_triangles(
    pixman_op_t op, pixman_image_t *src, pixman_image_t *dst,
    pixman_format_code_t mask_format, int src_x, int src_y,
    int dst_x, int dst_y, int n_triangles,
    const pixman_triangle_t *triangles)
{
    (void)op;
    (void)mask_format;
    (void)src_x;
    (void)src_y;
    (void)dst_x;
    (void)dst_y;

    if (src == NULL || src->kind != IMAGE_SOLID || dst == NULL ||
        dst->kind != IMAGE_TARGET || dst->canvas == NULL ||
        dst->canvas->sink == NULL ||
        dst->canvas->sink->fill_trapezoid == NULL)
    {
        return;
    }

    const struct foot_canvas *canvas = dst->canvas;
    for (int i = 0; i < n_triangles; i++) {
        const pixman_triangle_t *triangle = &triangles[i];
        canvas->sink->fill_trapezoid(
            canvas->sink->data,
            triangle->p1.x >> 16, row_y(canvas, triangle->p1.y >> 16),
            triangle->p2.x >> 16, row_y(canvas, triangle->p2.y >> 16),
            triangle->p3.x >> 16, row_y(canvas, triangle->p3.y >> 16),
            triangle->p3.x >> 16, row_y(canvas, triangle->p3.y >> 16),
            src->solid);
    }
}

pixman_fixed_t
pixman_int_to_fixed(int value)
{
    return (pixman_fixed_t)(value << 16);
}

/* Region storage is not needed: clipping is already applied by the Canvas row
 * clip and by the cell-level bounds checked by render_cell(). */
pixman_region32_t *
pixman_region32_create(int count)
{
    (void)count;
    return calloc(1, sizeof(pixman_region32_t));
}

void
pixman_region32_destroy(pixman_region32_t *region)
{
    free(region);
}

void pixman_region32_init(pixman_region32_t *region) { (void)region; }
void pixman_region32_init_rect(
    pixman_region32_t *region, int x, int y, unsigned width, unsigned height)
{
    (void)region;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
}
void pixman_region32_fini(pixman_region32_t *region) { (void)region; }
bool pixman_region32_empty(pixman_region32_t *region) { (void)region; return true; }
void pixman_region32_copy(
    pixman_region32_t *dst, const pixman_region32_t *src)
{
    (void)dst;
    (void)src;
}
void pixman_region32_union(
    pixman_region32_t *new_region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    (void)new_region;
    (void)region1;
    (void)region2;
}
void pixman_region32_union_rect(
    pixman_region32_t *region, pixman_region32_t *source, int x, int y,
    unsigned width, unsigned height)
{
    (void)region;
    (void)source;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
}
void pixman_region32_subtract(
    pixman_region32_t *region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    (void)region;
    (void)region1;
    (void)region2;
}
void pixman_region32_intersect(
    pixman_region32_t *new_region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    (void)new_region;
    (void)region1;
    (void)region2;
}
void pixman_region32_intersect_rect(
    pixman_region32_t *region, pixman_region32_t *source, int x, int y,
    unsigned width, unsigned height)
{
    (void)region;
    (void)source;
    (void)x;
    (void)y;
    (void)width;
    (void)height;
}
bool pixman_region32_contains_point(
    pixman_region32_t *region, int x, int y, pixman_box32_t *box)
{
    (void)region;
    (void)x;
    (void)y;
    if (box != NULL)
        *box = (pixman_box32_t){0, 0, 0, 0};
    return false;
}
pixman_box32_t *
pixman_region32_rectangles(pixman_region32_t *region, int *count)
{
    (void)region;
    if (count != NULL)
        *count = 0;
    return NULL;
}
void pixman_region32_translate(pixman_region32_t *region, int x, int y)
{
    (void)region;
    (void)x;
    (void)y;
}
void pixman_region32_clear(pixman_region32_t *region) { (void)region; }

/* Small sRGB helper used by the extracted foot renderer. Linear blending is
 * disabled on Android, so the lookup table is only needed for compilation. */
uint16_t
srgb_decode_8_to_16(uint8_t value)
{
    return (uint16_t)(value * 257);
}

struct fcft_font_options *
fcft_font_options_create(void)
{
    return calloc(1, sizeof(struct fcft_font_options));
}

void
fcft_font_options_destroy(struct fcft_font_options *options)
{
    free(options);
}

struct fcft_font *
fcft_from_name2(
    size_t count, const char *names[static count], const char *attributes,
    const struct fcft_font_options *options)
{
    (void)count;
    (void)names;
    (void)attributes;
    (void)options;
    return NULL;
}

struct fcft_font *
foot_canvas_font_create(
    int cell_width, int cell_height, int ascent, int font_index)
{
    struct fcft_font *font = calloc(1, sizeof(*font));
    if (font == NULL)
        return NULL;
    if (cell_width < 1)
        cell_width = 1;
    if (cell_height < 1)
        cell_height = 1;
    if (ascent < 0)
        ascent = 0;
    if (ascent > cell_height)
        ascent = cell_height;

    *font = (struct fcft_font){
        .height = cell_height,
        .ascent = ascent,
        .descent = cell_height - ascent,
        .max_advance = {.x = cell_width, .y = 0},
        .underline = {
            .position = -2,
            .thickness = cell_height >= 14 ? cell_height / 14 : 1,
        },
        .strikeout = {
            .position = ascent / 2,
            .thickness = cell_height >= 14 ? cell_height / 14 : 1,
        },
        .canvas_index = font_index,
    };
    return font;
}

void
fcft_destroy(struct fcft_font *font)
{
    if (font == NULL)
        return;
    struct canvas_glyph_cache *entry = font->canvas_cache;
    while (entry != NULL) {
        struct canvas_glyph_cache *next = entry->next;
        free(entry);
        entry = next;
    }
    free(font);
}

static const struct fcft_glyph *
rasterize_char(struct fcft_font *font, char32_t cp)
{
    if (font == NULL || cp == 0 || cp >= 0x40000000u)
        return NULL;

    for (struct canvas_glyph_cache *entry = font->canvas_cache;
         entry != NULL; entry = entry->next)
    {
        if (entry->cp == cp)
            return &entry->glyph;
    }

    int cols = 1;
    const int w = wcwidth((wchar_t)cp);
    if (w > 1)
        cols = w;
    if (cols > 2)
        cols = 2;

    struct canvas_glyph_cache *entry = calloc(1, sizeof(*entry));
    if (entry == NULL)
        return NULL;

    const int glyph_width = cols * font->max_advance.x;
    entry->cp = cp;
    entry->image = (struct pixman_image){
        .kind = IMAGE_GLYPH,
        .width = glyph_width,
        .height = font->height,
        .format = PIXMAN_a8r8g8b8,
        .glyph_cp = cp,
        .glyph_y = font->ascent,
        .glyph_font_index = font->canvas_index,
    };
    entry->glyph = (struct fcft_glyph){
        .pix = &entry->image,
        .width = glyph_width,
        .height = font->height,
        .x = 0,
        .y = font->ascent,
        .advance = {.x = font->max_advance.x, .y = 0},
        .codepoint = cp,
        .cols = cols,
        .is_color_glyph = false,
    };
    entry->next = font->canvas_cache;
    font->canvas_cache = entry;
    return &entry->glyph;
}

const struct fcft_glyph *
fcft_rasterize_char_utf32(
    struct fcft_font *font, char32_t cp, enum fcft_subpixel subpixel)
{
    (void)subpixel;
    return rasterize_char(font, cp);
}

const struct fcft_grapheme *
fcft_rasterize_grapheme_utf32(
    struct fcft_font *font, size_t count, const char32_t *cps,
    enum fcft_subpixel subpixel)
{
    (void)font;
    (void)count;
    (void)cps;
    (void)subpixel;
    return NULL;
}

struct fcft_text_run *
fcft_rasterize_text_run_utf32(
    struct fcft_font *font, size_t count, const char32_t *cps,
    enum fcft_subpixel subpixel)
{
    (void)font;
    (void)count;
    (void)cps;
    (void)subpixel;
    return NULL;
}

void
fcft_text_run_destroy(struct fcft_text_run *run)
{
    free(run);
}

uint64_t
fcft_capabilities(void)
{
    return 0;
}

char32_t
fcft_precompose(
    const struct fcft_font *font, char32_t base, char32_t comb,
    bool *base_from_primary, bool *comb_from_primary, bool *pre_from_primary)
{
    (void)font;
    (void)comb;
    if (base_from_primary != NULL)
        *base_from_primary = true;
    if (comb_from_primary != NULL)
        *comb_from_primary = false;
    if (pre_from_primary != NULL)
        *pre_from_primary = false;
    return base;
}

/*
 * render_cell() compiles the box-drawing fallback even when the runtime config
 * selects font glyphs. Android uses the platform font, so this path is never
 * reached; the symbol only has to exist.
 */
struct fcft_glyph *
box_drawing(const struct terminal *term, char32_t wc)
{
    (void)term;
    (void)wc;
    return NULL;
}

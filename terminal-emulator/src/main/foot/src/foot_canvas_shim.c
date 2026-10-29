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
    /* Monotonic identity used by the Java Bitmap cache. Unlike the image
     * pointer, it is never reused after a pixman image is freed. */
    uint64_t id;
    uint32_t solid;
    uint32_t *bits;
    int width;
    int height;
    int stride;
    pixman_format_code_t format;

    /* Sixel needs one scaling composite per image after a cell-size change.
     * Keep the transform factors instead of the full fixed-point matrix. */
    bool has_transform;
    double scale_x;
    double scale_y;

    /* Incremented whenever this image's pixels are changed. The Java side
     * uses it to invalidate its Bitmap cache for a sixel image. */
    uint32_t revision;

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

static uint64_t next_image_id = 1;

static uint64_t
allocate_image_id(void)
{
    uint64_t id = next_image_id++;
    if (id == 0)
        id = next_image_id++;
    return id;
}

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
        .id = allocate_image_id(),
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

uint32_t
foot_canvas_image_revision(pixman_image_t *image)
{
    return image != NULL ? image->revision : 0;
}

uint64_t
foot_canvas_image_id(pixman_image_t *image)
{
    return image != NULL ? image->id : 0;
}

void *
pixman_image_get_data(pixman_image_t *image)
{
    return image != NULL ? image->bits : NULL;
}

int
pixman_image_get_width(pixman_image_t *image)
{
    return image != NULL ? image->width : 0;
}

int
pixman_image_get_height(pixman_image_t *image)
{
    return image != NULL ? image->height : 0;
}

pixman_image_t *
pixman_image_ref(pixman_image_t *image)
{
    return image;
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
        .id = allocate_image_id(),
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
        .id = allocate_image_id(),
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
    if (image == NULL)
        return;
    image->has_transform = transform != NULL;
    if (transform == NULL) {
        image->scale_x = 1.0;
        image->scale_y = 1.0;
        return;
    }
    /* pixman_transform_t stores fixed-point values. Sixel only uses a
     * symmetric scale here; the caller's destination size is authoritative if
     * this extraction is ambiguous. */
    image->scale_x = (double)transform->xx / 65536.0;
    image->scale_y = (double)transform->yy / 65536.0;
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
        image->revision++;
    }
}

static uint32_t
blend_over_pixel(uint32_t src, uint32_t dst)
{
    const uint32_t sa = src >> 24;
    if (sa == 0)
        return dst;
    if (sa == 255)
        return src;
    const uint32_t inv = 255 - sa;
    const uint32_t dr = (dst >> 16) & 0xff;
    const uint32_t dg = (dst >> 8) & 0xff;
    const uint32_t db = dst & 0xff;
    const uint32_t r = ((src >> 16) & 0xff) + (dr * inv + 127) / 255;
    const uint32_t g = ((src >> 8) & 0xff) + (dg * inv + 127) / 255;
    const uint32_t b = (src & 0xff) + (db * inv + 127) / 255;
    return ((sa + (dst >> 24) * inv / 255) << 24) |
        ((r > 255 ? 255 : r) << 16) |
        ((g > 255 ? 255 : g) << 8) |
        (b > 255 ? 255 : b);
}

static uint32_t
image_pixel(const struct pixman_image *image, int x, int y, uint32_t fallback)
{
    if (image == NULL)
        return fallback;
    if (image->kind == IMAGE_SOLID)
        return image->solid;
    if (image->kind != IMAGE_BITS || image->bits == NULL)
        return fallback;
    if (x < 0 || y < 0 || x >= image->width || y >= image->height)
        return fallback;
    const uint32_t *row = (const uint32_t *)((const char *)image->bits +
                                             (size_t)y * image->stride);
    return row[x];
}

void
pixman_image_composite32(
    pixman_op_t op, pixman_image_t *src, pixman_image_t *mask,
    pixman_image_t *dst, int src_x, int src_y, int mask_x, int mask_y,
    int dst_x, int dst_y, int width, int height)
{
    (void)mask_x;
    (void)mask_y;

    if (dst == NULL || width <= 0 || height <= 0)
        return;

    if (dst->kind == IMAGE_TARGET && dst->canvas != NULL &&
        dst->canvas->sink != NULL)
    {
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
        return;
    }

    if (dst->kind != IMAGE_BITS || dst->bits == NULL)
        return;

    /* Sixel cropping/scaling composites into an owned ARGB32 buffer. */
    for (int y = 0; y < height; y++) {
        const int dy = dst_y + y;
        if (dy < 0 || dy >= dst->height)
            continue;
        uint32_t *dst_row = (uint32_t *)((char *)dst->bits +
                                         (size_t)dy * dst->stride);
        for (int x = 0; x < width; x++) {
            const int dx = dst_x + x;
            if (dx < 0 || dx >= dst->width)
                continue;

            int sx = src_x + x;
            int sy = src_y + y;
            if (src != NULL && src->has_transform) {
                sx = (int)(x * src->scale_x);
                sy = (int)(y * src->scale_y);
            }
            const uint32_t source = image_pixel(src, sx, sy, 0);
            if (op == PIXMAN_OP_SRC)
                dst_row[dx] = source;
            else if (op == PIXMAN_OP_CLEAR)
                dst_row[dx] = 0;
            else if (op == PIXMAN_OP_OVER_REVERSE)
                dst_row[dx] = blend_over_pixel(dst_row[dx], source);
            else
                dst_row[dx] = blend_over_pixel(source, dst_row[dx]);
        }
    }
    dst->revision++;
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

/* A compact rectangle-set region used by the upstream sixel code. The normal
 * cell renderer never needs it, so only the operations sixel.c actually calls
 * are implemented. Rectangles are kept as a list; subtract may split a
 * rectangle into four pieces, exactly like pixman's region algebra. */
static void
region_reserve(pixman_region32_t *region, int count)
{
    if (region == NULL || count <= region->capacity)
        return;
    int capacity = region->capacity > 0 ? region->capacity : 4;
    while (capacity < count)
        capacity *= 2;
    pixman_box32_t *resized = realloc(
        region->rects, (size_t)capacity * sizeof(resized[0]));
    if (resized == NULL)
        return;
    region->rects = resized;
    region->capacity = capacity;
}

static void
region_add(pixman_region32_t *region, int x1, int y1, int x2, int y2)
{
    if (region == NULL || x1 >= x2 || y1 >= y2)
        return;
    region_reserve(region, region->count + 1);
    if (region->count >= region->capacity)
        return;
    region->rects[region->count++] =
        (pixman_box32_t){x1, y1, x2, y2};
}

pixman_region32_t *
pixman_region32_create(int count)
{
    (void)count;
    return calloc(1, sizeof(pixman_region32_t));
}

void
pixman_region32_destroy(pixman_region32_t *region)
{
    if (region == NULL)
        return;
    free(region->rects);
    free(region);
}

void
pixman_region32_init(pixman_region32_t *region)
{
    if (region != NULL)
        memset(region, 0, sizeof(*region));
}

void
pixman_region32_init_rect(
    pixman_region32_t *region, int x, int y, unsigned width, unsigned height)
{
    if (region == NULL)
        return;
    memset(region, 0, sizeof(*region));
    region_add(region, x, y, x + (int)width, y + (int)height);
}

void
pixman_region32_init_rects(
    pixman_region32_t *region, const pixman_box32_t *boxes, int count)
{
    if (region == NULL)
        return;
    memset(region, 0, sizeof(*region));
    for (int i = 0; i < count; i++)
        region_add(region, boxes[i].x1, boxes[i].y1, boxes[i].x2, boxes[i].y2);
}

void
pixman_region32_fini(pixman_region32_t *region)
{
    if (region == NULL)
        return;
    free(region->rects);
    memset(region, 0, sizeof(*region));
}

bool
pixman_region32_empty(pixman_region32_t *region)
{
    return region == NULL || region->count == 0;
}

bool
pixman_region32_not_empty(pixman_region32_t *region)
{
    return region != NULL && region->count > 0;
}

void
pixman_region32_copy(
    pixman_region32_t *dst, const pixman_region32_t *src)
{
    if (dst == NULL || src == NULL || dst == src)
        return;
    pixman_region32_fini(dst);
    region_reserve(dst, src->count);
    for (int i = 0; i < src->count && i < dst->capacity; i++)
        dst->rects[dst->count++] = src->rects[i];
}

void
pixman_region32_union(
    pixman_region32_t *new_region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    if (new_region == NULL)
        return;
    if (new_region != region1 && new_region != region2)
        pixman_region32_fini(new_region);
    const int base = new_region->count;
    (void)base;
    if (region1 != NULL && region1 != new_region) {
        for (int i = 0; i < region1->count; i++)
            region_add(new_region, region1->rects[i].x1, region1->rects[i].y1,
                region1->rects[i].x2, region1->rects[i].y2);
    }
    if (region2 != NULL && region2 != new_region) {
        for (int i = 0; i < region2->count; i++)
            region_add(new_region, region2->rects[i].x1, region2->rects[i].y1,
                region2->rects[i].x2, region2->rects[i].y2);
    }
}

void
pixman_region32_union_rect(
    pixman_region32_t *region, pixman_region32_t *source, int x, int y,
    unsigned width, unsigned height)
{
    if (region == NULL)
        return;
    if (source != NULL && source != region) {
        for (int i = 0; i < source->count; i++)
            region_add(region, source->rects[i].x1, source->rects[i].y1,
                source->rects[i].x2, source->rects[i].y2);
    }
    region_add(region, x, y, x + (int)width, y + (int)height);
}

static void
region_subtract_one(
    const pixman_box32_t *a, const pixman_box32_t *b,
    pixman_region32_t *out)
{
    const int x1 = a->x1 > b->x1 ? a->x1 : b->x1;
    const int y1 = a->y1 > b->y1 ? a->y1 : b->y1;
    const int x2 = a->x2 < b->x2 ? a->x2 : b->x2;
    const int y2 = a->y2 < b->y2 ? a->y2 : b->y2;
    if (x1 >= x2 || y1 >= y2) {
        region_add(out, a->x1, a->y1, a->x2, a->y2);
        return;
    }
    /* Top, bottom, left and right remainder. */
    region_add(out, a->x1, a->y1, a->x2, y1);
    region_add(out, a->x1, y2, a->x2, a->y2);
    region_add(out, a->x1, y1, x1, y2);
    region_add(out, x2, y1, a->x2, y2);
}

void
pixman_region32_subtract(
    pixman_region32_t *region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    if (region == NULL || region1 == NULL)
        return;
    pixman_region32_t result;
    memset(&result, 0, sizeof(result));
    for (int i = 0; i < region1->count; i++) {
        pixman_region32_t current;
        memset(&current, 0, sizeof(current));
        region_add(&current, region1->rects[i].x1, region1->rects[i].y1,
            region1->rects[i].x2, region1->rects[i].y2);
        for (int j = 0; region2 != NULL && j < region2->count; j++) {
            pixman_region32_t next;
            memset(&next, 0, sizeof(next));
            for (int k = 0; k < current.count; k++)
                region_subtract_one(&current.rects[k], &region2->rects[j], &next);
            free(current.rects);
            current = next;
        }
        for (int k = 0; k < current.count; k++)
            region_add(&result, current.rects[k].x1, current.rects[k].y1,
                current.rects[k].x2, current.rects[k].y2);
        free(current.rects);
    }
    if (region == region1 || region == region2)
        pixman_region32_fini(region);
    free(region->rects);
    *region = result;
}

void
pixman_region32_intersect(
    pixman_region32_t *new_region, pixman_region32_t *region1,
    pixman_region32_t *region2)
{
    if (new_region == NULL || region1 == NULL || region2 == NULL)
        return;
    pixman_region32_t result;
    memset(&result, 0, sizeof(result));
    for (int i = 0; i < region1->count; i++) {
        for (int j = 0; j < region2->count; j++) {
            const pixman_box32_t *a = &region1->rects[i];
            const pixman_box32_t *b = &region2->rects[j];
            const int x1 = a->x1 > b->x1 ? a->x1 : b->x1;
            const int y1 = a->y1 > b->y1 ? a->y1 : b->y1;
            const int x2 = a->x2 < b->x2 ? a->x2 : b->x2;
            const int y2 = a->y2 < b->y2 ? a->y2 : b->y2;
            if (x1 < x2 && y1 < y2)
                region_add(&result, x1, y1, x2, y2);
        }
    }
    if (new_region == region1 || new_region == region2)
        pixman_region32_fini(new_region);
    free(new_region->rects);
    *new_region = result;
}

void
pixman_region32_intersect_rect(
    pixman_region32_t *region, pixman_region32_t *source, int x, int y,
    unsigned width, unsigned height)
{
    pixman_region32_t rect;
    pixman_region32_init_rect(&rect, x, y, width, height);
    pixman_region32_intersect(region, source, &rect);
    pixman_region32_fini(&rect);
}

bool
pixman_region32_contains_point(
    pixman_region32_t *region, int x, int y, pixman_box32_t *box)
{
    if (region == NULL)
        return false;
    for (int i = 0; i < region->count; i++) {
        const pixman_box32_t *rect = &region->rects[i];
        if (x >= rect->x1 && x < rect->x2 && y >= rect->y1 && y < rect->y2) {
            if (box != NULL)
                *box = *rect;
            return true;
        }
    }
    return false;
}

pixman_box32_t *
pixman_region32_rectangles(pixman_region32_t *region, int *count)
{
    if (count != NULL)
        *count = region != NULL ? region->count : 0;
    return region != NULL ? region->rects : NULL;
}

void
pixman_region32_translate(pixman_region32_t *region, int x, int y)
{
    if (region == NULL)
        return;
    for (int i = 0; i < region->count; i++) {
        region->rects[i].x1 += x;
        region->rects[i].x2 += x;
        region->rects[i].y1 += y;
        region->rects[i].y2 += y;
    }
}

void
pixman_region32_clear(pixman_region32_t *region)
{
    if (region != NULL)
        region->count = 0;
}

void
pixman_f_transform_init_scale(
    pixman_f_transform_t *transform, double x, double y)
{
    if (transform == NULL)
        return;
    *transform = (pixman_f_transform_t){
        .xx = (float)x, .yy = (float)y,
        .yx = 0, .xy = 0, .x0 = 0, .y0 = 0,
    };
}

void
pixman_transform_from_pixman_f_transform(
    pixman_transform_t *dst, const pixman_f_transform_t *src)
{
    if (dst == NULL || src == NULL)
        return;
    *dst = (pixman_transform_t){
        .xx = (int32_t)(src->xx * 65536.0f),
        .yx = (int32_t)(src->yx * 65536.0f),
        .xy = (int32_t)(src->xy * 65536.0f),
        .yy = (int32_t)(src->yy * 65536.0f),
        .x0 = (int32_t)(src->x0 * 65536.0f),
        .y0 = (int32_t)(src->y0 * 65536.0f),
    };
}

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
        .id = allocate_image_id(),
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

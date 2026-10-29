#pragma once

#include <stdbool.h>
#include <stdint.h>

typedef struct pixman_image pixman_image_t;
typedef struct pixman_region32 { int dummy; } pixman_region32_t;
typedef struct pixman_box32 { int32_t x1, y1, x2, y2; } pixman_box32_t;

typedef int32_t pixman_fixed_t;
typedef struct pixman_point_fixed {
    pixman_fixed_t x, y;
} pixman_point_fixed_t;
typedef struct pixman_line_fixed {
    pixman_point_fixed_t p1, p2;
} pixman_line_fixed_t;
typedef struct pixman_trapezoid {
    pixman_fixed_t top, bottom;
    pixman_line_fixed_t left, right;
} pixman_trapezoid_t;
typedef struct pixman_triangle {
    pixman_point_fixed_t p1, p2, p3;
} pixman_triangle_t;

typedef struct pixman_color {
    uint16_t red, green, blue, alpha;
} pixman_color_t;

typedef struct pixman_rectangle16 {
    int16_t x, y;
    uint16_t width, height;
} pixman_rectangle16_t;

typedef enum {
    PIXMAN_a8r8g8b8,
    PIXMAN_x8r8g8b8,
    PIXMAN_a8,
    PIXMAN_rgba_float,
    PIXMAN_FORMAT_A8R8G8B8,
    PIXMAN_FORMAT_X8R8G8B8,
    PIXMAN_FORMAT_A8,
} pixman_format_code_t;

typedef enum {
    PIXMAN_OP_SRC,
    PIXMAN_OP_OVER,
    PIXMAN_OP_CLEAR,
} pixman_op_t;

typedef enum {
    PIXMAN_FILTER_FAST,
    PIXMAN_FILTER_GOOD,
    PIXMAN_FILTER_BEST,
    PIXMAN_FILTER_NEAREST,
    PIXMAN_FILTER_BILINEAR,
} pixman_filter_t;

typedef struct { float xx, yx, xy, yy, x0, y0; } pixman_transform_t;

void *pixman_image_get_data(pixman_image_t *);
pixman_format_code_t pixman_image_get_format(pixman_image_t *);
void pixman_image_unref(pixman_image_t *);
pixman_image_t *pixman_image_create_bits(
    pixman_format_code_t, int, int, uint32_t *, int);
pixman_image_t *pixman_image_create_bits_no_clear(
    pixman_format_code_t, int, int, uint32_t *, int);
pixman_image_t *pixman_image_create_solid_fill(const pixman_color_t *);
void pixman_image_set_transform(pixman_image_t *, const pixman_transform_t *);
void pixman_image_set_filter(
    pixman_image_t *, pixman_filter_t, const void *, int);
void pixman_image_set_clip_region32(pixman_image_t *, pixman_region32_t *);
void pixman_image_fill_rectangles(
    pixman_op_t, pixman_image_t *, const pixman_color_t *, int,
    const pixman_rectangle16_t *);
void pixman_image_composite32(
    pixman_op_t, pixman_image_t *, pixman_image_t *, pixman_image_t *,
    int, int, int, int, int, int, int, int);
void pixman_composite_trapezoids(
    pixman_op_t, pixman_image_t *, pixman_image_t *, pixman_format_code_t,
    int, int, int, int, int, const pixman_trapezoid_t *);
void pixman_composite_triangles(
    pixman_op_t, pixman_image_t *, pixman_image_t *, pixman_format_code_t,
    int, int, int, int, int, const pixman_triangle_t *);
pixman_fixed_t pixman_int_to_fixed(int);

pixman_region32_t *pixman_region32_create(int);
void pixman_region32_destroy(pixman_region32_t *);
void pixman_region32_init(pixman_region32_t *);
void pixman_region32_init_rect(
    pixman_region32_t *, int, int, unsigned, unsigned);
void pixman_region32_fini(pixman_region32_t *);
bool pixman_region32_empty(pixman_region32_t *);
void pixman_region32_copy(
    pixman_region32_t *, const pixman_region32_t *);
void pixman_region32_union(
    pixman_region32_t *, pixman_region32_t *, pixman_region32_t *);
void pixman_region32_union_rect(
    pixman_region32_t *, pixman_region32_t *, int, int, unsigned, unsigned);
void pixman_region32_subtract(
    pixman_region32_t *, pixman_region32_t *, pixman_region32_t *);
void pixman_region32_intersect(
    pixman_region32_t *, pixman_region32_t *, pixman_region32_t *);
void pixman_region32_intersect_rect(
    pixman_region32_t *, pixman_region32_t *, int, int, unsigned, unsigned);
bool pixman_region32_contains_point(
    pixman_region32_t *, int, int, pixman_box32_t *);
pixman_box32_t *pixman_region32_rectangles(pixman_region32_t *, int *);
void pixman_region32_translate(pixman_region32_t *, int, int);
void pixman_region32_clear(pixman_region32_t *);

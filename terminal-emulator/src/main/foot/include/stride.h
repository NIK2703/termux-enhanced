#pragma once
#include <pixman.h>
static inline int stride_for_format_and_width(pixman_format_code_t format, int width) {
    (void)format;
    return ((32 * width + 7) / 8 + 3) & ~3;
}

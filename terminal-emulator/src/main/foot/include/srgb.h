#pragma once

#include <stdint.h>

/*
 * Android renders in sRGB without foot's linear-light compositing. The sixel
 * decoder only needs the transfer helpers when linear blending or 10-bit
 * surfaces are enabled; both are disabled on this backend.
 */
static inline uint8_t
srgb_decode_8_to_8(uint8_t value)
{
    return value;
}

static inline uint16_t
srgb_decode_8_to_16(uint8_t value)
{
    return (uint16_t)(value * 257);
}

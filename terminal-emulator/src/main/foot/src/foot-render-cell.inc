/* Generated from third_party/foot/render.c (sha256 97280b358e859d2c4f40f7ddf0b55311d7d66f9a5d9fe6e2c221611c6e0187cb).
 * Source lines: attrs_to_font() through render_row(). Do not edit; see
 * docs/foot-renderer-migration.md and the extractor command there. */
static struct fcft_font *
attrs_to_font(const struct terminal *term, const struct attributes *attrs)
{
    int idx = attrs->italic << 1 | attrs->bold;
    return term->fonts[idx];
}

static pixman_color_t
color_hex_to_pixman_srgb(uint32_t color, uint16_t alpha)
{
    return (pixman_color_t){
        .alpha = alpha,  /* Consider alpha linear already? */
        .red = srgb_decode_8_to_16((color >> 16) & 0xff),
        .green = srgb_decode_8_to_16((color >> 8) & 0xff),
        .blue = srgb_decode_8_to_16((color >> 0) & 0xff),
    };
}

static inline pixman_color_t
color_hex_to_pixman_with_alpha(uint32_t color, uint16_t alpha, bool srgb)
{
    pixman_color_t ret;

    if (srgb)
        ret = color_hex_to_pixman_srgb(color, alpha);
    else {
        ret = (pixman_color_t){
            .red =   ((color >> 16 & 0xff) | (color >> 8 & 0xff00)),
            .green = ((color >>  8 & 0xff) | (color >> 0 & 0xff00)),
            .blue =  ((color >>  0 & 0xff) | (color << 8 & 0xff00)),
            .alpha = alpha,
            };
    }

    ret.red = (uint32_t)ret.red * alpha / 0xffff;
    ret.green = (uint32_t)ret.green * alpha / 0xffff;
    ret.blue = (uint32_t)ret.blue * alpha / 0xffff;

    return ret;
}

static inline pixman_color_t
color_hex_to_pixman(uint32_t color, bool srgb)
{
    /* Count on the compiler optimizing this */
    return color_hex_to_pixman_with_alpha(color, 0xffff, srgb);
}

static inline int i_lerp(int from, int to, float t) {
    return from + (to - from) * t;
}

static inline uint32_t
color_blend_towards(uint32_t from, uint32_t to, float amount)
{
    if (unlikely(amount == 0))
        return from;
    float t = 1 - 1/amount;

    uint32_t alpha = from & 0xff000000;
    uint8_t r = i_lerp((from>>16)&0xff, (to>>16)&0xff, t);
    uint8_t g = i_lerp((from>>8)&0xff, (to>>8)&0xff, t);
    uint8_t b = i_lerp((from>>0)&0xff, (to>>0)&0xff, t);

    return alpha | (r<<16) | (g<<8) | (b<<0);
}

static inline uint32_t
color_dim(const struct terminal *term, uint32_t color)
{
    const struct config *conf = term->conf;
    const uint8_t custom_dim = conf->colors_dark.use_custom.dim;

    if (unlikely(custom_dim != 0)) {
        for (size_t i = 0; i < 8; i++) {
            if (((custom_dim >> i) & 1) == 0)
                continue;

            if (term->colors.table[0 + i] == color) {
                /* "Regular" color, return the corresponding "dim" */
                return conf->colors_dark.dim[i];
            }

            else if (term->colors.table[8 + i] == color) {
                /* "Bright" color, return the corresponding "regular" */
                return term->colors.table[i];
            }
        }
    }

    const struct color_theme *theme = term_theme_get(term);

    return color_blend_towards(
        color,
        theme->dim_blend_towards == DIM_BLEND_TOWARDS_BLACK ? 0x00000000 : 0x00ffffff,
        conf->dim.amount);
}

static inline uint32_t
color_brighten(const struct terminal *term, uint32_t color)
{
    /*
     * First try to match the color against the base 8 colors. If we
     * find a match, return the corresponding bright color.
     */
    if (term->conf->bold_in_bright.palette_based) {
        for (size_t i = 0; i < 8; i++) {
            if (term->colors.table[i] == color)
                return term->colors.table[i + 8];
        }
        return color;
    }

    return color_blend_towards(color, 0x00ffffff, term->conf->bold_in_bright.amount);
}

static void
draw_hollow_block(const struct terminal *term, pixman_image_t *pix,
                  const pixman_color_t *color, int x, int y, int cell_cols)
{
    const int scale = (int)roundf(term->scale);
    const int width = min(min(scale, term->cell_width), term->cell_height);

    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, color, 4,
        (pixman_rectangle16_t []){
         {x, y, cell_cols * term->cell_width, width},                              /* top */
         {x, y, width, term->cell_height},                                         /* left */
         {x + cell_cols * term->cell_width - width, y, width, term->cell_height},  /* right */
         {x, y + term->cell_height - width, cell_cols * term->cell_width, width},  /* bottom */
        });
}

static void
draw_beam_cursor(const struct terminal *term, pixman_image_t *pix,
                 const struct fcft_font *font,
                 const pixman_color_t *color, int x, int y)
{
    int baseline = y + term->font_baseline - term->fonts[0]->ascent;
    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, color,
        1, &(pixman_rectangle16_t){
            x, baseline,
            term_pt_or_px_as_pixels(term, &term->conf->cursor.beam_thickness),
            term->fonts[0]->ascent + term->fonts[0]->descent});
}

static int
underline_offset(const struct terminal *term, const struct fcft_font *font)
{
    return term->font_baseline -
        (term->conf->use_custom_underline_offset
         ? -term_pt_or_px_as_pixels(term, &term->conf->underline_offset)
         : font->underline.position);
}

static void
draw_underline_cursor(const struct terminal *term, pixman_image_t *pix,
               const struct fcft_font *font,
                      const pixman_color_t *color, int x, int y, int cols)
{
    int thickness = term->conf->cursor.underline_thickness.px >= 0
        ? term_pt_or_px_as_pixels(
            term, &term->conf->cursor.underline_thickness)
        : font->underline.thickness;

    /* Make sure the line isn't positioned below the cell */
    const int y_ofs = min(underline_offset(term, font) + thickness,
                          term->cell_height - thickness);

    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, color,
        1, &(pixman_rectangle16_t){
            x, y + y_ofs, cols * term->cell_width, thickness});
}

static void
draw_underline(const struct terminal *term, pixman_image_t *pix,
               const struct fcft_font *font,
               const pixman_color_t *color, int x, int y, int cols)
{
    const int thickness = term->conf->underline_thickness.px >= 0
        ? term_pt_or_px_as_pixels(
            term, &term->conf->underline_thickness)
        : font->underline.thickness;

    /* Make sure the line isn't positioned below the cell */
    const int y_ofs = min(underline_offset(term, font),
                          term->cell_height - thickness);

    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, color,
        1, &(pixman_rectangle16_t){
            x, y + y_ofs, cols * term->cell_width, thickness});
}

static void
draw_styled_underline(const struct terminal *term, pixman_image_t *pix,
                      const struct fcft_font *font,
                      const pixman_color_t *color,
                      enum underline_style style, int x, int y, int cols)
{
    xassert(style != UNDERLINE_NONE);

    if (style == UNDERLINE_SINGLE) {
        draw_underline(term, pix, font, color, x, y, cols);
        return;
    }

    const int thickness = term->conf->underline_thickness.px >= 0
        ? term_pt_or_px_as_pixels(
            term, &term->conf->underline_thickness)
        : font->underline.thickness;

    int y_ofs;

    /* Make sure the line isn't positioned below the cell */
    switch (style) {
    case UNDERLINE_DOUBLE:
    case UNDERLINE_CURLY:
        y_ofs = min(underline_offset(term, font),
                    term->cell_height - thickness * 3);
        break;

    case UNDERLINE_DASHED:
    case UNDERLINE_DOTTED:
        y_ofs = min(underline_offset(term, font),
                    term->cell_height - thickness);
        break;

    case UNDERLINE_NONE:
    case UNDERLINE_SINGLE:
    default:
        BUG("unexpected underline style: %d", (int)style);
        return;
    }

    const int ceil_w = cols * term->cell_width;

    switch (style) {
    case UNDERLINE_DOUBLE: {
        const pixman_rectangle16_t rects[] = {
            {x, y + y_ofs, ceil_w, thickness},
            {x, y + y_ofs + thickness * 2, ceil_w, thickness}};
        pixman_image_fill_rectangles(PIXMAN_OP_SRC, pix, color, 2, rects);
        break;
    }

    case UNDERLINE_DASHED: {
        const int ceil_w = cols * term->cell_width;
        const int dash_w = ceil_w / 3 + (ceil_w % 3 > 0);
        const pixman_rectangle16_t rects[] = {
            {x, y + y_ofs, dash_w, thickness},
            {x + dash_w * 2, y + y_ofs, dash_w, thickness},
        };
        pixman_image_fill_rectangles(
            PIXMAN_OP_SRC, pix, color, 2, rects);
        break;
    }

    case UNDERLINE_DOTTED: {
        /* Number of dots per cell */
        int per_cell = (term->cell_width / thickness) / 2;
        if (per_cell == 0)
            per_cell = 1;

        xassert(per_cell >= 1);

        /* Spacing between dots; start with the same width as the dots
           themselves, then widen them if necessary, to consume unused
           pixels */
        int spacing[per_cell];
        for (int i = 0; i < per_cell; i++)
            spacing[i] = thickness;

        /* Pixels remaining at the end of the cell */
        int remaining = term->cell_width - (per_cell * 2) * thickness;

        /* Spread out the left-over pixels across the spacing between
           the dots */
        for (int i = 0; remaining > 0; i = (i + 1) % per_cell, remaining--)
            spacing[i]++;

        xassert(remaining <= 0);

        pixman_rectangle16_t rects[per_cell];
        int dot_x = x;
        for (int i = 0; i < per_cell; i++) {
            rects[i] = (pixman_rectangle16_t){
                dot_x, y + y_ofs, thickness, thickness
            };

            dot_x += thickness + spacing[i];
        }

        pixman_image_fill_rectangles(PIXMAN_OP_SRC, pix, color, per_cell, rects);
        break;
    }

    case UNDERLINE_CURLY: {
        const int top = y + y_ofs;
        const int bot = top + thickness * 3;
        const int half_x = x + ceil_w / 2.0, full_x = x + ceil_w;

        const double bt_2 = (bot - top) * (bot - top);
        const double th_2 = thickness * thickness;
        const double hx_2 = ceil_w * ceil_w / 4.0;
        const int th = round(sqrt(th_2 + (th_2 * bt_2 / hx_2)) / 2.);

        #define I(x) pixman_int_to_fixed(x)
        const pixman_trapezoid_t traps[] = {
#if 0  /* characters sit within the "dips" of the curlies */
            {
                I(top), I(bot),
                {{I(x), I(top + th)}, {I(half_x), I(bot + th)}},
                {{I(x), I(top - th)}, {I(half_x), I(bot - th)}},
            },
            {
                I(top), I(bot),
                {{I(half_x), I(bot - th)}, {I(full_x), I(top - th)}},
                {{I(half_x), I(bot + th)}, {I(full_x), I(top + th)}},
            }
#else  /* characters sit on top of the curlies */
            {
                I(top), I(bot),
                {{I(x), I(bot - th)}, {I(half_x), I(top - th)}},
                {{I(x), I(bot + th)}, {I(half_x), I(top + th)}},
            },
            {
                I(top), I(bot),
                {{I(half_x), I(top + th)}, {I(full_x), I(bot + th)}},
                {{I(half_x), I(top - th)}, {I(full_x), I(bot - th)}},
            }
#endif
        };

        pixman_image_t *fill = pixman_image_create_solid_fill(color);
        pixman_composite_trapezoids(
            PIXMAN_OP_OVER, fill, pix, PIXMAN_a8, 0, 0, 0, 0,
            sizeof(traps) / sizeof(traps[0]), traps);

        pixman_image_unref(fill);
        break;
    }

    case UNDERLINE_NONE:
    case UNDERLINE_SINGLE:
        BUG("underline styles not supposed to be handled here");
        break;
    }
}

static void
draw_strikeout(const struct terminal *term, pixman_image_t *pix,
               const struct fcft_font *font,
               const pixman_color_t *color, int x, int y, int cols)
{
    const int thickness = term->conf->strikeout_thickness.px >= 0
        ? term_pt_or_px_as_pixels(
            term, &term->conf->strikeout_thickness)
        : font->strikeout.thickness;

    /* Try to center custom strikeout */
    const int position = term->conf->strikeout_thickness.px >= 0
        ? font->strikeout.position - round(font->strikeout.thickness / 2.) + round(thickness / 2.)
        : font->strikeout.position;

    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, color,
        1, &(pixman_rectangle16_t){
            x, y + term->font_baseline - position,
            cols * term->cell_width, thickness});
}

static void
cursor_colors_for_cell(const struct terminal *term, const struct cell *cell,
                       const pixman_color_t *fg, const pixman_color_t *bg,
                       pixman_color_t *cursor_color, pixman_color_t *text_color,
                       bool gamma_correct)
{
    if (term->colors.cursor_bg >> 31)
        *cursor_color = color_hex_to_pixman(term->colors.cursor_bg, gamma_correct);
    else
        *cursor_color = *fg;

    if (term->colors.cursor_fg >> 31)
        *text_color = color_hex_to_pixman(term->colors.cursor_fg, gamma_correct);
    else {
        xassert(bg->alpha == 0xffff);
        *text_color = *bg;
    }

    if (unlikely(text_color->red == cursor_color->red &&
                 text_color->green == cursor_color->green &&
                 text_color->blue == cursor_color->blue))
    {
        *text_color = *bg;
        *cursor_color = *fg;

        if (text_color->red == cursor_color->red &&
            text_color->green == cursor_color->green &&
            text_color->blue == cursor_color->blue)
        {
            cursor_color->red = ~cursor_color->red;
            cursor_color->green = ~cursor_color->green;
            cursor_color->blue = ~cursor_color->blue;
        }
    }
}

static void
draw_cursor(const struct terminal *term, const struct cell *cell,
            const struct fcft_font *font, pixman_image_t *pix, pixman_color_t *fg,
            const pixman_color_t *bg, int x, int y, int cols)
{
    pixman_color_t cursor_color;
    pixman_color_t text_color;
    cursor_colors_for_cell(term, cell, fg, bg, &cursor_color, &text_color,
                           wayl_do_linear_blending(term->wl, term->conf));

    if (unlikely(!term->kbd_focus)) {
        switch (term->conf->cursor.unfocused_style) {
        case CURSOR_UNFOCUSED_UNCHANGED:
            break;

        case CURSOR_UNFOCUSED_HOLLOW:
            draw_hollow_block(term, pix, &cursor_color, x, y, cols);
            return;

        case CURSOR_UNFOCUSED_NONE:
            return;
        }
    }

    switch (term->cursor_style) {
    case CURSOR_BLOCK:
        if (likely(term->cursor_blink.state == CURSOR_BLINK_ON) ||
            !term->kbd_focus)
        {
            *fg = text_color;
            pixman_image_fill_rectangles(
                PIXMAN_OP_SRC, pix, &cursor_color, 1,
                &(pixman_rectangle16_t){x, y, cols * term->cell_width, term->cell_height});
        }
        break;

    case CURSOR_BEAM:
        if (likely(term->cursor_blink.state == CURSOR_BLINK_ON ||
                   !term->kbd_focus))
        {
            draw_beam_cursor(term, pix, font, &cursor_color, x, y);
        }
        break;

    case CURSOR_UNDERLINE:
        if (likely(term->cursor_blink.state == CURSOR_BLINK_ON ||
                   !term->kbd_focus))
        {
            draw_underline_cursor(term, pix, font, &cursor_color, x, y, cols);
        }
        break;

    case CURSOR_HOLLOW:
        if (likely(term->cursor_blink.state == CURSOR_BLINK_ON))
            draw_hollow_block(term, pix, &cursor_color, x, y, cols);
        break;
    }
}

static int
render_cell(struct terminal *term, pixman_image_t *pix,
            pixman_region32_t *damage, struct row *row, int row_no, int col,
            bool has_cursor)
{
    struct cell *cell = &row->cells[col];
    if (cell->attrs.clean)
        return 0;

    cell->attrs.clean = 1;
    cell->attrs.confined = true;

    int width = term->cell_width;
    int height = term->cell_height;
    const int x = term->margins.left + col * width;
    const int y = term->margins.top + row_no * height;

    uint32_t _fg = 0;
    uint32_t _bg = 0;

    uint16_t alpha = 0xffff;
    const bool is_selected = cell->attrs.selected;

    /* Use cell specific color, if set, otherwise the default colors (possible reversed) */
    switch (cell->attrs.fg_src) {
    case COLOR_RGB:
        _fg = cell->attrs.fg;
        break;

    case COLOR_BASE16:
    case COLOR_BASE256:
        xassert(cell->attrs.fg < ALEN(term->colors.table));
        _fg = term->colors.table[cell->attrs.fg];
        break;

    case COLOR_DEFAULT:
        _fg = term->reverse ? term->colors.bg : term->colors.fg;
        break;
    }

    switch (cell->attrs.bg_src) {
    case COLOR_RGB:
        _bg = cell->attrs.bg;
        break;

    case COLOR_BASE16:
    case COLOR_BASE256:
        xassert(cell->attrs.bg < ALEN(term->colors.table));
        _bg = term->colors.table[cell->attrs.bg];
        break;

    case COLOR_DEFAULT:
        _bg = term->reverse ? term->colors.fg : term->colors.bg;
        break;
    }

    if (unlikely(is_selected)) {
        const uint32_t cell_fg = _fg;
        const uint32_t cell_bg = _bg;

        const bool custom_fg = term->colors.selection_fg >> 24 == 0;
        const bool custom_bg = term->colors.selection_bg >> 24 == 0;
        const bool custom_both = custom_fg && custom_bg;

        if (custom_both) {
            _fg = term->colors.selection_fg;
            _bg = term->colors.selection_bg;
        } else if (custom_bg) {
            _bg = term->colors.selection_bg;
            _fg = cell->attrs.reverse ? cell_bg : cell_fg;
        } else if (custom_fg) {
            _fg = term->colors.selection_fg;
            _bg = cell->attrs.reverse ? cell_fg : cell_bg;
        } else {
            _bg = cell_fg;
            _fg = cell_bg;
        }

        if (unlikely(_fg == _bg)) {
            /* Invert bg when selected/highlighted text has same fg/bg */
            _bg = ~_bg;
            alpha = 0xffff;
        }

    } else {
        if (unlikely(cell->attrs.reverse)) {
            uint32_t swap = _fg;
            _fg = _bg;
            _bg = swap;
        }

        else if (!term->window->is_fullscreen && term->colors.alpha != 0xffff) {
            switch (term->conf->colors_dark.alpha_mode) {
            case ALPHA_MODE_DEFAULT: {
                if (cell->attrs.bg_src == COLOR_DEFAULT) {
                    alpha = term->colors.alpha;
                }
                break;
            }

            case ALPHA_MODE_MATCHING: {
                if (cell->attrs.bg_src == COLOR_DEFAULT ||
                    ((cell->attrs.bg_src == COLOR_BASE16 ||
                      cell->attrs.bg_src == COLOR_BASE256) &&
                     term->colors.table[cell->attrs.bg] == term->colors.bg) ||
                    (cell->attrs.bg_src == COLOR_RGB &&
                     cell->attrs.bg == term->colors.bg))
                {
                    alpha = term->colors.alpha;
                }
                break;
            }

            case ALPHA_MODE_ALL: {
                alpha = term->colors.alpha;
                break;
            }
            }
        } else {
            /*
             * Note: disable transparency when fullscreened.
             *
             * This is because the wayland protocol mandates no screen
             * content is shown behind the fullscreened window.
             *
             * The _intent_ of the specification is that a black (or
             * other static color) should be used as background.
             *
             * There's a bit of gray area however, and some
             * compositors have chosen to interpret the specification
             * in a way that allows wallpapers to be seen through a
             * fullscreen window.
             *
             * Given that a) the intent of the specification, and b)
             * we don't know what the compositor will do, we simply
             * disable transparency while in fullscreen.
             *
             * To see why, consider what happens if we keep our
             * transparency. For example, if the background color is
             * white, and alpha is 0.5, then the window will be drawn
             * in a shade of gray while fullscreened.
             *
             * See
             * https://gitlab.freedesktop.org/wayland/wayland-protocols/-/issues/116
             * for a discussion on whether transparent, fullscreen
             * windows should be allowed in some way or not.
             *
             * NOTE: if changing this, also update render_margin()
             */
            xassert(alpha == 0xffff);
        }
    }

    if (cell->attrs.dim)
        _fg = color_dim(term, _fg);
    if (term->conf->bold_in_bright.enabled && cell->attrs.bold)
        _fg = color_brighten(term, _fg);

    if (cell->attrs.blink && term->blink.state == BLINK_OFF)
        _fg = color_blend_towards(_fg, 0x00000000, term->conf->dim.amount);

    const bool gamma_correct = wayl_do_linear_blending(term->wl, term->conf);
    pixman_color_t fg = color_hex_to_pixman(_fg, gamma_correct);
    pixman_color_t bg = color_hex_to_pixman_with_alpha(_bg, alpha, gamma_correct);

    struct fcft_font *font = attrs_to_font(term, &cell->attrs);
    const struct composed *composed = NULL;
    const struct fcft_grapheme *grapheme = NULL;
    const struct fcft_glyph *single = NULL;
    const struct fcft_glyph **glyphs = NULL;
    unsigned glyph_count = 0;

    char32_t base = cell->wc;
    int cell_cols = 1;

    if (base != 0) {
        if (unlikely(
                /* Classic box drawings */
                (base >= GLYPH_BOX_DRAWING_FIRST &&
                 base <= GLYPH_BOX_DRAWING_LAST) ||

                /* Braille */
                (base >= GLYPH_BRAILLE_FIRST &&
                 base <= GLYPH_BRAILLE_LAST) ||

                /*
                 * Unicode 13 "Symbols for Legacy Computing"
                 * sub-ranges below.
                 *
                 * Note, the full range is U+1FB00 - U+1FBF9
                 */
                (base >= GLYPH_LEGACY_FIRST &&
                 base <= GLYPH_LEGACY_LAST) ||

                /*
                 * Unicode 16 "Symbols for Legacy Computing Supplement"
                 *
                 * Note, the full range is U+1CC00 - U+1CEAF
                 */
                (base >= GLYPH_OCTANTS_FIRST &&
                 base <= GLYPH_OCTANTS_LAST)) &&

            likely(!term->conf->box_drawings_uses_font_glyphs))
        {
            struct fcft_glyph ***arr;
            size_t count;
            size_t idx;

            if (base >= GLYPH_LEGACY_FIRST) {
                arr = &term->custom_glyphs.legacy;
                count = GLYPH_LEGACY_COUNT;
                idx = base - GLYPH_LEGACY_FIRST;
            } else if (base >= GLYPH_OCTANTS_FIRST) {
                arr = &term->custom_glyphs.octants;
                count = GLYPH_OCTANTS_COUNT;
                idx = base - GLYPH_OCTANTS_FIRST;
            } else if (base >= GLYPH_BRAILLE_FIRST) {
                arr = &term->custom_glyphs.braille;
                count = GLYPH_BRAILLE_COUNT;
                idx = base - GLYPH_BRAILLE_FIRST;
            } else {
                arr = &term->custom_glyphs.box_drawing;
                count = GLYPH_BOX_DRAWING_COUNT;
                idx = base - GLYPH_BOX_DRAWING_FIRST;
            }

            if (unlikely(*arr == NULL))
                *arr = xcalloc(count, sizeof((*arr)[0]));

            if (likely((*arr)[idx] != NULL))
                single = (*arr)[idx];
            else {
                mtx_lock(&term->render.workers.lock);

                /* Other thread may have instantiated it while we
                 * acquired the lock */
                single = (*arr)[idx];
                if (likely(single == NULL))
                    single = (*arr)[idx] = box_drawing(term, base);
                mtx_unlock(&term->render.workers.lock);
            }

            if (single != NULL) {
                glyph_count = 1;
                glyphs = &single;
                cell_cols = single->cols;
            }
        }

        else if (base >= CELL_COMB_CHARS_LO && base <= CELL_COMB_CHARS_HI)
        {
            composed = composed_lookup(term->composed, base - CELL_COMB_CHARS_LO);
            base = composed->chars[0];

            if (term->conf->can_shape_grapheme && term->conf->tweak.grapheme_shaping) {
                grapheme = fcft_rasterize_grapheme_utf32(
                    font, composed->count, composed->chars, term->font_subpixel);
            }

            if (grapheme != NULL) {
                const int forced_width = composed->forced_width;

                cell_cols = forced_width > 0 ? forced_width : composed->width;

                composed = NULL;
                glyphs = grapheme->glyphs;
                glyph_count = grapheme->count;

                if (forced_width > 0)
                    glyph_count = min(glyph_count, forced_width);
            }
        }

        if (single == NULL && grapheme == NULL) {
            if (unlikely(base >= CELL_SPACER)) {
                glyph_count = 0;
                cell_cols = 1;
            } else {
                xassert(base != 0);
                single = fcft_rasterize_char_utf32(font, base, term->font_subpixel);
                if (single == NULL) {
                    glyph_count = 0;
                    cell_cols = 1;
                } else {
                    glyph_count = 1;
                    glyphs = &single;

                    const size_t forced_width = composed != NULL ? composed->forced_width : 0;
                    cell_cols = forced_width > 0 ? forced_width : single->cols;
                }
            }
        }
    }

    assert(glyph_count == 0 || glyphs != NULL);

    const int cols_left = term->cols - col;
    cell_cols = max(1, min(cell_cols, cols_left));

    /*
     * Determine cells that will bleed into their right neighbor and remember
     * them for cleanup in the next frame.
     */
    int render_width = cell_cols * width;
    if (term->conf->tweak.overflowing_glyphs &&
        glyph_count > 0 &&
        cols_left > cell_cols)
    {
        int glyph_width = 0, advance = 0;
        for (size_t i = 0; i < glyph_count; i++) {
            glyph_width = max(glyph_width,
                              advance + glyphs[i]->x + glyphs[i]->width);
            advance += glyphs[i]->advance.x;
        }

        if (glyph_width > render_width) {
            render_width = min(glyph_width, render_width + width);

            for (int i = 0; i < cell_cols; i++)
                row->cells[col + i].attrs.confined = false;
        }
    }

    pixman_region32_t clip;
    pixman_region32_init_rect(
        &clip, x, y,
        render_width, term->cell_height);
    pixman_image_set_clip_region32(pix, &clip);

    if (damage != NULL) {
        pixman_region32_union_rect(
            damage, damage, x, y, render_width, term->cell_height);
    }

    pixman_region32_fini(&clip);

    /* Background */
    pixman_image_fill_rectangles(
        PIXMAN_OP_SRC, pix, &bg, 1,
        &(pixman_rectangle16_t){x, y, cell_cols * width, height});

    if (cell->attrs.blink && term->blink.fd < 0) {
        /* TODO: use a custom lock for this? */
        mtx_lock(&term->render.workers.lock);
        term_arm_blink_timer(term);
        mtx_unlock(&term->render.workers.lock);
    }

    if (unlikely(has_cursor && term->cursor_style == CURSOR_BLOCK && term->kbd_focus)) {
        const pixman_color_t bg_without_alpha = color_hex_to_pixman(_bg, gamma_correct);
        draw_cursor(term, cell, font, pix, &fg, &bg_without_alpha, x, y, cell_cols);
    }

    if (cell->wc == 0 || cell->wc >= CELL_SPACER || cell->wc == U'\t' ||
        (unlikely(cell->attrs.conceal) && !is_selected))
    {
        goto draw_cursor;
    }

    pixman_image_t *clr_pix = pixman_image_create_solid_fill(&fg);

    int pen_x = x;
    for (unsigned i = 0; i < glyph_count; i++) {
        const int letter_x_ofs = i == 0 ? term->font_x_ofs : 0;

        const struct fcft_glyph *glyph = glyphs[i];
        if (glyph == NULL)
            continue;

        int g_x = glyph->x;
        int g_y = glyph->y;

        if (i > 0 && glyph->x >= 0 && cell_cols == 1)
            g_x -= term->cell_width;

        if (unlikely(glyph->is_color_glyph)) {
            /* Glyph surface is a pre-rendered image (typically a color emoji...) */
            if (!(cell->attrs.blink && term->blink.state == BLINK_OFF)) {
                pixman_image_composite32(
                    PIXMAN_OP_OVER, glyph->pix, NULL, pix, 0, 0, 0, 0,
                    pen_x + letter_x_ofs + g_x, y + term->font_baseline - g_y,
                    glyph->width, glyph->height);
            }
        } else {
            pixman_image_composite32(
                PIXMAN_OP_OVER, clr_pix, glyph->pix, pix, 0, 0, 0, 0,
                pen_x + letter_x_ofs + g_x, y + term->font_baseline - g_y,
                glyph->width, glyph->height);

            /* Combining characters */
            if (composed != NULL) {
                assert(glyph_count == 1);

                for (size_t j = 1; j < composed->count; j++) {
                    const struct fcft_glyph *g = fcft_rasterize_char_utf32(
                        font, composed->chars[j], term->font_subpixel);

                    if (g == NULL)
                        continue;

                    /*
                     * Fonts _should_ assume the pen position is now
                     * *after* the base glyph, and thus use negative
                     * offsets for combining glyphs.
                     *
                     * Not all fonts behave like this however, and we
                     * try to accommodate both variants.
                     *
                     * Since we haven't moved our pen position yet, we
                     * add a full cell width to the offset (or two, in
                     * case of double-width characters).
                     *
                     * If the font does *not* use negative offsets,
                     * we'd normally use an offset of 0. However, to
                     * somewhat deal with double-width glyphs we use
                     * an offset of *one* cell.
                     */
                    int x_ofs = cell_cols == 1
                        ? g->x < 0
                            ? cell_cols * term->cell_width
                            : (cell_cols - 1) * term->cell_width
                        : 0;

                    if (cell_cols > 1)
                        pen_x += term->cell_width;

                    pixman_image_composite32(
                        PIXMAN_OP_OVER, clr_pix, g->pix, pix, 0, 0, 0, 0,
                        /* Some fonts use a negative offset, while others use a
                         * "normal" offset */
                        pen_x + letter_x_ofs + x_ofs + g->x,
                        y + term->font_baseline - g->y, g->width, g->height);
                }
            }
        }

        pen_x += cell_cols > 1 ? term->cell_width : glyph->advance.x;
    }

    pixman_image_unref(clr_pix);

    /* Underline */
    if (cell->attrs.underline) {
        pixman_color_t underline_color = fg;
        enum underline_style underline_style = UNDERLINE_SINGLE;

        /* Check if cell has a styled underline. This lookup is fairly
           expensive... */
        if (row->extra != NULL) {
            for (int i = 0; i < row->extra->underline_ranges.count; i++) {
                const struct row_range *range = &row->extra->underline_ranges.v[i];

                if (range->start > col)
                    break;

                if (range->start <= col && col <= range->end) {
                    switch (range->underline.color_src) {
                    case COLOR_BASE256:
                        underline_color = color_hex_to_pixman(
                            term->colors.table[range->underline.color], gamma_correct);
                        break;

                    case COLOR_RGB:
                        underline_color =
                            color_hex_to_pixman(range->underline.color, gamma_correct);
                        break;

                    case COLOR_DEFAULT:
                        break;

                    case COLOR_BASE16:
                        BUG("underline color can't be base-16");
                        break;
                    }

                    underline_style = range->underline.style;
                    break;
                }
            }
        }

        draw_styled_underline(
            term, pix, font, &underline_color, underline_style, x, y, cell_cols);

    }

    if (cell->attrs.strikethrough)
        draw_strikeout(term, pix, font, &fg, x, y, cell_cols);

    if (unlikely(cell->attrs.url && term->conf->url.style != UNDERLINE_NONE)) {
        pixman_color_t url_color = color_hex_to_pixman(
            term->conf->colors_dark.use_custom.url
            ? term->conf->colors_dark.url
            : term->colors.table[3],
            gamma_correct);

        draw_styled_underline(
            term, pix, font, &url_color, term->conf->url.style,
            x, y, cell_cols);
    }

draw_cursor:
    if (has_cursor && (term->cursor_style != CURSOR_BLOCK || !term->kbd_focus)) {
        const pixman_color_t bg_without_alpha = color_hex_to_pixman(_bg, gamma_correct);
        draw_cursor(term, cell, font, pix, &fg, &bg_without_alpha, x, y, cell_cols);
    }

    pixman_image_set_clip_region32(pix, NULL);
    return cell_cols;
}

static void
render_row(struct terminal *term, pixman_image_t *pix,
           pixman_region32_t *damage, struct row *row,
           int row_no, int cursor_col)
{
    for (int col = term->cols - 1; col >= 0; col--)
        render_cell(term, pix, damage, row, row_no, col, cursor_col == col);
}

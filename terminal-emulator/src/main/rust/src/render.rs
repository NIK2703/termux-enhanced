//! Render planning.
//!
//! Rust decides what is visible — which cells form a run, how styles, selection,
//! reverse video and the cursor resolve into colours — and emits a compact
//! command list. Java remains the raster backend: it owns Android's font
//! shaping, glyph measurement and `Canvas`, so emoji/Devanagari/box-drawing keep
//! the platform's rendering quality.
//!
//! Command ABI (all values are 32-bit integers):
//!
//! ```text
//! 0  END
//! 1  ROW_BEGIN  row
//! 2  ROW_END
//! 3  RECT       startColumn, endColumn, argb, flags  (flags: 1 = span view width)
//! 4  TEXT       startChar, charCount, startColumn, widthColumns, fg, bg, effects
//! 5  CURSOR     column, widthColumns, argb, shape
//! ```

use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::vte::ansi::{Color, NamedColor};

use crate::terminal::Terminal;

pub const OP_END: i32 = 0;
pub const OP_ROW_BEGIN: i32 = 1;
pub const OP_ROW_END: i32 = 2;
pub const OP_RECT: i32 = 3;
pub const OP_TEXT: i32 = 4;
pub const OP_CURSOR: i32 = 5;

pub const RECT_SPAN_VIEW: i32 = 1;

pub const EFFECT_BOLD: i32 = 1;
pub const EFFECT_ITALIC: i32 = 1 << 1;
pub const EFFECT_UNDERLINE: i32 = 1 << 2;
pub const EFFECT_BLINK: i32 = 1 << 3;
pub const EFFECT_INVERSE: i32 = 1 << 4;
pub const EFFECT_INVISIBLE: i32 = 1 << 5;
pub const EFFECT_STRIKETHROUGH: i32 = 1 << 6;
pub const EFFECT_BLOCK_FILL: i32 = 1 << 7;
pub const EFFECT_DIM: i32 = 1 << 8;

pub const CURSOR_BLOCK: i32 = 0;
pub const CURSOR_UNDERLINE: i32 = 1;
pub const CURSOR_BAR: i32 = 2;

const COLOR_FOREGROUND: usize = 256;
const COLOR_BACKGROUND: usize = 257;
const COLOR_CURSOR: usize = 258;

#[derive(Clone, Copy)]
struct Run {
    start_column: usize,
    width_columns: usize,
    start_char: usize,
    char_count: usize,
    fg: u32,
    bg: u32,
    effects: i32,
    cursor: bool,
}

fn named_index(color: NamedColor) -> Option<usize> {
    match color {
        NamedColor::Black => Some(0),
        NamedColor::Red => Some(1),
        NamedColor::Green => Some(2),
        NamedColor::Yellow => Some(3),
        NamedColor::Blue => Some(4),
        NamedColor::Magenta => Some(5),
        NamedColor::Cyan => Some(6),
        NamedColor::White => Some(7),
        NamedColor::BrightBlack => Some(8),
        NamedColor::BrightRed => Some(9),
        NamedColor::BrightGreen => Some(10),
        NamedColor::BrightYellow => Some(11),
        NamedColor::BrightBlue => Some(12),
        NamedColor::BrightMagenta => Some(13),
        NamedColor::BrightCyan => Some(14),
        NamedColor::BrightWhite => Some(15),
        NamedColor::Foreground => Some(COLOR_FOREGROUND),
        NamedColor::Background => Some(COLOR_BACKGROUND),
        NamedColor::Cursor => Some(COLOR_CURSOR),
        _ => None,
    }
}

fn color_argb(color: Color, palette: &[u32; crate::terminal::COLOR_COUNT], default: usize) -> u32 {
    match color {
        Color::Spec(rgb) => 0xFF00_0000 | ((rgb.r as u32) << 16) | ((rgb.g as u32) << 8) | rgb.b as u32,
        Color::Indexed(index) => palette[index as usize],
        Color::Named(named) => match named_index(named) {
            Some(index) => palette[index],
            None => palette[default],
        },
    }
}

fn with_alpha(color: u32, alpha: i32) -> u32 {
    if alpha >= 255 {
        color
    } else {
        (color & 0x00FF_FFFF) | ((alpha.clamp(0, 255) as u32) << 24)
    }
}

fn effects_of(cell: &Cell) -> i32 {
    let mut effects = 0;
    if cell.flags.contains(Flags::BOLD) {
        effects |= EFFECT_BOLD;
    }
    if cell.flags.contains(Flags::ITALIC) {
        effects |= EFFECT_ITALIC;
    }
    if cell.flags.intersects(Flags::ALL_UNDERLINES) {
        effects |= EFFECT_UNDERLINE;
    }
    if cell.flags.contains(Flags::HIDDEN) {
        effects |= EFFECT_INVISIBLE;
    }
    if cell.flags.contains(Flags::STRIKEOUT) {
        effects |= EFFECT_STRIKETHROUGH;
    }
    if cell.flags.contains(Flags::DIM) {
        effects |= EFFECT_DIM;
    }
    if cell.flags.contains(Flags::INVERSE) {
        effects |= EFFECT_INVERSE;
    }
    if ('\u{2580}'..='\u{259F}').contains(&cell.c) {
        effects |= EFFECT_BLOCK_FILL;
    }
    effects
}

fn resolve_colors(
    cell: &Cell,
    palette: &[u32; crate::terminal::COLOR_COUNT],
    reverse: bool,
    alpha: i32,
) -> (u32, u32) {
    let effects = effects_of(cell);
    let bold = effects & EFFECT_BOLD != 0;
    let mut fg = color_argb(cell.fg, palette, COLOR_FOREGROUND);
    let mut bg = color_argb(cell.bg, palette, COLOR_BACKGROUND);
    if bold {
        // Traditional 8-colour bold brightening.
        if let Color::Indexed(index) = cell.fg {
            if index < 8 {
                fg = palette[(index + 8) as usize];
            }
        } else if let Color::Named(named) = cell.fg {
            if let Some(index) = named_index(named) {
                if index < 8 {
                    fg = palette[index + 8];
                }
            }
        }
    }
    let reverse_here = reverse ^ (effects & EFFECT_INVERSE != 0);
    if reverse_here {
        std::mem::swap(&mut fg, &mut bg);
    }
    (fg, with_alpha(bg, alpha))
}

fn cell_width(cell: &Cell) -> usize {
    if cell.flags.contains(Flags::WIDE_CHAR) {
        2
    } else {
        1
    }
}

/// One cell's UTF-16 payload. Returns true when the cell starts a wide glyph.
fn append_cell_text(cell: &Cell, text: &mut Vec<u16>) -> usize {
    let before = text.len();
    let mut buffer = [0u16; 2];
    text.extend_from_slice(cell.c.encode_utf16(&mut buffer));
    if let Some(extra) = cell.zerowidth() {
        for &c in extra {
            let mut buffer = [0u16; 2];
            text.extend_from_slice(c.encode_utf16(&mut buffer));
        }
    }
    text.len() - before
}

#[allow(clippy::too_many_arguments)]
pub fn build(
    terminal: &mut Terminal,
    top_row: i32,
    selection_y1: i32,
    selection_y2: i32,
    selection_x1: i32,
    selection_x2: i32,
    cursor_visible: bool,
    cursor_shape: i32,
    background_alpha: i32,
    _font_width: f32,
    _font_line_spacing: i32,
    _font_ascent: i32,
    columns: i32,
    rows: i32,
) -> Vec<i32> {
    let grid = terminal.grid();
    let palette = *terminal.palette();
    let history = grid.history_size() as i32;
    let min_top = -history;
    let top_row = top_row.max(min_top);
    let end_row = top_row + rows;
    let columns = columns.max(0) as usize;
    let screen_rows = terminal.rows() as i32;
    let cursor_row = terminal.cursor_row();
    let cursor_col = terminal.cursor_col() as i32;
    let reverse_video = terminal.reverse_video();
    let base_bg = if reverse_video { palette[COLOR_FOREGROUND] } else { palette[COLOR_BACKGROUND] };
    let base_bg = with_alpha(base_bg, background_alpha);
    let cursor_color = with_alpha(palette[COLOR_CURSOR], background_alpha);

    let mut commands = Vec::with_capacity((rows.max(0) as usize) * 8);
    let mut text = Vec::new();

    for row in top_row..end_row {
        if row < min_top || row >= screen_rows {
            continue;
        }
        commands.push(OP_ROW_BEGIN);
        commands.push(row);

        let line = Line(row);
        let is_cursor_row = cursor_visible && row == cursor_row;
        let in_selection = row >= selection_y1 && row <= selection_y2;
        let selection_start = if row == selection_y1 { selection_x1.max(0) as usize } else { 0 };
        let selection_end = if row == selection_y2 {
            (selection_x2.max(0) as usize).min(columns.saturating_sub(1))
        } else {
            columns.saturating_sub(1)
        };

        let mut runs: Vec<Run> = Vec::new();
        let mut column = 0usize;
        let mut cursor_run: Option<Run> = None;

        while column < columns {
            let cell = &grid[line][Column(column)];
            if cell.flags.contains(Flags::WIDE_CHAR_SPACER) {
                column += 1;
                continue;
            }
            let width = cell_width(cell).min(columns - column);
            let inside_cursor = is_cursor_row
                && (cursor_col == column as i32
                    || (width == 2 && cursor_col == column as i32 + 1));
            let inside_selection = in_selection && column >= selection_start && column <= selection_end;
            let cursor_block = inside_cursor && cursor_shape == CURSOR_BLOCK;
            // Termux ORs the three inverse sources and then XORs the cell's own
            // inverse attribute. Using XOR between the sources would cancel a
            // selection/cursor when reverse video is also active.
            let reverse_here = reverse_video || inside_selection || cursor_block;
            let (fg, bg) = resolve_colors(cell, &palette, reverse_here, background_alpha);
            let effects = effects_of(cell);
            let start_char = text.len();
            let chars = append_cell_text(cell, &mut text);
            let run = Run {
                start_column: column,
                width_columns: width,
                start_char,
                char_count: chars,
                fg,
                bg,
                effects,
                cursor: inside_cursor,
            };

            if inside_cursor {
                cursor_run = Some(run);
            }

            let merges = runs.last().is_some_and(|last| {
                last.fg == run.fg
                    && last.bg == run.bg
                    && last.effects == run.effects
                    && last.cursor == run.cursor
                    && last.start_column + last.width_columns == run.start_column
            });
            if merges {
                let last = runs.last_mut().unwrap();
                last.width_columns += run.width_columns;
                last.char_count = run.start_char + run.char_count - last.start_char;
            } else {
                runs.push(run);
            }
            column += width;
        }

        // Pass A: backgrounds. Cells already painted with the base colour are
        // skipped; the row base SW fill is emitted by Java for partial frames.
        for run in &runs {
            if run.bg == base_bg {
                continue;
            }
            commands.push(OP_RECT);
            commands.push(run.start_column as i32);
            commands.push((run.start_column + run.width_columns) as i32);
            commands.push(run.bg as i32);
            commands.push(RECT_SPAN_VIEW);
        }

        if let Some(run) = cursor_run {
            commands.push(OP_CURSOR);
            commands.push(run.start_column as i32);
            commands.push(run.width_columns as i32);
            commands.push(cursor_color as i32);
            commands.push(cursor_shape);
        }

        // Pass B: text.
        for run in &runs {
            if run.effects & EFFECT_INVISIBLE != 0 {
                continue;
            }
            // A run that is nothing but blanks with no decoration has no glyphs.
            let units = &text[run.start_char..run.start_char + run.char_count];
            let has_content = units.iter().any(|&unit| unit != b' ' as u16);
            let decorated = run.effects & (EFFECT_UNDERLINE | EFFECT_STRIKETHROUGH) != 0;
            if !has_content && !decorated {
                continue;
            }
            let mut char_count = run.char_count;
            if !decorated {
                while char_count > 0 && text[run.start_char + char_count - 1] == b' ' as u16 {
                    char_count -= 1;
                }
            }
            if char_count == 0 {
                continue;
            }
            commands.push(OP_TEXT);
            commands.push(run.start_char as i32);
            commands.push(char_count as i32);
            commands.push(run.start_column as i32);
            commands.push(run.width_columns as i32);
            commands.push(run.fg as i32);
            commands.push(run.bg as i32);
            commands.push(run.effects);
        }

        commands.push(OP_ROW_END);
    }

    commands.push(OP_END);
    terminal.set_render_text(text);
    commands
}

pub fn text_units(terminal: &Terminal) -> Vec<i32> {
    terminal.render_text().iter().map(|&unit| unit as i32).collect()
}

pub fn dirty_rows(terminal: &Terminal) -> Vec<i32> {
    // Dirty tracking lives in the Java facade: it needs cursor moves, selection
    // changes and viewport anchoring, none of which are grid mutations. The
    // native side still exposes row hashes through `append`; this endpoint is a
    // compatibility stub for callers that prefer to poll.
    let _ = terminal;
    Vec::new()
}

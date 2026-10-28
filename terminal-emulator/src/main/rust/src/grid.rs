//! Grid interrogation: Termux row snapshots, text extraction and selection.
//!
//! `alacritty_terminal` stores cells, not the `char[]` rows the Termux selection
//! UI was written around. This module reconstructs that representation (one
//! UTF-16 string with combining marks attached to their base cell, plus a
//! column -> char index map) on demand, and implements the selection/word
//! extraction algorithms against it.

use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::Flags;
use alacritty_terminal::vte::ansi::{Color, NamedColor};

use crate::terminal::Terminal;

/// Termux TextStyle.NORMAL: default foreground (256) and background (257).
const STYLE_DEFAULT: u64 = (256u64 << 40) | (257u64 << 16);

pub struct RowText {
    pub chars: Vec<u16>,
    /// First UTF-16 index of each column. A wide character's second column maps
    /// to the same index as its first, matching TerminalRow.findStartOfColumn().
    pub starts: Vec<usize>,
    /// Termux-style encoded cell attributes, one per column.
    pub styles: Vec<u64>,
    pub space_used: usize,
    pub line_wrap: bool,
    pub blank: bool,
    pub uniform_style: bool,
}

impl RowText {
    fn blank(columns: usize) -> Self {
        Self {
            chars: vec![b' ' as u16; columns],
            starts: (0..=columns).collect(),
            styles: vec![STYLE_DEFAULT; columns],
            space_used: columns,
            line_wrap: false,
            blank: true,
            uniform_style: true,
        }
    }

    pub fn find_start_of_column(&self, column: usize) -> usize {
        if column >= self.starts.len() {
            self.space_used
        } else {
            self.starts[column]
        }
    }
}

fn named_color_index(color: NamedColor) -> u32 {
    use NamedColor::*;
    match color {
        Black | DimBlack => 0,
        Red | DimRed => 1,
        Green | DimGreen => 2,
        Yellow | DimYellow => 3,
        Blue | DimBlue => 4,
        Magenta | DimMagenta => 5,
        Cyan | DimCyan => 6,
        White | DimWhite => 7,
        BrightBlack => 8,
        BrightRed => 9,
        BrightGreen => 10,
        BrightYellow => 11,
        BrightBlue => 12,
        BrightMagenta => 13,
        BrightCyan => 14,
        BrightWhite => 15,
        Foreground | BrightForeground | DimForeground => 256,
        Background => 257,
        Cursor => 258,
    }
}

fn encode_style(fg: Color, bg: Color, flags: Flags) -> u64 {
    let mut style = 0u64;
    if flags.contains(Flags::BOLD) {
        style |= 1 << 0;
    }
    if flags.contains(Flags::ITALIC) {
        style |= 1 << 1;
    }
    if flags.intersects(Flags::ALL_UNDERLINES) {
        style |= 1 << 2;
    }
    style |= u64::from(flags.contains(Flags::INVERSE)) << 4;
    style |= u64::from(flags.contains(Flags::HIDDEN)) << 5;
    style |= u64::from(flags.contains(Flags::STRIKEOUT)) << 6;
    style |= u64::from(flags.contains(Flags::DIM)) << 8;

    match fg {
        Color::Spec(rgb) => {
            let rgb = u32::from(rgb.r) << 16 | u32::from(rgb.g) << 8 | u32::from(rgb.b);
            style |= (1 << 9) | (u64::from(rgb) << 40);
        }
        Color::Named(name) => style |= u64::from(named_color_index(name)) << 40,
        Color::Indexed(index) => style |= u64::from(index) << 40,
    }
    match bg {
        Color::Spec(rgb) => {
            let rgb = u32::from(rgb.r) << 16 | u32::from(rgb.g) << 8 | u32::from(rgb.b);
            style |= (1 << 10) | (u64::from(rgb) << 16);
        }
        Color::Named(name) => style |= u64::from(named_color_index(name)) << 16,
        Color::Indexed(index) => style |= u64::from(index) << 16,
    }
    style
}

pub fn row_text(terminal: &Terminal, external_row: i32) -> RowText {
    let grid = terminal.grid();
    let history = grid.history_size() as i32;
    let rows = terminal.rows() as i32;
    if external_row < -history || external_row >= rows {
        return RowText::blank(terminal.columns());
    }

    let columns = terminal.columns();
    let line = Line(external_row);
    let mut chars: Vec<u16> = Vec::with_capacity(columns);
    let mut starts = vec![0usize; columns + 1];
    let mut styles = vec![STYLE_DEFAULT; columns];
    let mut line_wrap = false;
    let mut blank = true;
    let mut uniform_style = true;
    let mut first_style: Option<(Color, Color, u16)> = None;
    let mut last_start = 0usize;
    let mut last_style = STYLE_DEFAULT;

    for column in 0..columns {
        let cell = &grid[line][Column(column)];
        let style = encode_style(cell.fg, cell.bg, cell.flags);
        if cell.flags.contains(Flags::WRAPLINE) {
            line_wrap = true;
        }

        if cell.flags.contains(Flags::WIDE_CHAR_SPACER) {
            // Second half of the preceding wide character: no new text, same
            // char index. A leading spacer is a real blank cell, not a spacer.
            starts[column] = last_start;
            if cell.flags.contains(Flags::LEADING_WIDE_CHAR_SPACER) {
                starts[column] = chars.len();
                chars.push(b' ' as u16);
                last_start = starts[column];
                last_style = style;
            }
            styles[column] = last_style;
            blank = false;
            continue;
        }

        starts[column] = chars.len();
        last_start = starts[column];
        last_style = style;
        styles[column] = style;
        let mut encoded = [0u16; 2];
        let encoded = cell.c.encode_utf16(&mut encoded);
        chars.extend_from_slice(encoded);
        if let Some(extra) = cell.zerowidth() {
            for &c in extra {
                let mut buffer = [0u16; 2];
                chars.extend_from_slice(c.encode_utf16(&mut buffer));
            }
        }
        if cell.c != ' ' || cell.zerowidth().is_some() {
            blank = false;
        }
        let style = (cell.fg, cell.bg, cell.flags.bits() & !Flags::WRAPLINE.bits());
        match first_style {
            Some(previous) if previous != style => uniform_style = false,
            None => first_style = Some(style),
            _ => {}
        }
    }
    starts[columns] = chars.len();
    let space_used = chars.len();

    RowText { chars, starts, styles, space_used, line_wrap, blank, uniform_style }
}

/// Push a UTF-16 slice into a String, combining surrogate pairs correctly.
fn push_utf16(builder: &mut String, units: &[u16]) {
    for chunk in char::decode_utf16(units.iter().copied()) {
        builder.push(chunk.unwrap_or('\u{FFFD}'));
    }
}

fn selected_text(
    terminal: &Terminal,
    sel_x1: i32,
    sel_y1: i32,
    sel_x2: i32,
    sel_y2: i32,
    join_back_lines: bool,
    join_full_lines: bool,
) -> String {
    // Assembles UTF-16 slices so surrogate pairs survive.
    let columns = terminal.columns() as i32;
    let screen_rows = terminal.rows() as i32;
    let active_transcript = terminal.history_size() as i32;
    let sel_y1 = sel_y1.max(-active_transcript);
    let sel_y2 = sel_y2.min(screen_rows - 1);
    if sel_y1 > sel_y2 {
        return String::new();
    }

    let mut builder = String::new();
    for row in sel_y1..=sel_y2 {
        let x1 = if row == sel_y1 { sel_x1 } else { 0 };
        let mut x2 = if row == sel_y2 { sel_x2.saturating_add(1) } else { columns };
        if x2 > columns {
            x2 = columns;
        }
        let line = row_text(terminal, row);
        let x1_index = line.find_start_of_column(x1.max(0) as usize);
        let mut x2_index = if x2 < columns {
            line.find_start_of_column(x2.max(0) as usize)
        } else {
            line.space_used
        };
        if x2_index == x1_index {
            x2_index = line.find_start_of_column((x2 + 1).max(0) as usize);
        }

        let mut last_printing_char_index: i32 = -1;
        if line.line_wrap && x2 == columns {
            last_printing_char_index = x2_index as i32 - 1;
        } else {
            for i in x1_index..x2_index {
                if line.chars[i] != b' ' as u16 {
                    last_printing_char_index = i as i32;
                }
            }
        }

        let len = last_printing_char_index - x1_index as i32 + 1;
        if last_printing_char_index != -1 && len > 0 {
            let end = (x1_index + len as usize).min(line.chars.len());
            push_utf16(&mut builder, &line.chars[x1_index..end]);
        }

        let line_fills_width = last_printing_char_index == x2_index as i32 - 1;
        if (!join_back_lines || !line.line_wrap)
            && (!join_full_lines || !line_fills_width)
            && row < sel_y2
            && row < screen_rows - 1
        {
            builder.push('\n');
        }
    }
    builder
}

pub fn get_selected_text(
    terminal: &Terminal,
    x1: i32,
    y1: i32,
    x2: i32,
    y2: i32,
    join_back_lines: bool,
    join_full_lines: bool,
) -> String {
    selected_text(terminal, x1, y1, x2, y2, join_back_lines, join_full_lines)
}

pub fn get_transcript_text(terminal: &Terminal, join_back_lines: bool, join_full_lines: bool) -> String {
    let active = terminal.history_size() as i32;
    selected_text(terminal, 0, -active, terminal.columns() as i32, terminal.rows() as i32, join_back_lines, join_full_lines)
        .trim()
        .to_owned()
}

pub fn get_word_at_location(terminal: &Terminal, x: i32, y: i32) -> String {
    let screen_rows = terminal.rows() as i32;
    let columns = terminal.columns() as i32;
    let mut y1 = y;
    let mut y2 = y;
    while y1 > 0
        && !selected_text(terminal, 0, y1 - 1, columns, y, true, true).contains('\n')
    {
        y1 -= 1;
    }
    while y2 < screen_rows
        && !selected_text(terminal, 0, y, columns, y2 + 1, true, true).contains('\n')
    {
        y2 += 1;
    }

    let text = selected_text(terminal, 0, y1, columns, y2, true, true);
    let text_offset = (y - y1) * columns + x;
    if text_offset < 0 || text_offset as usize >= text.encode_utf16().count() {
        return String::new();
    }
    let text_offset = text_offset as usize;
    let units: Vec<u16> = text.encode_utf16().collect();
    let x1 = units[..text_offset.min(units.len())]
        .iter()
        .rposition(|&c| c == b' ' as u16)
        .map(|i| i as i32)
        .unwrap_or(-1);
    let x2 = units[text_offset.min(units.len())..]
        .iter()
        .position(|&c| c == b' ' as u16)
        .map(|i| i as i32 + text_offset as i32)
        .unwrap_or(units.len() as i32);
    if x1 == x2 {
        return String::new();
    }
    let mut result = String::new();
    push_utf16(&mut result, &units[(x1 + 1) as usize..x2 as usize]);
    result
}

/// Encode a row for JNI:
/// `[space_used, line_wrap, chars_len, chars..., starts...]`.
pub fn encode_row(terminal: &Terminal, external_row: i32) -> Vec<i32> {
    let row = row_text(terminal, external_row);
    let columns = terminal.columns();
    let mut out = Vec::with_capacity(3 + row.chars.len() + columns + 1 + columns * 2);
    out.push(row.space_used as i32);
    out.push(if row.line_wrap { 1 } else { 0 });
    out.push(row.chars.len() as i32);
    out.extend(row.chars.iter().map(|&c| c as i32));
    out.extend(row.starts.iter().map(|&s| s as i32));
    for &style in &row.styles {
        out.push(style as u32 as i32);
        out.push((style >> 32) as u32 as i32);
    }
    out
}

//! Owns the alacritty terminal state and the thin Termux-specific handler layer.

use std::sync::{Arc, Mutex};

use alacritty_terminal::event::{Event, EventListener};
use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::term::{Config, Term, TermMode};
use alacritty_terminal::vte::ansi::{
    Attr, CharsetIndex, ClearMode, CursorShape, CursorStyle, Handler, Hyperlink,
    KeyboardModes, KeyboardModesApplyBehavior, LineClearMode, ModifyOtherKeys, NamedPrivateMode,
    PrivateMode, Processor, Rgb, ScpCharPath, ScpUpdateMode, StandardCharset, TabulationClearMode,
};

use crate::events::TerminalEvent;
use crate::osc::{decode_base64_lossy, OscFilter};

pub const COLOR_COUNT: usize = 259;
pub const METHOD_COLOR_INDEX_FOREGROUND: usize = 256;
pub const METHOD_COLOR_INDEX_BACKGROUND: usize = 257;
pub const METHOD_COLOR_INDEX_CURSOR: usize = 258;

pub(crate) struct Listener {
    events: Arc<Mutex<Vec<TerminalEvent>>>,
}

impl EventListener for Listener {
    fn send_event(&self, event: Event) {
        let translated = match event {
            Event::Title(title) => Some(TerminalEvent::Title(title)),
            Event::ResetTitle => Some(TerminalEvent::ResetTitle),
            Event::Bell => Some(TerminalEvent::Bell),
            Event::ClipboardStore(_, text) => Some(TerminalEvent::ClipboardStore(text)),
            Event::ClipboardLoad(_, _) => Some(TerminalEvent::ClipboardLoad),
            Event::PtyWrite(text) => Some(TerminalEvent::Write(text)),
            Event::CursorBlinkingChange => Some(TerminalEvent::CursorBlinkingChanged),
            _ => None,
        };
        if let Some(event) = translated {
            if let Ok(mut events) = self.events.lock() {
                events.push(event);
            }
        }
    }
}

struct Size {
    columns: usize,
    screen_lines: usize,
}

impl Dimensions for Size {
    fn total_lines(&self) -> usize {
        self.screen_lines
    }

    fn screen_lines(&self) -> usize {
        self.screen_lines
    }

    fn columns(&self) -> usize {
        self.columns
    }
}

/// Handler wrapper around `Term` adding Termux behaviour that alacritty does
/// not implement itself:
///
/// * DECSET/DECRST 5 reverse video (alacritty treats it as an unknown mode);
/// * DECSET 47/1047 and 1048 aliases for the alternate screen and saved cursor;
/// * Termux-owned dynamic colours (OSC 4/10/11/12/104/110/111/112) so the Java
///   palette remains the single source of truth;
/// * OSC 52 clipboard notification;
/// * palette mirror updates used by the render command builder.
struct TermuxHandler<'a> {
    term: &'a mut Term<Listener>,
    palette: &'a mut [u32; COLOR_COUNT],
    defaults: &'a [u32; COLOR_COUNT],
    events: &'a Arc<Mutex<Vec<TerminalEvent>>>,
    reverse_video: &'a mut bool,
    colors_dirty: &'a mut bool,
}

impl TermuxHandler<'_> {
    fn emit(&self, event: TerminalEvent) {
        if let Ok(mut events) = self.events.lock() {
            events.push(event);
        }
    }

    fn set_color_internal(&mut self, index: usize, color: u32) {
        if index >= COLOR_COUNT {
            return;
        }
        if self.palette[index] != color {
            self.palette[index] = color;
            *self.colors_dirty = true;
        }
    }

    fn reset_color_internal(&mut self, index: usize) {
        if index >= COLOR_COUNT {
            return;
        }
        if self.palette[index] != self.defaults[index] {
            self.palette[index] = self.defaults[index];
            *self.colors_dirty = true;
        }
    }
}

fn rgb_to_argb(color: Rgb) -> u32 {
    0xFF00_0000 | ((color.r as u32) << 16) | ((color.g as u32) << 8) | color.b as u32
}

fn argb_to_rgb(color: u32) -> Rgb {
    Rgb { r: ((color >> 16) & 0xFF) as u8, g: ((color >> 8) & 0xFF) as u8, b: (color & 0xFF) as u8 }
}

impl Handler for TermuxHandler<'_> {
    fn set_title(&mut self, title: Option<String>) {
        self.term.set_title(title);
    }

    fn set_cursor_style(&mut self, style: Option<CursorStyle>) {
        self.term.set_cursor_style(style);
    }

    fn set_cursor_shape(&mut self, shape: CursorShape) {
        self.term.set_cursor_shape(shape);
    }

    fn input(&mut self, c: char) {
        self.term.input(c);
    }

    fn goto(&mut self, line: i32, col: usize) {
        self.term.goto(line, col);
    }

    fn goto_line(&mut self, line: i32) {
        self.term.goto_line(line);
    }

    fn goto_col(&mut self, col: usize) {
        self.term.goto_col(col);
    }

    fn insert_blank(&mut self, count: usize) {
        self.term.insert_blank(count);
    }

    fn move_up(&mut self, rows: usize) {
        self.term.move_up(rows);
    }

    fn move_down(&mut self, rows: usize) {
        self.term.move_down(rows);
    }

    fn identify_terminal(&mut self, intermediate: Option<char>) {
        self.term.identify_terminal(intermediate);
    }

    fn device_status(&mut self, arg: usize) {
        self.term.device_status(arg);
    }

    fn move_forward(&mut self, col: usize) {
        self.term.move_forward(col);
    }

    fn move_backward(&mut self, col: usize) {
        self.term.move_backward(col);
    }

    fn move_down_and_cr(&mut self, row: usize) {
        self.term.move_down_and_cr(row);
    }

    fn move_up_and_cr(&mut self, row: usize) {
        self.term.move_up_and_cr(row);
    }

    fn put_tab(&mut self, count: u16) {
        self.term.put_tab(count);
    }

    fn backspace(&mut self) {
        self.term.backspace();
    }

    fn carriage_return(&mut self) {
        self.term.carriage_return();
    }

    fn linefeed(&mut self) {
        self.term.linefeed();
    }

    fn bell(&mut self) {
        self.term.bell();
    }

    fn substitute(&mut self) {
        self.term.substitute();
    }

    fn newline(&mut self) {
        self.term.newline();
    }

    fn set_horizontal_tabstop(&mut self) {
        self.term.set_horizontal_tabstop();
    }

    fn scroll_up(&mut self, rows: usize) {
        self.term.scroll_up(rows);
    }

    fn scroll_down(&mut self, rows: usize) {
        self.term.scroll_down(rows);
    }

    fn insert_blank_lines(&mut self, count: usize) {
        self.term.insert_blank_lines(count);
    }

    fn delete_lines(&mut self, count: usize) {
        self.term.delete_lines(count);
    }

    fn erase_chars(&mut self, count: usize) {
        self.term.erase_chars(count);
    }

    fn delete_chars(&mut self, count: usize) {
        self.term.delete_chars(count);
    }

    fn move_backward_tabs(&mut self, count: u16) {
        self.term.move_backward_tabs(count);
    }

    fn move_forward_tabs(&mut self, count: u16) {
        self.term.move_forward_tabs(count);
    }

    fn save_cursor_position(&mut self) {
        self.term.save_cursor_position();
    }

    fn restore_cursor_position(&mut self) {
        self.term.restore_cursor_position();
    }

    fn clear_line(&mut self, mode: LineClearMode) {
        self.term.clear_line(mode);
    }

    fn clear_screen(&mut self, mode: ClearMode) {
        self.term.clear_screen(mode);
    }

    fn clear_tabs(&mut self, mode: TabulationClearMode) {
        self.term.clear_tabs(mode);
    }

    fn set_tabs(&mut self, interval: u16) {
        self.term.set_tabs(interval);
    }

    fn reset_state(&mut self) {
        // RIS also resets the user palette and reverse-video mode in Termux.
        self.palette.copy_from_slice(self.defaults);
        *self.colors_dirty = true;
        *self.reverse_video = false;
        self.term.reset_state();
    }

    fn reverse_index(&mut self) {
        self.term.reverse_index();
    }

    fn terminal_attribute(&mut self, attr: Attr) {
        self.term.terminal_attribute(attr);
    }

    fn set_mode(&mut self, mode: alacritty_terminal::vte::ansi::Mode) {
        self.term.set_mode(mode);
    }

    fn unset_mode(&mut self, mode: alacritty_terminal::vte::ansi::Mode) {
        self.term.unset_mode(mode);
    }

    fn report_mode(&mut self, mode: alacritty_terminal::vte::ansi::Mode) {
        self.term.report_mode(mode);
    }

    fn set_private_mode(&mut self, mode: PrivateMode) {
        match mode {
            PrivateMode::Unknown(5) => {
                *self.reverse_video = true;
                *self.colors_dirty = true;
            }
            PrivateMode::Unknown(47) | PrivateMode::Unknown(1047) => {
                self.term.set_private_mode(PrivateMode::Named(
                    NamedPrivateMode::SwapScreenAndSetRestoreCursor,
                ));
            }
            PrivateMode::Unknown(1048) => self.term.save_cursor_position(),
            PrivateMode::Unknown(69) => {
                // DECLRMM (left/right margins) is not supported by alacritty. Ignoring
                // it keeps the screen usable instead of corrupting the mode table.
            }
            PrivateMode::Unknown(66) => self.term.set_keypad_application_mode(),
            other => self.term.set_private_mode(other),
        }
    }

    fn unset_private_mode(&mut self, mode: PrivateMode) {
        match mode {
            PrivateMode::Unknown(5) => {
                *self.reverse_video = false;
                *self.colors_dirty = true;
            }
            PrivateMode::Unknown(47) | PrivateMode::Unknown(1047) => {
                self.term.unset_private_mode(PrivateMode::Named(
                    NamedPrivateMode::SwapScreenAndSetRestoreCursor,
                ));
            }
            PrivateMode::Unknown(1048) => self.term.restore_cursor_position(),
            PrivateMode::Unknown(69) => {}
            PrivateMode::Unknown(66) => self.term.unset_keypad_application_mode(),
            other => self.term.unset_private_mode(other),
        }
    }

    fn report_private_mode(&mut self, mode: PrivateMode) {
        self.term.report_private_mode(mode);
    }

    fn set_scrolling_region(&mut self, top: usize, bottom: Option<usize>) {
        self.term.set_scrolling_region(top, bottom);
    }

    fn set_keypad_application_mode(&mut self) {
        self.term.set_keypad_application_mode();
    }

    fn unset_keypad_application_mode(&mut self) {
        self.term.unset_keypad_application_mode();
    }

    fn set_active_charset(&mut self, index: CharsetIndex) {
        self.term.set_active_charset(index);
    }

    fn configure_charset(&mut self, index: CharsetIndex, charset: StandardCharset) {
        self.term.configure_charset(index, charset);
    }

    fn set_color(&mut self, index: usize, color: Rgb) {
        self.set_color_internal(index, rgb_to_argb(color));
    }

    fn dynamic_color_sequence(&mut self, prefix: String, index: usize, terminator: &str) {
        let argb = if index < COLOR_COUNT { self.palette[index] } else { 0xFFFFFFFF };
        let rgb = argb_to_rgb(argb);
        let r = (rgb.r as u32) * 257;
        let g = (rgb.g as u32) * 257;
        let b = (rgb.b as u32) * 257;
        self.emit(TerminalEvent::Write(format!(
            "\x1b]{prefix};rgb:{r:04x}/{g:04x}/{b:04x}{terminator}"
        )));
    }

    fn reset_color(&mut self, index: usize) {
        self.reset_color_internal(index);
    }

    fn clipboard_store(&mut self, _clipboard: u8, base64: &[u8]) {
        let text = String::from_utf8_lossy(base64);
        let decoded = decode_base64_lossy(&text);
        self.emit(TerminalEvent::ClipboardStore(decoded));
    }

    fn clipboard_load(&mut self, _clipboard: u8, _terminator: &str) {
        self.emit(TerminalEvent::ClipboardLoad);
    }

    fn decaln(&mut self) {
        self.term.decaln();
    }

    fn push_title(&mut self) {
        self.term.push_title();
    }

    fn pop_title(&mut self) {
        self.term.pop_title();
    }

    fn text_area_size_pixels(&mut self) {
        self.term.text_area_size_pixels();
    }

    fn text_area_size_chars(&mut self) {
        self.term.text_area_size_chars();
    }

    fn set_hyperlink(&mut self, hyperlink: Option<Hyperlink>) {
        self.term.set_hyperlink(hyperlink);
    }

    fn set_mouse_cursor_icon(&mut self, _icon: alacritty_terminal::vte::ansi::cursor_icon::CursorIcon) {
        // Mouse shape hints are irrelevant on touch screens.
    }

    fn report_keyboard_mode(&mut self) {
        self.term.report_keyboard_mode();
    }

    fn push_keyboard_mode(&mut self, mode: KeyboardModes) {
        self.term.push_keyboard_mode(mode);
    }

    fn pop_keyboard_modes(&mut self, to_pop: u16) {
        self.term.pop_keyboard_modes(to_pop);
    }

    fn set_keyboard_mode(&mut self, mode: KeyboardModes, behavior: KeyboardModesApplyBehavior) {
        self.term.set_keyboard_mode(mode, behavior);
    }

    fn set_modify_other_keys(&mut self, mode: ModifyOtherKeys) {
        self.term.set_modify_other_keys(mode);
    }

    fn report_modify_other_keys(&mut self) {
        self.term.report_modify_other_keys();
    }

    fn set_scp(&mut self, char_path: ScpCharPath, update_mode: ScpUpdateMode) {
        self.term.set_scp(char_path, update_mode);
    }
}

/// Everything the Java terminal facade needs, behind one mutex-owned object.
pub struct Terminal {
    term: Term<Listener>,
    processor: Processor,
    filter: OscFilter,
    events: Arc<Mutex<Vec<TerminalEvent>>>,
    defaults: [u32; COLOR_COUNT],
    palette: [u32; COLOR_COUNT],
    columns: usize,
    rows: usize,
    cell_width: u16,
    cell_height: u16,
    transcript_rows: usize,
    reverse_video: bool,
    scroll_base: u64,
    last_hashes: Vec<u64>,
    /// UTF-16 payload of the most recent `render::build` call.
    render_text: Vec<u16>,
}

impl Terminal {
    pub fn new(
        columns: usize,
        rows: usize,
        transcript_rows: usize,
        cell_width: u16,
        cell_height: u16,
        defaults: [u32; COLOR_COUNT],
    ) -> Self {
        let events = Arc::new(Mutex::new(Vec::new()));
        let listener = Listener { events: Arc::clone(&events) };
        // Termux historically treats the "transcript rows" setting as the total height
        // of the ring buffer, screen rows included. `scrolling_history` is the number of
        // rows *above* the screen, hence the subtraction.
        let scrolling_history = transcript_rows.saturating_sub(rows);
        let config = Config {
            scrolling_history,
            default_cursor_style: CursorStyle { shape: CursorShape::Block, blinking: false },
            osc52: alacritty_terminal::term::Osc52::OnlyCopy,
            ..Config::default()
        };
        let size = Size { columns, screen_lines: rows };
        let term = Term::new(config, &size, listener);
        let mut terminal = Self {
            term,
            processor: Processor::new(),
            filter: OscFilter::new(),
            events,
            defaults,
            palette: defaults,
            columns,
            rows,
            cell_width,
            cell_height,
            transcript_rows,
            reverse_video: false,
            scroll_base: 0,
            last_hashes: Vec::new(),
            render_text: Vec::new(),
        };
        terminal.last_hashes = terminal.screen_hashes();
        terminal
    }

    pub fn defaults(&self) -> &[u32; COLOR_COUNT] {
        &self.defaults
    }

    pub fn palette(&self) -> &[u32; COLOR_COUNT] {
        &self.palette
    }

    pub fn set_palette(&mut self, palette: [u32; COLOR_COUNT], defaults: [u32; COLOR_COUNT]) -> bool {
        let changed = self.palette != palette;
        self.palette = palette;
        self.defaults = defaults;
        changed
    }

    pub fn render_text(&self) -> &[u16] {
        &self.render_text
    }

    pub fn set_render_text(&mut self, text: Vec<u16>) {
        self.render_text = text;
    }

    pub fn columns(&self) -> usize {
        self.columns
    }

    pub fn rows(&self) -> usize {
        self.rows
    }

    pub fn cell_width(&self) -> u16 {
        self.cell_width
    }

    pub fn cell_height(&self) -> u16 {
        self.cell_height
    }

    pub fn transcript_rows(&self) -> usize {
        self.transcript_rows
    }

    pub fn history_size(&self) -> usize {
        self.term.grid().history_size()
    }

    pub fn grid(&self) -> &alacritty_terminal::grid::Grid<alacritty_terminal::term::cell::Cell> {
        self.term.grid()
    }

    pub fn term(&self) -> &Term<Listener> {
        &self.term
    }

    pub fn term_mut(&mut self) -> &mut Term<Listener> {
        &mut self.term
    }

    pub fn reverse_video(&self) -> bool {
        self.reverse_video
    }

    pub fn scroll_counter(&self) -> u32 {
        self.term.grid().scroll_count().saturating_sub(self.scroll_base) as u32
    }

    pub fn clear_scroll_counter(&mut self) {
        self.scroll_base = self.term.grid().scroll_count();
    }

    pub fn anchor_row(&self, external_row: i32) -> i32 {
        (self.term.grid().scroll_count() as i32).wrapping_add(external_row)
    }

    pub fn absolute_scroll_count(&self) -> u64 {
        self.term.grid().scroll_count()
    }

    pub fn mode_bits(&self) -> u32 {
        let mode = *self.term.mode();
        let mut bits = 0u32;
        // These bits deliberately mirror the DECSET internal bit layout used by
        // the Java facade (TerminalEmulator.DECSET_BIT_*).
        if mode.contains(TermMode::APP_CURSOR) {
            bits |= 1;
        }
        if self.reverse_video {
            bits |= 1 << 1;
        }
        if mode.contains(TermMode::ORIGIN) {
            bits |= 1 << 2;
        }
        if mode.contains(TermMode::LINE_WRAP) {
            bits |= 1 << 3;
        }
        if mode.contains(TermMode::SHOW_CURSOR) {
            bits |= 1 << 4;
        }
        if mode.contains(TermMode::APP_KEYPAD) {
            bits |= 1 << 5;
        }
        if mode.contains(TermMode::MOUSE_REPORT_CLICK) {
            bits |= 1 << 6;
        }
        if mode.intersects(TermMode::MOUSE_DRAG | TermMode::MOUSE_MOTION) {
            bits |= 1 << 7;
        }
        if mode.contains(TermMode::FOCUS_IN_OUT) {
            bits |= 1 << 8;
        }
        if mode.contains(TermMode::SGR_MOUSE) {
            bits |= 1 << 9;
        }
        if mode.contains(TermMode::BRACKETED_PASTE) {
            bits |= 1 << 10;
        }
        bits
    }

    pub fn is_alternate_screen(&self) -> bool {
        self.term.mode().contains(TermMode::ALT_SCREEN)
    }

    /// Cursor shape using the Termux constants: 0 = block, 1 = underline, 2 = bar.
    pub fn cursor_style(&self) -> i32 {
        match self.term.cursor_style().shape {
            CursorShape::Underline => 1,
            CursorShape::Beam => 2,
            _ => 0,
        }
    }

    /// Set the cursor shape from the Termux constants. DECSCUSR sequences override
    /// this value until the next reset, matching the old Java emulator.
    pub fn set_cursor_style(&mut self, shape: i32) {
        let shape = match shape {
            1 => CursorShape::Underline,
            2 => CursorShape::Beam,
            _ => CursorShape::Block,
        };
        self.term.set_cursor_style(Some(CursorStyle { shape, blinking: false }));
    }

    pub fn cursor_row(&self) -> i32 {
        self.term.grid().cursor.point.line.0.max(0)
    }

    pub fn cursor_col(&self) -> i32 {
        self.term.grid().cursor.point.column.0 as i32
    }

    fn screen_hashes(&self) -> Vec<u64> {
        (0..self.rows).map(|row| self.row_hash(row as i32)).collect()
    }

    fn row_hash(&self, external_row: i32) -> u64 {
        let grid = self.term.grid();
        let line = alacritty_terminal::index::Line(external_row);
        if external_row < -(grid.history_size() as i32) || external_row >= self.rows as i32 {
            return 0;
        }
        let mut hash = 0xcbf2_9ce4_8422_2325u64;
        for column in 0..self.columns {
            let cell = &grid[line][alacritty_terminal::index::Column(column)];
            hash ^= cell.c as u64;
            hash = hash.wrapping_mul(0x100_0000_01b3);
            hash ^= cell.flags.bits() as u64;
            hash = hash.wrapping_mul(0x100_0000_01b3);
            for &extra in cell.zerowidth().unwrap_or(&[]) {
                hash ^= extra as u64;
                hash = hash.wrapping_mul(0x100_0000_01b3);
            }
        }
        hash
    }

    /// Feed raw PTY bytes. Returns the visible rows whose content changed.
    pub fn append(&mut self, bytes: &[u8]) -> Vec<i32> {
        let alt_before = self.is_alternate_screen();
        let mode_before = self.mode_bits();
        let history_before = self.history_size();
        let before = self.screen_hashes();

        let mut filtered = Vec::with_capacity(bytes.len());
        let mut pending_events = Vec::new();
        self.filter.feed(bytes, &mut filtered, &mut pending_events);
        if !pending_events.is_empty() {
            if let Ok(mut events) = self.events.lock() {
                events.extend(pending_events);
            }
        }

        let mut colors_dirty = false;
        if !filtered.is_empty() {
            let mut handler = TermuxHandler {
                term: &mut self.term,
                palette: &mut self.palette,
                defaults: &self.defaults,
                events: &self.events,
                reverse_video: &mut self.reverse_video,
                colors_dirty: &mut colors_dirty,
            };
            self.processor.advance(&mut handler, &filtered);
        }

        let alt_after = self.is_alternate_screen();
        let mode_after = self.mode_bits();
        let history_after = self.history_size();
        if colors_dirty {
            if let Ok(mut events) = self.events.lock() {
                events.push(TerminalEvent::ColorsChanged);
            }
        }
        if alt_before != alt_after {
            if let Ok(mut events) = self.events.lock() {
                events.push(TerminalEvent::ColorsChanged);
            }
        }

        let mut dirty = Vec::new();
        if alt_before != alt_after || mode_before != mode_after || history_after < history_before {
            // A screen swap, reverse video or a mode-dependent repaint invalidates
            // every visible row. Losing transcript rows (CSI 3 J) also shifts the
            // absolute row addresses the view uses as anchors.
            for row in 0..self.rows {
                dirty.push(row as i32);
            }
            self.last_hashes = self.screen_hashes();
        } else {
            let after = self.screen_hashes();
            for (row, (old, new)) in before.iter().zip(after.iter()).enumerate() {
                if old != new {
                    dirty.push(row as i32);
                }
            }
            self.last_hashes = after;
        }
        dirty
    }

    pub fn reset(&mut self) {
        self.filter.reset();
        self.processor = Processor::new();
        Handler::reset_state(&mut self.term);
        self.reverse_video = false;
        self.palette = self.defaults;
        self.scroll_base = self.term.grid().scroll_count();
        self.last_hashes = self.screen_hashes();
        if let Ok(mut events) = self.events.lock() {
            events.push(TerminalEvent::ColorsChanged);
        }
    }

    pub fn resize(&mut self, columns: usize, rows: usize, cell_width: u16, cell_height: u16) {
        if columns < 2 || rows < 2 {
            return;
        }
        let old_columns = self.columns;
        let old_rows = self.rows;
        self.columns = columns;
        self.rows = rows;
        self.cell_width = cell_width;
        self.cell_height = cell_height;
        if columns != old_columns || rows != old_rows {
            self.term.resize(Size { columns, screen_lines: rows });
            self.last_hashes = self.screen_hashes();
        }
    }

    pub fn drain_events(&mut self) -> Vec<TerminalEvent> {
        match self.events.lock() {
            Ok(mut events) => std::mem::take(&mut *events),
            Err(_) => Vec::new(),
        }
    }
}

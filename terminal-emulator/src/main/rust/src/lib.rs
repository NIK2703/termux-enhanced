//! JNI bridge for the Termux terminal emulator.
//!
//! The Java side owns lifecycle, the PTY, keyboard handling and Canvas drawing.
//! This library owns the VT parser, the screen/history grid, the palette mirror,
//! selection/transcript extraction and the render command list.

mod events;
mod grid;
mod osc;
mod render;
mod terminal;

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use jni::objects::{JByteArray, JClass, JIntArray, JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;

use events::TerminalEvent;
use terminal::Terminal;

const PALETTE_SIZE: usize = terminal::COLOR_COUNT;

fn lock(handle: jlong) -> Option<&'static Mutex<Terminal>> {
    if handle == 0 {
        None
    } else {
        Some(unsafe { &*(handle as *const Mutex<Terminal>) })
    }
}

fn with_terminal<R>(_env: &mut JNIEnv, handle: jlong, f: impl FnOnce(&mut Terminal) -> R) -> Option<R> {
    let mutex = lock(handle)?;
    // A panic in one escape sequence must not poison the session or unwind into the
    // JVM. Recover the guard, report a neutral result and keep the terminal usable.
    let mut guard = match mutex.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    catch_unwind(AssertUnwindSafe(|| f(&mut guard))).ok()
}

fn read_palette(env: &mut JNIEnv, palette: &JIntArray) -> [u32; PALETTE_SIZE] {
    let mut values = [0i32; PALETTE_SIZE];
    if let Ok(length) = env.get_array_length(palette) {
        let count = (length as usize).min(PALETTE_SIZE);
        if count > 0 {
            let _ = env.get_int_array_region(palette, 0, &mut values[..count]);
        }
    }
    let mut colors = [0u32; PALETTE_SIZE];
    for (slot, value) in colors.iter_mut().zip(values.iter()) {
        *slot = *value as u32;
    }
    colors
}

fn to_int_array<'local>(env: &mut JNIEnv<'local>, values: &[i32]) -> JIntArray<'local> {
    match env.new_int_array(values.len() as i32) {
        Ok(array) => {
            let _ = env.set_int_array_region(&array, 0, values);
            array
        }
        Err(_) => JIntArray::default(),
    }
}

fn to_string_array_joined(env: &mut JNIEnv, events: &[TerminalEvent]) -> jstring {
    let joined = events.iter().map(|event| event.encode()).collect::<Vec<_>>().join("\n");
    match env.new_string(joined) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    columns: jint,
    rows: jint,
    transcript_rows: jint,
    cell_width: jint,
    cell_height: jint,
    palette: JIntArray,
) -> jlong {
    let defaults = read_palette(&mut env, &palette);
    let terminal = Terminal::new(
        columns.max(2) as usize,
        rows.max(2) as usize,
        transcript_rows.max(0) as usize,
        cell_width.max(1) as u16,
        cell_height.max(1) as u16,
        defaults,
    );
    Box::into_raw(Box::new(Mutex::new(terminal))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        unsafe {
            drop(Box::from_raw(handle as *mut Mutex<Terminal>));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeAppend<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    data: JByteArray<'local>,
    length: jint,
) -> JIntArray<'local> {
    let mut buffer = vec![0i8; length.max(0) as usize];
    if length > 0 {
        let _ = env.get_byte_array_region(&data, 0, &mut buffer);
    }
    let bytes: Vec<u8> = buffer.into_iter().map(|value| value as u8).collect();
    let dirty = with_terminal(&mut env, handle, |terminal| terminal.append(&bytes)).unwrap_or_default();
    to_int_array(&mut env, &dirty)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeReset(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = with_terminal(&mut env, handle, |terminal| terminal.reset());
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeResize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    columns: jint,
    rows: jint,
    cell_width: jint,
    cell_height: jint,
) {
    let _ = with_terminal(&mut env, handle, |terminal| {
        terminal.resize(columns as usize, rows as usize, cell_width as u16, cell_height as u16)
    });
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeDrainEvents(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let events = with_terminal(&mut env, handle, |terminal| terminal.drain_events()).unwrap_or_default();
    to_string_array_joined(&mut env, &events)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativePalette<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JIntArray<'local> {
    let palette = with_terminal(&mut env, handle, |terminal| *terminal.palette()).unwrap_or([0u32; PALETTE_SIZE]);
    let values: Vec<i32> = palette.iter().map(|&value| value as i32).collect();
    to_int_array(&mut env, &values)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeSetPalette<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    palette: JIntArray<'local>,
    defaults: JIntArray<'local>,
) -> jboolean {
    let palette = read_palette(&mut env, &palette);
    let defaults = read_palette(&mut env, &defaults);
    let changed = with_terminal(&mut env, handle, |terminal| terminal.set_palette(palette, defaults)).unwrap_or(false);
    if changed { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeHistorySize(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.history_size() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeRows(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.rows() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeColumns(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.columns() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeCursorRow(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.cursor_row()).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeCursorCol(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.cursor_col()).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeModeBits(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.mode_bits() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeScrollCounter(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.scroll_counter() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeClearScrollCounter(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    let _ = with_terminal(&mut env, handle, |terminal| terminal.clear_scroll_counter());
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeAnchorRow(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    external_row: jint,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.anchor_row(external_row)).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeAbsoluteScrollCount(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jlong {
    with_terminal(&mut env, handle, |terminal| terminal.absolute_scroll_count() as jlong).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeIsAlternate(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if with_terminal(&mut env, handle, |terminal| terminal.is_alternate_screen()).unwrap_or(false) {
        1
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeCursorStyle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_terminal(&mut env, handle, |terminal| terminal.cursor_style()).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeSetCursorStyle(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    shape: jint,
) {
    let _ = with_terminal(&mut env, handle, |terminal| terminal.set_cursor_style(shape));
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeRow<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    external_row: jint,
) -> JIntArray<'local> {
    let encoded = with_terminal(&mut env, handle, |terminal| grid::encode_row(terminal, external_row)).unwrap_or_default();
    to_int_array(&mut env, &encoded)
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeSelectedText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x1: jint,
    y1: jint,
    x2: jint,
    y2: jint,
    join_back_lines: jboolean,
    join_full_lines: jboolean,
) -> jstring {
    let text = with_terminal(&mut env, handle, |terminal| {
        grid::get_selected_text(
            terminal,
            x1,
            y1,
            x2,
            y2,
            join_back_lines != 0,
            join_full_lines != 0,
        )
    })
    .unwrap_or_default();
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeTranscriptText(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    mode: jint,
) -> jstring {
    let text = with_terminal(&mut env, handle, |terminal| match mode {
        1 => grid::get_transcript_text(terminal, false, false),
        2 => grid::get_transcript_text(terminal, true, true),
        _ => grid::get_transcript_text(terminal, true, false),
    })
    .unwrap_or_default();
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeWordAtLocation(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    x: jint,
    y: jint,
) -> jstring {
    let text = with_terminal(&mut env, handle, |terminal| grid::get_word_at_location(terminal, x, y)).unwrap_or_default();
    match env.new_string(text) {
        Ok(string) => string.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Render a frame to a packed command list. See `render.rs` for the ABI.
#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeRender<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    top_row: jint,
    selection_y1: jint,
    selection_y2: jint,
    selection_x1: jint,
    selection_x2: jint,
    cursor_visible: jboolean,
    cursor_shape: jint,
    background_alpha: jint,
    font_width: jfloat,
    font_line_spacing: jint,
    font_ascent: jint,
    columns: jint,
    rows: jint,
) -> JIntArray<'local> {
    let commands = with_terminal(&mut env, handle, |terminal| {
        render::build(
            terminal,
            top_row,
            selection_y1,
            selection_y2,
            selection_x1,
            selection_x2,
            cursor_visible != 0,
            cursor_shape,
            background_alpha,
            font_width,
            font_line_spacing,
            font_ascent,
            columns,
            rows,
        )
    })
    .unwrap_or_default();
    to_int_array(&mut env, &commands)
}

/// Text payload for the last render, as UTF-16 code units.
#[no_mangle]
pub extern "system" fn Java_com_termux_terminal_TerminalNative_nativeRenderText<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JIntArray<'local> {
    let units = with_terminal(&mut env, handle, |terminal| render::text_units(terminal)).unwrap_or_default();
    to_int_array(&mut env, &units)
}

//! Events raised by the emulation core and consumed by the Java session layer.
//!
//! The JNI boundary serialises these into a `String[]`; every dynamic field is
//! base64-encoded so titles, clipboard payloads and notification text can contain
//! any character (including the separator) without an escaping protocol of their
//! own.

use base64::Engine as _;

#[derive(Clone, Debug)]
pub enum TerminalEvent {
    Title(String),
    ResetTitle,
    Bell,
    ClipboardStore(String),
    ClipboardLoad,
    Write(String),
    ColorsChanged,
    Notification(Notification),
    CursorBlinkingChanged,
}

#[derive(Clone, Debug)]
pub struct Notification {
    pub id: String,
    pub title: Option<String>,
    pub body: Option<String>,
    pub occasions: i32,
    pub silent: bool,
    pub report: bool,
    pub focus: bool,
}

pub const OCCASION_FOCUSED: i32 = 1;
pub const OCCASION_UNFOCUSED: i32 = 2;
pub const OCCASION_INVISIBLE: i32 = 4;
pub const OCCASION_ALWAYS: i32 = 8;

fn b64(value: &str) -> String {
    base64::engine::general_purpose::STANDARD.encode(value.as_bytes())
}

impl TerminalEvent {
    /// Encode as a single tab-separated record. The first field is the event
    /// type; all remaining fields are base64 where they carry user text.
    pub fn encode(&self) -> String {
        match self {
            TerminalEvent::Title(title) => format!("title\t{}", b64(title)),
            TerminalEvent::ResetTitle => "reset_title".to_owned(),
            TerminalEvent::Bell => "bell".to_owned(),
            TerminalEvent::ClipboardStore(text) => format!("clipboard_store\t{}", b64(text)),
            TerminalEvent::ClipboardLoad => "clipboard_load".to_owned(),
            TerminalEvent::Write(text) => format!("write\t{}", b64(text)),
            TerminalEvent::ColorsChanged => "colors".to_owned(),
            TerminalEvent::CursorBlinkingChanged => "cursor_blink".to_owned(),
            TerminalEvent::Notification(notification) => format!(
                "notification\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                b64(&notification.id),
                b64(notification.title.as_deref().unwrap_or("")),
                b64(notification.body.as_deref().unwrap_or("")),
                notification.occasions,
                if notification.silent { 1 } else { 0 },
                if notification.report { 1 } else { 0 },
                if notification.focus { 1 } else { 0 },
            ),
        }
    }
}

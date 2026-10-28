//! Pre-parser for operating-system commands that Termux handles outside the VT
//! grid: OSC 99 desktop notifications, and the streaming wrapper that keeps them
//! out of `alacritty_terminal` (which would silently discard them).

use crate::events::{Notification, TerminalEvent, OCCASION_ALWAYS, OCCASION_FOCUSED, OCCASION_INVISIBLE, OCCASION_UNFOCUSED};

const MAX_OSC: usize = 65536;
const MAX_PENDING: usize = 32;
const MAX_CHARS_TOTAL: usize = 65536;
const MAX_CHARS_PER_NOTIFICATION: usize = 16384;

/// Parse an X11 `XParseColor` specification into opaque ARGB.
pub fn parse_color(text: &str) -> Option<u32> {
    let bytes = text.as_bytes();
    let (skip_initial, skip_between) = if bytes.first() == Some(&b'#') {
        (1usize, 0usize)
    } else if text.starts_with("rgb:") {
        (4usize, 1usize)
    } else {
        return None;
    };
    if bytes.len() <= skip_initial + 2 * skip_between {
        return None;
    }
    let chars_for_colors = bytes.len() - skip_initial - 2 * skip_between;
    if chars_for_colors % 3 != 0 {
        return None;
    }
    let component_length = chars_for_colors / 3;
    if component_length == 0 {
        return None;
    }
    let max = ((1u64 << (component_length * 4)) - 1) as f64;
    let scale = 255.0 / max;

    let mut position = skip_initial;
    let mut components = [0u32; 3];
    for component in &mut components {
        let end = position + component_length;
        let chunk = text.get(position..end)?;
        let value = u32::from_str_radix(chunk, 16).ok()?;
        *component = (value as f64 * scale) as u32;
        position = end + skip_between;
    }
    Some(0xFF00_0000 | (components[0] << 16) | (components[1] << 8) | components[2])
}

#[derive(Default)]
struct Pending {
    title: String,
    body: String,
}

impl Pending {
    fn length(&self) -> usize {
        self.title.len() + self.body.len()
    }
}

/// Kitty OSC 99 desktop-notification assembler.
struct NotificationParser {
    pending: Vec<(String, Pending)>,
    pending_chars: usize,
}

impl NotificationParser {
    fn new() -> Self {
        Self { pending: Vec::new(), pending_chars: 0 }
    }

    fn clear(&mut self) {
        self.pending.clear();
        self.pending_chars = 0;
    }

    /// Handle the payload after `OSC 99;`, returning a support-query reply if one
    /// is requested.
    fn handle(&mut self, metadata_and_payload: &str, events: &mut Vec<TerminalEvent>) -> Option<String> {
        let separator = metadata_and_payload.find(';');
        let (metadata, payload) = match separator {
            Some(index) => (&metadata_and_payload[..index], &metadata_and_payload[index + 1..]),
            None => (metadata_and_payload, ""),
        };

        let mut id = "0".to_owned();
        let mut payload_type = "body";
        let mut done = true;
        let mut base64_encoded = false;
        let mut saw_query = false;
        let mut occasions = OCCASION_ALWAYS;
        let mut silent = false;
        let mut report = false;
        let mut focus = true;

        for pair in metadata.split(':') {
            let bytes = pair.as_bytes();
            if bytes.len() < 2 || bytes[1] != b'=' {
                continue;
            }
            let key = bytes[0];
            let value = &pair[2..];
            match key {
                b'i' => {
                    if !value.is_empty() {
                        id = value.to_owned();
                    }
                }
                b'p' => {
                    payload_type = value;
                    if value == "?" {
                        saw_query = true;
                    }
                }
                b'd' => done = value != "0",
                b'e' => base64_encoded = value == "1",
                b'o' => occasions = parse_occasions(value),
                b'a' => {
                    if let Some(parsed) = parse_action(value, "focus") {
                        focus = parsed;
                    }
                    if parse_action(value, "report") == Some(true) {
                        report = true;
                    }
                }
                b's' => silent = sound_name(value) == "silent",
                _ => {}
            }
        }

        if saw_query && payload.is_empty() {
            return Some(build_support_reply(&id));
        }

        let is_title = payload_type == "title";
        let is_body = payload_type == "body";
        if !is_title && !is_body {
            return None;
        }

        let text = if base64_encoded { decode_base64_lossy(payload) } else { payload.to_owned() };

        let index = match self.pending.iter().position(|(key, _)| key == &id) {
            Some(index) => index,
            None => {
                if self.pending.len() >= MAX_PENDING {
                    self.evict_oldest();
                }
                self.pending.push((id.clone(), Pending::default()));
                self.pending.len() - 1
            }
        };

        {
            let pending = &mut self.pending[index].1;
            if is_title {
                pending.title.push_str(&text);
            } else {
                pending.body.push_str(&text);
            }
        }
        self.pending_chars += text.len();

        let too_large = self.pending[index].1.length() > MAX_CHARS_PER_NOTIFICATION;
        if too_large {
            let (_, pending) = self.pending.remove(index);
            self.pending_chars = self.pending_chars.saturating_sub(pending.length());
            return None;
        }
        if self.pending_chars > MAX_CHARS_TOTAL {
            self.evict_oldest();
        }

        if !done {
            return None;
        }

        let (_, pending) = self.pending.remove(index);
        self.pending_chars = self.pending_chars.saturating_sub(pending.length());
        self.emit(&id, pending, occasions, silent, report, focus, events);
        None
    }

    fn evict_oldest(&mut self) {
        if self.pending.is_empty() {
            return;
        }
        let (_, victim) = self.pending.remove(0);
        self.pending_chars = self.pending_chars.saturating_sub(victim.length());
    }

    #[allow(clippy::too_many_arguments)]
    fn emit(
        &self,
        id: &str,
        pending: Pending,
        occasions: i32,
        silent: bool,
        report: bool,
        focus: bool,
        events: &mut Vec<TerminalEvent>,
    ) {
        let body = pending.body.trim().to_owned();
        let title = pending.title.trim().to_owned();
        if body.is_empty() && title.is_empty() {
            return;
        }
        events.push(TerminalEvent::Notification(Notification {
            id: id.to_owned(),
            title: if title.is_empty() { None } else { Some(title) },
            body: if body.is_empty() { None } else { Some(body) },
            occasions,
            silent,
            report,
            focus,
        }));
    }
}

fn build_support_reply(id: &str) -> String {
    format!(
        "\x1b]99;i={id}:p=?;p=title,body;a=focus,report:o=focused,unfocused,invisible,always:s=system,silent\x1b\\"
    )
}

fn parse_occasions(value: &str) -> i32 {
    let mut mask = 0;
    for token in value.split(',') {
        match token {
            "focused" => mask |= OCCASION_FOCUSED,
            "unfocused" => mask |= OCCASION_UNFOCUSED,
            "invisible" => mask |= OCCASION_INVISIBLE,
            "always" => mask |= OCCASION_ALWAYS,
            _ => {}
        }
    }
    if mask == 0 {
        OCCASION_ALWAYS
    } else {
        mask
    }
}

fn parse_action(value: &str, action: &str) -> Option<bool> {
    for token in value.split(',') {
        if let Some(rest) = token.strip_prefix('-') {
            if rest == action {
                return Some(false);
            }
        } else if token == action {
            return Some(true);
        }
    }
    None
}

fn sound_name(value: &str) -> String {
    let decoded = decode_base64_lossy(value).trim().to_lowercase();
    if is_known_sound(&decoded) {
        return decoded;
    }
    let raw = value.trim().to_lowercase();
    if is_known_sound(&raw) {
        raw
    } else {
        String::new()
    }
}

fn is_known_sound(name: &str) -> bool {
    matches!(name, "system" | "silent" | "unset")
}

/// Lenient base64 decode matching the hand-rolled Java parser: characters
/// outside the alphabet are skipped and a truncated tail yields the bytes it
/// did contain.
pub fn decode_base64_lossy(input: &str) -> String {
    fn value(c: u8) -> Option<u8> {
        match c {
            b'A'..=b'Z' => Some(c - b'A'),
            b'a'..=b'z' => Some(c - b'a' + 26),
            b'0'..=b'9' => Some(c - b'0' + 52),
            b'+' => Some(62),
            b'/' => Some(63),
            _ => None,
        }
    }

    let mut out = Vec::with_capacity(input.len() * 3 / 4 + 3);
    let mut accumulator: u32 = 0;
    let mut bits = 0;
    for byte in input.bytes() {
        let Some(value) = value(byte) else { continue };
        accumulator = (accumulator << 6) | value as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push(((accumulator >> bits) & 0xFF) as u8);
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[derive(Clone, Copy, PartialEq)]
enum State {
    Ground,
    Escape,
    Osc,
    OscEscape,
}

/// Streaming OSC filter. Intercepts OSC 99 and leaves every other byte
/// untouched, so `alacritty_terminal` sees the exact original stream.
pub struct OscFilter {
    state: State,
    introducer: Vec<u8>,
    body: Vec<u8>,
    overflow: bool,
    parser: NotificationParser,
}

impl OscFilter {
    pub fn new() -> Self {
        Self {
            state: State::Ground,
            introducer: Vec::new(),
            body: Vec::new(),
            overflow: false,
            parser: NotificationParser::new(),
        }
    }

    pub fn reset(&mut self) {
        self.state = State::Ground;
        self.introducer.clear();
        self.body.clear();
        self.overflow = false;
        self.parser.clear();
    }

    fn begin_osc(&mut self, introducer: &[u8]) {
        self.state = State::Osc;
        self.introducer.clear();
        self.introducer.extend_from_slice(introducer);
        self.body.clear();
        self.overflow = false;
    }

    fn push_body(&mut self, byte: u8) {
        if self.body.len() < MAX_OSC {
            self.body.push(byte);
        } else {
            self.overflow = true;
        }
    }

    fn finish_osc(&mut self, terminator: &[u8], out: &mut Vec<u8>, events: &mut Vec<TerminalEvent>) {
        self.state = State::Ground;

        let body = std::mem::take(&mut self.body);
        let introducer = std::mem::take(&mut self.introducer);
        let overflow = self.overflow;
        self.overflow = false;

        if !overflow {
            if let Some(separator) = body.iter().position(|&b| b == b';') {
                if &body[..separator] == b"99" {
                    let text = String::from_utf8_lossy(&body[separator + 1..]);
                    if let Some(reply) = self.parser.handle(&text, events) {
                        events.push(TerminalEvent::Write(reply));
                    }
                    return;
                }
            }
        }

        if overflow {
            // An over-long OSC is not something Termux supports either; dropping
            // it is safer than feeding a truncated command to the emulator.
            return;
        }

        out.extend_from_slice(&introducer);
        out.extend_from_slice(&body);
        out.extend_from_slice(terminator);
    }

    /// Feed one chunk, appending bytes that the emulator should process to `out`.
    pub fn feed(&mut self, bytes: &[u8], out: &mut Vec<u8>, events: &mut Vec<TerminalEvent>) {
        for &byte in bytes {
            match self.state {
                State::Ground => match byte {
                    0x1B => self.state = State::Escape,
                    0x9D => self.begin_osc(&[0x9D]),
                    _ => out.push(byte),
                },
                State::Escape => {
                    if byte == b']' {
                        self.begin_osc(b"\x1b]");
                    } else {
                        out.push(0x1B);
                        out.push(byte);
                        self.state = State::Ground;
                    }
                }
                State::Osc => match byte {
                    0x07 => self.finish_osc(b"\x07", out, events),
                    0x1B => self.state = State::OscEscape,
                    0x9C => self.finish_osc(b"\x9C", out, events),
                    _ => self.push_body(byte),
                },
                State::OscEscape => {
                    if byte == b'\\' {
                        self.finish_osc(b"\x1b\\", out, events);
                    } else {
                        self.push_body(0x1B);
                        self.push_body(byte);
                        self.state = State::Osc;
                    }
                }
            }
        }
    }
}

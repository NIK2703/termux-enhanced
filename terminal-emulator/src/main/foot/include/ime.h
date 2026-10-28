#pragma once
struct seat;
struct terminal;
void ime_enable(struct seat *seat);
void ime_disable(struct seat *seat);
void ime_update_cursor_rect(struct seat *seat);
void ime_reset_pending(struct seat *seat);
void ime_reset_preedit(struct seat *seat);
static inline bool ime_enabled(const struct seat *seat) { (void)seat; return false; }

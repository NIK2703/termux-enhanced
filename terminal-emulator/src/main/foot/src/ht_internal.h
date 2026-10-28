#pragma once

#include <stddef.h>
#include <stdint.h>

struct terminal;
struct notification;

void headless_emit_bytes(struct terminal *term, const void *data, size_t length);
void headless_emit_title(struct terminal *term);
void headless_emit_bell(struct terminal *term);
void headless_emit_clipboard_store(struct terminal *term, const void *data, size_t length);
void headless_emit_notification(
    struct terminal *term, const struct notification *notification);

// Streaming pre-filter for the few sequences where Termux's Java contract is
// stricter than, or different from, upstream foot. See vt_filter.h.

#include "vt_filter.h"

#include "char32.h"

#include <ctype.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_OSC 65536
#define MAX_CSI 4096
#define MAX_PENDING 32
#define MAX_CHARS_TOTAL 65536
#define MAX_CHARS_PER_NOTIFICATION 16384

#define OCCASION_FOCUSED 1
#define OCCASION_UNFOCUSED 2
#define OCCASION_INVISIBLE 4
#define OCCASION_ALWAYS 8

enum state {
    STATE_GROUND,
    STATE_ESCAPE,
    STATE_OSC,
    STATE_OSC_ESCAPE,
    STATE_CSI,
};

struct pending {
    char *id;
    char *title;
    char *body;
    size_t title_length;
    size_t title_capacity;
    size_t body_length;
    size_t body_capacity;
};

struct buffer {
    uint8_t *data;
    size_t length;
    size_t capacity;
};

struct vt_filter {
    enum state state;
    struct buffer introducer;
    struct buffer body;
    bool overflow;
    bool line_start;

    struct pending pending[MAX_PENDING];
    size_t pending_count;
    size_t pending_chars;

    vt_filter_write_fn write_fn;
    vt_filter_notification_fn notification_fn;
    void *user;
};

static bool
buffer_reserve(struct buffer *buffer, size_t extra)
{
    if (extra > SIZE_MAX - buffer->length)
        return false;
    const size_t needed = buffer->length + extra;
    if (needed <= buffer->capacity)
        return true;

    size_t capacity = buffer->capacity > 0 ? buffer->capacity : 64;
    while (capacity < needed) {
        if (capacity > SIZE_MAX / 2)
            return false;
        capacity *= 2;
    }

    uint8_t *data = realloc(buffer->data, capacity);
    if (data == NULL)
        return false;
    buffer->data = data;
    buffer->capacity = capacity;
    return true;
}

static bool
buffer_append(struct buffer *buffer, const void *data, size_t length)
{
    if (!buffer_reserve(buffer, length))
        return false;
    if (length > 0)
        memcpy(buffer->data + buffer->length, data, length);
    buffer->length += length;
    return true;
}

static void
buffer_clear(struct buffer *buffer)
{
    buffer->length = 0;
}

static void
buffer_free(struct buffer *buffer)
{
    free(buffer->data);
    buffer->data = NULL;
    buffer->length = 0;
    buffer->capacity = 0;
}

static bool
append_char(struct buffer *buffer, uint8_t value)
{
    return buffer_append(buffer, &value, 1);
}

static size_t valid_utf8_length(const uint8_t *data, size_t length);

static size_t
decode_utf8(const uint8_t *data, size_t length, char32_t *out)
{
    const size_t sequence = valid_utf8_length(data, length);
    if (sequence == 0) {
        return 0;
    }

    uint32_t value;
    switch (sequence) {
    case 1:
        value = data[0];
        break;
    case 2:
        value = ((uint32_t)(data[0] & 0x1f) << 6) | (data[1] & 0x3f);
        break;
    case 3:
        value = ((uint32_t)(data[0] & 0x0f) << 12) |
                ((uint32_t)(data[1] & 0x3f) << 6) | (data[2] & 0x3f);
        break;
    default:
        value = ((uint32_t)(data[0] & 0x07) << 18) |
                ((uint32_t)(data[1] & 0x3f) << 12) |
                ((uint32_t)(data[2] & 0x3f) << 6) | (data[3] & 0x3f);
        break;
    }

    *out = (char32_t)value;
    return sequence;
}

static char *
string_append(char *text, size_t *length, size_t *capacity,
              const char *data, size_t count)
{
    if (count > SIZE_MAX - *length - 1)
        return NULL;
    const size_t needed = *length + count + 1;
    if (needed > *capacity) {
        size_t new_capacity = *capacity > 0 ? *capacity : 32;
        while (new_capacity < needed) {
            if (new_capacity > SIZE_MAX / 2)
                return NULL;
            new_capacity *= 2;
        }
        char *resized = realloc(text, new_capacity);
        if (resized == NULL)
            return NULL;
        text = resized;
        *capacity = new_capacity;
    }
    if (count > 0)
        memcpy(text + *length, data, count);
    *length += count;
    text[*length] = '\0';
    return text;
}

static size_t
valid_utf8_length(const uint8_t *data, size_t length)
{
    if (length == 0)
        return 0;

    const uint8_t c = data[0];
    if (c < 0x80)
        return 1;

    if (c >= 0xc2 && c <= 0xdf) {
        if (length >= 2 && data[1] >= 0x80 && data[1] <= 0xbf)
            return 2;
        return 0;
    }

    if (c >= 0xe0 && c <= 0xef) {
        if (length < 3 || data[1] < 0x80 || data[1] > 0xbf ||
            data[2] < 0x80 || data[2] > 0xbf)
        {
            return 0;
        }
        if (c == 0xe0 && data[1] < 0xa0)
            return 0;
        if (c == 0xed && data[1] > 0x9f)
            return 0;
        return 3;
    }

    if (c >= 0xf0 && c <= 0xf4) {
        if (length < 4 || data[1] < 0x80 || data[1] > 0xbf ||
            data[2] < 0x80 || data[2] > 0xbf ||
            data[3] < 0x80 || data[3] > 0xbf)
        {
            return 0;
        }
        if (c == 0xf0 && data[1] < 0x90)
            return 0;
        if (c == 0xf4 && data[1] > 0x8f)
            return 0;
        return 4;
    }

    return 0;
}

/* Lossy UTF-8 conversion with U+FFFD replacement. */
static char *
utf8_lossy_dup(const uint8_t *data, size_t length)
{
    static const char replacement[] = "\xef\xbf\xbd";
    char *out = NULL;
    size_t out_length = 0;
    size_t out_capacity = 0;

    size_t index = 0;
    while (index < length) {
        const size_t sequence = valid_utf8_length(data + index, length - index);
        if (sequence > 0) {
            out = string_append(
                out, &out_length, &out_capacity,
                (const char *)data + index, sequence);
            index += sequence;
        } else {
            out = string_append(
                out, &out_length, &out_capacity,
                replacement, sizeof(replacement) - 1);
            index++;
        }
        if (out == NULL)
            return NULL;
    }

    if (out == NULL)
        out = strdup("");
    return out;
}

static char *
trimmed_dup(const char *text)
{
    if (text == NULL)
        return strdup("");

    const char *start = text;
    while (*start != '\0' && (unsigned char)*start <= 0x20)
        start++;

    const char *end = start + strlen(start);
    while (end > start && (unsigned char)end[-1] <= 0x20)
        end--;

    const size_t length = (size_t)(end - start);
    char *out = malloc(length + 1);
    if (out == NULL)
        return NULL;
    memcpy(out, start, length);
    out[length] = '\0';
    return out;
}

static int
base64_value(uint8_t c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static char *
decode_base64_lossy(const char *input)
{
    const size_t input_length = strlen(input);
    uint8_t *out = malloc(input_length * 3 / 4 + 4);
    if (out == NULL)
        return NULL;

    size_t out_length = 0;
    uint32_t accumulator = 0;
    int bits = 0;
    for (size_t i = 0; i < input_length; i++) {
        const int value = base64_value((uint8_t)input[i]);
        if (value < 0)
            continue;
        accumulator = (accumulator << 6) | (uint32_t)value;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out[out_length++] = (uint8_t)((accumulator >> bits) & 0xff);
        }
    }

    char *text = utf8_lossy_dup(out, out_length);
    free(out);
    return text;
}

static char *
lower_ascii(char *text)
{
    if (text == NULL)
        return NULL;
    for (char *p = text; *p != '\0'; p++) {
        const unsigned char c = (unsigned char)*p;
        if (c >= 'A' && c <= 'Z')
            *p = (char)(c - 'A' + 'a');
    }
    return text;
}

static bool
known_sound(const char *name)
{
    return strcmp(name, "system") == 0 ||
           strcmp(name, "silent") == 0 ||
           strcmp(name, "unset") == 0;
}

static char *
sound_name(const char *value)
{
    char *decoded = decode_base64_lossy(value);
    char *trimmed = trimmed_dup(decoded != NULL ? decoded : "");
    free(decoded);
    lower_ascii(trimmed);
    if (trimmed != NULL && known_sound(trimmed))
        return trimmed;
    free(trimmed);

    trimmed = trimmed_dup(value);
    lower_ascii(trimmed);
    if (trimmed != NULL && known_sound(trimmed))
        return trimmed;
    free(trimmed);
    return strdup("");
}

static int
parse_occasions(const char *value)
{
    int mask = 0;
    char *copy = strdup(value);
    if (copy == NULL)
        return OCCASION_ALWAYS;

    char *save = NULL;
    for (char *token = strtok_r(copy, ",", &save);
         token != NULL; token = strtok_r(NULL, ",", &save))
    {
        if (strcmp(token, "focused") == 0)
            mask |= OCCASION_FOCUSED;
        else if (strcmp(token, "unfocused") == 0)
            mask |= OCCASION_UNFOCUSED;
        else if (strcmp(token, "invisible") == 0)
            mask |= OCCASION_INVISIBLE;
        else if (strcmp(token, "always") == 0)
            mask |= OCCASION_ALWAYS;
    }
    free(copy);
    return mask != 0 ? mask : OCCASION_ALWAYS;
}

/* -1 = action not mentioned, 0 = disabled, 1 = enabled. */
static int
parse_action(const char *value, const char *action)
{
    char *copy = strdup(value);
    if (copy == NULL)
        return -1;

    int result = -1;
    char *save = NULL;
    for (char *token = strtok_r(copy, ",", &save);
         token != NULL; token = strtok_r(NULL, ",", &save))
    {
        if (token[0] == '-') {
            if (strcmp(token + 1, action) == 0) {
                result = 0;
                break;
            }
        } else if (strcmp(token, action) == 0) {
            result = 1;
            break;
        }
    }

    free(copy);
    return result;
}

static struct pending *
find_pending(struct vt_filter *filter, const char *id)
{
    for (size_t i = 0; i < filter->pending_count; i++) {
        if (strcmp(filter->pending[i].id, id) == 0)
            return &filter->pending[i];
    }
    return NULL;
}

static size_t
pending_length(const struct pending *pending)
{
    return pending->title_length + pending->body_length;
}

static void
pending_free(struct pending *pending)
{
    free(pending->id);
    free(pending->title);
    free(pending->body);
    memset(pending, 0, sizeof(*pending));
}

static void
remove_pending(struct vt_filter *filter, size_t index)
{
    struct pending *pending = &filter->pending[index];
    const size_t length = pending_length(pending);
    filter->pending_chars = length <= filter->pending_chars
        ? filter->pending_chars - length : 0;
    pending_free(pending);

    if (index + 1 < filter->pending_count) {
        memmove(
            &filter->pending[index], &filter->pending[index + 1],
            (filter->pending_count - index - 1) * sizeof(filter->pending[0]));
    }
    filter->pending_count--;
}

static void
evict_oldest(struct vt_filter *filter)
{
    if (filter->pending_count > 0)
        remove_pending(filter, 0);
}

static void
emit_notification(
    struct vt_filter *filter, const char *id,
    const struct pending *pending, int occasions,
    bool silent, bool report, bool focus)
{
    char *body = trimmed_dup(pending->body);
    char *title = trimmed_dup(pending->title);
    if (body == NULL || title == NULL) {
        free(body);
        free(title);
        return;
    }

    if (body[0] != '\0' || title[0] != '\0') {
        if (filter->notification_fn != NULL) {
            filter->notification_fn(
                filter->user, id,
                title[0] != '\0' ? title : NULL,
                body[0] != '\0' ? body : NULL,
                occasions, silent, report, focus);
        }
    }

    free(body);
    free(title);
}

static char *
build_support_reply(const char *id)
{
    char *reply = NULL;
    size_t length = 0;
    size_t capacity = 0;
    reply = string_append(reply, &length, &capacity, "\033]99;i=", 7);
    if (reply != NULL)
        reply = string_append(reply, &length, &capacity, id, strlen(id));
    if (reply != NULL) {
        reply = string_append(
            reply, &length, &capacity,
            ":p=?;p=title,body;a=focus,report:"
            "o=focused,unfocused,invisible,always:s=system,silent\033\\",
            strlen(":p=?;p=title,body;a=focus,report:"
                   "o=focused,unfocused,invisible,always:s=system,silent\033\\"));
    }
    return reply;
}

static void
handle_osc99(struct vt_filter *filter, const uint8_t *data, size_t length)
{
    const char *separator = memchr(data, ';', length);
    if (separator == NULL)
        return;

    const size_t metadata_length = (size_t)(separator - (const char *)data);
    char *metadata = utf8_lossy_dup(data, metadata_length);
    char *payload_text = utf8_lossy_dup(
        (const uint8_t *)separator + 1, length - metadata_length - 1);
    if (metadata == NULL || payload_text == NULL) {
        free(metadata);
        free(payload_text);
        return;
    }

    char *id = strdup("0");
    char *payload_type = strdup("body");
    bool done = true;
    bool base64_encoded = false;
    bool saw_query = false;
    int occasions = OCCASION_ALWAYS;
    bool silent = false;
    bool report = false;
    bool focus = true;

    char *save = NULL;
    for (char *pair = strtok_r(metadata, ":", &save);
         pair != NULL; pair = strtok_r(NULL, ":", &save))
    {
        if (strlen(pair) < 2 || pair[1] != '=')
            continue;
        const char key = pair[0];
        const char *value = pair + 2;

        switch (key) {
        case 'i':
            if (value[0] != '\0') {
                free(id);
                id = strdup(value);
            }
            break;
        case 'p':
            free(payload_type);
            payload_type = strdup(value);
            if (strcmp(value, "?") == 0)
                saw_query = true;
            break;
        case 'd':
            done = strcmp(value, "0") != 0;
            break;
        case 'e':
            base64_encoded = strcmp(value, "1") == 0;
            break;
        case 'o':
            occasions = parse_occasions(value);
            break;
        case 'a': {
            const int parsed_focus = parse_action(value, "focus");
            if (parsed_focus >= 0)
                focus = parsed_focus != 0;
            if (parse_action(value, "report") == 1)
                report = true;
            break;
        }
        case 's': {
            char *name = sound_name(value);
            silent = name != NULL && strcmp(name, "silent") == 0;
            free(name);
            break;
        }
        default:
            break;
        }
    }

    if (id == NULL || payload_type == NULL) {
        free(metadata);
        free(payload_text);
        free(id);
        free(payload_type);
        return;
    }

    if (saw_query && payload_text[0] == '\0') {
        char *reply = build_support_reply(id);
        if (reply != NULL && filter->write_fn != NULL)
            filter->write_fn(filter->user, reply, strlen(reply));
        free(reply);
        goto out;
    }

    const bool is_title = strcmp(payload_type, "title") == 0;
    const bool is_body = strcmp(payload_type, "body") == 0;
    if (!is_title && !is_body)
        goto out;

    char *text = NULL;
    if (base64_encoded) {
        text = decode_base64_lossy(payload_text);
    } else {
        text = strdup(payload_text);
    }
    if (text == NULL)
        goto out;
    const size_t text_length = strlen(text);

    struct pending *pending = find_pending(filter, id);
    if (pending == NULL) {
        if (filter->pending_count >= MAX_PENDING)
            evict_oldest(filter);
        pending = &filter->pending[filter->pending_count++];
        memset(pending, 0, sizeof(*pending));
        pending->id = strdup(id);
        if (pending->id == NULL) {
            filter->pending_count--;
            free(text);
            goto out;
        }
    }

    if (is_title) {
        pending->title = string_append(
            pending->title, &pending->title_length,
            &pending->title_capacity, text, text_length);
        if (pending->title == NULL) {
            remove_pending(filter, (size_t)(pending - filter->pending));
            free(text);
            goto out;
        }
    } else {
        pending->body = string_append(
            pending->body, &pending->body_length,
            &pending->body_capacity, text, text_length);
        if (pending->body == NULL) {
            remove_pending(filter, (size_t)(pending - filter->pending));
            free(text);
            goto out;
        }
    }
    free(text);
    filter->pending_chars += text_length;

    if (pending_length(pending) > MAX_CHARS_PER_NOTIFICATION) {
        remove_pending(filter, (size_t)(pending - filter->pending));
        goto out;
    }
    if (filter->pending_chars > MAX_CHARS_TOTAL)
        evict_oldest(filter);

    if (!done)
        goto out;

    const size_t index = (size_t)(pending - filter->pending);
    emit_notification(
        filter, id, pending, occasions, silent, report, focus);
    remove_pending(filter, index);

out:
    free(metadata);
    free(payload_text);
    free(id);
    free(payload_type);
}

static void
finish_osc(struct vt_filter *filter, const uint8_t *terminator,
           size_t terminator_length, struct buffer *out)
{
    filter->state = STATE_GROUND;

    const uint8_t *body = filter->body.data;
    const size_t body_length = filter->body.length;
    const bool overflow = filter->overflow;
    filter->overflow = false;

    if (!overflow) {
        const uint8_t *separator = memchr(body, ';', body_length);
        const size_t command_length = separator != NULL
            ? (size_t)(separator - body) : body_length;
        const char *params = separator != NULL
            ? (const char *)separator + 1 : "";
        const size_t params_length = separator != NULL
            ? body_length - command_length - 1 : 0;

        if (command_length == 2 && memcmp(body, "99", 2) == 0) {
            handle_osc99(filter, (const uint8_t *)params, params_length);
            return;
        }

        /* OSC 10/11/12: xterm accepts a list of successive colours. */
        int command = -1;
        if (command_length == 2 &&
            ((body[0] == '1' && body[1] >= '0' && body[1] <= '2')))
        {
            command = 10 + (body[1] - '0');
        }

        if (command >= 0) {
            size_t start = 0;
            int next_command = command;
            bool wrote = false;
            while (start <= params_length && next_command <= 12) {
                size_t end = start;
                while (end < params_length && params[end] != ';')
                    end++;
                const size_t part_length = end - start;
                if (part_length > 0) {
                    char number[8];
                    const int number_length = snprintf(
                        number, sizeof(number), "%d", next_command);
                    buffer_append(out, filter->introducer.data,
                                  filter->introducer.length);
                    buffer_append(out, number, (size_t)number_length);
                    append_char(out, ';');
                    buffer_append(out, params + start, part_length);
                    buffer_append(out, terminator, terminator_length);
                    wrote = true;
                }
                next_command++;
                if (end >= params_length)
                    break;
                start = end + 1;
            }
            if (wrote)
                return;
        }
    }

    if (overflow) {
        /* Dropping an over-long sequence is safer than feeding a truncated
         * command to the terminal core. */
        return;
    }

    buffer_append(out, filter->introducer.data, filter->introducer.length);
    buffer_append(out, body, body_length);
    buffer_append(out, terminator, terminator_length);
}

static void
finish_csi(struct vt_filter *filter, uint8_t final, struct buffer *out)
{
    filter->state = STATE_GROUND;
    const uint8_t *body = filter->body.data;
    const size_t length = filter->body.length;
    const bool deccolm =
        !filter->overflow && length == 2 && body[0] == '?' && body[1] == '3' &&
        (final == 'h' || final == 'l');

    if (deccolm) {
        /* DECCOLM resets the scrolling region (which also homes the cursor)
         * and clears the current grid. Translate those two side effects into
         * sequences foot understands. */
        buffer_append(out, "\033[r\033[2J", 7);
    } else if (!filter->overflow) {
        buffer_append(out, filter->introducer.data, filter->introducer.length);
        buffer_append(out, body, length);
        append_char(out, final);
    }
    filter->overflow = false;
}

static void
begin_sequence(struct vt_filter *filter, enum state state,
               const void *introducer, size_t introducer_length)
{
    filter->state = state;
    buffer_clear(&filter->introducer);
    buffer_clear(&filter->body);
    filter->overflow = false;
    buffer_append(&filter->introducer, introducer, introducer_length);
}

static void
push_body(struct vt_filter *filter, uint8_t byte, size_t limit)
{
    if (filter->body.length >= limit) {
        filter->overflow = true;
        return;
    }
    if (!append_char(&filter->body, byte))
        filter->overflow = true;
}

static uint8_t *
filter_feed(
    struct vt_filter *filter, const uint8_t *data, size_t length,
    size_t *out_length)
{
    struct buffer out = {0};
    size_t i = 0;

    while (i < length) {
        /* The common case is a long run of plain text. Copy it in one go
         * instead of paying a buffer_append() call per byte. ESC and C1 OSC
         * always end the run; a high-bit byte only needs inspection when a
         * combining mark could be at the start of a line. */
        if (filter->state == STATE_GROUND) {
            const size_t start = i;
            while (i < length) {
                const uint8_t byte = data[i];
                if (byte == 0x1b)
                    break;
                if (byte >= 0x80) {
                    if (filter->line_start)
                        break;
                    /* Skip a complete UTF-8 sequence. In particular, a
                     * continuation byte 0x9d must not be mistaken for C1 OSC. */
                    const size_t sequence = valid_utf8_length(data + i, length - i);
                    if (sequence > 1) {
                        i += sequence;
                        continue;
                    }
                    if (byte == 0x9d)
                        break;
                    i++;
                    continue;
                }
                if (byte == '\r')
                    filter->line_start = true;
                else if (byte != '\n' && byte >= 0x20)
                    filter->line_start = false;
                i++;
            }
            if (i > start)
                buffer_append(&out, data + start, i - start);
            if (i >= length)
                break;
        }

        const uint8_t byte = data[i++];

        switch (filter->state) {
        case STATE_GROUND:
            if (byte == 0x1b) {
                filter->state = STATE_ESCAPE;
            } else if (byte == 0x9d) {
                begin_sequence(filter, STATE_OSC, "\x9d", 1);
            } else if (byte >= 0x80) {
                const size_t start = i - 1;
                char32_t codepoint;
                const size_t sequence =
                    decode_utf8(data + start, length - start, &codepoint);
                if (sequence > 0) {
                    /* Termux gives a combining mark at the start of a line a
                     * space to attach to. foot would silently drop it. */
                    if (filter->line_start && codepoint >= 0x300 &&
                        c32width(codepoint) <= 0)
                    {
                        append_char(&out, ' ');
                    }
                    buffer_append(&out, data + start, sequence);
                    filter->line_start = false;
                    i += sequence - 1;
                } else {
                    append_char(&out, byte);
                    filter->line_start = false;
                }
            } else {
                append_char(&out, byte);
                if (byte == '\r')
                    filter->line_start = true;
                else if (byte != '\n' && byte >= 0x20)
                    filter->line_start = false;
            }
            break;

        case STATE_ESCAPE:
            if (byte == ']') {
                begin_sequence(filter, STATE_OSC, "\x1b]", 2);
            } else if (byte == '[') {
                begin_sequence(filter, STATE_CSI, "\x1b[", 2);
            } else {
                append_char(&out, 0x1b);
                append_char(&out, byte);
                filter->state = STATE_GROUND;
            }
            break;

        case STATE_OSC:
            if (byte == 0x07) {
                static const uint8_t terminator[] = {0x07};
                finish_osc(filter, terminator, sizeof(terminator), &out);
            } else if (byte == 0x1b) {
                filter->state = STATE_OSC_ESCAPE;
            } else if (byte == 0x9c) {
                static const uint8_t terminator[] = {0x9c};
                finish_osc(filter, terminator, sizeof(terminator), &out);
            } else {
                push_body(filter, byte, MAX_OSC);
            }
            break;

        case STATE_OSC_ESCAPE:
            if (byte == '\\') {
                static const uint8_t terminator[] = {'\x1b', '\\'};
                finish_osc(filter, terminator, sizeof(terminator), &out);
            } else {
                push_body(filter, 0x1b, MAX_OSC);
                push_body(filter, byte, MAX_OSC);
                filter->state = STATE_OSC;
            }
            break;

        case STATE_CSI:
            if (byte >= 0x40 && byte <= 0x7e) {
                finish_csi(filter, byte, &out);
            } else if (byte == 0x1b) {
                /* Malformed CSI: pass what we saw through, then start a new
                 * escape sequence with this ESC. */
                if (!filter->overflow) {
                    buffer_append(&out, filter->introducer.data,
                                  filter->introducer.length);
                    buffer_append(&out, filter->body.data,
                                  filter->body.length);
                }
                filter->state = STATE_ESCAPE;
                filter->overflow = false;
            } else {
                push_body(filter, byte, MAX_CSI);
            }
            break;
        }
    }

    if (out.data == NULL) {
        *out_length = 0;
        return NULL;
    }
    *out_length = out.length;
    return out.data;
}

struct vt_filter *
vt_filter_create(
    vt_filter_write_fn write_fn,
    vt_filter_notification_fn notification_fn, void *user)
{
    struct vt_filter *filter = calloc(1, sizeof(*filter));
    if (filter == NULL)
        return NULL;
    filter->write_fn = write_fn;
    filter->notification_fn = notification_fn;
    filter->user = user;
    filter->line_start = true;
    return filter;
}

void
vt_filter_reset(struct vt_filter *filter)
{
    if (filter == NULL)
        return;
    filter->state = STATE_GROUND;
    buffer_clear(&filter->introducer);
    buffer_clear(&filter->body);
    filter->overflow = false;
    filter->line_start = true;
    while (filter->pending_count > 0)
        remove_pending(filter, 0);
    filter->pending_chars = 0;
}

void
vt_filter_destroy(struct vt_filter *filter)
{
    if (filter == NULL)
        return;
    vt_filter_reset(filter);
    buffer_free(&filter->introducer);
    buffer_free(&filter->body);
    free(filter);
}

void
vt_filter_feed(
    struct vt_filter *filter, const uint8_t *data, size_t length,
    uint8_t **out_data, size_t *out_length, bool *out_owned)
{
    *out_data = NULL;
    *out_length = 0;
    *out_owned = false;
    if (filter == NULL || length == 0)
        return;

    /* Plain text in the ground state needs no rewriting. Detect it up front
     * and hand the caller the original bytes instead of allocating and copying
     * a same-sized buffer. ESC and C1 OSC always need the state machine; a
     * high-bit byte only does while a line-start combining mark is possible. */
    if (filter->state == STATE_GROUND) {
        bool line_start = filter->line_start;
        bool plain = true;
        for (size_t i = 0; i < length; ) {
            const uint8_t byte = data[i];
            if (byte == 0x1b) {
                plain = false;
                break;
            }
            if (byte >= 0x80) {
                if (line_start) {
                    plain = false;
                    break;
                }
                const size_t sequence = valid_utf8_length(data + i, length - i);
                if (sequence > 1) {
                    i += sequence;
                    continue;
                }
                if (byte == 0x9d) {
                    plain = false;
                    break;
                }
                i++;
                continue;
            }
            if (byte == '\r')
                line_start = true;
            else if (byte != '\n' && byte >= 0x20)
                line_start = false;
            i++;
        }
        if (plain) {
            filter->line_start = line_start;
            *out_data = (uint8_t *)data;
            *out_length = length;
            return;
        }
    }

    *out_data = filter_feed(filter, data, length, out_length);
    *out_owned = *out_data != NULL;
}

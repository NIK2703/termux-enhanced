package com.termux.terminal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parser for the kitty OSC 99 desktop notification protocol.
 *
 * <p>The wire form is {@code OSC 99 ; metadata ; payload}, where {@code metadata} is a
 * colon-separated list of single-character {@code key=value} pairs. A notification is delivered
 * in chunks: the program may send {@code p=title} and {@code p=body} any number of times, and the
 * notification is only complete once a chunk with {@code d=1} arrives. Chunks are accumulated per
 * {@code i} identifier.
 *
 * <p>A program can also ask whether the terminal supports the protocol at all, by sending
 * {@code p=?} with an empty payload. That is answered with a reply written back to the pty, which
 * {@link #handle(String)} returns.
 *
 * <p>Deliberately independent of any particular client: this is a terminal protocol, so it lives
 * in the emulator and hands finished {@link TerminalNotification}s to whoever is listening.
 *
 * @see <a href="https://sw.kovidgoyal.net/kitty/desktop-notifications/">kitty desktop notifications</a>
 */
final class Osc99NotificationParser {

    /** Receives notifications that have been fully assembled. */
    interface Listener {
        void onNotification(@NonNull TerminalNotification notification);
    }

    /**
     * Cap on notifications being assembled at once. The protocol explicitly leaves this to the
     * terminal ("terminal emulators are free to impose a sensible limit to avoid Denial-of-Service
     * attacks"), and without it a program that never sends {@code d=1} would grow this map forever.
     */
    private static final int MAX_PENDING = 32;
    /**
     * Cap on the decoded text held for all pending notifications together.
     *
     * <p>Only ever reached by notifications that are still incomplete. A completed one is handed
     * over whole, with whatever text it accumulated: inventing a length policy here would mean
     * silently mangling or dropping a notification a program did finish sending, and the protocol
     * asks terminals to be generous about text, not clever about it. Incomplete ones are different
     * — a program that never sends {@code d=1} is either broken or trying to exhaust memory, and
     * there is nothing to deliver.
     */
    private static final int MAX_CHARS_TOTAL = 65536;

    private final Listener mListener;
    private final Map<String, Pending> mPending = new LinkedHashMap<>();
    private int mPendingChars = 0;

    Osc99NotificationParser(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * Handle one {@code OSC 99} escape sequence.
     *
     * @param metadataAndPayload everything after the {@code 99;} prefix, i.e. the raw
     *                           {@code metadata;payload} text.
     * @return the support-query reply that must be written back to the pty, or {@code null} if
     *         this sequence needs no reply.
     */
    @Nullable
    String handle(@Nullable String metadataAndPayload) {
        if (metadataAndPayload == null) return null;

        int separator = metadataAndPayload.indexOf(';');
        String metadata = separator < 0 ? metadataAndPayload : metadataAndPayload.substring(0, separator);
        String payload = separator < 0 ? "" : metadataAndPayload.substring(separator + 1);

        String id = TerminalNotification.DEFAULT_ID;
        // No p key at all means a plain body: the protocol's own one-liner for a shell script is
        // "OSC 99;;Hello world", i.e. a notification with no metadata whatsoever.
        String payloadType = "body";
        // Absent d means done: the protocol's own shell one-liner (OSC 99;;Hello world) carries no
        // metadata at all and is shown immediately. Only an explicit d=0 holds a notification back.
        boolean done = true;
        boolean base64 = false;
        boolean sawQuery = false;
        int occasions = TerminalNotification.OCCASION_ALWAYS;
        boolean silent = false;
        boolean report = false;
        boolean focus = true;

        for (String pair : metadata.split(":", -1)) {
            if (pair.length() < 2 || pair.charAt(1) != '=') continue; // no '=': not a key=value pair
            char key = pair.charAt(0);
            String value = pair.substring(2);
            switch (key) {
                case 'i':
                    if (!value.isEmpty()) id = value;
                    break;
                case 'p':
                    payloadType = value;
                    if ("?".equals(value)) sawQuery = true;
                    break;
                case 'd':
                    // "A value of 0 means the notification is not yet done [...] A non-zero value
                    // means the notification is done."
                    done = !"0".equals(value);
                    break;
                case 'e':
                    base64 = "1".equals(value);
                    break;
                case 'o':
                    occasions = parseOccasions(value);
                    break;
                case 'a':
                    Boolean parsedFocus = parseAction(value, "focus");
                    if (parsedFocus != null) focus = parsedFocus;
                    if (Boolean.TRUE.equals(parseAction(value, "report"))) report = true;
                    break;
                case 's':
                    silent = "silent".equals(soundName(value));
                    break;
                default:
                    // Unknown keys must be ignored, so the protocol can grow.
                    break;
            }
        }

        // A support query is a request, not a chunk: answer it and keep nothing.
        if (sawQuery && payload.isEmpty()) return buildSupportReply(id);

        boolean isTitle = "title".equals(payloadType);
        boolean isBody = "body".equals(payloadType);
        if (!isTitle && !isBody) return null; // unknown payload type: ignore, per the protocol

        // An empty payload is not skipped: it still carries d, and bailing out here would leave the
        // notification pending forever when the program meant "and that was all of it".
        String text = base64 ? decodeBase64(payload) : payload;

        Pending pending = mPending.get(id);
        if (pending == null) {
            if (mPending.size() >= MAX_PENDING) evictOldest();
            pending = new Pending();
            mPending.put(id, pending);
        }
        if (isTitle) {
            pending.title.append(text);
        } else {
            pending.body.append(text);
        }
        mPendingChars += text.length();
        if (mPendingChars > MAX_CHARS_TOTAL) evictOldest();

        if (!done) return null;
        mPending.remove(id);
        mPendingChars = Math.max(0, mPendingChars - pending.length());
        emit(id, pending, occasions, silent, report, focus);
        return null;
    }

    /**
     * Drop the least recently created notification, so a program that never completes a
     * notification cannot pin memory indefinitely.
     */
    private void evictOldest() {
        java.util.Iterator<Map.Entry<String, Pending>> it = mPending.entrySet().iterator();
        if (!it.hasNext()) return;
        Pending victim = it.next().getValue();
        mPendingChars = Math.max(0, mPendingChars - victim.length());
        it.remove();
    }

    private void emit(String id, Pending pending, int occasions, boolean silent, boolean report, boolean focus) {
        // "A notification with not title and no body is ignored." An all-whitespace body carries no
        // information either, so treat it the same way.
        String body = pending.body.toString().trim();
        String title = pending.title.toString().trim();
        if (body.isEmpty() && title.isEmpty()) return;
        // Only the protocol's own substitution: an absent title becomes the body. The reverse is
        // left alone on purpose, so a consumer can still see that the program sent a title and no
        // body rather than one piece of text standing in for both.
        mListener.onNotification(new TerminalNotification(id, title.isEmpty() ? null : title,
            body.isEmpty() ? null : body, occasions, silent, report, focus));
    }

    /**
     * Build the reply to a support query.
     *
     * <p>The protocol fixes the shape: echo the identifier and {@code p=?}, then list the supported
     * {@code key=value} details. Only keys this terminal actually honours are listed — a client
     * that trusts the list would otherwise wait for behaviour that never comes. The {@code p} value
     * must contain at least {@code title}, and {@code o=always} is mandatory when no occasion is
     * supported (this one supports all four, so it lists them).
     */
    private static String buildSupportReply(String id) {
        return "\033]99;i=" + id + ":p=?;p=title,body;a=focus,report"
            + ":o=focused,unfocused,invisible,always:s=system,silent\033\\";
    }

    /**
     * Parse the {@code o} key. Unknown occasions are ignored, and if none of the requested
     * occasions is supported the notification is treated as unconditional rather than dropped.
     */
    private static int parseOccasions(String value) {
        int mask = 0;
        for (String token : value.split(",", -1)) {
            switch (token) {
                case "focused": mask |= TerminalNotification.OCCASION_FOCUSED; break;
                case "unfocused": mask |= TerminalNotification.OCCASION_UNFOCUSED; break;
                case "invisible": mask |= TerminalNotification.OCCASION_INVISIBLE; break;
                case "always": mask |= TerminalNotification.OCCASION_ALWAYS; break;
                default: break;
            }
        }
        return mask == 0 ? TerminalNotification.OCCASION_ALWAYS : mask;
    }

    /**
     * Look one action up in the {@code a} key, honouring the leading '-' that turns an action off.
     *
     * @return {@code TRUE}/{@code FALSE} when the key mentions the action, {@code null} when it
     *         does not, so the caller can keep the protocol default.
     */
    @Nullable
    private static Boolean parseAction(String value, String action) {
        for (String token : value.split(",", -1)) {
            if (token.startsWith("-")) {
                if (token.substring(1).equals(action)) return Boolean.FALSE;
            } else if (token.equals(action)) {
                return Boolean.TRUE;
            }
        }
        return null;
    }

    /**
     * Read the {@code s} key.
     *
     * <p>Per the protocol this value is <em>always</em> base64 in a notification — the {@code e=1}
     * key only marks the payload, not the metadata. Decoded first, then the raw value, so a program
     * that sends the name unencoded anyway still works. Returns {@code ""} when neither matches a
     * known sound, which callers read as "no explicit request".
     */
    private static String soundName(String value) {
        String decoded = decodeBase64(value).trim().toLowerCase(Locale.ROOT);
        if (isKnownSound(decoded)) return decoded;
        String raw = value.trim().toLowerCase(Locale.ROOT);
        return isKnownSound(raw) ? raw : "";
    }

    private static boolean isKnownSound(String name) {
        return "system".equals(name) || "silent".equals(name) || "unset".equals(name);
    }

    /**
     * Decode standard base64 into UTF-8 text.
     *
     * <p>Hand-rolled rather than {@code android.util.Base64} so the protocol stays free of Android
     * dependencies and remains unit-testable. Characters outside the alphabet (including padding
     * and line breaks) are skipped and input is decoded up to the first complete group, so a
     * truncated chunk yields the text it did contain instead of nothing.
     *
     * <p>The bytes are interpreted as UTF-8 rather than as Latin-1: a program that base64s "é"
     * means "é", and widening each byte to a char would hand the application "Ã©".
     */
    private static String decodeBase64(String input) {
        byte[] out = new byte[input.length() * 3 / 4 + 3];
        int length = 0;
        int accumulator = 0;
        int bits = 0;
        for (int i = 0; i < input.length(); i++) {
            int value = base64Value(input.charAt(i));
            if (value < 0) continue;
            accumulator = (accumulator << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[length++] = (byte) ((accumulator >> bits) & 0xFF);
            }
        }
        return new String(out, 0, length, StandardCharsets.UTF_8);
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    }

    /** One notification being assembled from its chunks. */
    private static final class Pending {
        final StringBuilder title = new StringBuilder();
        final StringBuilder body = new StringBuilder();

        int length() {
            return title.length() + body.length();
        }
    }
}

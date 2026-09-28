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
 * <p>Wire form is {@code OSC 99 ; metadata ; payload}, metadata being colon-separated
 * {@code key=value} pairs. Notifications arrive in chunks — {@code p=title} and {@code p=body} any
 * number of times — and are complete once a chunk carries {@code d=1}; chunks are accumulated per
 * {@code i}. A {@code p=?} query is answered with the reply this returns.
 *
 * @see <a href="https://sw.kovidgoyal.net/kitty/desktop-notifications/">kitty desktop notifications</a>
 */
final class Osc99NotificationParser {

    /** Receives notifications that have been fully assembled. */
    interface Listener {
        void onNotification(@NonNull TerminalNotification notification);
    }

    /**
     * The protocol leaves this cap to the terminal; without it a program that never sends {@code d=1}
     * grows this map forever.
     */
    private static final int MAX_PENDING = 32;
    /**
     * Cap on the decoded text held for all pending notifications together.
     *
     * <p>Reached only by incomplete notifications. A finished one is handed over whole: a length
     * policy here would silently mangle what a program did send.
     */
    private static final int MAX_CHARS_TOTAL = 65536;

    /**
     * Cap on a single notification's accumulated text.
     *
     * <p>{@link #MAX_CHARS_TOTAL} bounds the total by evicting the <em>oldest</em> pending
     * notification, which a growing one is not: it is the newest, so it survives every eviction and
     * can append without limit while its program never sends {@code d=1}. Past this the notification
     * is dropped whole, which costs one notification from a program that was not going to finish it
     * anyway — a truncated one would be shown as if it were complete.
     */
    private static final int MAX_CHARS_PER_NOTIFICATION = 16384;

    private final Listener mListener;
    private final Map<String, Pending> mPending = new LinkedHashMap<>();
    private int mPendingChars = 0;

    Osc99NotificationParser(@NonNull Listener listener) {
        mListener = listener;
    }

    /**
     * Handle one {@code OSC 99} escape sequence.
     *
     * @param metadataAndPayload the raw {@code metadata;payload} text after the {@code 99;} prefix.
     * @return the reply to write back to the pty, or {@code null} if none is needed.
     */
    @Nullable
    String handle(@Nullable String metadataAndPayload) {
        if (metadataAndPayload == null) return null;

        int separator = metadataAndPayload.indexOf(';');
        String metadata = separator < 0 ? metadataAndPayload : metadataAndPayload.substring(0, separator);
        String payload = separator < 0 ? "" : metadataAndPayload.substring(separator + 1);

        String id = TerminalNotification.DEFAULT_ID;
        // No p key means a plain body, and absent d means done: "OSC 99;;Hello world", the
        // protocol's own one-liner, carries no metadata and is shown immediately.
        String payloadType = "body";
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
                    break; // unknown keys are ignored so the protocol can grow
            }
        }

        if (sawQuery && payload.isEmpty()) return buildSupportReply(id);

        boolean isTitle = "title".equals(payloadType);
        boolean isBody = "body".equals(payloadType);
        if (!isTitle && !isBody) return null; // unknown payload type: ignore, per the protocol

        // An empty payload is not skipped: it still carries d, and bailing out would leave the
        // notification pending when the program meant "that was all of it".
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
        // Checked before the total, and against this notification alone: the total is bounded by
        // evicting the oldest, which is never the one growing here.
        if (pending.length() > MAX_CHARS_PER_NOTIFICATION) {
            mPending.remove(id);
            mPendingChars = Math.max(0, mPendingChars - pending.length());
            return null;
        }
        if (mPendingChars > MAX_CHARS_TOTAL) evictOldest();

        if (!done) return null;
        mPending.remove(id);
        mPendingChars = Math.max(0, mPendingChars - pending.length());
        emit(id, pending, occasions, silent, report, focus);
        return null;
    }

    /**
     * Drop the least recently created notification, so an incomplete one cannot pin memory.
     */
    private void evictOldest() {
        java.util.Iterator<Map.Entry<String, Pending>> it = mPending.entrySet().iterator();
        if (!it.hasNext()) return;
        Pending victim = it.next().getValue();
        mPendingChars = Math.max(0, mPendingChars - victim.length());
        it.remove();
    }

    private void emit(String id, Pending pending, int occasions, boolean silent, boolean report, boolean focus) {
        // "A notification with not title and no body is ignored"; so is an all-whitespace one.
        String body = pending.body.toString().trim();
        String title = pending.title.toString().trim();
        if (body.isEmpty() && title.isEmpty()) return;
        // Only the protocol's own substitution, so a consumer can still tell a missing title from an
        // absent body.
        mListener.onNotification(new TerminalNotification(id, title.isEmpty() ? null : title,
            body.isEmpty() ? null : body, occasions, silent, report, focus));
    }

    /**
     * Build the reply to a support query.
     *
     * <p>Echo the identifier and {@code p=?}, then list the details honoured. Only keys actually
     * honoured are listed — a client that trusts the list would otherwise wait for behaviour that
     * never comes.
     */
    private static String buildSupportReply(String id) {
        return "\033]99;i=" + id + ":p=?;p=title,body;a=focus,report"
            + ":o=focused,unfocused,invisible,always:s=system,silent\033\\";
    }

    /**
     * Unknown occasions are ignored; none recognised means unconditional rather than dropped.
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
     * @return {@code null} when the key does not mention the action, so the caller keeps the
     *         protocol default.
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
     * <p>The protocol says this value is always base64 — {@code e=1} marks the payload, not the
     * metadata. The raw value is tried too, for a program that sends it unencoded.
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
     * <p>Hand-rolled rather than {@code android.util.Base64} so this stays Android-free and
     * unit-testable. Characters outside the alphabet are skipped, a truncated chunk yields the text
     * it did contain, and bytes are read as UTF-8 rather than Latin-1.
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

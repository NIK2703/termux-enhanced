package com.termux.shared.termux.monet;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide cache of the generated Monet terminal schemes.
 *
 * <p>There is one entry per {@link SchemeVariant}: the light and the dark scheme of a variant are
 * built together from a single palette snapshot, so flipping night mode is free, and switching
 * variant only costs the first build.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>expose a cheap {@link #token(SchemeVariant)} for {@code buildSchemeKey()}, which runs on
 *       every tab switch and must not do IPC;</li>
 *   <li>re-read the system palette when the wallpaper changes
 *       ({@code WallpaperManager.OnColorsChangedListener}), on resume, or when the
 *       {@code monet-*} options change.</li>
 * </ul>
 *
 * <p>Everything expensive is done here and only here; callers just ask for
 * {@link #get(Context, boolean, SchemeVariant)} or {@link #token(SchemeVariant)}.
 */
public final class MonetSchemeStore {

    private static final String LOG_TAG = "MonetSchemeStore";

    private static final Object LOCK = new Object();

    /** One generated variant: the snapshot it came from plus both night modes. */
    private static final class Entry {
        MonetSource source;
        int optionsRevision;
        Properties light;
        Properties dark;
        long token;
    }

    private static final Map<SchemeVariant, Entry> ENTRIES = new HashMap<>();

    /** The two IPC reads, cached so every variant can share them. */
    private static android.app.WallpaperColors sWallpaperColors;
    private static int sWallpaperId = -1;
    private static boolean sWallpaperRead;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static ExecutorService sExecutor;

    /** Listeners notified (on the main thread) whenever a generated scheme changes. */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private MonetSchemeStore() {}

    /** Dynamic color is an Android 12 (API 31) feature; below that the scheme is not offered. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /**
     * Identity of the cached scheme for a variant. Cheap - a map lookup, no IPC, no disk - so it is
     * safe to call from {@code buildSchemeKey()} on every tab switch.
     */
    public static long token(@NonNull SchemeVariant variant) {
        synchronized (LOCK) {
            Entry e = ENTRIES.get(variant);
            return e == null ? 0L : e.token;
        }
    }

    /** Whether the given variant has been generated at all yet. */
    public static boolean hasScheme(@NonNull SchemeVariant variant) {
        synchronized (LOCK) {
            Entry e = ENTRIES.get(variant);
            return e != null && e.light != null && e.dark != null;
        }
    }

    // ------------------------------------------------------------------ access ---

    /**
     * The generated scheme for the given night mode and variant, building it on demand.
     *
     * @return the scheme, or {@code null} when Monet is unsupported or the system refused to
     *         give us a palette - the caller must then fall back to the default scheme.
     */
    @Nullable
    @AnyThread
    public static Properties get(@NonNull Context context, boolean isNight,
                                 @NonNull SchemeVariant variant) {
        Entry entry = ensure(context, variant);
        if (entry == null) return null;
        synchronized (LOCK) {
            return isNight ? entry.dark : entry.light;
        }
    }

    /**
     * Make sure the given variants are generated. Called from {@code onCreate} so that
     * {@link #token(SchemeVariant)} is already stable by the time the first
     * {@code buildSchemeKey()} runs.
     */
    @AnyThread
    public static void warmUp(@NonNull Context context, @NonNull SchemeVariant... variants) {
        if (!isSupported()) return;
        for (SchemeVariant variant : variants) {
            if (variant != null) ensure(context, variant);
        }
    }

    // ----------------------------------------------------------------- refresh ---

    /**
     * Re-read the wallpaper and the options, regenerating every already-built variant when anything
     * changed.
     *
     * <p>Safe to call from any thread, but the heavy part (two {@code WallpaperManager} IPCs) makes
     * it worth running off the main thread - see {@link #refreshAsync}.
     *
     * @return {@code true} if any generated scheme changed.
     */
    @AnyThread
    public static boolean refresh(@NonNull Context context) {
        if (!isSupported()) return false;
        final Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            final int oldWallpaperId = sWallpaperId;
            final android.app.WallpaperColors oldColors = sWallpaperColors;
            final List<SchemeVariant> built = new ArrayList<>(ENTRIES.keySet());

            sWallpaperRead = false;
            MonetOptions.invalidate();
            readWallpaperLocked(appContext);

            final boolean wallpaperChanged = sWallpaperId != oldWallpaperId
                    || (sWallpaperColors == null ? oldColors != null : !sWallpaperColors.equals(oldColors));
            // Nothing to refresh if neither the wallpaper nor any previously built scheme exists.
            if (!wallpaperChanged && built.isEmpty()) return false;

            final Map<SchemeVariant, Long> oldTokens = new HashMap<>();
            for (SchemeVariant variant : built) {
                Entry e = ENTRIES.get(variant);
                if (e != null) oldTokens.put(variant, e.token);
            }
            ENTRIES.clear();

            boolean changed = false;
            for (SchemeVariant variant : built) {
                Entry e = ensure(appContext, variant);
                Long before = oldTokens.get(variant);
                if (e != null && (before == null || before != e.token)) changed = true;
            }
            return changed;
        }
    }

    /** {@link #refresh(Context)} on a background thread, with the listeners fired on the main one. */
    public static void refreshAsync(@NonNull Context context) {
        if (!isSupported()) return;
        executor().execute(() -> {
            final boolean changed;
            try {
                changed = refresh(context.getApplicationContext());
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh Monet scheme", e);
                return;
            }
            if (changed) notifyListeners();
        });
    }

    /** Register a callback invoked on the main thread whenever a scheme changes. */
    public static void addListener(@NonNull Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(@NonNull Runnable listener) {
        LISTENERS.remove(listener);
    }

    @MainThread
    private static void notifyListeners() {
        MAIN_HANDLER.post(() -> {
            for (Runnable listener : LISTENERS) {
                try {
                    listener.run();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Monet listener failed", e);
                }
            }
        });
    }

    // ------------------------------------------------------------------ internals ---

    @Nullable
    private static Entry ensure(@NonNull Context context, @NonNull SchemeVariant variant) {
        if (!isSupported()) return null;
        final Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (!sWallpaperRead) readWallpaperLocked(appContext);

            MonetOptions options = MonetOptions.load().withVariant(variant);
            Entry entry = ENTRIES.get(variant);
            if (entry != null && entry.optionsRevision == options.revision()) return entry;

            MonetSource source;
            try {
                source = SystemPaletteSource.read(appContext, options);
            } catch (Throwable t) {
                // OEM firmware may not implement dynamic color at all - degrade, never crash.
                Logger.logError(LOG_TAG, "Failed to read the Monet palette: " + t.getMessage());
                return null;
            }

            Properties light;
            Properties dark;
            try {
                light = TerminalPaletteBuilder.build(source, false, options);
                dark = TerminalPaletteBuilder.build(source, true, options);
            } catch (Throwable t) {
                Logger.logError(LOG_TAG, "Failed to build the Monet palette: " + t.getMessage());
                return null;
            }

            entry = new Entry();
            entry.source = source;
            entry.optionsRevision = options.revision();
            entry.light = light;
            entry.dark = dark;
            entry.token = mix(mix(source.token, options.revision()), variant.ordinal());
            ENTRIES.put(variant, entry);
            Logger.logDebug(LOG_TAG, "Scheme built: " + source);
            return entry;
        }
    }

    private static void readWallpaperLocked(@NonNull Context context) {
        try {
            android.app.WallpaperManager wm = android.app.WallpaperManager.getInstance(context);
            sWallpaperColors = wm.getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM);
            sWallpaperId = wm.getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read wallpaper colors: " + e.getMessage());
            sWallpaperColors = null;
            sWallpaperId = -1;
        }
        sWallpaperRead = true;
    }

    private static long mix(long a, int b) {
        long h = a;
        h ^= b + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        return h == 0 ? 1 : h;
    }

    @NonNull
    private static synchronized ExecutorService executor() {
        if (sExecutor == null || sExecutor.isShutdown()) {
            sExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "monet");
                t.setDaemon(true);
                return t;
            });
        }
        return sExecutor;
    }

    /** Drop everything; the next access rebuilds from scratch. */
    public static void invalidate() {
        synchronized (LOCK) {
            ENTRIES.clear();
            sWallpaperColors = null;
            sWallpaperId = -1;
            sWallpaperRead = false;
        }
        MonetOptions.invalidate();
    }

    /** The last snapshot for a variant, for diagnostics. */
    @Nullable
    public static MonetSource peekSource(@NonNull SchemeVariant variant) {
        synchronized (LOCK) {
            Entry e = ENTRIES.get(variant);
            return e == null ? null : e.source;
        }
    }

    // ------------------------------------------------------------- wallpaper hook ---

    /**
     * Wallpaper change hook for {@code TermuxActivity}. Separated out so the activity only has to
     * forward lifecycle calls and never touches {@code WallpaperManager} on API &lt; 31.
     */
    public static final class WallpaperObserver {

        private WallpaperObserver() {}

        /** Register {@code WallpaperManager.OnColorsChangedListener}; no-op below API 31. */
        @MainThread
        public static void register(@NonNull Context context) {
            if (!isSupported()) return;
            try {
                WallpaperListenerHolder.register(context.getApplicationContext());
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to register the wallpaper listener: " + e.getMessage());
            }
        }

        @MainThread
        public static void unregister(@NonNull Context context) {
            if (!isSupported()) return;
            try {
                WallpaperListenerHolder.unregister(context.getApplicationContext());
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to unregister the wallpaper listener: " + e.getMessage());
            }
        }
    }

    /** Kept in a separate class so the API-31 types are never loaded on older devices. */
    private static final class WallpaperListenerHolder {

        private static android.app.WallpaperManager sManager;
        private static WallpaperListener sListener;

        @MainThread
        static synchronized void register(@NonNull Context context) {
            if (sListener != null) return;
            sManager = android.app.WallpaperManager.getInstance(context);
            sListener = new WallpaperListener(context.getApplicationContext());
            sManager.addOnColorsChangedListener(sListener, MAIN_HANDLER);
        }

        @MainThread
        static synchronized void unregister(@NonNull Context context) {
            if (sListener == null) return;
            try {
                if (sManager == null) sManager = android.app.WallpaperManager.getInstance(context);
                sManager.removeOnColorsChangedListener(sListener);
            } finally {
                sManager = null;
                sListener = null;
            }
        }
    }

    @MainThread
    private static final class WallpaperListener
            implements android.app.WallpaperManager.OnColorsChangedListener {

        @NonNull private final Context mContext;

        WallpaperListener(@NonNull Context context) {
            mContext = context;
        }

        @Override
        public void onColorsChanged(android.app.WallpaperColors colors, int which) {
            if ((which & android.app.WallpaperManager.FLAG_SYSTEM) == 0) return;
            refreshAsync(mContext);
        }
    }
}

package com.termux.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Xml;

import com.termux.installer.TermuxBootstrapState;
import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class TermuxSettingsBackupUtils {

    private static final String LOG_TAG = "TermuxSettingsBackupUtils";

    public static final String BACKUP_FORMAT_VERSION = "1";
    public static final Error CANCELLED_ERROR = new Error("__SETTINGS_CANCELLED__");

    public static final String MIME_TYPE_ZIP = "application/zip";
    public static final String DEFAULT_FILENAME_PREFIX = "termux-settings-backup-";

    public interface ResultListener {
        void onResult(Error error);
    }

    public interface ProgressCallback {
        void onProgress(String stage);
    }

    private static final String[] EXCLUDED_APP_KEYS = {
        "current_session",
        "last_notification_id",
        "app_shell_number_since_boot",
        "terminal_session_number_since_boot"
    };

    private static final String PREFERENCES_APP = "app";
    private static final String PREFERENCES_UI = "ui";
    private static final String PREFERENCES_BOOTSTRAP = "bootstrap";

    private TermuxSettingsBackupUtils() {}

    /** Backup all app settings to a ZIP OutputStream. */
    public static void exportSettings(Context context, OutputStream out,
                                      ResultListener listener,
                                      ProgressCallback progress,
                                      AtomicBoolean cancelled) {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(out))) {
            if (isCancelled(cancelled)) { listener.onResult(CANCELLED_ERROR); return; }
            report(progress, "manifest");
            writeManifest(zos);

            if (isCancelled(cancelled)) { listener.onResult(CANCELLED_ERROR); return; }
            report(progress, "preferences");
            writePrefsEntry(zos, PREFERENCES_APP, prefsForName(context, PREFERENCES_APP),
                EXCLUDED_APP_KEYS);
            writePrefsEntry(zos, PREFERENCES_UI, prefsForName(context, PREFERENCES_UI), null);
            writePrefsEntry(zos, PREFERENCES_BOOTSTRAP, prefsForName(context, PREFERENCES_BOOTSTRAP),
                null);

            if (isCancelled(cancelled)) { listener.onResult(CANCELLED_ERROR); return; }
            report(progress, "config files");
            writeFileEntry(zos, "config/colors.properties", TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH);
            writeFileEntry(zos, "config/colors.light.properties", TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE_PATH);
            writeFileEntry(zos, "config/colors.dark.properties", TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE_PATH);
            writeFileEntry(zos, "config/font.ttf", TermuxConstants.TERMUX_FONT_FILE_PATH);
            writeFileEntry(zos, "config/termux.float.properties", TermuxConstants.TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH);

            if (isCancelled(cancelled)) { listener.onResult(CANCELLED_ERROR); return; }
            report(progress, "bootstrap marker");
            writeFileEntry(zos, "marker/bootstrap_variant",
                TermuxConstants.TERMUX_CONFIG_PREFIX_DIR_PATH + "/bootstrap_variant");

            listener.onResult(null);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Settings export failed", e);
            listener.onResult(new Error(e.getMessage(), e));
        }
    }

    /** Restore all app settings from a ZIP InputStream.
     *  Supports multiple formats: strict (preferences/*, config/*, marker/*, manifest.properties)
     *  and fallback basename-based (SharedPreferences XML, config files by name from any path). */
    public static void importSettings(Context context, InputStream in,
                                      ResultListener listener,
                                      ProgressCallback progress,
                                      AtomicBoolean cancelled) {
        int manifestCount = 0;
        int preferenceEntries = 0;
        int configEntries = 0;
        int markerEntries = 0;
        int unrecognizedEntries = 0;
        List<String> sampleUnrecognized = new ArrayList<>(5);

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            ZipEntry entry;

            while ((entry = zis.getNextEntry()) != null) {
                if (isCancelled(cancelled)) { listener.onResult(CANCELLED_ERROR); return; }
                String rawName = entry.getName();
                report(progress, rawName);

                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }

                String normalizedName = normalizeZipEntryName(rawName);
                if (normalizedName == null || normalizedName.isEmpty()) {
                    Logger.logWarn(LOG_TAG, "Skipping unsafe or empty ZIP entry: \"" + rawName + "\"");
                    zis.closeEntry();
                    continue;
                }

                boolean matched = false;

                // --- Strict matching (current export format) ---
                if (!matched && normalizedName.equals("manifest.properties")) {
                    matched = handleManifestEntry(zis);
                    if (matched) manifestCount++;
                }

                if (!matched && normalizedName.startsWith("preferences/")) {
                    matched = handleStrictPrefsEntry(context, normalizedName, rawName, zis);
                    if (matched) preferenceEntries++;
                }

                if (!matched && normalizedName.startsWith("config/")) {
                    matched = handleStrictConfigEntry(normalizedName, rawName, zis);
                    if (matched) configEntries++;
                }

                if (!matched && normalizedName.startsWith("marker/")) {
                    matched = handleStrictMarkerEntry(context, normalizedName, rawName, zis);
                    if (matched) markerEntries++;
                }

                // --- Fallback matching (basename-based, works with any directory structure) ---
                if (!matched) {
                    String basename = getBasename(normalizedName);

                    if (basename.equals("manifest.properties")) {
                        matched = handleManifestEntry(zis);
                        if (matched) manifestCount++;
                    }

                    if (!matched && isConfigBasename(basename)) {
                        matched = handleConfigBasenameEntry(basename, zis);
                        if (matched) configEntries++;
                    }

                    if (!matched && "bootstrap_variant".equals(basename)) {
                        matched = handleMarkerBasenameEntry(context, zis);
                        if (matched) markerEntries++;
                    }

                    if (!matched && basename.endsWith(".xml")) {
                        matched = tryImportSharedPreferencesXml(context, basename, zis);
                        if (matched) preferenceEntries++;
                    }
                }

                if (!matched) {
                    unrecognizedEntries++;
                    if (sampleUnrecognized.size() < 5) {
                        sampleUnrecognized.add(rawName);
                    }
                }

                zis.closeEntry();
            }

            if (manifestCount == 0) {
                Logger.logWarn(LOG_TAG, "Backup file is missing manifest.properties; proceeding without format check");
            }

            if (preferenceEntries == 0 && configEntries == 0 && markerEntries == 0) {
                StringBuilder msg = new StringBuilder("Backup file contained no recognizable Termux settings data"
                    + " (unrecognized entries: " + unrecognizedEntries + ")");
                if (!sampleUnrecognized.isEmpty()) {
                    msg.append(". Sample entries: ");
                    for (int i = 0; i < sampleUnrecognized.size(); i++) {
                        if (i > 0) msg.append(", ");
                        msg.append('"').append(sampleUnrecognized.get(i)).append('"');
                    }
                }
                listener.onResult(new Error(msg.toString()));
                return;
            }

            Logger.logInfo(LOG_TAG, "Settings restore complete: "
                + preferenceEntries + " preference files, "
                + configEntries + " config files, "
                + markerEntries + " markers"
                + (unrecognizedEntries > 0 ? ", " + unrecognizedEntries + " unrecognized entries" : ""));
            listener.onResult(null);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Settings import failed", e);
            listener.onResult(new Error(e.getMessage(), e));
        }
    }

    // ------------------------------------------------------------------
    // Per-entry handlers (strict format)
    // ------------------------------------------------------------------

    private static boolean handleManifestEntry(ZipInputStream zis) throws IOException {
        Properties manifest = new Properties();
        manifest.load(zis);
        String version = manifest.getProperty("format.version");
        if (!BACKUP_FORMAT_VERSION.equals(version)) {
            Logger.logWarn(LOG_TAG, "Backup format version mismatch: expected "
                + BACKUP_FORMAT_VERSION + ", got " + version + " — proceeding anyway");
        }
        return true;
    }

    private static boolean handleStrictPrefsEntry(Context context, String normalizedName, String rawName, ZipInputStream zis) throws IOException {
        String basename = normalizedName.substring("preferences/".length());
        if (basename.isEmpty()) {
            Logger.logWarn(LOG_TAG, "Skipping empty preferences entry: \"" + rawName + "\"");
            return false;
        }
        String prefName = basename.endsWith(".properties")
            ? basename.substring(0, basename.length() - ".properties".length())
            : basename;
        SharedPreferences prefs = prefsForName(context, prefName);
        if (prefs == null) {
            Logger.logWarn(LOG_TAG, "No SharedPreferences mapping for entry: \"" + rawName + "\"");
            return false;
        }
        readPrefsEntry(prefs, zis);
        return true;
    }

    private static boolean handleStrictConfigEntry(String normalizedName, String rawName, ZipInputStream zis) throws IOException {
        String fileName = normalizedName.substring("config/".length());
        if (fileName.isEmpty()) {
            Logger.logWarn(LOG_TAG, "Skipping empty config entry: \"" + rawName + "\"");
            return false;
        }
        String destPath = configDestPath(fileName);
        if (destPath == null) {
            Logger.logWarn(LOG_TAG, "Rejected config file entry: \"" + rawName + "\"");
            return false;
        }
        writeFileFromStream(destPath, zis);
        return true;
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
    }

    /** Read a single-line bootstrap_variant marker and write it; null empty message means success. */
    private static boolean writeVariantFromEntry(Context context, ZipInputStream zis, String emptyMessage) throws IOException {
        String variant = readLine(zis);
        if (variant == null || variant.isEmpty()) {
            Logger.logWarn(LOG_TAG, emptyMessage);
            return false;
        }
        TermuxBootstrapState.writeVariantMarker(context, variant);
        TermuxBootstrapState.setInstalledVariant(context, variant);
        return true;
    }

    private static boolean handleStrictMarkerEntry(Context context, String normalizedName, String rawName, ZipInputStream zis) throws IOException {
        String fileName = normalizedName.substring("marker/".length());
        if (!"bootstrap_variant".equals(fileName)) {
            Logger.logWarn(LOG_TAG, "Unrecognized marker entry: \"" + rawName + "\"");
            return false;
        }
        return writeVariantFromEntry(context, zis, "Empty bootstrap_variant marker in backup");
    }

    // ------------------------------------------------------------------
    // Fallback helpers
    // ------------------------------------------------------------------

    private static String getBasename(String path) {
        int idx = path.lastIndexOf('/');
        return idx >= 0 ? path.substring(idx + 1) : path;
    }

    private static boolean isConfigBasename(String basename) {
        return "colors.properties".equals(basename)
            || "colors.light.properties".equals(basename)
            || "colors.dark.properties".equals(basename)
            || "font.ttf".equals(basename)
            || "termux.float.properties".equals(basename);
    }

    private static boolean handleConfigBasenameEntry(String basename, ZipInputStream zis) throws IOException {
        String destPath = configDestPath(basename);
        if (destPath == null) return false;
        writeFileFromStream(destPath, zis);
        return true;
    }

    private static boolean handleMarkerBasenameEntry(Context context, ZipInputStream zis) throws IOException {
        return writeVariantFromEntry(context, zis, "Empty bootstrap_variant marker in backup (fallback)");
    }

    /**
     * Try to parse a ZIP entry as an Android SharedPreferences XML file and import it.
     * Supports the standard Android format: {@code <map>} root with typed tags
     * ({@code <string>}, {@code <int>}, {@code <boolean>}, {@code <float>},
     * {@code <long>}, {@code <set>}).
     * @return true if the entry was successfully parsed and imported
     */
    private static boolean tryImportSharedPreferencesXml(Context context, String basename, ZipInputStream zis) {
        String prefName = basename.endsWith(".xml")
            ? basename.substring(0, basename.length() - ".xml".length())
            : basename;
        SharedPreferences prefs = prefsForName(context, prefName);
        if (prefs == null) {
            prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
        }

        byte[] data;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            copyStream(zis, baos);
            data = baos.toByteArray();
        } catch (IOException e) {
            Logger.logWarn(LOG_TAG, "Failed to read XML entry \"" + basename + "\": " + e.getMessage());
            return false;
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(bais, "UTF-8");

            int eventType = parser.getEventType();
            while (eventType != XmlPullParser.START_TAG && eventType != XmlPullParser.END_DOCUMENT) {
                eventType = parser.next();
            }
            if (eventType == XmlPullParser.END_DOCUMENT) {
                Logger.logWarn(LOG_TAG, "Empty XML file: \"" + basename + "\"");
                return false;
            }
            if (!"map".equals(parser.getName())) {
                Logger.logWarn(LOG_TAG, "XML \"" + basename + "\" root is <" + parser.getName() + ">, not <map>");
                return false;
            }

            SharedPreferences.Editor editor = prefs.edit();
            parseXmlMap(parser, editor);
            editor.commit();
            return true;
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to parse SharedPreferences XML \"" + basename + "\": " + e.getMessage());
            return false;
        }
    }

    private static void parseXmlMap(XmlPullParser parser, SharedPreferences.Editor editor) throws Exception {
        int depth = 1;
        while (depth > 0) {
            int eventType = parser.next();
            switch (eventType) {
                case XmlPullParser.START_TAG: {
                    String tag = parser.getName();
                    String key = parser.getAttributeValue(null, "name");
                    if (key == null) break;
                    switch (tag) {
                        case "string":
                            editor.putString(key, parser.nextText());
                            break;
                        case "int":
                            editor.putInt(key, Integer.parseInt(parser.getAttributeValue(null, "value")));
                            break;
                        case "boolean":
                            editor.putBoolean(key, Boolean.parseBoolean(parser.getAttributeValue(null, "value")));
                            break;
                        case "long":
                            editor.putLong(key, Long.parseLong(parser.getAttributeValue(null, "value")));
                            break;
                        case "float":
                            editor.putFloat(key, Float.parseFloat(parser.getAttributeValue(null, "value")));
                            break;
                        case "set": {
                            Set<String> set = new LinkedHashSet<>();
                            int setDepth = 1;
                            while (setDepth > 0) {
                                int se = parser.next();
                                if (se == XmlPullParser.START_TAG && "string".equals(parser.getName())) {
                                    set.add(parser.nextText());
                                } else if (se == XmlPullParser.END_TAG && "set".equals(parser.getName())) {
                                    setDepth--;
                                }
                            }
                            editor.putStringSet(key, set);
                            break;
                        }
                    }
                    break;
                }
                case XmlPullParser.END_TAG: {
                    if ("map".equals(parser.getName())) depth--;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private static void writeManifest(ZipOutputStream zos) throws IOException {
        zos.putNextEntry(new ZipEntry("manifest.properties"));
        try {
            Properties p = new Properties();
            p.setProperty("format.version", BACKUP_FORMAT_VERSION);
            p.store(zos, null);
        } finally {
            zos.closeEntry();
        }
    }

    private static void writePrefsEntry(ZipOutputStream zos, String name,
                                        SharedPreferences prefs, String[] excludeKeys) throws IOException {
        String entryName = "preferences/" + name + ".properties";
        zos.putNextEntry(new ZipEntry(entryName));
        try {
            Properties props = new Properties();
            Map<String, ?> all = prefs.getAll();
            for (Map.Entry<String, ?> e : all.entrySet()) {
                String key = e.getKey();
                if (excludeKeys != null && contains(excludeKeys, key)) continue;
                Object val = e.getValue();
                if (val == null) continue;
                String suffix = typeSuffix(val);
                props.setProperty(key + ":" + suffix, valueToString(val));
            }
            props.store(zos, null);
        } finally {
            zos.closeEntry();
        }
    }

    private static void readPrefsEntry(SharedPreferences prefs, InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        SharedPreferences.Editor editor = prefs.edit();
        for (String fullKey : props.stringPropertyNames()) {
            int colonIdx = fullKey.lastIndexOf(':');
            if (colonIdx < 0) continue;
            String key = fullKey.substring(0, colonIdx);
            String type = fullKey.substring(colonIdx + 1);
            String value = props.getProperty(fullKey);
            putTypedValue(editor, key, type, value);
        }
        editor.commit();
    }

    @SuppressWarnings("unchecked")
    private static void putTypedValue(SharedPreferences.Editor editor, String key, String type, String value) {
        try {
            switch (type) {
                case "bool":
                    editor.putBoolean(key, Boolean.parseBoolean(value));
                    break;
                case "int":
                    editor.putInt(key, Integer.parseInt(value));
                    break;
                case "long":
                    editor.putLong(key, Long.parseLong(value));
                    break;
                case "float":
                    editor.putFloat(key, Float.parseFloat(value));
                    break;
                case "string":
                    editor.putString(key, value);
                    break;
                case "stringset":
                    Set<String> set = new LinkedHashSet<>();
                    StringBuilder buf = new StringBuilder();
                    boolean escaped = false;
                    for (int i = 0; i < value.length(); i++) {
                        char c = value.charAt(i);
                        if (escaped) {
                            buf.append(c);
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if (c == ',') {
                            set.add(buf.toString());
                            buf.setLength(0);
                        } else {
                            buf.append(c);
                        }
                    }
                    set.add(buf.toString());
                    editor.putStringSet(key, set);
                    break;
            }
        } catch (Exception ex) {
            Logger.logError(LOG_TAG, "Failed to restore key '" + key + "' (type=" + type + "): " + ex.getMessage());
        }
    }

    private static String typeSuffix(Object val) {
        if (val instanceof Boolean) return "bool";
        if (val instanceof Integer) return "int";
        if (val instanceof Long) return "long";
        if (val instanceof Float) return "float";
        if (val instanceof String) return "string";
        if (val instanceof Set) return "stringset";
        return "string";
    }

    @SuppressWarnings("unchecked")
    private static String valueToString(Object val) {
        if (val instanceof Set) {
            Set<String> set = (Set<String>) val;
            StringBuilder sb = new StringBuilder();
            for (String s : set) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s.replace("\\", "\\\\").replace(",", "\\,"));
            }
            return sb.toString();
        }
        return String.valueOf(val);
    }

    private static void writeFileEntry(ZipOutputStream zos, String entryName, String filePath) throws IOException {
        File f = new File(filePath);
        if (!f.isFile()) return;
        zos.putNextEntry(new ZipEntry(entryName));
        try (FileInputStream fis = new FileInputStream(f)) {
            copyStream(fis, zos);
        } finally {
            zos.closeEntry();
        }
    }

    private static void writeFileFromStream(String destPath, InputStream in) throws IOException {
        File f = new File(destPath);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            copyStream(in, fos);
        }
    }

    /**
     * Normalize a ZIP entry name by removing leading {@code ./} and {@code /} prefixes,
     * collapsing duplicate slashes and {@code /./} segments, and rejecting path traversal.
     * @return the normalized name, or {@code null} if the name is unsafe.
     */
    private static String normalizeZipEntryName(String name) {
        if (name == null) return null;
        name = name.replace('\\', '/');
        while (name.startsWith("/")) name = name.substring(1);
        while (name.startsWith("./")) name = name.substring(2);
        if (name.equals("..") || name.startsWith("../")
            || name.contains("/../") || name.endsWith("/..")) return null;
        String[] parts = name.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            if (b != '\r') baos.write(b);
        }
        String s = baos.toString("UTF-8").trim();
        return s.isEmpty() ? null : s;
    }

    private static SharedPreferences prefsForName(Context context, String name) {
        switch (name) {
            case PREFERENCES_APP:
                return context.getSharedPreferences(
                    TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE);
            case PREFERENCES_UI:
                return context.getSharedPreferences("termux_prefs", Context.MODE_PRIVATE);
            case PREFERENCES_BOOTSTRAP:
                return context.getSharedPreferences("termux_bootstrap_state", Context.MODE_PRIVATE);
            default:
                return null;
        }
    }

    private static String configDestPath(String fileName) {
        switch (fileName) {
            case "colors.properties":
                return TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE_PATH;
            case "colors.light.properties":
                return TermuxConstants.TERMUX_COLOR_LIGHT_PROPERTIES_FILE_PATH;
            case "colors.dark.properties":
                return TermuxConstants.TERMUX_COLOR_DARK_PROPERTIES_FILE_PATH;
            case "font.ttf":
                return TermuxConstants.TERMUX_FONT_FILE_PATH;
            case "termux.float.properties":
                return TermuxConstants.TERMUX_FLOAT_PROPERTIES_PRIMARY_FILE_PATH;
            default:
                return null;
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    private static void report(ProgressCallback cb, String stage) {
        if (cb != null) cb.onProgress(stage);
    }

    private static boolean contains(String[] arr, String key) {
        for (String s : arr) if (s.equals(key)) return true;
        return false;
    }
}

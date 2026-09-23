package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utilities for selecting a terminal font from the assets of the installed
 * <a href="https://github.com/termux/termux-styling">Termux:Style</a> plugin.
 * <p>
 * Fonts are stored as {@code .ttf} files under {@code assets/fonts/} in the styling APK.
 * The selected font is copied to {@code ~/.termux/font.ttf}, which the terminal loads
 * on the next styling reload. The sentinel {@link #FONT_DEFAULT} deletes the file, falling
 * back to {@link Typeface#MONOSPACE}.
 *
 * @see ColorSchemeUtils
 */
public final class FontUtils {

    private static final String LOG_TAG = "FontUtils";

    /** Termux:Style stores its fonts as {@code *.ttf} files under this asset folder. */
    private static final String STYLING_FONTS_ASSET_DIR = "fonts";

    /** Sentinel value meaning "reset to the default terminal font (Monospace)". */
    public static final String FONT_DEFAULT = "Default";

    private FontUtils() {}

    /**
     * List the font files shipped by the installed Termux:Style app, read directly from its
     * assets (no duplication, always current). The returned array is the asset file names (e.g.
     * {@code JetBrains-Mono.ttf}) sorted alphabetically, with {@link #FONT_DEFAULT} first.
     *
     * @return The font file names, or {@code null} if Termux:Style is not installed / has no assets.
     */
    public static String[] listStylingFonts(Context context) {
        Context stylingContext = ColorSchemeUtils.getStylingContext(context);
        if (stylingContext == null) return null;
        try {
            String[] files = stylingContext.getAssets().list(STYLING_FONTS_ASSET_DIR);
            if (files == null) return null;
            List<String> fonts = new ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".ttf")) fonts.add(f);
            }
            if (fonts.isEmpty()) return null;
            Collections.sort(fonts, String.CASE_INSENSITIVE_ORDER);
            fonts.add(0, FONT_DEFAULT);
            return fonts.toArray(new String[0]);
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to list Termux:Style font assets: " + e.getMessage());
            return null;
        }
    }

    /**
     * Turn a Termux:Style font asset file name into a human-readable label:
     * strip the extension, replace hyphens with spaces, and title-case words.
     */
    public static String fontDisplayName(String fileName) {
        if (FONT_DEFAULT.equals(fileName)) return FONT_DEFAULT;
        return ColorSchemeUtils.styleAssetDisplayName(fileName);
    }

    /**
     * Show the font picker dialog: lists the installed Termux:Style fonts (each drawn in itself,
     * {@link #FONT_DEFAULT} in {@link Typeface#MONOSPACE}), applies the selection to
     * {@code ~/.termux/font.ttf} and invokes {@code onApplied} for a live restyle.
     *
     * @param notInstalledMessage Message shown if Termux:Style is not installed.
     * @param onApplied Run after a font is applied; may be {@code null}.
     */
    public static void showFontDialog(Context context,
                                      CharSequence notInstalledMessage,
                                      Runnable onApplied) {
        final String[] fonts = listStylingFonts(context);
        final Context dialogContext = ColorSchemeUtils.createDialogContext(context);
        if (fonts == null) {
            ColorSchemeUtils.showNotInstalledDialog(dialogContext, notInstalledMessage);
            return;
        }

        // The framework's plain item rows cannot carry a per-row typeface, so the picker supplies
        // its own adapter; the rows it inflates are a single TextView each.
        final FontPreviewAdapter adapter = new FontPreviewAdapter(dialogContext, fonts);

        AlertDialog d = ColorSchemeUtils.showListDialog(dialogContext,
            dialogContext.getString(com.termux.shared.R.string.title_select_font),
            adapter, (dialog, which) -> {
                applyStylingFont(context, fonts[which]);
                if (onApplied != null) onApplied.run();
                dialog.dismiss();
            });

        // Bounded like the color-scheme picker's: a long list would draw its last row past the
        // bottom edge, unreachable.
        final ListView list = d.getListView();
        if (list != null) {
            PickerDialogList.boundHeightToWindow(list);
        }
    }

    /**
     * Load a Termux:Style font as a {@link Typeface}, so it can be previewed before it is applied.
     *
     * @param fileName The font asset file name, as returned by {@link #listStylingFonts}.
     * @return the typeface, or {@code null} when Termux:Style is missing or the file cannot be
     *         parsed вЂ” the caller should then fall back to {@link Typeface#MONOSPACE}.
     */
    @Nullable
    public static Typeface loadStylingTypeface(@Nullable Context context, @NonNull String fileName) {
        final Context stylingContext = ColorSchemeUtils.getStylingContext(context);
        if (stylingContext == null) return null;
        try {
            return Typeface.createFromAsset(stylingContext.getAssets(),
                    STYLING_FONTS_ASSET_DIR + "/" + fileName);
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to load Termux:Style font \"" + fileName + "\": " + e.getMessage());
            return null;
        }
    }

    /**
     * Copy the selected Termux:Style font to {@code ~/.termux/font.ttf} (or delete it for
     * {@link #FONT_DEFAULT} so the terminal falls back to {@link Typeface#MONOSPACE}).
     *
     * @param context  Any context (used to reach the styling package).
     * @param fileName The font asset file name to apply, or {@link #FONT_DEFAULT} to reset.
     * @return {@code true} if the font was successfully applied.
     */
    public static boolean applyStylingFont(Context context, String fileName) {
        File fontFile = TermuxConstants.TERMUX_FONT_FILE;

        if (FONT_DEFAULT.equals(fileName)) {
            if (fontFile.exists()) fontFile.delete();
            return true;
        }

        Context stylingContext = ColorSchemeUtils.getStylingContext(context);
        if (stylingContext == null) return false;

        // Ensure the ~/.termux directory exists.
        File fontDir = fontFile.getParentFile();
        if (fontDir != null && !fontDir.exists()) {
            fontDir.mkdirs();
        }

        try (InputStream in = stylingContext.getAssets()
                .open(STYLING_FONTS_ASSET_DIR + "/" + fileName);
             FileOutputStream out = new FileOutputStream(fontFile)) {
            ColorSchemeUtils.copyStream(in, out);
            return true;
        } catch (IOException e) {
            Logger.logError(LOG_TAG, "Failed to apply Termux:Style font \"" + fileName
                + "\": " + e.getMessage());
            return false;
        }
    }
}

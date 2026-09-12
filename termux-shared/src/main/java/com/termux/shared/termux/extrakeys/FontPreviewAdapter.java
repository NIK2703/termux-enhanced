package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.shared.R;

import java.util.HashMap;
import java.util.Map;

/**
 * Rows of the font picker: one entry per font shipped by Termux:Style, each drawn <b>in that
 * font</b>, so the list is a set of samples rather than a list of names.
 *
 * <p>The typefaces are loaded lazily — a row only pays for its own font the first time it is shown —
 * and memoized in the adapter, which means they are released together with the dialog. That is
 * deliberate: a {@link Typeface} keeps its font data alive natively, so a process-wide cache of
 * every installed font would hold several megabytes for a dialog that is opened a handful of times.
 */
public final class FontPreviewAdapter extends BaseAdapter {

    private final Context mContext;
    private final String[] mFontNames;
    private final String[] mLabels;
    private final LayoutInflater mInflater;

    /** Loaded typefaces by asset file name; lives exactly as long as the dialog. */
    private final Map<String, Typeface> mTypefaces = new HashMap<>();

    /**
     * @param context   a context whose theme should inflate the rows (the dialog context).
     * @param fontNames the entries, exactly as {@link FontUtils#listStylingFonts} returned them.
     */
    public FontPreviewAdapter(@NonNull Context context, @NonNull String[] fontNames) {
        mContext = context;
        mFontNames = fontNames;

        // Labels are pure formatting of the entry name, so they are built once instead of on every
        // bind — the rows are re-bound on every scroll.
        mLabels = new String[fontNames.length];
        for (int i = 0; i < fontNames.length; i++) {
            mLabels[i] = FontUtils.fontDisplayName(fontNames[i]);
        }

        mInflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return mFontNames.length;
    }

    @Override
    public Object getItem(int position) {
        return mFontNames[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final TextView row;
        if (convertView == null) {
            row = (TextView) mInflater.inflate(R.layout.termux_shared_dialog_font_item, parent, false);
        } else {
            row = (TextView) convertView;
        }

        row.setText(mLabels[position]);
        row.setTypeface(typefaceFor(position));
        return row;
    }

    /** The typeface of an entry: its own font, or monospace when it cannot be loaded. */
    @NonNull
    private Typeface typefaceFor(int position) {
        final String name = mFontNames[position];
        if (FontUtils.FONT_DEFAULT.equals(name)) return Typeface.MONOSPACE;

        final Typeface cached = mTypefaces.get(name);
        if (cached != null) return cached;

        Typeface typeface = FontUtils.loadStylingTypeface(mContext, name);
        if (typeface == null) {
            // A font that cannot be parsed is previewed as what the terminal would actually fall
            // back to, rather than as the theme's default face.
            typeface = Typeface.MONOSPACE;
        }
        mTypefaces.put(name, typeface);
        return typeface;
    }
}

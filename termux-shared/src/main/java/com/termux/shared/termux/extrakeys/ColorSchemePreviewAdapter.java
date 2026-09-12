package com.termux.shared.termux.extrakeys;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;

import com.termux.shared.R;

/**
 * Rows of the color-scheme picker: one entry per available scheme, each painted in <b>its own</b>
 * terminal colors — the row background is that scheme's terminal background, the label is its
 * terminal foreground — with its 16 ANSI colors shown as a two-row by eight-column swatch.
 *
 * <p>The point is that the list becomes a strip of live previews: you pick a scheme by looking at
 * it rather than by reading its name.
 *
 * <p>A custom adapter replaces the framework's single-choice rows (a framework row cannot carry a
 * per-row background), so the adapter also owns the single-choice state: it checks the radio of the
 * applied entry. The radio itself is the framework's own indicator, drawn by the row layout from
 * {@code ?android:attr/listChoiceIndicatorSingle} and tinted to the row's own foreground.
 */
public final class ColorSchemePreviewAdapter extends BaseAdapter {

    /** How far the pressed background is pulled from the scheme background towards its foreground. */
    private static final float PRESSED_BLEND = 0.18f;

    private final Context mContext;
    private final boolean mIsNight;
    private final String[] mSchemeNames;
    private final String[] mLabels;
    private final LayoutInflater mInflater;

    private int mCheckedPosition;

    /**
     * @param context      a context whose theme should inflate the rows (the dialog context).
     * @param isNight      which theme's terminal colors are being picked.
     * @param schemeNames  the entries, exactly as {@link ColorSchemeUtils#listStylingColorSchemes}
     *                     returned them; they are also what gets persisted on selection.
     * @param checkedPosition index of the entry currently applied, or {@code -1} for none.
     */
    public ColorSchemePreviewAdapter(@NonNull Context context, boolean isNight,
                                     @NonNull String[] schemeNames, int checkedPosition) {
        mContext = context;
        mIsNight = isNight;
        mSchemeNames = schemeNames;
        mCheckedPosition = checkedPosition;

        // Labels are pure formatting of the entry name, so they are built once instead of on every
        // bind — the rows are re-bound on every scroll.
        mLabels = new String[schemeNames.length];
        for (int i = 0; i < schemeNames.length; i++) {
            mLabels[i] = ColorSchemeUtils.schemeDisplayName(schemeNames[i]);
        }

        mInflater = LayoutInflater.from(context);
    }

    /** Mark a different entry as the applied one. */
    public void setCheckedPosition(int position) {
        if (mCheckedPosition == position) return;
        mCheckedPosition = position;
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return mSchemeNames.length;
    }

    @Override
    public Object getItem(int position) {
        return mSchemeNames[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        final RowHolder holder;
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.termux_shared_dialog_color_scheme_item, parent, false);
            holder = new RowHolder(convertView);
            convertView.setTag(holder);
        } else {
            holder = (RowHolder) convertView.getTag();
        }

        bind(holder, position);
        return convertView;
    }

    private void bind(@NonNull RowHolder holder, int position) {
        final ColorSchemePreview preview =
                ColorSchemePreview.resolve(mContext, mIsNight, mSchemeNames[position]);

        // The row's background is repainted in place rather than replaced: it stays the same
        // drawable for as long as the row lives, so the bounds the framework gave it when the row
        // was framed are never lost. A freshly created drawable starts with empty bounds and the
        // framework only re-bounds it when the *view* is re-framed, which a recycled row that keeps
        // its frame is not — that is what left rows painted only up to the previous drawable's
        // extent on the first scroll.
        holder.pressed.setColor(
                ColorSchemePreview.blend(preview.background, preview.foreground, PRESSED_BLEND));
        holder.normal.setColor(preview.background);

        // Belt and braces for the same reason: if the row already has a size, bound the background
        // now instead of waiting for a frame change that may never come.
        final View row = holder.itemView;
        if (row.getWidth() > 0) {
            holder.background.setBounds(0, 0, row.getWidth(), row.getHeight());
        }

        holder.name.setText(mLabels[position]);
        // Label and radio share the scheme's terminal foreground. The radio is the row's own
        // compound drawable, so it is tinted rather than replaced: on schemes whose background sits
        // close to the dialog's surface an untinted indicator simply blends into the row.
        holder.name.setTextColor(preview.foreground);
        TextViewCompat.setCompoundDrawableTintList(
                holder.name, ColorStateList.valueOf(preview.foreground));
        holder.name.setChecked(position == mCheckedPosition);
        holder.palette.setPalette(preview.palette());
        holder.palette.setFrameColor(preview.frame);
    }

    private static final class RowHolder {
        final View itemView;
        final CheckedTextView name;
        final ColorSchemePaletteView palette;

        /**
         * The row's background, built once and then only recolored. Kept per row rather than
         * shared between rows: a {@link android.graphics.drawable.Drawable}'s bounds belong to the
         * drawable, so one instance handed to several rows would be bounded by whichever row
         * framed it last.
         */
        final ColorDrawable pressed = new ColorDrawable();
        final ColorDrawable normal = new ColorDrawable();
        final StateListDrawable background = new StateListDrawable();

        RowHolder(@NonNull View row) {
            itemView = row;
            name = row.findViewById(R.id.color_scheme_name);
            palette = row.findViewById(R.id.color_scheme_palette);

            background.addState(new int[]{android.R.attr.state_pressed}, pressed);
            background.addState(new int[]{}, normal);
            row.setBackground(background);
        }
    }
}

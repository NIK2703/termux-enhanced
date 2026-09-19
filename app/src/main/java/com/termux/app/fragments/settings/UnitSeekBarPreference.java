package com.termux.app.fragments.settings;

import android.content.Context;
import android.content.res.TypedArray;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

import com.termux.R;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link SeekBarPreference} that shows a unit after the numeric value — {@code 42 px},
 * {@code 5%}, {@code 12 dp} — configured per preference with {@code app:valueUnit} in the XML, and
 * optionally rescaled with {@code app:valueDivisor} (the extra-keys button margin slider counts
 * tenths of a dp, so {@code app:valueDivisor="10"} renders 15 as {@code 1.5 dp}).
 *
 * <p>Units matter here because the sliders on the Display screen are not all the same kind of
 * number: the terminal font size and the blur radius are raw pixels, the transparency sliders are
 * percentages and the terminal margins are dp. A bare {@code 12} next to a slider says nothing.
 *
 * <p><b>Why a {@link TextWatcher}.</b> {@code SeekBarPreference} owns the label and rewrites it
 * itself from {@code updateLabelValue(int)} — a package-private method, so a subclass cannot
 * override it, and the progress callback is a private inner listener that cannot be intercepted
 * either. The label view ({@code androidx.preference.R.id.seekbar_value}) is therefore the only
 * hook that sees <em>every</em> update: a drag ({@code onProgressChanged}), a programmatic
 * {@code setValue} from a fragment, {@code setMin}/{@code setMax}, and every rebind of the recycled
 * row. The watcher normalises whatever text lands there back into "number + unit"; it never touches
 * the preference's own value, which is what {@code getValue()} and the change listeners use.
 *
 * <p>Rows are recycled by {@code RecyclerView}, so the same label view is bound to different
 * preferences over time; the watcher installed by the previous bind is detached first (its identity
 * is parked in the {@code seekbar_value_watcher_tag} view tag) so two preferences can never fight
 * over one label.
 *
 * <p>Without {@code app:valueUnit} and {@code app:valueDivisor} the class behaves exactly like
 * {@link SeekBarPreference}.
 *
 * <p>Referenced from {@code res/xml/*.xml}, hence {@code @Keep}.
 */
@Keep
public class UnitSeekBarPreference extends SeekBarPreference implements TextWatcher {

    /** The number {@code SeekBarPreference} writes into the label (its value is always an int). */
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+");

    /** Unit suffix, e.g. {@code "px"}; empty means "show the bare number". */
    private final String mUnit;

    /** Divisor applied to the value before display, e.g. 10 for tenths of a dp. 1 = no scaling. */
    private final int mDivisor;

    /** The label currently bound to this preference (recycled view — updated on every bind). */
    private TextView mValueView;

    /** Re-entrancy guard for the rewrite triggered by our own {@link TextView#setText}. */
    private boolean mRewriting;

    public UnitSeekBarPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUnit = readUnit(context, attrs);
        mDivisor = readDivisor(context, attrs);
    }

    public UnitSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mUnit = readUnit(context, attrs);
        mDivisor = readDivisor(context, attrs);
    }

    public UnitSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr,
                                 int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mUnit = readUnit(context, attrs);
        mDivisor = readDivisor(context, attrs);
    }

    private static String readUnit(Context context, AttributeSet attrs) {
        if (attrs == null) return "";
        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{R.attr.valueUnit});
        try {
            String unit = a.getString(0);
            return unit == null ? "" : unit;
        } finally {
            a.recycle();
        }
    }

    private static int readDivisor(Context context, AttributeSet attrs) {
        if (attrs == null) return 1;
        TypedArray a = context.obtainStyledAttributes(attrs, new int[]{R.attr.valueDivisor});
        try {
            return Math.max(1, a.getInt(0, 1));
        } finally {
            a.recycle();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        TextView valueView = (TextView) holder.findViewById(androidx.preference.R.id.seekbar_value);
        if (valueView == null) return;

        Object previous = valueView.getTag(R.id.seekbar_value_watcher_tag);
        if (previous instanceof TextWatcher) {
            valueView.removeTextChangedListener((TextWatcher) previous);
        }
        valueView.addTextChangedListener(this);
        valueView.setTag(R.id.seekbar_value_watcher_tag, this);

        // super.onBindViewHolder() has just written the bare number into the label.
        mValueView = valueView;
        rewrite(valueView.getText());
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) { }

    @Override
    public void afterTextChanged(Editable s) {
        rewrite(s);
    }

    private void rewrite(CharSequence text) {
        if (mRewriting || mValueView == null) return;

        String withUnit = withUnit(text.toString());
        if (withUnit.contentEquals(text)) return;

        mRewriting = true;
        try {
            mValueView.setText(withUnit);
        } finally {
            mRewriting = false;
        }
    }

    /**
     * "42" + "px" -> "42 px"; "42" + "%" -> "42%"; "15" + divisor 10 + "dp" -> "1.5 dp". Idempotent,
     * so it is safe to run on a label that already carries the unit (and on a label the preference
     * has just reset to a bare number).
     */
    private String withUnit(String text) {
        if (mUnit.isEmpty() && mDivisor <= 1) return text;

        Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return text;

        String number = matcher.group();
        if (mDivisor > 1) {
            // BigDecimal, not String.format: no locale-dependent decimal separator, and
            // stripTrailingZeros() gives "2" for 20/10 and "1.5" for 15/10. The MathContext only
            // matters for a divisor that is not a power of ten, where an exact division would
            // otherwise throw ArithmeticException in the middle of a bind.
            number = new BigDecimal(number).divide(BigDecimal.valueOf(mDivisor), MathContext.DECIMAL64)
                .stripTrailingZeros().toPlainString();
        }
        if (mUnit.isEmpty()) return number;

        // A word-like unit reads as a separate token ("12 dp"); a symbol unit hugs the number ("5%").
        String separator = Character.isLetter(mUnit.charAt(0)) ? " " : "";
        return number + separator + mUnit;
    }
}

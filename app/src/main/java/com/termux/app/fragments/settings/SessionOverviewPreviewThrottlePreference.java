package com.termux.app.fragments.settings;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SeekBarPreference;

import com.termux.R;
import com.termux.app.terminal.SessionOverviewPreviewThrottle;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seek bar for the overview preview FPS cap.
 *
 * <p>The seek bar value is the slider position: 0 is off, 1 is 30 FPS and 30 is 1 FPS. The
 * {@link SeekBarPreference} label therefore shows the raw position; this class rewrites it to the
 * mapped rate with a unit ({@code 30 fps} / {@code 30 к/с}) or {@code Off}.</p>
 */
@Keep
public class SessionOverviewPreviewThrottlePreference extends SeekBarPreference
        implements TextWatcher {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+");

    private TextView mValueView;
    private boolean mRewriting;

    public SessionOverviewPreviewThrottlePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final TextView valueView = (TextView) holder.findViewById(androidx.preference.R.id.seekbar_value);
        if (valueView == null) return;

        final Object previous = valueView.getTag(R.id.seekbar_value_watcher_tag);
        if (previous instanceof TextWatcher) {
            valueView.removeTextChangedListener((TextWatcher) previous);
        }
        valueView.addTextChangedListener(this);
        valueView.setTag(R.id.seekbar_value_watcher_tag, this);

        // super.onBindViewHolder() has just written the bare slider position into the label.
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

        final Matcher matcher = NUMBER.matcher(text);
        if (!matcher.find()) return;

        final int position;
        try {
            position = Integer.parseInt(matcher.group());
        } catch (NumberFormatException e) {
            return;
        }

        final String label = format(position);
        if (label.contentEquals(text)) return;

        mRewriting = true;
        try {
            mValueView.setText(label);
        } finally {
            mRewriting = false;
        }
    }

    private String format(int position) {
        final Context context = getContext();
        final int fps = SessionOverviewPreviewThrottle.fpsForSliderPosition(position);
        return fps == 0
            ? context.getString(R.string.session_overview_preview_throttle_off_value)
            : context.getString(R.string.session_overview_preview_throttle_value, fps);
    }
}

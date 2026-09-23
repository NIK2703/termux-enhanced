package com.termux.shared.models;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

/**
 * A custom action button shown on {@link com.termux.shared.activities.ReportActivity}.
 *
 * <p>The label is supplied by the host app (see
 * {@link com.termux.shared.activities.ReportActivity.ReportActionHost}), so it is localized by
 * whoever contributes the action and the report screen itself needs to know nothing about it.
 *
 * <p>The {@code @Keep} annotation is necessary to prevent the field from being removed by proguard
 * when the app is compiled, even if it is kept during library compilation.
 */
@Keep
public class ReportAction {

    /** Unique id of the action, passed back to the host when its button is clicked. */
    @NonNull public final String id;

    /** The label shown on the button. Must already be localized by the host. */
    @NonNull public final String title;

    public ReportAction(@NonNull String id, @NonNull String title) {
        this.id = id;
        this.title = title;
    }

    @NonNull
    @Override
    public String toString() {
        return "ReportAction{id=" + id + ", title=" + title + "}";
    }

}

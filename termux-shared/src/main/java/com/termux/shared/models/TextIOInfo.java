package com.termux.shared.models;

import android.graphics.Typeface;

import androidx.annotation.Keep;

import com.termux.shared.activities.TextIOActivity;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;

import java.io.Serializable;

/**
 * An object that stored info for {@link TextIOActivity}.
 * Max text limit is 95KB to prevent TransactionTooLargeException as per
 * {@link DataUtils#TRANSACTION_SIZE_LIMIT_IN_BYTES}. Larger size can be supported for in-app
 * transactions by storing {@link TextIOInfo} as a serialized object in a file like
 * {@link com.termux.shared.activities.ReportActivity} does.
 */
public class TextIOInfo implements Serializable {

    /**
     * Explicitly define `serialVersionUID` to prevent exceptions on deserialization.
     *
     * Like when calling `Bundle.getSerializable()` on Android.
     * `android.os.BadParcelableException: Parcelable encountered IOException reading a Serializable object` (name = <class_name>)
     * `java.io.InvalidClassException: <class_name>; local class incompatible`
     *
     * The `@Keep` annotation is necessary to prevent the field from being removed by proguard when
     * app is compiled, even if its kept during library compilation.
     *
     * **See Also:**
     * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/version.html#a6678
     * - https://docs.oracle.com/javase/8/docs/platform/serialization/spec/class.html#a4100
     */
    @Keep
    private static final long serialVersionUID = 1L;

    public static final int GENERAL_DATA_SIZE_LIMIT_IN_BYTES = 1000;
    public static final int LABEL_SIZE_LIMIT_IN_BYTES = 4000;
    public static final int TEXT_SIZE_LIMIT_IN_BYTES = 100000 - GENERAL_DATA_SIZE_LIMIT_IN_BYTES - LABEL_SIZE_LIMIT_IN_BYTES; // < 100KB

    private String mTitle;

    /** If back button should be shown in {@link android.app.ActionBar}. */
    private boolean mShowBackButtonInActionBar = false;

    private boolean mLabelEnabled = false;
    /**
     * The label of text input set in {@link android.widget.TextView} that can be updated by user.
     * Max allowed length is {@link #LABEL_SIZE_LIMIT_IN_BYTES}.
     */
    private String mLabel;
    /** The text size of label. Defaults to 14sp. */
    private int mLabelSize = 14;
    /** The text color of label. Defaults to the active scheme foreground. */
    private int mLabelColor = ColorSchemeUtils.getSchemeForeground();
    /** The {@link Typeface} family  of label. Defaults to "sans-serif". */
    private String mLabelTypeFaceFamily = "sans-serif";
    /** The {@link Typeface} style  of label. Defaults to {@link Typeface#BOLD}. */
    private int mLabelTypeFaceStyle = Typeface.BOLD;

    /**
     * The text of text input set in {@link android.widget.EditText} that can be updated by user.
     * Max allowed length is {@link #TEXT_SIZE_LIMIT_IN_BYTES}.
     */
    private String mText;
    /** The text size for text. Defaults to 12sp. */
    private int mTextSize = 12;
    /** Length limit for the text. Defaults to {@link #TEXT_SIZE_LIMIT_IN_BYTES}. */
    private int mTextLengthLimit = TEXT_SIZE_LIMIT_IN_BYTES;
    /** The text color of text. Defaults to the active scheme foreground. */
    private int mTextColor = ColorSchemeUtils.getSchemeForeground();
    /** The {@link Typeface} family for text. Defaults to "sans-serif". */
    private String mTextTypeFaceFamily = "sans-serif";
    /** The {@link Typeface} style for text. Defaults to {@link Typeface#NORMAL}. */
    private int mTextTypeFaceStyle = Typeface.NORMAL;
    /** If horizontal scrolling should be enabled for text. */
    private boolean mTextHorizontallyScrolling = false;
    /** If character usage should be enabled for text. */
    private boolean mShowTextCharacterUsage = false;
    /** If editing text should be disabled so that text acts like its in a {@link android.widget.TextView}. */
    private boolean mEditingTextDisabled = false;

    public String getTitle() {
        return mTitle;
    }

    public boolean shouldShowBackButtonInActionBar() {
        return mShowBackButtonInActionBar;
    }

    public boolean isLabelEnabled() {
        return mLabelEnabled;
    }

    public String getLabel() {
        return mLabel;
    }

    public int getLabelSize() {
        return mLabelSize;
    }

    public int getLabelColor() {
        return mLabelColor;
    }

    public String getLabelTypeFaceFamily() {
        return mLabelTypeFaceFamily;
    }

    public int getLabelTypeFaceStyle() {
        return mLabelTypeFaceStyle;
    }

    public String getText() {
        return mText;
    }

    public void setText(String text) {
        mText = DataUtils.getTruncatedCommandOutput(text, TEXT_SIZE_LIMIT_IN_BYTES, true, false, false);
    }

    public int getTextSize() {
        return mTextSize;
    }

    public int getTextLengthLimit() {
        return mTextLengthLimit;
    }

    public int getTextColor() {
        return mTextColor;
    }

    public String getTextTypeFaceFamily() {
        return mTextTypeFaceFamily;
    }

    public int getTextTypeFaceStyle() {
        return mTextTypeFaceStyle;
    }

    public boolean isHorizontallyScrollable() {
        return mTextHorizontallyScrolling;
    }

    public boolean shouldShowTextCharacterUsage() {
        return mShowTextCharacterUsage;
    }

    public boolean isEditingTextDisabled() {
        return mEditingTextDisabled;
    }

}

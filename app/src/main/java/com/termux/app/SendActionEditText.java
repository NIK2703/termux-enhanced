package com.termux.app;

import android.content.Context;
import android.os.Build;
import android.text.method.ArrowKeyMovementMethod;
import android.util.AttributeSet;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;

/**
 * Multi-line EditText that still shows a "Send" IME action on the soft keyboard.
 *
 * By default Android forces a newline key (and drops the IME action) for any
 * multi-line text field via {@link EditorInfo#IME_FLAG_NO_ENTER_ACTION}. We undo
 * that here so the keyboard presents a "Send" IME button while the field
 * keeps wrapping long lines and scrolling vertically when filled.
 */
public class SendActionEditText extends EditText {

    public SendActionEditText(Context context) {
        super(context);
        init();
    }

    public SendActionEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SendActionEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // ArrowKeyMovementMethod instead of ScrollingMovementMethod: the latter drops the
        // touch-selection path, so long-press + drag to select stopped working. ArrowKey
        // keeps selection AND still scrolls when the selection is dragged past the edge.
        setMovementMethod(ArrowKeyMovementMethod.getInstance());
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        InputConnection ic = super.onCreateInputConnection(outAttrs);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            outAttrs.imeOptions = (outAttrs.imeOptions & ~EditorInfo.IME_MASK_ACTION)
                    | EditorInfo.IME_ACTION_SEND;
            outAttrs.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        }
        return ic;
    }
}

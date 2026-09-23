package com.termux.shared.errors;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

public class Errno {

    private static final HashMap<String, Errno> map = new HashMap<>();

    public static final String TYPE = "Error";

    public static final Errno ERRNO_SUCCESS = new Errno(TYPE, Activity.RESULT_OK, "Success");
    public static final Errno ERRNO_CANCELLED = new Errno(TYPE, Activity.RESULT_CANCELED, "Cancelled");
    public static final Errno ERRNO_FAILED = new Errno(TYPE, Activity.RESULT_FIRST_USER + 1, "Failed");

    protected final String type;
    protected final int code;
    protected final String message;

    private static final String LOG_TAG = "Errno";

    public Errno(@NonNull final String type, final int code, @NonNull final String message) {
        this.type = type;
        this.code = code;
        this.message = message;
        map.put(type + ":" + code, this);
    }

    @NonNull
    @Override
    public String toString() {
        return "type=" + type + ", code=" + code + ", message=\"" + message + "\"";
    }

    @NonNull
    public String getType() {
        return type;
    }

    public int getCode() {
        return code;
    }

    @NonNull
    public String getMessage() {
        return message;
    }

    public Error getError() {
        return new Error(getType(), getCode(), getMessage());
    }

    public Error getError(Object... args) {
        return getError((List<Throwable>) null, args);
    }

    public Error getError(Throwable throwable, Object... args) {
        if (throwable == null)
            return getError(args);
        else
            return getError(Collections.singletonList(throwable), args);
    }

    public Error getError(List<Throwable> throwablesList, Object... args) {
        try {
            if (throwablesList == null)
                return new Error(getType(), getCode(), String.format(getMessage(), args));
            else
                return new Error(getType(), getCode(), String.format(getMessage(), args), throwablesList);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Exception raised while calling String.format() for error message of errno " + this + " with args" + Arrays.toString(args) + "\n" + e.getMessage());
            // Return unformatted message as a backup
            return new Error(getType(), getCode(), getMessage() + ": " + Arrays.toString(args), throwablesList);
        }
    }

    public boolean equalsErrorTypeAndCode(Error error) {
        if (error == null) return false;
        return type.equals(error.getType()) && code == error.getCode();
    }

}

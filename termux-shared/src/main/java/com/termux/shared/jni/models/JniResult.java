package com.termux.shared.jni.models;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;

/**
 * A class that can be used to return result for JNI calls with support for multiple fields to easily
 * return success and error states.
 *
 * https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/functions.html
 * https://developer.android.com/training/articles/perf-jni
 */
@Keep
public class JniResult {

    /**
     * The return value for the JNI call.
     * This should be 0 for success.
     */
    public int retval;

    /**
     * The errno value for any failed native system or library calls if {@link #retval} does not equal 0.
     * This should be 0 if no errno was set.
     *
     * https://manpages.debian.org/testing/manpages-dev/errno.3.en.html
     */
    public int errno;

    /**
     * The error message for the failure if {@link #retval} does not equal 0.
     * The message will contain errno message returned by strerror() if errno was set.
     *
     * https://manpages.debian.org/testing/manpages-dev/strerror.3.en.html
     */
    public String errmsg;

    /**
     * Optional additional int data that needs to be returned by JNI call, like bytes read on success.
     */
    public int intData;

    public JniResult(int retval, int errno, String errmsg) {
        this.retval = retval;
        this.errno = errno;
        this.errmsg = errmsg;
    }

    public JniResult(int retval, int errno, String errmsg, int intData) {
        this(retval, errno, errmsg);
        this.intData = intData;
    }

    /** Instance with {@link #retval} -1; message is the formatted stack trace. */
    public JniResult(String message, Throwable throwable) {
        this(-1, 0, Logger.getMessageAndStackTraceString(message, throwable));
    }

    /** Null-safe {@link #getErrorString()}; {@code "null"} when {@code result} is null. */
    @NonNull
    public static String getErrorString(final JniResult result) {
        if (result == null) return "null";
        return result.getErrorString();
    }

    /** Get error {@link String} for {@link JniResult}. */
    @NonNull
    public String getErrorString() {
        StringBuilder logString = new StringBuilder();

        logString.append(Logger.getSingleLineLogStringEntry("Retval", retval, "-"));

        if (errno != 0)
            logString.append("\n").append(Logger.getSingleLineLogStringEntry("Errno", errno, "-"));

        if (errmsg != null && !errmsg.isEmpty())
            logString.append("\n").append(Logger.getMultiLineLogStringEntry("Errmsg", errmsg, "-"));

        return logString.toString();
    }

}

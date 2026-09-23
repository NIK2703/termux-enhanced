package com.termux.shared.shell.command.result;

import androidx.annotation.NonNull;

import com.termux.shared.data.DataUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.errors.Error;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ResultData implements Serializable {

    public final StringBuilder stdout = new StringBuilder();
    public final StringBuilder stderr = new StringBuilder();
    public Integer exitCode;
    public List<Error> errorsList =  new ArrayList<>();

    public ResultData() {
    }

    public void clearStdout() {
        stdout.setLength(0);
    }

    public StringBuilder prependStdout(String message) {
        return stdout.insert(0, message);
    }

    public StringBuilder prependStdoutLn(String message) {
        return stdout.insert(0, message + "\n");
    }

    public StringBuilder appendStdout(String message) {
        return stdout.append(message);
    }

    public StringBuilder appendStdoutLn(String message) {
        return stdout.append(message).append("\n");
    }

    public void clearStderr() {
        stderr.setLength(0);
    }

    public StringBuilder prependStderr(String message) {
        return stderr.insert(0, message);
    }

    public StringBuilder prependStderrLn(String message) {
        return stderr.insert(0, message + "\n");
    }

    public StringBuilder appendStderr(String message) {
        return stderr.append(message);
    }

    public StringBuilder appendStderrLn(String message) {
        return stderr.append(message).append("\n");
    }

    public synchronized boolean setStateFailed(@NonNull Error error) {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), null);
    }

    public synchronized boolean setStateFailed(@NonNull Error error, Throwable throwable) {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), Collections.singletonList(throwable));
    }

    public synchronized boolean setStateFailed(@NonNull Error error, List<Throwable> throwablesList) {
        return setStateFailed(error.getType(), error.getCode(), error.getMessage(), throwablesList);
    }

    public synchronized boolean setStateFailed(int code, String message) {
        return setStateFailed(null, code, message, null);
    }

    public synchronized boolean setStateFailed(int code, String message, Throwable throwable) {
        return setStateFailed(null, code, message, Collections.singletonList(throwable));
    }

    public synchronized boolean setStateFailed(int code, String message, List<Throwable> throwablesList) {
        return setStateFailed(null, code, message, throwablesList);
    }

    public synchronized boolean setStateFailed(String type, int code, String message, List<Throwable> throwablesList) {
        if (errorsList == null)
            errorsList =  new ArrayList<>();

        Error error = new Error();
        errorsList.add(error);

        return error.setStateFailed(type, code, message, throwablesList);
    }

    public boolean isStateFailed() {
        if (errorsList != null) {
            for (Error error : errorsList)
                if (error.isStateFailed())
                    return true;
        }

        return false;
    }

    public int getErrCode() {
        if (errorsList != null && errorsList.size() > 0)
            return errorsList.get(errorsList.size() - 1).getCode();
        else
            return Errno.ERRNO_SUCCESS.getCode();
    }

    @NonNull
    @Override
    public String toString() {
        return getResultDataLogString(this, true);
    }

    /**
     * Get a log friendly {@link String} for {@link ResultData} parameters.
     *
     * @param resultData The {@link ResultData} to convert.
     * @param logStdoutAndStderr Set to {@code true} if {@link #stdout} and {@link #stderr} should be logged.
     * @return Returns the log friendly {@link String}.
     */
    public static String getResultDataLogString(final ResultData resultData, boolean logStdoutAndStderr) {
        if (resultData == null) return "null";

        StringBuilder logString = new StringBuilder();

        if (logStdoutAndStderr) {
            logString.append("\n").append(resultData.getStdoutLogString());
            logString.append("\n").append(resultData.getStderrLogString());
        }
        logString.append("\n").append(resultData.getExitCodeLogString());

        logString.append("\n\n").append(getErrorsListLogString(resultData));

        return logString.toString();
    }

    public String getStdoutLogString() {
        return getStreamLogString("Stdout", stdout);
    }

    public String getStderrLogString() {
        return getStreamLogString("Stderr", stderr);
    }

    private String getStreamLogString(String label, StringBuilder stream) {
        if (stream.toString().isEmpty())
            return Logger.getSingleLineLogStringEntry(label, null, "-");
        else
            return Logger.getMultiLineLogStringEntry(label, DataUtils.getTruncatedCommandOutput(stream.toString(), Logger.LOGGER_ENTRY_MAX_SAFE_PAYLOAD / 5, false, false, true), "-");
    }

    public String getExitCodeLogString() {
        return Logger.getSingleLineLogStringEntry("Exit Code", exitCode, "-");
    }

    public static String getErrorsListLogString(final ResultData resultData) {
        return getErrorsListString(resultData, error -> Error.getErrorLogString(error));
    }

    /**
     * Get a markdown {@link String} for {@link ResultData}.
     *
     * @param resultData The {@link ResultData} to convert.
     * @return Returns the markdown {@link String}.
     */
    public static String getResultDataMarkdownString(final ResultData resultData) {
        if (resultData == null) return "null";

        StringBuilder markdownString = new StringBuilder();

        markdownString.append(getStreamMarkdownString("Stdout", resultData.stdout));
        markdownString.append("\n").append(getStreamMarkdownString("Stderr", resultData.stderr));

        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Exit Code", resultData.exitCode, "-"));

        markdownString.append("\n\n").append(getErrorsListMarkdownString(resultData));

        return markdownString.toString();
    }

    private static String getStreamMarkdownString(String label, StringBuilder stream) {
        if (stream.toString().isEmpty())
            return MarkdownUtils.getSingleLineMarkdownStringEntry(label, null, "-");
        else
            return MarkdownUtils.getMultiLineMarkdownStringEntry(label, stream.toString(), "-");
    }

    public static String getErrorsListMarkdownString(final ResultData resultData) {
        return getErrorsListString(resultData, error -> Error.getErrorMarkdownString(error));
    }

    public static String getErrorsListMinimalString(final ResultData resultData) {
        return getErrorsListString(resultData, error -> Error.getMinimalErrorString(error));
    }

    private static String getErrorsListString(final ResultData resultData, ErrorStringGetter errorStringGetter) {
        if (resultData == null) return "null";

        StringBuilder string = new StringBuilder();

        if (resultData.errorsList != null) {
            for (Error error : resultData.errorsList) {
                if (error.isStateFailed()) {
                    if (!string.toString().isEmpty())
                        string.append("\n");
                    string.append(errorStringGetter.get(error));
                }
            }
        }

        return string.toString();
    }

    private interface ErrorStringGetter {
        String get(Error error);
    }

}

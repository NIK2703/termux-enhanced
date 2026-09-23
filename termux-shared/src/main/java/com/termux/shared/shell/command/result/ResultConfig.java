package com.termux.shared.shell.command.result;

import android.app.PendingIntent;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;

import java.util.Formatter;

public class ResultConfig {

    /** Defines {@link PendingIntent} that should be sent with the result of the command. We cannot
     * implement {@link java.io.Serializable} because {@link PendingIntent} cannot be serialized. */
    public PendingIntent resultPendingIntent;
    /** The key with which to send result {@link android.os.Bundle} in {@link #resultPendingIntent}. */
    public String resultBundleKey;
    /** The key with which to send {@link ResultData#stdout} in {@link #resultPendingIntent}. */
    public String resultStdoutKey;
    /** The key with which to send {@link ResultData#stderr} in {@link #resultPendingIntent}. */
    public String resultStderrKey;
    /** The key with which to send {@link ResultData#exitCode} in {@link #resultPendingIntent}. */
    public String resultExitCodeKey;
    /** The key with which to send {@link ResultData#errorsList} errCode in {@link #resultPendingIntent}. */
    public String resultErrCodeKey;
    /** The key with which to send {@link ResultData#errorsList} errmsg in {@link #resultPendingIntent}. */
    public String resultErrmsgKey;
    /** The key with which to send original length of {@link ResultData#stdout} in {@link #resultPendingIntent}. */
    public String resultStdoutOriginalLengthKey;
    /** The key with which to send original length of {@link ResultData#stderr} in {@link #resultPendingIntent}. */
    public String resultStderrOriginalLengthKey;

    /** Defines the directory path in which to write the result of the command. */
    public String resultDirectoryPath;
    /** Defines the directory path under which {@link #resultDirectoryPath} can exist. */
    public String resultDirectoryAllowedParentPath;
    /** Defines whether the result should be written to a single file or multiple files
     * (err, error, stdout, stderr, exit_code) in {@link #resultDirectoryPath}. */
    public boolean resultSingleFile;
    /** Defines the basename of the result file that should be created in {@link #resultDirectoryPath}
     * if {@link #resultSingleFile} is {@code true}. */
    public String resultFileBasename;
    /** Defines the output {@link Formatter} format of the {@link #resultFileBasename} result file. */
    public String resultFileOutputFormat;
    /** Defines the error {@link Formatter} format of the {@link #resultFileBasename} result file. */
    public String resultFileErrorFormat;
    /** Defines the suffix of the result files that should be created in {@link #resultDirectoryPath}
     * if {@link #resultSingleFile} is {@code true}. */
    public String resultFilesSuffix;

    public ResultConfig() {
    }

    public boolean isCommandWithPendingResult() {
        return resultPendingIntent != null || resultDirectoryPath != null;
    }

    @NonNull
    @Override
    public String toString() {
        return getResultConfigLogString(this, true);
    }

    /**
     * Get a log friendly {@link String} for {@link ResultConfig} parameters.
     *
     * @param resultConfig The {@link ResultConfig} to convert.
     * @param ignoreNull Set to {@code true} if non-critical {@code null} values are to be ignored.
     * @return Returns the log friendly {@link String}.
     */
    public static String getResultConfigLogString(final ResultConfig resultConfig, boolean ignoreNull) {
        if (resultConfig == null) return "null";

        StringBuilder logString = new StringBuilder();

        logString.append("Result Pending: `").append(resultConfig.isCommandWithPendingResult()).append("`\n");

        if (resultConfig.resultPendingIntent != null) {
            logString.append(resultConfig.getResultPendingIntentVariablesLogString(ignoreNull));
            if (resultConfig.resultDirectoryPath != null)
                logString.append("\n");
        }

        if (resultConfig.resultDirectoryPath != null && !resultConfig.resultDirectoryPath.isEmpty())
            logString.append(resultConfig.getResultDirectoryVariablesLogString(ignoreNull));

        return logString.toString();
    }
    
    public String getResultPendingIntentVariablesLogString(boolean ignoreNull) {
        if (resultPendingIntent == null) return "Result PendingIntent Creator: -";

        StringBuilder resultPendingIntentVariablesString = new StringBuilder();

        resultPendingIntentVariablesString.append("Result PendingIntent Creator: `").append(resultPendingIntent.getCreatorPackage()).append("`");

        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultBundleKey, "Result Bundle Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultStdoutKey, "Result Stdout Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultStderrKey, "Result Stderr Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultExitCodeKey, "Result Exit Code Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultErrCodeKey, "Result Err Code Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultErrmsgKey, "Result Error Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultStdoutOriginalLengthKey, "Result Stdout Original Length Key");
        appendLogEntry(resultPendingIntentVariablesString, ignoreNull, resultStderrOriginalLengthKey, "Result Stderr Original Length Key");

        return resultPendingIntentVariablesString.toString();
    }

    public String getResultDirectoryVariablesLogString(boolean ignoreNull) {
        if (resultDirectoryPath == null) return "Result Directory Path: -";

        StringBuilder resultDirectoryVariablesString = new StringBuilder();

        resultDirectoryVariablesString.append(Logger.getSingleLineLogStringEntry("Result Directory Path", resultDirectoryPath, "-"));

        resultDirectoryVariablesString.append("\n").append(Logger.getSingleLineLogStringEntry("Result Single File", resultSingleFile, "-"));
        appendLogEntry(resultDirectoryVariablesString, ignoreNull, resultFileBasename, "Result File Basename");
        appendLogEntry(resultDirectoryVariablesString, ignoreNull, resultFileOutputFormat, "Result File Output Format");
        appendLogEntry(resultDirectoryVariablesString, ignoreNull, resultFileErrorFormat, "Result File Error Format");
        appendLogEntry(resultDirectoryVariablesString, ignoreNull, resultFilesSuffix, "Result Files Suffix");

        return resultDirectoryVariablesString.toString();
    }

    private static void appendLogEntry(StringBuilder builder, boolean ignoreNull, Object value, String label) {
        if (!ignoreNull || value != null)
            builder.append("\n").append(Logger.getSingleLineLogStringEntry(label, value, "-"));
    }

    /**
     * Get a markdown {@link String} for {@link ResultConfig}.
     *
     * @param resultConfig The {@link ResultConfig} to convert.
     * @return Returns the markdown {@link String}.
     */
    public static String getResultConfigMarkdownString(final ResultConfig resultConfig) {
        if (resultConfig == null) return "null";

        StringBuilder markdownString = new StringBuilder();

        if (resultConfig.resultPendingIntent != null)
            markdownString.append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result PendingIntent Creator", resultConfig.resultPendingIntent.getCreatorPackage(), "-"));
        else
            markdownString.append("**Result PendingIntent Creator:** -  ");

        if (resultConfig.resultDirectoryPath != null) {
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Directory Path", resultConfig.resultDirectoryPath, "-"));
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Single File", resultConfig.resultSingleFile, "-"));
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Basename", resultConfig.resultFileBasename, "-"));
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Output Format", resultConfig.resultFileOutputFormat, "-"));
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result File Error Format", resultConfig.resultFileErrorFormat, "-"));
            markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Result Files Suffix", resultConfig.resultFilesSuffix, "-"));
        }

        return markdownString.toString();
    }

}

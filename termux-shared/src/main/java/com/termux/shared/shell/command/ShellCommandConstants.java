package com.termux.shared.shell.command;

import com.termux.shared.errors.Errno;
import com.termux.shared.shell.command.result.ResultConfig;

import java.util.Formatter;
import java.util.IllegalFormatException;

public class ShellCommandConstants {

    /**
     * Class to send back results of commands to their callers like plugin or 3rd party apps.
     */
    public static final class RESULT_SENDER {

        /*
         * Default `Formatter` format strings for `ResultConfig#resultFileBasename`
         * when `ResultConfig#resultSingleFile` is `true`.
         */

        /** Success, stdout only (`err` success, empty `stderr`, `exit_code` 0, no
         * {@link ResultConfig#resultFileOutputFormat}); `stdout` maps to `%1$s`. */
        public static final String FORMAT_SUCCESS_STDOUT = "%1$s%n";
        /** Success with stdout + non-zero exit_code; `stdout` -> `%1$s`, `exit_code` -> `%2$s`
         * (markdown inline code). Used when no {@link ResultConfig#resultFileOutputFormat}. */
        public static final String FORMAT_SUCCESS_STDOUT__EXIT_CODE = "%1$s%n%n%n%nexit_code=%2$s%n";
        /** Success with stdout + stderr (+ exit_code); `stdout`/`stderr`/`exit_code` -> `%1$s`..`%3$s`
         * in markdown code blocks / inline code. Surrounding backticks are 3 more than any
         * consecutive backticks inside a parameter. No {@link ResultConfig#resultFileOutputFormat}. */
        public static final String FORMAT_SUCCESS_STDOUT__STDERR__EXIT_CODE = "stdout=%n%1$s%n%n%n%nstderr=%n%2$s%n%n%n%nexit_code=%3$s%n";
        /** Failure: `err` -> `%1$s`, `errmsg` -> `%2$s`, `stdout`/`stderr`/`exit_code` -> `%3$s`..`%5$s`.
         * Do not use `%6$s` or higher — {@link IllegalFormatException}. Used when `err` is not
         * {@link Errno#ERRNO_SUCCESS} and {@link ResultConfig#resultFileErrorFormat} is not passed.
         * errmsg/stdout/stderr in markdown code blocks; empty fields may omit backticks. */
        public static final String FORMAT_FAILED_ERR__ERRMSG__STDOUT__STDERR__EXIT_CODE = "err=%1$s%n%n%n%nerrmsg=%n%2$s%n%n%n%nstdout=%n%3$s%n%n%n%nstderr=%n%4$s%n%n%n%nexit_code=%5$s%n";

        /*
         * Default prefixes for result files under `ResultConfig#resultDirectoryPath`
         * when `ResultConfig#resultSingleFile` is `false`.
         */

        public static final String RESULT_FILE_ERR_PREFIX = "err";
        public static final String RESULT_FILE_ERRMSG_PREFIX = "errmsg";
        public static final String RESULT_FILE_STDOUT_PREFIX = "stdout";
        public static final String RESULT_FILE_STDERR_PREFIX = "stderr";
        public static final String RESULT_FILE_EXIT_CODE_PREFIX = "exit_code";

    }

}

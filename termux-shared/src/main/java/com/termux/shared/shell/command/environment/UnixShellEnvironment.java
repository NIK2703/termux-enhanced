package com.termux.shared.shell.command.environment;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.shell.ShellUtils;
import com.termux.shared.shell.command.ExecutionCommand;

import java.util.HashMap;

/**
 * Environment for Unix-like systems.
 *
 * https://manpages.debian.org/testing/manpages/environ.7.en.html
 * https://pubs.opengroup.org/onlinepubs/9699919799/basedefs/V1_chap08.html
 */
public abstract class UnixShellEnvironment implements IShellEnvironment {

    /** Environment variable for the terminal's colour capabilities. */
    public static final String ENV_COLORTERM = "COLORTERM";

    /** Environment variable for the path of the user's home directory. */
    public static final String ENV_HOME = "HOME";

    /** Locale for language/customs/charset when LC_ALL and other LC_* are unset. */
    public static final String ENV_LANG = "LANG";

    /** Colon-separated search path for dynamic shared libraries. */
    public static final String ENV_LD_LIBRARY_PATH = "LD_LIBRARY_PATH";

    /** Colon-separated search path for executables. */
    public static final String ENV_PATH = "PATH";

    /** Absolute path of the current working directory (no dot/dot-dot components). */
    public static final String ENV_PWD = "PWD";

    /** Terminal type for output prepared for a terminal's special capabilities. */
    public static final String ENV_TERM = "TERM";

    /** Path to a directory for temporary files. */
    public static final String ENV_TMPDIR = "TMPDIR";

    /** Names for common/supported login shell binaries. */
    public static final String[] LOGIN_SHELL_BINARIES = new String[]{"login", "bash", "zsh", "fish", "sh"};

    @NonNull
    public abstract HashMap<String, String> getEnvironment(@NonNull Context currentPackageContext,
                                                           boolean isFailSafe);

    @NonNull
    @Override
    public abstract String getDefaultWorkingDirectoryPath();

    @NonNull
    @Override
    public abstract String getDefaultBinPath();

    @NonNull
    @Override
    public String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        return ShellUtils.setupShellCommandArguments(executable, arguments);
    }

    @NonNull
    @Override
    public abstract HashMap<String, String> setupShellCommandEnvironment(@NonNull Context currentPackageContext,
                                                                         @NonNull ExecutionCommand executionCommand);

}

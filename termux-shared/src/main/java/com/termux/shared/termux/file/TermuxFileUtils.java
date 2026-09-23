package com.termux.shared.termux.file;

import static com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.errors.Error;
import com.termux.shared.file.FileUtilsErrno;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class TermuxFileUtils {

    private static final String LOG_TAG = "TermuxFileUtils";

    /**
     * Replace "$PREFIX/" or "~/" prefix with termux absolute paths.
     *
     * @param paths The {@code paths} to expand.
     * @return Returns the {@code expand paths}.
     */
    public static List<String> getExpandedTermuxPaths(List<String> paths) {
        if (paths == null) return null;
        List<String> expandedPaths = new ArrayList<>();

        for (int i = 0; i < paths.size(); i++) {
            expandedPaths.add(getExpandedTermuxPath(paths.get(i)));
        }

        return expandedPaths;
    }

    /**
     * Replace "$PREFIX/" or "~/" prefix with termux absolute paths.
     *
     * @param path The {@code path} to expand.
     * @return Returns the {@code expand path}.
     */
    public static String getExpandedTermuxPath(String path) {
        if (path != null && !path.isEmpty()) {
            path = path.replaceAll("^\\$PREFIX$", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            path = path.replaceAll("^\\$PREFIX/", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/");
            path = path.replaceAll("^~/$", TermuxConstants.TERMUX_HOME_DIR_PATH);
            path = path.replaceAll("^~/", TermuxConstants.TERMUX_HOME_DIR_PATH + "/");
        }

        return path;
    }

    /**
     * Replace termux absolute paths with "$PREFIX/" or "~/" prefix.
     *
     * @param paths The {@code paths} to unexpand.
     * @return Returns the {@code unexpand paths}.
     */
    public static List<String> getUnExpandedTermuxPaths(List<String> paths) {
        if (paths == null) return null;
        List<String> unExpandedPaths = new ArrayList<>();

        for (int i = 0; i < paths.size(); i++) {
            unExpandedPaths.add(getUnExpandedTermuxPath(paths.get(i)));
        }

        return unExpandedPaths;
    }

    /**
     * Replace termux absolute paths with "$PREFIX/" or "~/" prefix.
     *
     * @param path The {@code path} to unexpand.
     * @return Returns the {@code unexpand path}.
     */
    public static String getUnExpandedTermuxPath(String path) {
        if (path != null && !path.isEmpty()) {
            path = path.replaceAll("^" + Pattern.quote(TermuxConstants.TERMUX_PREFIX_DIR_PATH) + "/", "\\$PREFIX/");
            path = path.replaceAll("^" + Pattern.quote(TermuxConstants.TERMUX_HOME_DIR_PATH) + "/", "~/");
        }

        return path;
    }

    /**
     * Get canonical path.
     *
     * @param path The {@code path} to convert.
     * @param prefixForNonAbsolutePath Optional prefix path to prefix before non-absolute paths. This
     *                                 can be set to {@code null} if non-absolute paths should
     *                                 be prefixed with "/". The call to {@link File#getCanonicalPath()}
     *                                 will automatically do this anyways.
     * @param expandPath The {@code boolean} that decides if input path is first attempted to be expanded by calling
     *                   {@link TermuxFileUtils#getExpandedTermuxPath(String)} before its passed to
     *                   {@link FileUtils#getCanonicalPath(String, String)}.

     * @return Returns the {@code canonical path}.
     */
    public static String getCanonicalPath(String path, final String prefixForNonAbsolutePath, final boolean expandPath) {
        if (path == null) path = "";

        if (expandPath)
            path = getExpandedTermuxPath(path);

        return FileUtils.getCanonicalPath(path, prefixForNonAbsolutePath);
    }

    /**
     * Check if {@code path} is under the allowed termux working directory paths. If it is, then
     * allowed parent path is returned.
     *
     * @param path The {@code path} to check.
     * @return Returns the allowed path if it {@code path} is under it, otherwise {@link TermuxConstants#TERMUX_FILES_DIR_PATH}.
     */
    public static String getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(String path) {
        if (path == null || path.isEmpty()) return TermuxConstants.TERMUX_FILES_DIR_PATH;

        if (path.startsWith(TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH + "/")) {
            return TermuxConstants.TERMUX_STORAGE_HOME_DIR_PATH;
        } if (path.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath() + "/")) {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        } else if (path.startsWith("/sdcard/")) {
            return "/sdcard";
        } else {
            return TermuxConstants.TERMUX_FILES_DIR_PATH;
        }
    }

    /**
     * Validate the existence and permissions of directory file at path as a working directory for
     * termux app.
     *
     * The creation of missing directory and setting of missing permissions will only be done if
     * {@code path} is under paths returned by {@link #getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(String)}.
     *
     * The permissions set to directory will be {@link FileUtils#APP_WORKING_DIRECTORY_PERMISSIONS}.
     *
     * @param label The optional label for the directory file. This can optionally be {@code null}.
     * @param filePath The {@code path} for file to validate or create. Symlinks will not be followed.
     * @param createDirectoryIfMissing The {@code boolean} that decides if directory file
     *                                 should be created if its missing.
     * @param setPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set defined by {@code permissionsToCheck}.
     * @param setMissingPermissionsOnly The {@code boolean} that decides if only missing permissions
     *                                  are to be set or if they should be overridden.
     * @param ignoreErrorsIfPathIsInParentDirPath The {@code boolean} that decides if existence
     *                                  and permission errors are to be ignored if path is
     *                                  in {@code parentDirPath}.
     * @param ignoreIfNotExecutable The {@code boolean} that decides if missing executable permission
     *                              error is to be ignored. This allows making an attempt to set
     *                              executable permissions, but ignoring if it fails.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    public static Error validateDirectoryFileExistenceAndPermissions(String label, final String filePath, final boolean createDirectoryIfMissing,
                                                                     final boolean setPermissions, final boolean setMissingPermissionsOnly,
                                                                     final boolean ignoreErrorsIfPathIsInParentDirPath, final boolean ignoreIfNotExecutable) {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(label, filePath,
            TermuxFileUtils.getMatchedAllowedTermuxWorkingDirectoryParentPathForPath(filePath), createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setPermissions, setMissingPermissionsOnly,
            ignoreErrorsIfPathIsInParentDirPath, ignoreIfNotExecutable);
    }

    /**
     * Validate if {@link TermuxConstants#TERMUX_FILES_DIR_PATH} exists and has
     * {@link FileUtils#APP_WORKING_DIRECTORY_PERMISSIONS} permissions.
     *
     * Required because binaries compiled for termux hard-code
     * {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH} and the path must be accessible. The
     * directory is created via {@link Context#getFilesDir()} so Android itself creates it —
     * an app cannot create the parent {@code /data/user/0/[package_name]} if missing (owned
     * by {@code system}, created at install/update time).
     *
     * <p>Note {@link Context#getFilesDir()} may return {@code /data/user/[id]/[package_name]}
     * instead of {@code /data/data/[package_name]} defined by
     * {@link TermuxConstants#TERMUX_FILES_DIR_PATH}: under a work profile or secondary user
     * the fixed path may not be accessible unless bind-mounted from {@code /data/data}
     * (https://source.android.com/devices/tech/admin/multi-user). On Android ≤10,
     * {@code /data/user/0} is a symlink to {@code /data/data}; on ≥11 it is a bind mount.
     *
     * @param context The {@link Context} for operations.
     * @param createDirectoryIfMissing create the directory if missing.
     * @param setMissingPermissions set missing permissions if {@code true}.
     * @return {@code error} if path is not a directory, failed to create, or permissions failed;
     *         otherwise {@code null}.
     */
    public static Error isTermuxFilesDirectoryAccessible(@NonNull final Context context, boolean createDirectoryIfMissing, boolean setMissingPermissions) {
        // Use the actual runtime files directory so this works when the app package
        // differs from the compile-time TERMUX_PACKAGE_NAME (e.g. debug builds).
        if (createDirectoryIfMissing)
            context.getFilesDir();

        String actualFilesDirPath = context.getFilesDir().getAbsolutePath();
        if (!FileUtils.directoryFileExists(actualFilesDirPath, true))
            return FileUtilsErrno.ERRNO_FILE_NOT_FOUND_AT_PATH.getError("termux files directory", actualFilesDirPath);

        if (setMissingPermissions)
            FileUtils.setMissingFilePermissions("termux files directory", actualFilesDirPath,
                FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS);

        return FileUtils.checkMissingFilePermissions("termux files directory", actualFilesDirPath,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, false);
    }

    /**
     * Validate if {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH} exists and has
     * {@link FileUtils#APP_WORKING_DIRECTORY_PERMISSIONS} permissions.
     *
     * The {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH} directory would not exist if termux has
     * not been installed or the bootstrap setup has not been run or if it was deleted by the user.
     *
     * @param createDirectoryIfMissing The {@code boolean} that decides if directory file
     *                                 should be created if its missing.
     * @param setMissingPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    public static Error isTermuxPrefixDirectoryAccessible(boolean createDirectoryIfMissing, boolean setMissingPermissions) {
        return validateTermuxDirectoryAccessible("termux prefix directory", TermuxConstants.TERMUX_PREFIX_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    /**
     * Validate if {@link TermuxConstants#TERMUX_STAGING_PREFIX_DIR_PATH} exists and has
     * {@link FileUtils#APP_WORKING_DIRECTORY_PERMISSIONS} permissions.
     *
     * @param createDirectoryIfMissing The {@code boolean} that decides if directory file
     *                                 should be created if its missing.
     * @param setMissingPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    public static Error isTermuxPrefixStagingDirectoryAccessible(boolean createDirectoryIfMissing, boolean setMissingPermissions) {
        return validateTermuxDirectoryAccessible("termux prefix staging directory", TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    /**
     * Validate if {@link TermuxConstants.TERMUX_APP#APPS_DIR_PATH} exists and has
     * {@link FileUtils#APP_WORKING_DIRECTORY_PERMISSIONS} permissions.
     *
     * @param createDirectoryIfMissing The {@code boolean} that decides if directory file
     *                                 should be created if its missing.
     * @param setMissingPermissions The {@code boolean} that decides if permissions are to be
     *                              automatically set.
     * @return Returns the {@code error} if path is not a directory file, failed to create it,
     * or validating permissions failed, otherwise {@code null}.
     */
    public static Error isAppsTermuxAppDirectoryAccessible(boolean createDirectoryIfMissing, boolean setMissingPermissions) {
        return validateTermuxDirectoryAccessible("apps/termux-app directory", TermuxConstants.TERMUX_APP.APPS_DIR_PATH,
            createDirectoryIfMissing, setMissingPermissions);
    }

    private static Error validateTermuxDirectoryAccessible(String label, String filePath, boolean createDirectoryIfMissing,
                                                           boolean setMissingPermissions) {
        return FileUtils.validateDirectoryFileExistenceAndPermissions(label, filePath,
            null, createDirectoryIfMissing,
            FileUtils.APP_WORKING_DIRECTORY_PERMISSIONS, setMissingPermissions, true,
            false, false);
    }

    /**
     * If {@link TermuxConstants#TERMUX_PREFIX_DIR_PATH} doesn't exist, is empty or only contains
     * files in {@link TermuxConstants#TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY}.
     */
    public static boolean isTermuxPrefixDirectoryEmpty() {
        Error error = FileUtils.validateDirectoryFileEmptyOrOnlyContainsSpecificFiles("termux prefix",
            TERMUX_PREFIX_DIR_PATH, TermuxConstants.TERMUX_PREFIX_DIR_IGNORED_SUB_FILES_PATHS_TO_CONSIDER_AS_EMPTY, true);
        if (error == null)
            return true;

        if (!FileUtilsErrno.ERRNO_NON_EMPTY_DIRECTORY_FILE.equalsErrorTypeAndCode(error))
            Logger.logErrorExtended(LOG_TAG, "Failed to check if termux prefix directory is empty:\n" + error.getErrorLogString());
        return false;
    }

    /**
     * Get a markdown {@link String} for stat output for various Termux app files paths.
     *
     * @param context The context for operations.
     * @return Returns the markdown {@link String}.
     */
    public static String getTermuxFilesStatMarkdownString(@NonNull final Context context) {
        Context termuxPackageContext = TermuxUtils.getTermuxPackageContext(context);
        if (termuxPackageContext == null) return null;

        // Also ensures that termux files directory is created if it does not already exist
        String filesDir = termuxPackageContext.getFilesDir().getAbsolutePath();

        StringBuilder statScript = new StringBuilder();
        statScript
            .append("echo 'ls info:'\n")
            .append("/system/bin/ls -lhdZ")
            .append(" '/data/data'")
            .append(" '/data/user/0'")
            .append(" '" + TermuxConstants.TERMUX_INTERNAL_PRIVATE_APP_DATA_DIR_PATH + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "'")
            .append(" '" + TermuxConstants.TERMUX_FILES_DIR_PATH + "'")
            .append(" '" + filesDir + "'")
            .append(" '/data/user/0/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '/data/user/" + TermuxConstants.TERMUX_PACKAGE_NAME + "/files'")
            .append(" '" + TermuxConstants.TERMUX_STAGING_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_PREFIX_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_HOME_DIR_PATH + "'")
            .append(" '" + TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/login'")
            .append(" 2>&1")
            .append("\necho; echo 'mount info:'\n")
            .append("/system/bin/grep -E '( /data )|( /data/data )|( /data/user/[0-9]+ )' /proc/self/mountinfo 2>&1 | /system/bin/grep -v '/data_mirror' 2>&1");

        ExecutionCommand executionCommand = new ExecutionCommand(-1, "/system/bin/sh", null,
            statScript.toString() + "\n", "/", ExecutionCommand.Runner.APP_SHELL.getName(), true);
        executionCommand.commandLabel = TermuxConstants.TERMUX_APP_NAME + " Files Stat Command";
        executionCommand.backgroundCustomLogLevel = Logger.LOG_LEVEL_OFF;
        if (!TermuxUtils.executeQuietAppShell(context, LOG_TAG, executionCommand)) {
            return null;
        }

        StringBuilder statOutput = new StringBuilder();
        statOutput.append("$ ").append(statScript.toString());
        statOutput.append("\n\n").append(executionCommand.resultData.stdout.toString());
        TermuxUtils.appendCommandErrorOutput(statOutput, LOG_TAG, executionCommand);

        StringBuilder markdownString = new StringBuilder();
        markdownString.append("## ").append(TermuxConstants.TERMUX_APP_NAME).append(" Files Info\n\n");
        AndroidUtils.appendPropertyToMarkdown(markdownString,"TERMUX_REQUIRED_FILES_DIR_PATH ($PREFIX)", TermuxConstants.TERMUX_FILES_DIR_PATH);
        AndroidUtils.appendPropertyToMarkdown(markdownString,"ANDROID_ASSIGNED_FILES_DIR_PATH", filesDir);
        markdownString.append("\n\n").append(MarkdownUtils.getMarkdownCodeForString(statOutput.toString(), true));
        markdownString.append("\n##\n");

        return markdownString.toString();
    }

}

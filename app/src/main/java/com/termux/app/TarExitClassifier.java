package com.termux.app;

/**
 * Classifies non-zero tar exit statuses as warnings or failures.
 *
 * <p>The rules differ between GNU tar and the system toybox tar, so callers pass the resolved
 * binary flavor instead of trying to infer it from the command line.
 */
final class TarExitClassifier {

    private static final int EXIT_WARNINGS = 1;
    private static final int EXIT_FATAL = 2;

    private static final String GNU_TAR_PREFIX = "tar: ";
    private static final String GNU_FAILURE_SUMMARY =
        "tar: Exiting with failure status due to previous errors";

    private TarExitClassifier() {}

    static boolean isGnuRestoreWarning(int exitCode) {
        return exitCode == EXIT_WARNINGS;
    }

    static boolean isGnuBackupWarning(int exitCode, String stderr, boolean stderrTruncated) {
        if (exitCode == EXIT_WARNINGS) return true;
        if (exitCode != EXIT_FATAL || stderrTruncated) return false;
        return isBenignGnuBackupStderr(stderr);
    }

    static boolean isToyboxBackupWarning(int exitCode, String stderr, boolean stderrTruncated) {
        if (exitCode != EXIT_WARNINGS || stderrTruncated) return false;
        return isBenignToyboxBackupStderr(stderr);
    }

    /**
     * GNU tar returns 2 when a file disappears between directory traversal and stat. That is a
     * normal race in a live container: the archive is complete and restorable, so the backup
     * should finish with a warning. Only accept the exact per-file diagnostics for vanished
     * entries and the matching summary; permission errors and read errors remain failures.
     */
    private static boolean isBenignGnuBackupStderr(String stderr) {
        boolean sawWarning = false;
        for (String rawLine : stderr.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (GNU_FAILURE_SUMMARY.equals(line)) continue;
            if (isBenignGnuVanishedFileLine(line)) {
                sawWarning = true;
                continue;
            }
            return false;
        }
        return sawWarning;
    }

    private static boolean isBenignGnuVanishedFileLine(String line) {
        String path = gnuPathBefore(line, ": Cannot stat: No such file or directory");
        if (path == null) path = gnuPathBefore(line, ": Cannot open: No such file or directory");
        if (path == null) path = gnuPathBefore(line, ": Cannot read: No such file or directory");
        if (path == null) path = gnuPathBefore(line, ": file changed as we read it");
        if (path == null) path = gnuPathBefore(line, ": File removed before we read it");
        return path != null && isNotArchiveRoot(path);
    }

    private static String gnuPathBefore(String line, String suffix) {
        if (!line.startsWith(GNU_TAR_PREFIX) || !line.endsWith(suffix)) return null;
        int pathStart = GNU_TAR_PREFIX.length();
        int pathEnd = line.length() - suffix.length();
        if (pathEnd <= pathStart) return null;
        return line.substring(pathStart, pathEnd);
    }

    private static boolean isBenignToyboxBackupStderr(String stderr) {
        boolean sawWarning = false;
        for (String rawLine : stderr.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if ("tar: had errors".equals(line)) continue;
            if (isBenignToyboxMissingFileLine(line)) {
                sawWarning = true;
                continue;
            }
            if (line.startsWith("tar: unknown file type '") && line.endsWith("'")) {
                sawWarning = true;
                continue;
            }
            return false;
        }
        return sawWarning;
    }

    private static boolean isBenignToyboxMissingFileLine(String line) {
        final String prefix = "tar: ";
        final String suffix = ": No such file or directory";
        if (!line.startsWith(prefix) || !line.endsWith(suffix)) return false;
        String path = line.substring(prefix.length(), line.length() - suffix.length());
        return isNotArchiveRoot(path);
    }

    private static boolean isNotArchiveRoot(String path) {
        return !path.isEmpty() && !".".equals(path) && !"./".equals(path);
    }
}

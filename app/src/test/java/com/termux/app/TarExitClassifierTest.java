package com.termux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TarExitClassifierTest {

    private static final String GNU_MISSING_FILE =
        "tar: sub/missing: Cannot stat: No such file or directory\n"
            + "tar: Exiting with failure status due to previous errors\n";

    private static final String TOYBOX_MISSING_FILE =
        "tar: home/cache/entry: No such file or directory\n"
            + "tar: had errors\n";

    @Test
    public void gnuExitOneMeansFilesChangedDuringBackup() {
        assertTrue(TarExitClassifier.isGnuBackupWarning(1, "", false));
    }

    @Test
    public void gnuMissingFileDuringBackupIsAWarning() {
        assertTrue(TarExitClassifier.isGnuBackupWarning(2, GNU_MISSING_FILE, false));
    }

    @Test
    public void gnuFileRemovedDuringBackupIsAWarning() {
        String stderr = "tar: home/cache/entry: File removed before we read it\n"
            + "tar: Exiting with failure status due to previous errors\n";
        assertTrue(TarExitClassifier.isGnuBackupWarning(2, stderr, false));
    }

    @Test
    public void gnuPermissionDeniedDuringBackupIsAFailure() {
        String stderr = "tar: home/private: Cannot open: Permission denied\n"
            + "tar: Exiting with failure status due to previous errors\n";
        assertFalse(TarExitClassifier.isGnuBackupWarning(2, stderr, false));
    }

    @Test
    public void oneRealFailureAmongMissingFilesIsAFailure() {
        String stderr = "tar: home/cache/a: Cannot stat: No such file or directory\n"
            + "tar: home/private/b: Cannot open: Permission denied\n"
            + "tar: Exiting with failure status due to previous errors\n";
        assertFalse(TarExitClassifier.isGnuBackupWarning(2, stderr, false));
    }

    @Test
    public void gnuMissingArchiveRootIsAFailure() {
        String stderr = "tar: ./: Cannot stat: No such file or directory\n"
            + "tar: Exiting with failure status due to previous errors\n";
        assertFalse(TarExitClassifier.isGnuBackupWarning(2, stderr, false));
    }

    @Test
    public void truncatedGnuStderrIsNotTrusted() {
        assertFalse(TarExitClassifier.isGnuBackupWarning(2, GNU_MISSING_FILE, true));
    }

    @Test
    public void toyboxMissingFileDuringBackupIsAWarning() {
        assertTrue(TarExitClassifier.isToyboxBackupWarning(1, TOYBOX_MISSING_FILE, false));
    }

    @Test
    public void toyboxPermissionDeniedDuringBackupIsAFailure() {
        String stderr = "tar: home/private: Permission denied\n"
            + "tar: had errors\n";
        assertFalse(TarExitClassifier.isToyboxBackupWarning(1, stderr, false));
    }

    @Test
    public void warningResultIsStillASuccessfulBackup() {
        TermuxBackupUtils.Result result =
            TermuxBackupUtils.Result.warning("Some files changed");

        assertTrue(result.isSuccess());
        assertFalse(result.isCancelled());
        assertNull(result.getError());
        assertEquals("Some files changed", result.getWarning());
    }
}

package com.termux.shared.shell.am;

import com.termux.shared.CommonValues;

import android.Manifest;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.net.socket.local.ILocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;

import java.io.Serializable;

public class AmSocketServerRunConfig extends LocalSocketRunConfig implements Serializable {

    /**
     * Check if {@link Manifest.permission#SYSTEM_ALERT_WINDOW} has been granted if running on Android `>= 10`
     * if starting activities. Will also check when starting services in case starting foreground
     * service is not allowed.
     *
     * https://developer.android.com/guide/components/activities/background-starts
     */
    private Boolean mCheckDisplayOverAppsPermission;
    public static final boolean DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION = true;

    public AmSocketServerRunConfig(@NonNull String title, @NonNull String path, @NonNull ILocalSocketManager localSocketManagerClient) {
        super(title, path, localSocketManagerClient);
    }

    /** Get {@link #mCheckDisplayOverAppsPermission} if set, otherwise {@link #DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION}. */
    public boolean shouldCheckDisplayOverAppsPermission() {
        return mCheckDisplayOverAppsPermission != null ? mCheckDisplayOverAppsPermission : DEFAULT_CHECK_DISPLAY_OVER_APPS_PERMISSION;
    }

    /** Get a log {@link String} for the {@link AmSocketServerRunConfig}. */
    @NonNull
    public String getLogString() {
        StringBuilder logString = new StringBuilder();
        logString.append(super.getLogString()).append(MarkdownUtils.SUBSECTION_SEPARATOR);

        logString.append("Am Command:");
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), CommonValues.EMPTY_VALUE_PLACEHOLDER));

        return logString.toString();
    }

    /** Get a markdown {@link String} for the {@link AmSocketServerRunConfig}. */
    @NonNull
    public String getMarkdownString() {
        StringBuilder markdownString = new StringBuilder();
        markdownString.append(super.getMarkdownString()).append(MarkdownUtils.SUBSECTION_SEPARATOR);

        markdownString.append(MarkdownUtils.HEADING_PREFIX).append("Am Command");
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), CommonValues.EMPTY_VALUE_PLACEHOLDER));

        return markdownString.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return getLogString();
    }

}

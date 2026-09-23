package com.termux.shared.shell.am;

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
        logString.append(super.getLogString()).append("\n\n\n");

        logString.append("Am Command:");
        logString.append("\n").append(Logger.getSingleLineLogStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"));

        return logString.toString();
    }

    /** Get a markdown {@link String} for the {@link AmSocketServerRunConfig}. */
    @NonNull
    public String getMarkdownString() {
        StringBuilder markdownString = new StringBuilder();
        markdownString.append(super.getMarkdownString()).append("\n\n\n");

        markdownString.append("## ").append("Am Command");
        markdownString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("CheckDisplayOverAppsPermission", shouldCheckDisplayOverAppsPermission(), "-"));

        return markdownString.toString();
    }

    @NonNull
    @Override
    public String toString() {
        return getLogString();
    }

}

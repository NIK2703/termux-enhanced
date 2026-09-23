package com.termux.shared.termux.shell.am;

import android.content.Context;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalServerSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;
import com.termux.shared.shell.am.AmSocketServerRunConfig;
import com.termux.shared.shell.am.AmSocketServer;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.command.environment.TermuxAppShellEnvironment;

/**
 * A wrapper for {@link AmSocketServer} for termux-app usage.
 *
 * The static {@link #termuxAmSocketServer} variable stores the {@link LocalSocketManager} for the
 * {@link AmSocketServer}.
 *
 * The {@link TermuxAmSocketServerClient} extends the {@link AmSocketServer.AmSocketServerClient}
 * class to also show plugin error notifications for errors and disallowed client connections in
 * addition to logging the messages to logcat, which are only logged by {@link LocalSocketManagerClientBase}
 * if log level is debug or higher for privacy issues.
 *
 * It uses a filesystem socket server with the socket file at
 * {@link TermuxConstants.TERMUX_APP#TERMUX_AM_SOCKET_FILE_PATH}. It would normally only allow
 * processes belonging to the termux user and root user to connect to it. If commands are sent by the
 * root user, then the am commands executed will be run as the termux user and its permissions,
 * capabilities and selinux context instead of root.
 *
 * The `$PREFIX/bin/termux-am` client connects to the server via `$PREFIX/bin/termux-am-socket` to
 * run the am commands. It provides similar functionality to "$PREFIX/bin/am"
 * (and "/system/bin/am"), but should be faster since it does not require starting a dalvik vm for
 * every command as done by "am" via termux/TermuxAm.
 *
 * The server is started by termux-app Application class but is not started if
 * {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER} is `false` which can be done by
 * adding the prop with value "false" to the "~/.termux/termux.properties" file. Changes
 * require termux-app to be force stopped and restarted.
 *
 * The current state of the server can be checked with the
 * {@link TermuxAppShellEnvironment#ENV_TERMUX_APP__AM_SOCKET_SERVER_ENABLED} env variable, which is exported
 * for all shell sessions and tasks.
 *
 * https://github.com/termux/termux-am-socket
 * https://github.com/termux/TermuxAm
 */
public class TermuxAmSocketServer {

    public static final String LOG_TAG = "TermuxAmSocketServer";

    public static final String TITLE = "TermuxAm";

    /** Static instance of the {@link LocalSocketManager}, or {@code null} when not running. */
    private static LocalSocketManager termuxAmSocketServer;

    /** Whether the server is enabled and running. */
    @Keep
    protected static Boolean TERMUX_APP_AM_SOCKET_SERVER_ENABLED;

    /**
     * Set up the {@link AmSocketServer} and start listening if enabled by
     * {@link TermuxPropertyConstants#KEY_RUN_TERMUX_AM_SOCKET_SERVER}.
     */
    public static void setupTermuxAmSocketServer(@NonNull Context context) {
        boolean enabled = false;
        if (TermuxAppSharedProperties.getProperties().shouldRunTermuxAmSocketServer()) {
            Logger.logDebug(LOG_TAG, "Starting " + TITLE + " socket server since its enabled");
            start(context);
            if (termuxAmSocketServer != null && termuxAmSocketServer.isRunning()) {
                enabled = true;
                Logger.logDebug(LOG_TAG, TITLE + " socket server successfully started");
            }
        } else {
            Logger.logDebug(LOG_TAG, "Not starting " + TITLE + " socket server since its not enabled");
        }

        // Once termux-app has started, the server state must not be changed since the variable is
        // exported in shell sessions and tasks and if state is changed, then env of older shells will
        // retain invalid value. User should force stop the app to update state after changing prop.
        TERMUX_APP_AM_SOCKET_SERVER_ENABLED = enabled;
        TermuxAppShellEnvironment.updateTermuxAppAMSocketServerEnabled(context);
    }

    /**
     * Create the {@link AmSocketServer} {@link LocalServerSocket} and start listening for new {@link LocalClientSocket}.
     */
    public static synchronized void start(@NonNull Context context) {
        stop();

        AmSocketServerRunConfig amSocketServerRunConfig = new AmSocketServerRunConfig(TITLE,
            TermuxConstants.TERMUX_APP.TERMUX_AM_SOCKET_FILE_PATH, new TermuxAmSocketServerClient());

        termuxAmSocketServer = AmSocketServer.start(context, amSocketServerRunConfig);
    }

    /**
     * Stop the {@link AmSocketServer} {@link LocalServerSocket} and stop listening for new {@link LocalClientSocket}.
     */
    public static synchronized void stop() {
        if (termuxAmSocketServer != null) {
            Error error = termuxAmSocketServer.stop();
            if (error != null) {
                termuxAmSocketServer.onError(error);
            }
            termuxAmSocketServer = null;
        }
    }
    
    /**
     * Show a plugin error notification for a socket server error.
     *
     * @param clientSocket optional client socket the error came from.
     */
    public static synchronized void showErrorNotification(@NonNull Context context, @NonNull Error error,
                                                          @NonNull LocalSocketRunConfig localSocketRunConfig,
                                                          @Nullable LocalClientSocket clientSocket) {
        TermuxPluginUtils.sendPluginCommandErrorNotification(context, LOG_TAG,
            localSocketRunConfig.getTitle() + " Socket Server Error", error.getMinimalErrorString(),
            LocalSocketManager.getErrorMarkdownString(error, localSocketRunConfig, clientSocket));
    }

    public static Boolean getTermuxAppAMSocketServerEnabled(@NonNull Context currentPackageContext) {
        boolean isTermuxApp = TermuxConstants.TERMUX_PACKAGE_NAME.equals(currentPackageContext.getPackageName());
        if (isTermuxApp) {
            return TERMUX_APP_AM_SOCKET_SERVER_ENABLED;
        } else {
            // Unsupported for plugin processes: they cannot see this process's static field.
            // A binder API or an actual "is it running" probe would be needed; long checks
            // are also not viable on the main thread.
            return null;
        }

    }

    /** Enhanced implementation for {@link AmSocketServer.AmSocketServerClient} for {@link TermuxAmSocketServer}. */
    public static class TermuxAmSocketServerClient extends AmSocketServer.AmSocketServerClient {

        public static final String LOG_TAG = "TermuxAmSocketServerClient";

        @Nullable
        @Override
        public Thread.UncaughtExceptionHandler getLocalSocketManagerClientThreadUEH(
            @NonNull LocalSocketManager localSocketManager) {
            // Use termux crash handler for socket listener thread just like used for main app process thread.
            return TermuxCrashUtils.getCrashHandler(localSocketManager.getContext());
        }

        @Override
        public void onError(@NonNull LocalSocketManager localSocketManager,
                            @Nullable LocalClientSocket clientSocket, @NonNull Error error) {
            // Don't show notification if server is not running since errors may be triggered
            // when server is stopped and server and client sockets are closed.
            if (localSocketManager.isRunning()) {
                TermuxAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                    localSocketManager.getLocalSocketRunConfig(), clientSocket);
            }

            // But log the exception
            super.onError(localSocketManager, clientSocket, error);
        }

        @Override
        public void onDisallowedClientConnected(@NonNull LocalSocketManager localSocketManager,
                                                @NonNull LocalClientSocket clientSocket, @NonNull Error error) {
            // Always show notification and log error regardless of if server is running or not
            TermuxAmSocketServer.showErrorNotification(localSocketManager.getContext(), error,
                localSocketManager.getLocalSocketRunConfig(), clientSocket);
            super.onDisallowedClientConnected(localSocketManager, clientSocket, error);
        }

        @Override
        protected String getLogTag() {
            return LOG_TAG;
        }

    }

}

package com.termux.shared.crash;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.errors.Error;
import com.termux.shared.android.AndroidUtils;

import java.nio.charset.Charset;

/**
 * Catches uncaught exceptions and logs them.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context mContext;
    private final CrashHandlerClient mCrashHandlerClient;
    private final Thread.UncaughtExceptionHandler mDefaultUEH;
    private final boolean mIsDefaultHandler;

    private static final String LOG_TAG = "CrashUtils";

    private CrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient,
                         boolean isDefaultHandler) {
        mContext = context;
        mCrashHandlerClient = crashHandlerClient;
        mDefaultUEH = Thread.getDefaultUncaughtExceptionHandler();
        mIsDefaultHandler = isDefaultHandler;
    }

    public void uncaughtException(@NonNull Thread thread, @NonNull Throwable throwable) {
        Logger.logInfo(LOG_TAG, "uncaughtException() for " + thread +  ": " + throwable.getMessage());
        logCrash(thread, throwable);

        // Only the default handler chain kills the app; handlers installed on a worker
        // thread just log, so the thread can die without taking the process with it.
        if (mIsDefaultHandler)
            mDefaultUEH.uncaughtException(thread, throwable);
    }

    /**
     * Set default uncaught crash handler for the app to {@link CrashHandler}.
     */
    public static void setDefaultCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        if (!(Thread.getDefaultUncaughtExceptionHandler() instanceof CrashHandler)) {
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(context, crashHandlerClient, true));
        }
    }

    /**
     * Set uncaught crash handler of current non-main thread to {@link CrashHandler}.
     */
    public static void setCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        Thread.currentThread().setUncaughtExceptionHandler(new CrashHandler(context, crashHandlerClient, false));
    }

    /**
     * Get {@link CrashHandler} instance that can be set as uncaught crash handler of a non-main thread.
     */
    public static CrashHandler getCrashHandler(@NonNull final Context context, @NonNull final CrashHandlerClient crashHandlerClient) {
        return new CrashHandler(context, crashHandlerClient, false);
    }

    /**
     * Log a crash in the crash log file at path returned by {@link CrashHandlerClient#getCrashLogFilePath(Context)}.
     */
    public static void logCrash(@NonNull Context context,
                                @NonNull CrashHandlerClient crashHandlerClient,
                                @NonNull Thread thread,  @NonNull Throwable throwable) {
        Logger.logInfo(LOG_TAG, "logCrash() for " + thread +  ": " + throwable.getMessage());
        new CrashHandler(context, crashHandlerClient, false).logCrash(thread, throwable);
    }

    public void logCrash(@NonNull Thread thread, @NonNull Throwable throwable) {
        if (!mCrashHandlerClient.onPreLogCrash(mContext, thread, throwable)) {
            logCrashToFile(mContext, mCrashHandlerClient, thread, throwable);
            mCrashHandlerClient.onPostLogCrash(mContext, thread, throwable);
        }
    }

    public void logCrashToFile(@NonNull Context context,
                               @NonNull CrashHandlerClient crashHandlerClient,
                               @NonNull Thread thread, @NonNull Throwable throwable) {
        StringBuilder reportString = new StringBuilder();

        reportString.append("## Crash Details\n");
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Thread", thread.toString(), "-"));
        reportString.append("\n").append(MarkdownUtils.getSingleLineMarkdownStringEntry("Crash Timestamp", AndroidUtils.getCurrentMilliSecondUTCTimeStamp(), "-"));
        reportString.append("\n\n").append(MarkdownUtils.getMultiLineMarkdownStringEntry("Crash Message", throwable.getMessage(), "-"));
        reportString.append("\n\n").append(Logger.getStackTracesMarkdownString("Stacktrace", Logger.getStackTracesStringArray(throwable)));

        String appInfoMarkdownString = crashHandlerClient.getAppInfoMarkdownString(context);
        if (appInfoMarkdownString != null && !appInfoMarkdownString.isEmpty())
            reportString.append("\n\n").append(appInfoMarkdownString);

        reportString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context));

        Logger.logError(reportString.toString());

        Error error = FileUtils.writeTextToFile("crash log", crashHandlerClient.getCrashLogFilePath(context),
                        Charset.defaultCharset(), reportString.toString(), false);
        if (error != null) {
            Logger.logErrorExtended(LOG_TAG, error.toString());
        }
    }

    public interface CrashHandlerClient {

        /**
         * Called before {@link #logCrashToFile(Context, CrashHandlerClient, Thread, Throwable)} is called.
         *
         * @return {@code true} if crash has been handled and should not be logged, otherwise {@code false}.
         */
        boolean onPreLogCrash(Context context, Thread thread, Throwable throwable);

        /**
         * Called after {@link #logCrashToFile(Context, CrashHandlerClient, Thread, Throwable)} is called.
         */
        void onPostLogCrash(Context context, Thread thread, Throwable throwable);

        /**
         * Get crash log file path.
         */
        @NonNull
        String getCrashLogFilePath(Context context);

        /**
         * Get app info markdown string to add to crash log.
         */
        String getAppInfoMarkdownString(Context context);

    }

}

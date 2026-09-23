package com.termux.shared.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.errors.Error;
import com.termux.shared.errors.FunctionErrno;

public class ActivityUtils {

    private static final String LOG_TAG = "ActivityUtils";

    private static Error logAndReturnError(Error error, Context context, boolean logErrorMessage, boolean showErrorMessage) {
        if (logErrorMessage)
            error.logErrorAndShowToast(showErrorMessage ? context : null, LOG_TAG);
        return error;
    }

    /**
     * Wrapper for {@link #startActivity(Context, Intent, boolean, boolean)}.
     */
    public static Error startActivity(@NonNull Context context, @NonNull Intent intent) {
        return startActivity(context, intent, true, true);
    }

    /**
     * Start an {@link Activity}.
     *
     * @param context context.
     * @param intent intent to start the activity with.
     * @param logErrorMessage log an error message on failure.
     * @param showErrorMessage also toast the error on failure; {@code context} must not be
     *                         {@code null}.
     * @return Returns the {@code error} if starting activity was not successful, otherwise {@code null}.
     */
    public static Error startActivity(Context context, @NonNull Intent intent,
                                      boolean logErrorMessage, boolean showErrorMessage) {
        String activityName = intent.getComponent() != null ? intent.getComponent().getClassName() : "Unknown";

        if (context == null) {
            return logAndReturnError(ActivityErrno.ERRNO_STARTING_ACTIVITY_WITH_NULL_CONTEXT.getError(activityName),
                context, logErrorMessage, showErrorMessage);
        }

        try {
            context.startActivity(intent);
        } catch (Exception e) {
            return logAndReturnError(ActivityErrno.ERRNO_START_ACTIVITY_FAILED_WITH_EXCEPTION.getError(e, activityName, e.getMessage()),
                context, logErrorMessage, showErrorMessage);
        }

        return null;
    }

    /**
     * Wrapper for {@link #startActivityForResult(Context, int, Intent, boolean, boolean, ActivityResultLauncher)}.
     */
    public static Error startActivityForResult(Context context, int requestCode, @NonNull Intent intent) {
        return startActivityForResult(context, requestCode, intent, true, true, null);
    }

    /**
     * Wrapper for {@link #startActivityForResult(Context, int, Intent, boolean, boolean, ActivityResultLauncher)}.
     */
    public static Error startActivityForResult(Context context, int requestCode, @NonNull Intent intent,
                                               boolean logErrorMessage, boolean showErrorMessage) {
        return startActivityForResult(context, requestCode, intent, logErrorMessage, showErrorMessage, null);
    }

    /**
     * Start an {@link Activity} for result.
     *
     * @param context context; must be an {@link Activity} or {@link AppCompatActivity}, ignored
     *                if {@code activityResultLauncher} is not {@code null}.
     * @param requestCode request code (&gt;= 0); ignored if {@code activityResultLauncher} is
     *                    {@code null}.
     * @param intent intent to start the activity with.
     * @param logErrorMessage log an error message on failure.
     * @param showErrorMessage also toast the error on failure; {@code context} must not be
     *                         {@code null}.
     * @param activityResultLauncher launcher to use; if {@code null},
     *                               {@link Activity#startActivityForResult(Intent, int)} is used
     *                               instead (deprecated).
     * @return Returns the {@code error} if starting activity was not successful, otherwise {@code null}.
     */
    public static Error startActivityForResult(Context context, int requestCode, @NonNull Intent intent,
                                               boolean logErrorMessage, boolean showErrorMessage,
                                               @Nullable ActivityResultLauncher<Intent> activityResultLauncher) {
        String activityName = intent.getComponent() != null ? intent.getComponent().getClassName() : "Unknown";
        try {
            if (activityResultLauncher != null) {
                activityResultLauncher.launch(intent);
            } else {
                if (context == null) {
                    return logAndReturnError(ActivityErrno.ERRNO_STARTING_ACTIVITY_WITH_NULL_CONTEXT.getError(activityName),
                        context, logErrorMessage, showErrorMessage);
                }

                if (context instanceof AppCompatActivity)
                    ((AppCompatActivity) context).startActivityForResult(intent, requestCode);
                else if (context instanceof Activity)
                    ((Activity) context).startActivityForResult(intent, requestCode);
                else {
                    return logAndReturnError(FunctionErrno.ERRNO_PARAMETER_NOT_INSTANCE_OF.getError("context", "startActivityForResult", "Activity or AppCompatActivity"),
                        context, logErrorMessage, showErrorMessage);
                }
            }
        } catch (Exception e) {
            return logAndReturnError(ActivityErrno.ERRNO_START_ACTIVITY_FOR_RESULT_FAILED_WITH_EXCEPTION.getError(e, activityName, e.getMessage()),
                context, logErrorMessage, showErrorMessage);
        }

        return null;
    }

}

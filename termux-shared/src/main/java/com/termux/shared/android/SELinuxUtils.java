package com.termux.shared.android;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.reflection.ReflectionUtils;

import java.lang.reflect.Method;

public class SELinuxUtils {

    public static final String ANDROID_OS_SELINUX_CLASS = "android.os.SELinux";

    private static final String LOG_TAG = "SELinuxUtils";

    @Nullable
    private static String invokeSELinuxMethod(String methodName, Class<?>[] parameterTypes, Object... args) {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions();
        try {
            @SuppressLint("PrivateApi") Class<?> clazz = Class.forName(ANDROID_OS_SELINUX_CLASS);
            Method method = ReflectionUtils.getDeclaredMethod(clazz, methodName, parameterTypes);
            if (method == null) {
                Logger.logError(LOG_TAG, "Failed to get " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class");
                return null;
            }

            return (String) ReflectionUtils.invokeMethod(method, null, args).value;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to call " + methodName + "() method of " + ANDROID_OS_SELINUX_CLASS + " class", e);
            return null;
        }
    }

    /**
     * Gets the security context of the current process.
     *
     * @return the security context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getContext() {
        return invokeSELinuxMethod("getContext", new Class<?>[0]);
    }

    /**
     * Get the security context of a given process id.
     *
     * @param pid The pid of process.
     * @return the security context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getPidContext(int pid) {
        return invokeSELinuxMethod("getPidContext", new Class<?>[]{int.class}, pid);
    }

    /**
     * Get the security context of a file object.
     *
     * @param path The pathname of the file object.
     * @return the security context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getFileContext(@NonNull String path) {
        return invokeSELinuxMethod("getFileContext", new Class<?>[]{String.class}, path);
    }

}

package com.termux.shared.reflection;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public class ReflectionUtils {

    private static boolean HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED = Build.VERSION.SDK_INT < Build.VERSION_CODES.P;

    private static final String LOG_TAG = "ReflectionUtils";

    /**
     * Bypass android hidden API reflection restrictions.
     * https://github.com/LSPosed/AndroidHiddenApiBypass
     * https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
     */
    public static void bypassHiddenAPIReflectionRestrictions() {
        if (!HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Logger.logDebug(LOG_TAG, "Bypassing android hidden api reflection restrictions");
            try {
                HiddenApiBypass.addHiddenApiExemptions("");
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to bypass hidden API reflection restrictions", t);
            }

            HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED = true;
        }
    }

    /**
     * Get a {@link Field} for the specified class.
     *
     * @param clazz The {@link Class} for which to return the field.
     * @param fieldName The name of the {@link Field}.
     * @return Returns the {@link Field} if getting the it was successful, otherwise {@code null}.
     */
    @Nullable
    public static Field getDeclaredField(@NonNull Class<?> clazz, @NonNull String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" field for \"" + clazz.getName() + "\" class", e);
            return null;
        }
    }

    /** Class that represents result of invoking a field. */
    public static class FieldInvokeResult {
        public boolean success;
        public Object value;

        FieldInvokeResult(boolean success, Object value) {
            this.value = value;
        }
    }

    /**
     * Get a value for a {@link Field} of an object for the specified class.
     *
     * Trying to access {@code null} fields will result in {@link NoSuchFieldException}.
     *
     * @param clazz The {@link Class} to which the object belongs to.
     * @param fieldName The name of the {@link Field}.
     * @param object The {@link Object} instance from which to get the field value.
     * @return Returns the {@link FieldInvokeResult} of invoking the field. The
     * {@link FieldInvokeResult#success} will be {@code true} if invoking the field was successful,
     * otherwise {@code false}. The {@link FieldInvokeResult#value} will contain the field
     * {@link Object} value.
     */
    @NonNull
    public static <T> FieldInvokeResult invokeField(@NonNull Class<? extends T> clazz, @NonNull String fieldName, T object) {
        try {
            Field field = getDeclaredField(clazz, fieldName);
            if (field == null) return new FieldInvokeResult(false, null);
            return new FieldInvokeResult(true, field.get(object));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" field value for \"" + clazz.getName() + "\" class", e);
            return new FieldInvokeResult(false, null);
        }
    }

    /**
     * Wrapper for {@link #getDeclaredMethod(Class, String, Class[])} without parameters.
     */
    @Nullable
    public static Method getDeclaredMethod(@NonNull Class<?> clazz, @NonNull String methodName) {
        return getDeclaredMethod(clazz, methodName, new Class<?>[0]);
    }

    /**
     * Get a {@link Method} for the specified class with the specified parameters.
     *
     * @param clazz The {@link Class} for which to return the method.
     * @param methodName The name of the {@link Method}.
     * @param parameterTypes The parameter types of the method.
     * @return Returns the {@link Method} if getting the it was successful, otherwise {@code null}.
     */
    @Nullable
    public static Method getDeclaredMethod(@NonNull Class<?> clazz, @NonNull String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + methodName + "\" method for \"" + clazz.getName() + "\" class with parameter types: " + Arrays.toString(parameterTypes), e);
            return null;
        }
    }

    /** Class that represents result of invoking a method that has a non-void return type. */
    public static class MethodInvokeResult {
        public boolean success;
        public Object value;

        MethodInvokeResult(boolean success, Object value) {
            this.value = value;
        }
    }

    /**
     * Wrapper for {@link #invokeMethod(Method, Object, Object...)} without arguments.
     */
    @NonNull
    public static MethodInvokeResult invokeMethod(@NonNull Method method, Object obj) {
        return invokeMethod(method, obj, new Object[0]);
    }

    /**
     * Invoke a {@link Method} on the specified object with the specified arguments.
     *
     * @param method The {@link Method} to invoke.
     * @param obj The {@link Object} the method should be invoked from.
     * @param args The arguments to pass to the method.
     * @return Returns the {@link MethodInvokeResult} of invoking the method. The
     * {@link MethodInvokeResult#success} will be {@code true} if invoking the method was successful,
     * otherwise {@code false}. The {@link MethodInvokeResult#value} will contain the {@link Object}
     * returned by the method.
     */
    @NonNull
    public static MethodInvokeResult invokeMethod(@NonNull Method method, Object obj, Object... args) {
        try {
            method.setAccessible(true);
            return new MethodInvokeResult(true, method.invoke(obj, args));
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"" + method.getName() + "\" method with object \"" + obj + "\" and args: " + Arrays.toString(args), e);
            return new MethodInvokeResult(false, null);
        }
    }

}

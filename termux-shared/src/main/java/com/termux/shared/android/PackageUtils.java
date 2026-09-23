package com.termux.shared.android;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.shared.R;
import com.termux.shared.data.DataUtils;
import com.termux.shared.interact.MessageDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.reflection.ReflectionUtils;

import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.List;

public class PackageUtils {

    private static final String LOG_TAG = "PackageUtils";

    @Nullable
    private static <T> T getApplicationInfoFieldValue(@NonNull String fieldName, @Nullable ApplicationInfo applicationInfo, @NonNull Class<T> fieldType, @NonNull String logMessage) {
        ReflectionUtils.bypassHiddenAPIReflectionRestrictions();
        try {
            return fieldType.cast(ReflectionUtils.invokeField(ApplicationInfo.class, fieldName, applicationInfo).value);
        } catch (Exception e) {
            // ClassCastException may be thrown
            Logger.logStackTraceWithMessage(LOG_TAG, logMessage, e);
            return null;
        }
    }

    /**
     * Get the {@link Context} for the package name with {@link Context#CONTEXT_RESTRICTED} flags.
     *
     * @return the context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackage(@NonNull final Context context, String packageName) {
       return getContextForPackage(context, packageName, Context.CONTEXT_RESTRICTED);
    }

    /**
     * Get the {@link Context} for the package name.
     *
     * @param flags the {@link Context} flags.
     * @return the context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackage(@NonNull final Context context, String packageName, int flags) {
        try {
            return context.createPackageContext(packageName, flags);
        } catch (Exception e) {
            Logger.logVerbose(LOG_TAG, "Failed to get \"" + packageName + "\" package context with flags " + flags + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the {@link Context} for a package name; on failure may show a dialog that exits the app.
     *
     * @param exitAppOnError show an error dialog and exit the app if the context cannot be obtained.
     * @param helpUrl appended as help for the error dialog.
     * @return the context, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Context getContextForPackageOrExitApp(@NonNull Context context, String packageName,
                                                        final boolean exitAppOnError, @Nullable String helpUrl) {
        Context packageContext = getContextForPackage(context, packageName);

        if (packageContext == null && exitAppOnError) {
            String errorMessage = context.getString(R.string.error_get_package_context_failed_message,
                packageName);
            if (!DataUtils.isNullOrEmpty(helpUrl))
                errorMessage += "\n" + context.getString(R.string.error_get_package_context_failed_help_url_message, helpUrl);
            Logger.logError(LOG_TAG, errorMessage);
            MessageDialogUtils.exitAppWithErrorMessage(context,
                context.getString(R.string.error_get_package_context_failed_title),
                errorMessage);
        }

        return packageContext;
    }

    /**
     * Get the {@link PackageInfo} for the package associated with the {@code context}.
     *
     * @return the package info, or {@code null} if an exception is raised.
     */
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context) {
        return getPackageInfoForPackage(context, context.getPackageName());
    }

    /**
     * Get the {@link PackageInfo} for the package associated with the {@code context}.
     *
     * @param flags passed to {@link PackageManager#getPackageInfo(String, int)}.
     * @return the package info, or {@code null} if an exception is raised.
     */
    @Nullable
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context, final int flags) {
        return getPackageInfoForPackage(context, context.getPackageName(), flags);
    }

    /**
     * Get the {@link PackageInfo} for {@code packageName}.
     *
     * @return the package info, or {@code null} if an exception is raised.
     */
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getPackageInfoForPackage(context, packageName, 0);
    }

    /**
     * Get the {@link PackageInfo} for {@code packageName}.
     *
     * <p>May throw {@link PackageManager.NameNotFoundException} when targeting sdk 30; see
     * {@link #isAppInstalled(Context, String, String)}.
     *
     * @param flags passed to {@link PackageManager#getPackageInfo(String, int)}.
     * @return the package info, or {@code null} if an exception is raised.
     */
    @Nullable
    public static PackageInfo getPackageInfoForPackage(@NonNull final Context context, @NonNull final String packageName, final int flags) {
        try {
            return context.getPackageManager().getPackageInfo(packageName, flags);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Get the {@link ApplicationInfo} for {@code packageName}.
     *
     * @return the application info, or {@code null} if an exception is raised.
     */
    @Nullable
    public static ApplicationInfo getApplicationInfoForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getApplicationInfoForPackage(context, packageName, 0);
    }

    /**
     * Get the {@link ApplicationInfo} for {@code packageName}.
     *
     * <p>May throw {@link PackageManager.NameNotFoundException} when targeting sdk 30; see
     * {@link #isAppInstalled(Context, String, String)}.
     *
     * @param flags passed to {@link PackageManager#getApplicationInfo(String, int)}.
     * @return the application info, or {@code null} if an exception is raised.
     */
    @Nullable
    public static ApplicationInfo getApplicationInfoForPackage(@NonNull final Context context, @NonNull final String packageName, final int flags) {
        try {
            return context.getPackageManager().getApplicationInfo(packageName, flags);
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Read the {@code privateFlags} field of {@link ApplicationInfo} via reflection.
     *
     * @return the private flags, or {@code null} if an exception was raised.
     */
    @Nullable
    public static Integer getApplicationInfoPrivateFlagsForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return getApplicationInfoFieldValue("privateFlags", applicationInfo, Integer.class,
            "Failed to get privateFlags field value for ApplicationInfo class");
    }

    /**
     * Read the {@code seInfo}/{@code seinfo} field of {@link ApplicationInfo} (SELinux security
     * context for the process and data directory; settable via mac_permissions.xml).
     *
     * <p>https://cs.android.com/android/platform/superproject/+/android-7.1.0_r1:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=609
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/core/java/android/content/pm/ApplicationInfo.java;l=981
     * https://cs.android.com/android/platform/superproject/+/android-7.0.0_r1:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.java;l=282
     * https://cs.android.com/android/platform/superproject/+/android-12.0.0_r32:frameworks/base/services/core/java/com/android/server/pm/SELinuxMMAC.java;l=375
     * https://cs.android.com/android/_/android/platform/frameworks/base/+/be0b8896d1bc385d4c8fb54c21929745935dcbea
     *
     * @return the selinux info, or {@code null} if an exception was raised.
     */
    @Nullable
    public static String getApplicationInfoSeInfoForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return getApplicationInfoFieldValue(Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? "seinfo" : "seInfo", applicationInfo, String.class,
            "Failed to get seInfo field value for ApplicationInfo class");
    }

    /**
     * Read the {@code seInfoUser} field of {@link ApplicationInfo} (API 26+); see
     * {@link #getApplicationInfoSeInfoForPackage(ApplicationInfo)}.
     *
     * @return the selinux info user, or {@code null} if unavailable/an exception was raised.
     */
    @Nullable
    public static String getApplicationInfoSeInfoUserForPackage(@NonNull final ApplicationInfo applicationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        return getApplicationInfoFieldValue("seInfoUser", applicationInfo, String.class,
            "Failed to get seInfoUser field value for ApplicationInfo class");
    }

    /**
     * Read a static int field of {@link ApplicationInfo} via reflection.
     *
     * @return the field value, or {@code null} if an exception was raised.
     */
    @Nullable
    public static Integer getApplicationInfoStaticIntFieldValue(@NonNull String fieldName) {
        return getApplicationInfoFieldValue(fieldName, null, Integer.class,
            "Failed to get \"" + fieldName + "\" field value for ApplicationInfo class");
    }

    /**
     * Check if a specific private flag is set on the app's {@link ApplicationInfo}.
     *
     * @return {@code true} if set, {@code false} if not, {@code null} if an exception is raised.
     */
    @Nullable
    public static Boolean isApplicationInfoPrivateFlagSetForPackage(@NonNull String flagToCheckName, @NonNull final ApplicationInfo applicationInfo) {
        Integer privateFlags = getApplicationInfoPrivateFlagsForPackage(applicationInfo);
        if (privateFlags == null) return null;

        Integer flagToCheck = getApplicationInfoStaticIntFieldValue(flagToCheckName);
        if (flagToCheck == null) return null;

        return ( 0 != ( privateFlags & flagToCheck ) );
    }

    /** App label ({@code android:name}) for the package of {@code context}. */
    public static String getAppNameForPackage(@NonNull final Context context) {
        return getAppNameForPackage(context, context.getApplicationInfo());
    }

    /** App label ({@code android:name}) for {@code applicationInfo}. */
    public static String getAppNameForPackage(@NonNull final Context context, @NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.loadLabel(context.getPackageManager()).toString();
    }

    /** Package name for the package of {@code context}. */
    public static String getPackageNameForPackage(@NonNull final Context context) {
        return getPackageNameForPackage(context.getApplicationInfo());
    }

    /** Package name for {@code applicationInfo}. */
    public static String getPackageNameForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.packageName;
    }

    /** UID for the package of {@code context}. */
    public static int getUidForPackage(@NonNull final Context context) {
        return getUidForPackage(context.getApplicationInfo());
    }

    /** UID for {@code applicationInfo}. */
    public static int getUidForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.uid;
    }

    /** {@code targetSdkVersion} for the package of {@code context}. */
    public static int getTargetSDKForPackage(@NonNull final Context context) {
        return getTargetSDKForPackage(context.getApplicationInfo());
    }

    /** {@code targetSdkVersion} for {@code applicationInfo}. */
    public static int getTargetSDKForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.targetSdkVersion;
    }

    /** Base apk path for the package of {@code context}. */
    public static String getBaseAPKPathForPackage(@NonNull final Context context) {
        return getBaseAPKPathForPackage(context.getApplicationInfo());
    }

    /** Base apk path for {@code applicationInfo}. */
    public static String getBaseAPKPathForPackage(@NonNull final ApplicationInfo applicationInfo) {
        return applicationInfo.publicSourceDir;
    }

    /** Whether the app of {@code context} has {@link ApplicationInfo#FLAG_DEBUGGABLE} set. */
    public static boolean isAppForPackageADebuggableBuild(@NonNull final Context context) {
        return isAppForPackageADebuggableBuild(context.getApplicationInfo());
    }

    /** Whether {@code applicationInfo} has {@link ApplicationInfo#FLAG_DEBUGGABLE} set. */
    public static boolean isAppForPackageADebuggableBuild(@NonNull final ApplicationInfo applicationInfo) {
        return ( 0 != ( applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE ) );
    }

    /** Whether the app of {@code context} has {@link ApplicationInfo#FLAG_EXTERNAL_STORAGE} set. */
    public static boolean isAppInstalledOnExternalStorage(@NonNull final Context context) {
        return isAppInstalledOnExternalStorage(context.getApplicationInfo());
    }

    /** Whether {@code applicationInfo} has {@link ApplicationInfo#FLAG_EXTERNAL_STORAGE} set. */
    public static boolean isAppInstalledOnExternalStorage(@NonNull final ApplicationInfo applicationInfo) {
        return ( 0 != ( applicationInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE ) );
    }

    /**
     * Whether the app of {@code context} requests legacy external storage
     * (PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE).
     *
     * @return {@code true}/{@code false}, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Boolean hasRequestedLegacyExternalStorage(@NonNull final Context context) {
        return hasRequestedLegacyExternalStorage(context.getApplicationInfo());
    }

    /**
     * Whether {@code applicationInfo} requests legacy external storage
     * (PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE).
     *
     * @return {@code true}/{@code false}, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Boolean hasRequestedLegacyExternalStorage(@NonNull final ApplicationInfo applicationInfo) {
        return isApplicationInfoPrivateFlagSetForPackage("PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE", applicationInfo);
    }

    /**
     * {@code versionCode} for the package of {@code context}.
     *
     * @return the version code, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Integer getVersionCodeForPackage(@NonNull final Context context) {
        return getVersionCodeForPackage(context, context.getPackageName());
    }

    /**
     * {@code versionCode} for {@code packageName}.
     *
     * @return the version code, or {@code null} if an exception is raised.
     */
    @Nullable
    public static Integer getVersionCodeForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getVersionCodeForPackage(getPackageInfoForPackage(context, packageName));
    }

    /**
     * {@code versionCode} for {@code packageInfo}.
     *
     * @return the version code, or {@code null} if {@code packageInfo} is {@code null}.
     */
    @Nullable
    public static Integer getVersionCodeForPackage(@Nullable final PackageInfo packageInfo) {
        return packageInfo != null ? packageInfo.versionCode : null;
    }

    /**
     * {@code versionName} for the package of {@code context}.
     *
     * @return the version name, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getVersionNameForPackage(@NonNull final Context context) {
        return getVersionNameForPackage(context, context.getPackageName());
    }

    /**
     * {@code versionName} for {@code packageName}.
     *
     * @return the version name, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getVersionNameForPackage(@NonNull final Context context, @NonNull final String packageName) {
        return getVersionNameForPackage(getPackageInfoForPackage(context, packageName));
    }

    /**
     * {@code versionName} for {@code packageInfo}.
     *
     * @return the version name, or {@code null} if {@code packageInfo} is {@code null}.
     */
    @Nullable
    public static String getVersionNameForPackage(@Nullable final PackageInfo packageInfo) {
        return packageInfo != null ? packageInfo.versionName : null;
    }

    /**
     * SHA-256 digest of the signing certificate for the package of {@code context}.
     *
     * @return the digest, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getSigningCertificateSHA256DigestForPackage(@NonNull final Context context) {
        return getSigningCertificateSHA256DigestForPackage(context, context.getPackageName());
    }

    /**
     * SHA-256 digest of the signing certificate for {@code packageName}.
     *
     * @return the digest, or {@code null} if an exception is raised.
     */
    @Nullable
    public static String getSigningCertificateSHA256DigestForPackage(@NonNull final Context context, @NonNull final String packageName) {
        try {
            /*
             * Todo: We may need AndroidManifest queries entries if package is installed but with a different signature on android 11
             * https://developer.android.com/training/package-visibility
             * Need a device that allows (manual) installation of apk with mismatched signature of
             * sharedUserId apps to test. Currently, if its done, PackageManager just doesn't load
             * the package and removes its apk automatically if its installed as a user app instead of system app
             * W/PackageManager: Failed to parse /path/to/com.termux.tasker.apk: Signature mismatch for shared user: SharedUserSetting{xxxxxxx com.termux/10xxx}
             */
            PackageInfo packageInfo = getPackageInfoForPackage(context, packageName, PackageManager.GET_SIGNATURES);
            if (packageInfo == null) return null;
            return DataUtils.bytesToHex(MessageDigest.getInstance("SHA-256").digest(packageInfo.signatures[0].toByteArray()));
        } catch (final Exception e) {
            return null;
        }
    }

    /**
     * Serial number of the user owning the package of {@code context}.
     *
     * @return the user id, or {@code null} if failed to get it.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Nullable
    public static Long getUserIdForPackage(@NonNull Context context) {
        UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (userManager == null) return null;
        return userManager.getSerialNumberForUser(UserHandle.getUserHandleForUid(getUidForPackage(context)));
    }

    /**
     * Whether the current user is the primary user (serial number equals 0).
     *
     * @return {@code true} for the primary user.
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static boolean isCurrentUserThePrimaryUser(@NonNull Context context) {
        Long userId = getUserIdForPackage(context);
        return userId != null && userId == 0;
    }

    /**
     * Profile owner package name for the current user.
     *
     * @return the package name, or {@code null} if none/failure.
     */
    @Nullable
    public static String getProfileOwnerPackageNameForUser(@NonNull Context context) {
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (devicePolicyManager == null) return null;
        List<ComponentName> activeAdmins = devicePolicyManager.getActiveAdmins();
        if (activeAdmins != null){
            for (ComponentName admin:activeAdmins){
                String packageName = admin.getPackageName();
                if(devicePolicyManager.isProfileOwnerApp(packageName))
                    return packageName;
            }
        }
        return null;
    }

    /**
     * PID of the main process of {@code packageName} (works for sharedUserId; note some apps
     * declare extra processes via {@code android:process=":background"}).
     *
     * @return the pid as a string, or {@code null} if not found/running.
     */
    @Nullable
    public static String getPackagePID(final Context context, String packageName) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            List<ActivityManager.RunningAppProcessInfo> processInfos = activityManager.getRunningAppProcesses();
            if (processInfos != null) {
                ActivityManager.RunningAppProcessInfo processInfo;
                for (int i = 0; i < processInfos.size(); i++) {
                    processInfo = processInfos.get(i);
                    if (processInfo.processName.equals(packageName))
                        return String.valueOf(processInfo.pid);
                }
            }
        }
        return null;
    }

    /**
     * Check if app is installed and enabled. External apps without sharedUserId targeting sdk 30
     * need the package in {@code <queries>} or {@code QUERY_ALL_PACKAGES}, otherwise
     * {@link PackageManager.NameNotFoundException} may be thrown (see package-visibility docs).
     *
     * <p>{@code
     * <manifest
     *     <queries>
     *         <package android:name="com.termux" />
     *    </queries>
     *
     *    <application
     *        ....
     *    </application>
     * </manifest>
     * }
     *
     * @return an error message if not installed/disabled, otherwise {@code null}.
     */
    public static String isAppInstalled(@NonNull final Context context, String appName, String packageName) {
        String errmsg = null;

        ApplicationInfo applicationInfo = getApplicationInfoForPackage(context, packageName);
        boolean isAppEnabled = (applicationInfo != null && applicationInfo.enabled);

        // App not installed or disabled
        if (!isAppEnabled)
            errmsg = context.getString(R.string.error_app_not_installed_or_disabled_warning, appName, packageName);

        return errmsg;
    }

    /** Wrapper for {@link #setComponentState(Context, String, String, boolean, String, boolean, boolean)}
     * with {@code alwaysShowToast} {@code true}. */
    public static String setComponentState(@NonNull final Context context, @NonNull String packageName,
                                           @NonNull String className, boolean newState, String toastString,
                                           boolean showErrorMessage) {
        return setComponentState(context, packageName, className, newState, toastString, showErrorMessage, true);
    }

    /**
     * Enable or disable a {@link ComponentName} via
     * {@link PackageManager#setComponentEnabledSetting(ComponentName, int, int)}.
     *
     * @param newState whether to enable or disable.
     * @param toastString optional toast before setting state when non-empty.
     * @param showErrorMessage show an error toast on failure.
     * @param alwaysShowToast show the toast even if the state already matches.
     * @return an error message if failed, otherwise {@code null}.
     */
    @Nullable
    public static String setComponentState(@NonNull final Context context, @NonNull String packageName,
                                           @NonNull String className, boolean newState, String toastString,
                                           boolean alwaysShowToast, boolean showErrorMessage) {
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager != null) {
                if (toastString != null && alwaysShowToast) {
                    Logger.showToast(context, toastString, true);
                    toastString = null;
                }

                Boolean currentlyDisabled = PackageUtils.isComponentDisabled(context, packageName, className, false);
                if (currentlyDisabled == null)
                    throw new UnsupportedOperationException("Failed to find if component currently disabled");

                Boolean setState = null;
                if (newState && currentlyDisabled)
                    setState = true;
                else if (!newState && !currentlyDisabled)
                    setState = false;

                if (setState == null) return null;

                if (toastString != null) Logger.showToast(context, toastString, true);
                ComponentName componentName = new ComponentName(packageName, className);
                packageManager.setComponentEnabledSetting(componentName,
                    setState ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            }
            return null;
        } catch (final Exception e) {
            String errmsg = context.getString(
                newState ? R.string.error_enable_component_failed : R.string.error_disable_component_failed,
                packageName, className) + ": " + e.getMessage();
            if (showErrorMessage)
                Logger.showToast(context, errmsg, true);
            return errmsg;
        }
    }

    /**
     * Whether a {@link ComponentName} is
     * {@link PackageManager#COMPONENT_ENABLED_STATE_DISABLED} (via
     * {@link PackageManager#getComponentEnabledSetting(ComponentName)}).
     *
     * @param logErrorMessage log an error if the state cannot be read.
     * @return {@code true} if disabled, {@code false} if not, {@code null} on failure.
     */
    public static Boolean isComponentDisabled(@NonNull final Context context, @NonNull String packageName,
                                              @NonNull String className, boolean logErrorMessage) {
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager != null) {
                ComponentName componentName = new ComponentName(packageName, className);
                // Will throw IllegalArgumentException: Unknown component: ComponentInfo{} if app
                // for context is not installed or component does not exist.
                return packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            }
        } catch (final Exception e) {
            if (logErrorMessage)
                Logger.logStackTraceWithMessage(LOG_TAG, context.getString(R.string.error_get_component_state_failed, packageName, className), e);
        }

        return null;
    }

    /**
     * Whether an activity component can be launched, via
     * {@link PackageManager#queryIntentActivities(Intent, int)}.
     *
     * @param flags filter flags for the query.
     * @return {@code true} if it exists.
     */
    public static boolean doesActivityComponentExist(@NonNull final Context context, @NonNull String packageName,
                                                     @NonNull String className, int flags) {
        try {
            PackageManager packageManager = context.getPackageManager();
            if (packageManager != null) {
                Intent intent = new Intent();
                intent.setClassName(packageName, className);
                return packageManager.queryIntentActivities(intent, flags).size() > 0;
            }
        } catch (final Exception e) {
            // ignore
        }

        return false;
    }

}

package com.termux.shared.android.resource;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.data.DataUtils;
import com.termux.shared.logger.Logger;

public class ResourceUtils {

    public static final String RES_TYPE_COLOR = "color";
    public static final String RES_TYPE_DRAWABLE = "drawable";
    public static final String RES_TYPE_ID = "id";
    public static final String RES_TYPE_LAYOUT = "layout";
    public static final String RES_TYPE_STRING = "string";
    public static final String RES_TYPE_STYLE = "style";

    private static final String LOG_TAG = "ResourceUtils";

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} without {@code defPackage}. */
    @Nullable
    public static Integer getResourceId(@NonNull Context context, String name,
                                        @Nullable String defType,
                                        boolean logErrorMessage) {
        return getResourceId(context, name, defType, null, logErrorMessage);
    }

    /**
     * Get the resource identifier for the given resource name. A fully qualified name is of the
     * form "package:type/entry"; the package and type components may come from
     * {@code defPackage}/{@code defType} instead (each {@code null} requires it explicitly in the
     * name).
     *
     * @return the resource id, or {@code null} on exception / not found.
     */
    @Nullable
    public static Integer getResourceId(@NonNull Context context, String name,
                                        @Nullable String defType, @Nullable String defPackage,
                                        boolean logErrorMessage) {
        if (DataUtils.isNullOrEmpty(name)) return null;

        Integer resourceId = null;
        try {
            resourceId = context.getResources().getIdentifier(name, defType, defPackage);
            if (resourceId == 0) resourceId = null;
        } catch (Exception e) {
            // Ignore
        }

        if (resourceId == null && logErrorMessage) {
            Logger.logError(LOG_TAG, "Resource id not found. name: \"" + name + "\", type: \"" + defType+ "\", package: \"" + defPackage + "\", component \"" + context.getClass().getName() + "\"");
        }

        return resourceId;
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_COLOR}. */
    @Nullable
    public static Integer getColorResourceId(@NonNull Context context, String name,
                                             @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_COLOR, defPackage, logErrorMessage);
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_DRAWABLE}. */
    @Nullable
    public static Integer getDrawableResourceId(@NonNull Context context, String name,
                                                @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_DRAWABLE, defPackage, logErrorMessage);
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_ID}. */
    @Nullable
    public static Integer getIdResourceId(@NonNull Context context, String name,
                                          @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_ID, defPackage, logErrorMessage);
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_LAYOUT}. */
    @Nullable
    public static Integer getLayoutResourceId(@NonNull Context context, String name,
                                              @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_LAYOUT, defPackage, logErrorMessage);
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_STRING}. */
    @Nullable
    public static Integer getStringResourceId(@NonNull Context context, String name,
                                              @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_STRING, defPackage, logErrorMessage);
    }

    /** Wrapper for {@link #getResourceId(Context, String, String, String, boolean)} with {@code RES_TYPE_STYLE}. */
    @Nullable
    public static Integer getStyleResourceId(@NonNull Context context, String name,
                                             @Nullable String defPackage, boolean logErrorMessage) {
        return getResourceId(context, name, RES_TYPE_STYLE, defPackage, logErrorMessage);
    }

}

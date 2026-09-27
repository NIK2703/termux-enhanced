package com.termux.app.activities;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.termux.R;
import com.termux.app.TermuxActivityUtils;
import com.termux.app.TermuxLocaleUtils;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.file.FileUtils;
import com.termux.shared.models.ReportInfo;
import com.termux.app.models.UserAction;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.android.PackageUtils;
import com.termux.shared.android.AndroidUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.DisplayCutoutUtils;

public class SettingsActivity extends AppCompatActivity {

    @Nullable private DisplayCutoutUtils.CutoutInsetsListener mCutoutInsetsListener;

    @Override
    protected void attachBaseContext(@NonNull Context base) {
        super.attachBaseContext(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Honour Display > "Screen orientation": the requested orientation is per-activity, so
        // TermuxActivity locking itself has no effect here. The initial orientation on open is
        // decided by the system before any of our code runs and only the manifest can inform it:
        // android:screenOrientation="behind" (see AndroidManifest.xml) makes the window follow
        // TermuxActivity below, so there is no rotation on open. This call is the authority for
        // every other case — launcher shortcut, stale value, the user changing the setting while
        // this activity is alive, and the bubble, where the preference is "follow the device".
        // Applied before setContentView() so a change takes effect before the first layout.
        TermuxActivityUtils.applyScreenOrientation(this);

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_settings);
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new RootPreferencesFragment())
                    .commit();
        }

        AppCompatActivityUtils.setToolbar(this, com.termux.shared.R.id.toolbar);
        AppCompatActivityUtils.setShowBackButtonInActionBar(this, true);

        // Give the settings header a slightly different background from the content so it does
        // not blend in, and match the status bar to it so the two read as one bar.
        int headerColor = getResources().getColor(com.termux.shared.R.color.settings_header_background, getTheme());
        // The insets are applied on the root, since it is the only view spanning the window: with
        // the header handling them, the list below was laid out right under the bars and the cutout.
        // The root is deliberately NOT painted the header colour — the preference list is
        // transparent, so that would tint the whole screen instead of just the inset band (the
        // band is covered by the opaque status bar anyway).
        // The id of the <include> below overrides the one on the included root, so
        // R.id.toolbar_container is NOT what that view answers to.
        View root = findViewById(R.id.settings_root);
        if (root != null) {
            mCutoutInsetsListener = new DisplayCutoutUtils.CutoutInsetsListener(root);
            root.setOnApplyWindowInsetsListener(mCutoutInsetsListener);
        }
        View header = findViewById(R.id.partial_primary_toolbar);
        if (header != null) {
            header.setBackgroundColor(headerColor);
        }
        // The Toolbar child has its own opaque ?attr/colorSurface background that paints over the
        // container, so it must be tinted too or the header colour never shows.
        View toolbar = findViewById(com.termux.shared.R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(headerColor);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            // Match the status bar colour to the settings header so it reads as one continuous bar.
            window.setStatusBarColor(headerColor);
        }

        applyWindowStyling();
    }

    /**
     * Apply the Display &gt; window settings to this window, the same way
     * {@link TermuxActivity} applies them to the terminal window: fullscreen, the display cutout, and
     * the status bar icon appearance.
     *
     * <p>The cutout part matters here on its own: the status bar here is opaque rather than
     * transparent, so it covers the cutout while it is visible — but fullscreen hides it, and then
     * the cutout strip would be outside the window and show as a black gap.
     *
     * <p>{@code LAYOUT_FULLSCREEN} is what puts the content under the status bar, and through it the
     * cutout. The root view keeps the bar's height as padding; the bar itself is opaque in the
     * header colour, so the band it covers needs no background of its own.
     */
    private void applyWindowStyling() {
        final TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this, true);
        final boolean fullScreen = prefs != null && prefs.isUsingFullScreen();

        DisplayCutoutUtils.allowWindowIntoCutout(getWindow());

        if (fullScreen)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

        int visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
        // In light theme the header is light, so force dark status-bar text/icons (API 23+); in
        // night theme keep the default light text.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    != Configuration.UI_MODE_NIGHT_YES)
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(visibility);

        if (mCutoutInsetsListener != null)
            mCutoutInsetsListener.setExtend(prefs != null && prefs.isExtendIntoCutout());
    }

    @Override
    protected void onDestroy() {
        // Clear the light status-bar flag so it doesn't leak to other activities.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class RootPreferencesFragment extends TermuxPreferenceFragmentBase {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = getContext();
            if (context == null) return;

            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            new Thread() {
                @Override
                public void run() {
                    configureAboutPreference(context);
                    configureDonatePreference(context);
                    configureLocalePreference();
                }
            }.start();
        }

        private void configureAboutPreference(@NonNull Context context) {
            Preference aboutPreference = findPreference("about");
            if (aboutPreference != null) {
                aboutPreference.setOnPreferenceClickListener(preference -> {
                    new Thread() {
                        @Override
                        public void run() {
                            String title = getString(com.termux.R.string.about_preference_title);

                            StringBuilder aboutString = new StringBuilder();
                            aboutString.append(TermuxUtils.getAppInfoMarkdownString(context, TermuxUtils.AppInfoMode.TERMUX_AND_PLUGIN_PACKAGES));
                            aboutString.append("\n\n").append(AndroidUtils.getDeviceInfoMarkdownString(context, true));
                            aboutString.append("\n\n").append(TermuxUtils.getImportantLinksMarkdownString(context));

                            String userActionName = UserAction.ABOUT.getName();

                            ReportInfo reportInfo = new ReportInfo(userActionName,
                                TermuxConstants.TERMUX_APP.TERMUX_SETTINGS_ACTIVITY_NAME, title);
                            reportInfo.setReportString(aboutString.toString());
                            reportInfo.setReportSaveFileLabelAndPath(userActionName,
                                Environment.getExternalStorageDirectory() + "/" +
                                    FileUtils.sanitizeFileName(TermuxConstants.TERMUX_APP_NAME + "-" + userActionName + ".log", true, true));

                            ReportActivity.startReportActivity(context, reportInfo);
                        }
                    }.start();

                    return true;
                });
            }
        }

        private void configureDonatePreference(@NonNull Context context) {
            Preference donatePreference = findPreference("donate");
            if (donatePreference != null) {
                String signingCertificateSHA256Digest = PackageUtils.getSigningCertificateSHA256DigestForPackage(context);
                if (signingCertificateSHA256Digest != null) {
                    // If APK is a Google Playstore release, then do not show the donation link
                    // since Termux isn't exempted from the playstore policy donation links restriction
                    // Check Fund solicitations: https://pay.google.com/intl/en_in/about/policy/
                    String apkRelease = TermuxUtils.getAPKRelease(signingCertificateSHA256Digest);
                    if (apkRelease == null || apkRelease.equals(TermuxConstants.APK_RELEASE_GOOGLE_PLAYSTORE_SIGNING_CERTIFICATE_SHA256_DIGEST)) {
                        donatePreference.setVisible(false);
                        return;
                    } else {
                        donatePreference.setVisible(true);
                    }
                }

                donatePreference.setOnPreferenceClickListener(preference -> {
                    ShareUtils.openUrl(context, TermuxConstants.TERMUX_DONATE_URL);
                    return true;
                });
            }
        }

        private void configureLocalePreference() {
            final ListPreference localePref = findPreference("locale_override");
            if (localePref != null) {
                localePref.setValue(TermuxLocaleUtils.getLocaleOverride());
                localePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    TermuxLocaleUtils.applyLocale((String) newValue);
                    return true;
                });
            }
        }
    }

}

package com.termux.shared.activities;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import com.google.android.material.button.MaterialButton;
import com.termux.shared.R;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.file.filesystem.FileType;
import com.termux.shared.logger.Logger;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.markdown.MarkdownUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.models.ReportAction;
import com.termux.shared.models.ReportInfo;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.theme.NightMode;

import org.commonmark.node.FencedCodeBlock;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

import io.noties.markwon.Markwon;
import io.noties.markwon.recycler.MarkwonAdapter;
import io.noties.markwon.recycler.SimpleEntry;

/**
 * An activity to show reports in markdown format as per CommonMark spec based on config passed as {@link ReportInfo}.
 * Add Following to `AndroidManifest.xml` to use in an app:
 * {@code `<activity android:name="com.termux.shared.activities.ReportActivity" android:theme="@style/Theme.AppCompat.TermuxReportActivity" android:documentLaunchMode="intoExisting" />` }
 * and
 * {@code `<receiver android:name="com.termux.shared.activities.ReportActivity$ReportActivityBroadcastReceiver"  android:exported="false" />` }
 * Receiver **must not** be `exported="true"`!!!
 *
 * Also make an incremental call to {@link #deleteReportInfoFilesOlderThanXDays(Context, int, boolean)}
 * in the app to cleanup cached files.
 */
public class ReportActivity extends AppCompatActivity {

    private static final String CLASS_NAME = ReportActivity.class.getCanonicalName();
    private static final String ACTION_DELETE_REPORT_INFO_OBJECT_FILE = CLASS_NAME + ".ACTION_DELETE_REPORT_INFO_OBJECT_FILE";

    private static final String EXTRA_REPORT_INFO_OBJECT = CLASS_NAME + ".EXTRA_REPORT_INFO_OBJECT";
    private static final String EXTRA_REPORT_INFO_OBJECT_FILE_PATH = CLASS_NAME + ".EXTRA_REPORT_INFO_OBJECT_FILE_PATH";

    private static final String CACHE_DIR_BASENAME = "report_activity";
    private static final String CACHE_FILE_BASENAME_PREFIX = "report_info_";

    public static final int REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE = 1000;

    public static final int ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES = 1000 * 1024; // 1MB

    private ReportInfo mReportInfo;
    private String mReportInfoFilePath;
    private String mReportActivityMarkdownString;
    private Bundle mBundle;

    private static final String LOG_TAG = "ReportActivity";

    /**
     * Host app hook that contributes the extra action buttons shown on report screens — see
     * {@link ReportActionHost}. {@code null} when the host app does not use it, in which case report
     * screens look and behave exactly as they did before.
     */
    private static ReportActionHost sReportActionHost;

    /** Adapter for the report list, which prepends the host app's action buttons (see below). */
    private ReportListAdapter mReportListAdapter;

    /**
     * Set the {@link ReportActionHost} that contributes the extra action buttons of report screens.
     * Should be called before the first {@link ReportActivity} is started, typically from the host
     * app's {@code Application.onCreate()}.
     */
    public static void setReportActionHost(final ReportActionHost host) {
        sReportActionHost = host;
    }

    /**
     * The report/About screen deliberately does <b>not</b> wrap its context with the
     * Termux:Style colour scheme ({@code SchemeDialogTheme.wrapActivityTheme}): this is an app
     * screen, not a terminal surface, so it must follow the app day/night theme. Scheming it
     * made the window (and the overflow popup menu) unreadable whenever the app theme and the
     * terminal colour scheme disagreed — e.g. black window + black text in light mode.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logVerbose(LOG_TAG, "onCreate");

        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);

        setContentView(R.layout.activity_report);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
        }

        // Chrome follows the app day/night theme (not the terminal colour scheme) so the report
        // stays readable in light mode: surface background + on-surface text/icons.
        applyAppThemeToChrome();

        mBundle = null;
        Intent intent = getIntent();
        if (intent != null)
            mBundle = intent.getExtras();
        else if (savedInstanceState != null)
            mBundle = savedInstanceState;

        updateUI();

    }

    /**
     * Paint the window, toolbar and status bar with the app day/night theme colours.
     *
     * <p>Every colour is resolved from the same theme the content is inflated with
     * ({@code ?attr/colorSurface} / {@code ?android:attr/textColorPrimary}), so background and
     * text can never be taken from two different sources and end up unreadable. This also keeps
     * the red {@code colorPrimary} of the base theme out of the toolbar.
     */
    private void applyAppThemeToChrome() {
        // Fallbacks follow the actual night mode so even an unresolved attribute cannot produce
        // an unreadable (black-on-black / white-on-white) screen.
        final boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        final int surface = getThemeColor(com.google.android.material.R.attr.colorSurface,
                night ? 0xFF000000 : 0xFFFFFFFF);
        final int onSurface = getThemeColor(android.R.attr.textColorPrimary,
                night ? 0xFFFFFFFF : 0xFF000000);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setBackgroundColor(surface);
            toolbar.setTitleTextColor(onSurface);
            tintDrawable(toolbar.getNavigationIcon(), onSurface);
            tintDrawable(toolbar.getOverflowIcon(), onSurface);
        }

        Window window = getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setBackgroundDrawable(new ColorDrawable(surface));
        window.setStatusBarColor(surface);
        // Icons must contrast with the (now themed) status bar background.
        new WindowInsetsControllerCompat(window, window.getDecorView())
                .setAppearanceLightStatusBars(ColorSchemeUtils.isColorLight(surface));
    }

    /** Tint {@code drawable} with {@code color}, if present. */
    private static void tintDrawable(android.graphics.drawable.Drawable drawable, int color) {
        if (drawable == null) return;
        DrawableCompat.setTintList(drawable, ColorStateList.valueOf(color));
    }

    /** Resolve a colour attribute of this activity's theme, with a fallback. */
    private int getThemeColor(int attr, int fallback) {
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT)
                return value.data;
            if (value.resourceId != 0) {
                try {
                    return ContextCompat.getColor(this, value.resourceId);
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Failed to resolve theme colour: " + e.getMessage());
                }
            }
        }
        return fallback;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Logger.logVerbose(LOG_TAG, "onNewIntent");

        setIntent(intent);

        if (intent != null) {
            deleteReportInfoFile(this, mReportInfoFilePath);
            mBundle = intent.getExtras();
            updateUI();
        }
    }

    private void updateUI() {

        if (mBundle == null) {
            finish(); return;
        }

        mReportInfo = null;
        mReportInfoFilePath = null;

        if (mBundle.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
            mReportInfoFilePath = mBundle.getString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH);
            Logger.logVerbose(LOG_TAG, ReportInfo.class.getSimpleName() + " serialized object will be read from file at path \"" + mReportInfoFilePath + "\"");
            if (mReportInfoFilePath != null) {
                try {
                    FileUtils.ReadSerializableObjectResult result = FileUtils.readSerializableObjectFromFile(ReportInfo.class.getSimpleName(), mReportInfoFilePath, ReportInfo.class, false);
                    if (result.error != null) {
                        Logger.logErrorExtended(LOG_TAG, result.error.toString());
                        Logger.showToast(this, Error.getMinimalErrorString(result.error), true);
                        finish(); return;
                    } else {
                        if (result.serializableObject != null)
                            mReportInfo = (ReportInfo) result.serializableObject;
                    }
                } catch (Exception e) {
                    Logger.logErrorAndShowToast(this, LOG_TAG, e.getMessage());
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failure while getting " + ReportInfo.class.getSimpleName() + " serialized object from file at path \"" + mReportInfoFilePath + "\"", e);
                }
            }
        } else {
            mReportInfo = (ReportInfo) mBundle.getSerializable(EXTRA_REPORT_INFO_OBJECT);
        }

        if (mReportInfo == null) {
            finish(); return;
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            if (mReportInfo.reportTitle != null)
                actionBar.setTitle(mReportInfo.reportTitle);
            else
                actionBar.setTitle(TermuxConstants.TERMUX_APP_NAME + " App Report");
        }

        RecyclerView recyclerView = findViewById(R.id.recycler_view);

        final Markwon markwon = MarkdownUtils.getRecyclerMarkwonBuilder(this);

        final MarkwonAdapter adapter = MarkwonAdapter.builderTextViewIsRoot(R.layout.markdown_adapter_node_default)
            .include(FencedCodeBlock.class, SimpleEntry.create(R.layout.markdown_adapter_node_code_block, R.id.code_text_view))
            .build();

        mReportListAdapter = new ReportListAdapter(adapter);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(mReportListAdapter);

        generateReportActivityMarkdownString();
        adapter.setMarkdown(markwon, mReportActivityMarkdownString);
        adapter.notifyDataSetChanged();

        setupReportActions();
    }

    /**
     * Hand the host app's action buttons to the list, which shows them as its first items
     * (see {@link ReportListAdapter}).
     */
    private void setupReportActions() {
        if (mReportListAdapter == null) return;

        if (sReportActionHost == null || mReportInfo == null) {
            mReportListAdapter.setActions(null);
            return;
        }

        final List<ReportAction> actions;
        try {
            actions = sReportActionHost.getReportActions(this, mReportInfo);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get report actions from the host app", e);
            mReportListAdapter.setActions(null);
            return;
        }
        mReportListAdapter.setActions(actions);
    }

    /**
     * Build one outlined action button, coloured from the app day/night theme rather than the
     * button style defaults — keeps the theme's red colorPrimary (avoided elsewhere on this screen)
     * out of the buttons and guarantees readable text in both modes.
     */
    private MaterialButton createReportActionButton() {
        final boolean night = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;

        final int surface = getThemeColor(com.google.android.material.R.attr.colorSurface,
                night ? 0xFF000000 : 0xFFFFFFFF);
        final int onSurface = getThemeColor(android.R.attr.textColorPrimary,
                night ? 0xFFFFFFFF : 0xFF000000);

        MaterialButton button = new MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setAllCaps(false);
        button.setTextColor(onSurface);
        // Same colour as the text, at 25% alpha, so the outline is visible but not a hard border.
        button.setStrokeColor(ColorStateList.valueOf((onSurface & 0x00FFFFFF) | 0x40000000));
        button.setBackgroundTintList(ColorStateList.valueOf(surface));
        return button;
    }

    /** Hand a clicked action button over to the host app. */
    private void dispatchReportAction(final ReportAction action) {
        if (sReportActionHost == null) return;
        Logger.logInfo(LOG_TAG, "Report action \"" + action.id + "\" clicked");
        try {
            sReportActionHost.onReportActionClicked(this, mReportInfo, action);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to handle report action \"" + action.id + "\"", e);
        }
    }

    /**
     * Wraps the markdown adapter so the host app's action buttons become the report list's FIRST
     * items: they sit at the very top of the report and scroll away with it, rather than being
     * pinned under the toolbar. This is what {@code ConcatAdapter} does, but this module compiles
     * against RecyclerView 1.1.0, which does not have it yet, so the header is prepended by hand.
     */
    private final class ReportListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        /** View type of an action button item. Markdown item types are shifted by one to stay clear. */
        private static final int VIEW_TYPE_ACTION = 0;

        /** The markdown adapter that renders the report itself. */
        private final RecyclerView.Adapter<RecyclerView.ViewHolder> mReportAdapter;

        private final List<ReportAction> mActions = new ArrayList<>();

        /** Keeps the RecyclerView in sync when the markdown adapter replaces the whole report. */
        private final RecyclerView.AdapterDataObserver mReportAdapterObserver = new RecyclerView.AdapterDataObserver() {
            @Override public void onChanged() { notifyDataSetChanged(); }
            @Override public void onItemRangeChanged(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeInserted(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeRemoved(int positionStart, int itemCount) { notifyDataSetChanged(); }
            @Override public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) { notifyDataSetChanged(); }
        };

        @SuppressWarnings("unchecked")
        ReportListAdapter(@NonNull RecyclerView.Adapter<?> reportAdapter) {
            mReportAdapter = (RecyclerView.Adapter<RecyclerView.ViewHolder>) reportAdapter;
            mReportAdapter.registerAdapterDataObserver(mReportAdapterObserver);
        }

        void setActions(@Nullable final List<ReportAction> actions) {
            mActions.clear();
            if (actions != null) {
                for (ReportAction action : actions) {
                    if (action != null && !action.title.isEmpty()) mActions.add(action);
                }
            }
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return mActions.size() + mReportAdapter.getItemCount();
        }

        @Override
        public int getItemViewType(int position) {
            if (isActionItem(position)) return VIEW_TYPE_ACTION;
            return mReportAdapter.getItemViewType(position - mActions.size()) + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_ACTION) {
                MaterialButton button = createReportActionButton();
                final int sidePadding = getResources().getDimensionPixelSize(R.dimen.content_padding);
                RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.leftMargin = sidePadding;
                params.rightMargin = sidePadding;
                params.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density + 0.5f);
                button.setLayoutParams(params);
                return new RecyclerView.ViewHolder(button) {};
            }
            return mReportAdapter.onCreateViewHolder(parent, viewType - 1);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (isActionItem(position)) {
                final ReportAction action = mActions.get(position);
                MaterialButton button = (MaterialButton) holder.itemView;
                button.setText(action.title);
                button.setOnClickListener(v -> dispatchReportAction(action));
                return;
            }
            mReportAdapter.onBindViewHolder(holder, position - mActions.size());
        }

        private boolean isActionItem(int position) {
            return position < mActions.size();
        }

    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mBundle.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
            outState.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, mReportInfoFilePath);
        } else {
            outState.putSerializable(EXTRA_REPORT_INFO_OBJECT, mReportInfo);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Logger.logVerbose(LOG_TAG, "onDestroy");

        deleteReportInfoFile(this, mReportInfoFilePath);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_report, menu);

        if (mReportInfo.reportSaveFilePath == null) {
            MenuItem item = menu.findItem(R.id.menu_item_save_report_to_file);
            if (item != null)
                item.setEnabled(false);
        }

        return true;
    }

    @Override
    public void onBackPressed() {
        // Remove activity from recents menu on back button press
        finishAndRemoveTask();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_item_share_report) {
            ShareUtils.shareText(this, getString(R.string.title_report_text), ReportInfo.getReportInfoMarkdownString(mReportInfo));
        } else if (id == R.id.menu_item_copy_report) {
            ShareUtils.copyTextToClipboard(this, ReportInfo.getReportInfoMarkdownString(mReportInfo), null);
        } else if (id == R.id.menu_item_save_report_to_file) {
            ShareUtils.saveTextToFile(this, mReportInfo.reportSaveFileLabel,
                mReportInfo.reportSaveFilePath, ReportInfo.getReportInfoMarkdownString(mReportInfo),
                true, REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE);
        }

        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Forward every result to the host app, so it can receive the results of the intents it
        // started from ReportActionHost.onReportActionClicked() (e.g. a file chosen with
        // ACTION_CREATE_DOCUMENT). The host ignores request codes it does not own.
        if (sReportActionHost == null) return;
        try {
            sReportActionHost.onReportActionActivityResult(this, mReportInfo, requestCode, resultCode, data);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to forward activity result to the host app", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Logger.logInfo(LOG_TAG, "Storage permission granted by user on request.");
            if (requestCode == REQUEST_GRANT_STORAGE_PERMISSION_FOR_SAVE_FILE) {
                ShareUtils.saveTextToFile(this, mReportInfo.reportSaveFileLabel,
                    mReportInfo.reportSaveFilePath, ReportInfo.getReportInfoMarkdownString(mReportInfo),
                    true, -1);
            }
        } else {
            Logger.logInfo(LOG_TAG, "Storage permission denied by user on request.");
        }
    }

    private void generateReportActivityMarkdownString() {
        // We need to reduce chances of OutOfMemoryError happening so reduce new allocations and
        // do not keep output of getReportInfoMarkdownString in memory
        StringBuilder reportString = new StringBuilder();

        if (mReportInfo.reportStringPrefix != null)
            reportString.append(mReportInfo.reportStringPrefix);

        String reportMarkdownString = ReportInfo.getReportInfoMarkdownString(mReportInfo);
        int reportMarkdownStringSize = reportMarkdownString.getBytes().length;
        boolean truncated = false;
        if (reportMarkdownStringSize > ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES) {
            Logger.logVerbose(LOG_TAG, mReportInfo.reportTitle + " report string size " + reportMarkdownStringSize + " is greater than " + ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES + " and will be truncated");
            reportString.append(DataUtils.getTruncatedCommandOutput(reportMarkdownString, ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES, true, false, true));
            truncated = true;
        } else {
            reportString.append(reportMarkdownString);
        }

        // Free reference
        reportMarkdownString = null;

        if (mReportInfo.reportStringSuffix != null)
            reportString.append(mReportInfo.reportStringSuffix);

        int reportStringSize = reportString.length();
        if (reportStringSize > ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES) {
            // This may break markdown formatting
            Logger.logVerbose(LOG_TAG, mReportInfo.reportTitle + " report string total size " + reportStringSize + " is greater than " + ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES + " and will be truncated");
            mReportActivityMarkdownString = this.getString(R.string.msg_report_truncated) +
                DataUtils.getTruncatedCommandOutput(reportString.toString(), ACTIVITY_TEXT_SIZE_LIMIT_IN_BYTES, true, false, false);
        } else if (truncated) {
            mReportActivityMarkdownString = this.getString(R.string.msg_report_truncated) + reportString.toString();
        } else {
            mReportActivityMarkdownString = reportString.toString();
        }

    }

    public static class NewInstanceResult {
        /** An intent that can be used to start the {@link ReportActivity}. */
        public Intent contentIntent;
        /** An intent that can should be adding as the {@link android.app.Notification#deleteIntent}
         * by a call to {@link android.app.PendingIntent#getBroadcast(Context, int, Intent, int)}
         * so that {@link ReportActivityBroadcastReceiver} can do cleanup of {@link #EXTRA_REPORT_INFO_OBJECT_FILE_PATH}. */
        public Intent deleteIntent;

        NewInstanceResult(Intent contentIntent, Intent deleteIntent) {
            this.contentIntent = contentIntent;
            this.deleteIntent = deleteIntent;
        }
    }

    /**
     * Start the {@link ReportActivity}.
     *
     * @param context The {@link Context} for operations.
     * @param reportInfo The {@link ReportInfo} containing info that needs to be displayed.
     */
    public static void startReportActivity(@NonNull final Context context, @NonNull ReportInfo reportInfo) {
        NewInstanceResult result = newInstance(context, reportInfo);
        if (result.contentIntent == null) return;
        context.startActivity(result.contentIntent);
    }

    /**
     * Get content and delete intents for the {@link ReportActivity} that can be used to start it
     * and do cleanup.
     *
     * If {@link ReportInfo} is too large for a Bundle (TransactionTooLargeException), its object is
     * written to a file in {@link Context#getCacheDir()}, read back on start, and deleted in
     * {@link #onDestroy()}. If that never runs, sweep leftovers with
     * {@link #deleteReportInfoFilesOlderThanXDays(Context, int, boolean)} at app startup.
     *
     * @param context The {@link Context} for operations.
     * @param reportInfo The {@link ReportInfo} containing info that needs to be displayed.
     * @return Returns {@link NewInstanceResult}.
     */
    @NonNull
    public static NewInstanceResult newInstance(@NonNull final Context context, @NonNull final ReportInfo reportInfo) {

        long size = DataUtils.getSerializedSize(reportInfo);
        if (size > DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES) {
            String reportInfoDirectoryPath = getReportInfoDirectoryPath(context);
            String reportInfoFilePath = reportInfoDirectoryPath + "/" + CACHE_FILE_BASENAME_PREFIX + reportInfo.reportTimestamp;
            Logger.logVerbose(LOG_TAG, reportInfo.reportTitle + " " + ReportInfo.class.getSimpleName() + " serialized object size " + size + " is greater than " + DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES + " and it will be written to file at path \"" + reportInfoFilePath + "\"");
            Error error = FileUtils.writeSerializableObjectToFile(ReportInfo.class.getSimpleName(), reportInfoFilePath, reportInfo);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString());
                Logger.showToast(context, Error.getMinimalErrorString(error), true);
                return new NewInstanceResult(null, null);
            }

            return new NewInstanceResult(createContentIntent(context, null, reportInfoFilePath),
                createDeleteIntent(context, reportInfoFilePath));
        } else {
            return new NewInstanceResult(createContentIntent(context, reportInfo, null),
                null);
        }
    }

    private static Intent createContentIntent(@NonNull final Context context, final ReportInfo reportInfo, final String reportInfoFilePath) {
        Intent intent = new Intent(context, ReportActivity.class);
        Bundle bundle = new Bundle();

        if (reportInfoFilePath != null) {
            bundle.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, reportInfoFilePath);
        } else {
            bundle.putSerializable(EXTRA_REPORT_INFO_OBJECT, reportInfo);
        }

        intent.putExtras(bundle);

        // Note that ReportActivity should have `documentLaunchMode="intoExisting"` set in `AndroidManifest.xml`
        // which has equivalent behaviour to FLAG_ACTIVITY_NEW_DOCUMENT.
        // FLAG_ACTIVITY_SINGLE_TOP must also be passed for onNewIntent to be called.
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        return intent;
    }

    private static Intent createDeleteIntent(@NonNull final Context context, final String reportInfoFilePath) {
        if (reportInfoFilePath == null) return null;

        Intent intent = new Intent(context, ReportActivityBroadcastReceiver.class);
        intent.setAction(ACTION_DELETE_REPORT_INFO_OBJECT_FILE);

        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH, reportInfoFilePath);
        intent.putExtras(bundle);

        return intent;
    }

    @NotNull
    private static String getReportInfoDirectoryPath(Context context) {
        // Canonicalize to solve /data/data and /data/user/0 issues when comparing with reportInfoFilePath
        return FileUtils.getCanonicalPath(context.getCacheDir().getAbsolutePath(), null) + "/" + CACHE_DIR_BASENAME;
    }

    private static void deleteReportInfoFile(Context context, String reportInfoFilePath) {
        if (context == null || reportInfoFilePath == null) return;

        // Extra protection for mainly if someone set `exported="true"` for ReportActivityBroadcastReceiver
        String reportInfoDirectoryPath = getReportInfoDirectoryPath(context);
        reportInfoFilePath = FileUtils.getCanonicalPath(reportInfoFilePath, null);
        if(!reportInfoFilePath.equals(reportInfoDirectoryPath) && reportInfoFilePath.startsWith(reportInfoDirectoryPath + "/")) {
            Logger.logVerbose(LOG_TAG, "Deleting " + ReportInfo.class.getSimpleName() + " serialized object file at path \"" + reportInfoFilePath + "\"");
            Error error = FileUtils.deleteRegularFile(ReportInfo.class.getSimpleName(), reportInfoFilePath, true);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, error.toString());
            }
        } else {
            Logger.logError(LOG_TAG, "Not deleting " + ReportInfo.class.getSimpleName() + " serialized object file at path \"" + reportInfoFilePath + "\" since its not under \"" + reportInfoDirectoryPath + "\"");
        }
    }

    /**
     * Delete {@link ReportInfo} serialized object files from cache older than x days. If a
     * notification has still not been opened after x days, opening it will throw a file-not-found
     * error — choose the days value accordingly. The {@link Context} must be of the same package
     * {@link #newInstance(Context, ReportInfo)} was called with ({@link Context#getCacheDir()}).
     *
     * @param context The {@link Context} for operations.
     * @param days The x amount of days before which files should be deleted. This must be `>=0`.
     * @param isSynchronous If {@code true}, run on the caller thread and return the result;
     *                      otherwise start a background thread, log any error and return null.
     * @return Returns the {@code error} if deleting was not successful, otherwise {@code null}.
     */
    public static Error deleteReportInfoFilesOlderThanXDays(@NonNull final Context context, int days, final boolean isSynchronous) {
        if (isSynchronous) {
            return deleteReportInfoFilesOlderThanXDaysInner(context, days);
        } else {
            new Thread() { public void run() {
                Error error = deleteReportInfoFilesOlderThanXDaysInner(context, days);
                if (error != null) {
                    Logger.logErrorExtended(LOG_TAG, error.toString());
                }
            }}.start();
            return null;
        }
    }

    private static Error deleteReportInfoFilesOlderThanXDaysInner(@NonNull final Context context, int days) {
        // Only regular files are deleted and subdirectories are not checked
        String reportInfoDirectoryPath = getReportInfoDirectoryPath(context);
        Logger.logVerbose(LOG_TAG, "Deleting " + ReportInfo.class.getSimpleName() + " serialized object files under directory path \"" + reportInfoDirectoryPath + "\" older than " + days + " days");
        return FileUtils.deleteFilesOlderThanXDays(ReportInfo.class.getSimpleName(), reportInfoDirectoryPath, null, days, true, FileType.REGULAR.getValue());
    }

    /**
     * Implemented by the host app to add custom action buttons to report screens — e.g. the
     * "back up the container" / "back up app settings" buttons on a crash report — without
     * {@link ReportActivity} having to know anything about them: the host supplies the (already
     * localized) labels and handles the clicks.
     *
     * <p>All callbacks are invoked on the main thread.
     *
     * @see #setReportActionHost(ReportActionHost)
     */
    public interface ReportActionHost {

        /**
         * Get the actions to show as buttons under the toolbar of the report screen for
         * {@code reportInfo}.
         *
         * @param activity The {@link Activity} showing the report.
         * @param reportInfo The report that is being shown.
         * @return The actions to show, or {@code null}/empty for none — the button row is hidden
         *         then, so a host only has to return actions for the reports it cares about.
         */
        @Nullable
        List<ReportAction> getReportActions(@NonNull Activity activity, @NonNull ReportInfo reportInfo);

        /**
         * Called when the button of {@code action} is clicked.
         *
         * <p>The host may start another activity for a result with
         * {@link Activity#startActivityForResult(Intent, int)}; the result is then delivered to
         * {@link #onReportActionActivityResult(Activity, ReportInfo, int, int, Intent)}.
         */
        void onReportActionClicked(@NonNull Activity activity, @NonNull ReportInfo reportInfo,
                                   @NonNull ReportAction action);

        /**
         * Called for every activity result received while the report screen is open, so the host can
         * receive the results of intents it started from
         * {@link #onReportActionClicked(Activity, ReportInfo, ReportAction)}. Request codes the host
         * does not own must be ignored.
         */
        void onReportActionActivityResult(@NonNull Activity activity, @NonNull ReportInfo reportInfo,
                                          int requestCode, int resultCode, @Nullable Intent data);

    }

    /**
     * The {@link BroadcastReceiver} for {@link ReportActivity} that currently does cleanup when
     * {@link android.app.Notification#deleteIntent} is called. It must be registered in `AndroidManifest.xml`.
     */
    public static class ReportActivityBroadcastReceiver extends BroadcastReceiver {
        private static final String LOG_TAG = "ReportActivityBroadcastReceiver";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            String action = intent.getAction();
            Logger.logVerbose(LOG_TAG, "onReceive: \"" + action + "\" action");

            if (ACTION_DELETE_REPORT_INFO_OBJECT_FILE.equals(action)) {
                Bundle bundle = intent.getExtras();
                if (bundle == null) return;
                if (bundle.containsKey(EXTRA_REPORT_INFO_OBJECT_FILE_PATH)) {
                    deleteReportInfoFile(context, bundle.getString(EXTRA_REPORT_INFO_OBJECT_FILE_PATH));
                }
            }
        }
    }

}

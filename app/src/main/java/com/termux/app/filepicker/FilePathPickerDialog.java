package com.termux.app.filepicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A picker for the real filesystem paths that are inserted into the terminal input panel.
 *
 * <p>Uses {@link File} rather than the Storage Access Framework for two reasons: the terminal
 * needs paths that a shell can use, not content URIs, and the SAF file picker cannot select
 * directories. A single dialog that allows ticking any mix of files and folders is therefore only
 * possible on the real hierarchy. The list starts in the current session's directory, so the
 * common case is one tap away.
 */
public final class FilePathPickerDialog {

    public interface OnPathsPickedListener {
        /** Called after the dialog has closed with the paths in the order they were ticked. */
        void onPathsPicked(@NonNull List<String> paths);
    }

    private static final Comparator<Entry> ENTRY_COMPARATOR = (first, second) -> {
        if (first.directory != second.directory) return first.directory ? -1 : 1;
        return first.name.compareToIgnoreCase(second.name);
    };

    private static final String PREFS_NAME = "file_path_picker_bookmarks";
    private static final String KEY_BOOKMARKS = "bookmarks";

    @NonNull private final Context mContext;
    @NonNull private final OnPathsPickedListener mListener;
    @Nullable private final Runnable mOnDismissed;
    @NonNull private final LinkedHashSet<String> mSelectedPaths = new LinkedHashSet<>();
    @NonNull private final LinkedHashSet<String> mBookmarks = new LinkedHashSet<>();
    @NonNull private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    @NonNull private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    @NonNull private final FileAdapter mAdapter = new FileAdapter();

    @Nullable private AlertDialog mDialog;
    @Nullable private ListView mListView;
    @Nullable private TextView mPathView;
    @Nullable private TextView mEmptyView;
    @Nullable private ImageButton mUpButton;
    @Nullable private Button mInsertButton;
    @Nullable private HorizontalScrollView mBookmarksScroll;
    @Nullable private LinearLayout mBookmarksContainer;

    @Nullable private File mCurrentDirectory;
    private long mLoadGeneration;
    private boolean mDismissed;

    private FilePathPickerDialog(@NonNull Context context,
                                 @NonNull OnPathsPickedListener listener,
                                 @Nullable Runnable onDismissed) {
        mContext = context;
        mListener = listener;
        mOnDismissed = onDismissed;
    }

    /**
     * Show the picker, starting at {@code initialDirectory} when it is readable and falling back
     * to the Termux home directory, the shared storage root and {@code /} in that order.
     *
     * @return the dialog, so the caller can dismiss it when its window goes away
     */
    @NonNull
    public static FilePathPickerDialog show(@NonNull Context context,
                                            @Nullable String initialDirectory,
                                            @NonNull OnPathsPickedListener listener,
                                            @Nullable Runnable onDismissed) {
        // Inflate the custom view with the same dialog overlay the AlertDialog window uses.
        // TermuxActivity's context carries the runtime colour-scheme overlay, whose colours are
        // not guaranteed to contrast with the dialog surface (and may reference attributes the
        // activity theme does not define). The dialog overlay pins readable light/dark colours.
        Context dialogContext = new ContextThemeWrapper(context, R.style.ThemeOverlay_TermuxActivity_Dialog);
        FilePathPickerDialog picker = new FilePathPickerDialog(dialogContext, listener, onDismissed);
        picker.show(resolveStartDirectory(initialDirectory));
        return picker;
    }

    /** Join selected paths for the input panel: single spaces only, no quoting or escaping. */
    @NonNull
    public static String joinPathsForInsertion(@NonNull List<String> paths) {
        StringBuilder joined = new StringBuilder();
        for (String path : paths) {
            if (joined.length() > 0) joined.append(' ');
            joined.append(path);
        }
        return joined.toString();
    }

    public void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    private void show(@NonNull File startDirectory) {
        View content = LayoutInflater.from(mContext)
            .inflate(R.layout.dialog_file_path_picker, null);

        mPathView = content.findViewById(R.id.file_path_picker_path);
        mListView = content.findViewById(R.id.file_path_picker_list);
        mEmptyView = content.findViewById(R.id.file_path_picker_empty);
        mUpButton = content.findViewById(R.id.file_path_picker_up);
        mBookmarksScroll = content.findViewById(R.id.file_path_picker_bookmarks_scroll);
        mBookmarksContainer = content.findViewById(R.id.file_path_picker_bookmarks);
        loadBookmarks();
        rebuildBookmarks();

        mListView.setAdapter(mAdapter);
        mUpButton.setOnClickListener(v -> navigateUp());
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = mAdapter.getItem(position);
            if (entry.directory) {
                loadDirectory(entry.file);
            } else {
                togglePath(entry.file.getAbsolutePath());
            }
        });

        mDialog = new MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.file_path_picker_title)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.file_path_picker_insert, null)
            .create();
        mDialog.setOnShowListener(dialog -> {
            mInsertButton = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            mInsertButton.setOnClickListener(v -> insertSelectedPaths());
            updateSelectionUi();
        });
        mDialog.setOnDismissListener(dialog -> {
            mDismissed = true;
            mMainHandler.removeCallbacksAndMessages(null);
            mExecutor.shutdownNow();
            if (mOnDismissed != null) mOnDismissed.run();
        });
        mDialog.show();

        // MaterialAlertDialog measures its custom panel with the window height. Making the
        // window full-height lets the list container (weight 1) use all space that the title,
        // bookmarks and button row do not need instead of leaving a large empty gap below a
        // fixed-height list on phones.
        Window window = mDialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.height = WindowManager.LayoutParams.MATCH_PARENT;
            window.setAttributes(attributes);
        }

        loadDirectory(startDirectory);
    }

    private int dp(int value) {
        return Math.round(value * mContext.getResources().getDisplayMetrics().density);
    }

    private void loadDirectory(@NonNull File directory) {
        if (mDismissed) return;

        mCurrentDirectory = directory;
        if (mPathView != null) mPathView.setText(directory.getAbsolutePath());
        if (mUpButton != null) mUpButton.setEnabled(directory.getParentFile() != null);

        mLoadGeneration++;
        mAdapter.setEntries(Collections.emptyList());
        showEmptyState(R.string.file_path_picker_loading);
        updateSelectionUi();

        final long generation = mLoadGeneration;
        mExecutor.execute(() -> {
            List<Entry> entries = null;
            try {
                File[] children = directory.listFiles();
                if (children != null) {
                    entries = new ArrayList<>(children.length);
                    for (File child : children) entries.add(new Entry(child));
                    Collections.sort(entries, ENTRY_COMPARATOR);
                }
            } catch (SecurityException ignored) {
                // Treated exactly like listFiles() returning null: the folder is unreadable.
            }

            final List<Entry> loadedEntries = entries;
            mMainHandler.post(() -> {
                if (mDismissed || generation != mLoadGeneration) return;

                if (loadedEntries == null) {
                    mAdapter.setEntries(Collections.emptyList());
                    showEmptyState(R.string.file_path_picker_unreadable);
                } else if (loadedEntries.isEmpty()) {
                    mAdapter.setEntries(loadedEntries);
                    showEmptyState(R.string.file_path_picker_empty);
                } else {
                    mAdapter.setEntries(loadedEntries);
                    hideEmptyState();
                }
            });
        });
    }

    private void navigateUp() {
        if (mCurrentDirectory == null) return;
        File parent = mCurrentDirectory.getParentFile();
        if (parent != null) loadDirectory(parent);
    }

    /**
     * Rebuild the quick-navigation chips under the title. A bookmark is only ever created by
     * inserting paths while its folder is the picker's current directory; there is no manual
     * add action, so an empty list simply hides the row.
     */
    private void rebuildBookmarks() {
        if (mBookmarksContainer == null || mBookmarksScroll == null) return;

        mBookmarksContainer.removeAllViews();
        if (mBookmarks.isEmpty()) {
            mBookmarksScroll.setVisibility(View.GONE);
            return;
        }

        mBookmarksScroll.setVisibility(View.VISIBLE);
        for (String path : mBookmarks) {
            TextView bookmark = new TextView(mContext);
            bookmark.setText(getBookmarkName(path));
            bookmark.setTextSize(14);
            bookmark.setTextColor(MaterialColors.getColor(
                bookmark, com.google.android.material.R.attr.colorOnSurface));
            bookmark.setGravity(Gravity.CENTER);
            bookmark.setIncludeFontPadding(false);
            bookmark.setSingleLine(true);
            bookmark.setMinHeight(dp(32));
            bookmark.setPadding(dp(12), dp(6), dp(12), dp(6));
            bookmark.setBackgroundResource(R.drawable.bg_file_path_picker_bookmark);
            bookmark.setClickable(true);
            bookmark.setFocusable(true);

            int margin = dp(4);
            LinearLayout.LayoutParams bookmarkParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            bookmarkParams.setMargins(margin, 0, margin, 0);
            bookmark.setLayoutParams(bookmarkParams);

            bookmark.setContentDescription(path);
            bookmark.setOnClickListener(v -> {
                File directory = new File(path);
                if (directory.isDirectory() && directory.canRead()) loadDirectory(directory);
            });
            mBookmarksContainer.addView(bookmark);
        }
    }

    /**
     * The bookmark label is the last segment of the path, i.e. the text after the last slash.
     * Root has no segment, so it keeps the slash as its label.
     */
    @NonNull
    static String getBookmarkName(@NonNull String path) {
        String normalized = path;
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        int lastSlash = normalized.lastIndexOf('/');
        String name = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
        return name.isEmpty() ? "/" : name;
    }

    private void loadBookmarks() {
        String json = prefs().getString(KEY_BOOKMARKS, null);
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                String path = array.optString(i, "");
                if (!TextUtils.isEmpty(path)) mBookmarks.add(path);
            }
        } catch (JSONException ignored) {
            // A corrupt bookmark list must not prevent the picker from opening.
        }
    }

    private void addBookmark(@NonNull String path) {
        if (mBookmarks.add(path)) saveBookmarks();
    }

    private void saveBookmarks() {
        JSONArray array = new JSONArray();
        for (String path : mBookmarks) array.put(path);
        prefs().edit().putString(KEY_BOOKMARKS, array.toString()).apply();
    }

    private SharedPreferences prefs() {
        return mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void showEmptyState(int textResId) {
        if (mListView != null) mListView.setVisibility(View.GONE);
        if (mEmptyView != null) {
            mEmptyView.setText(textResId);
            mEmptyView.setVisibility(View.VISIBLE);
        }
    }

    private void hideEmptyState() {
        if (mListView != null) mListView.setVisibility(View.VISIBLE);
        if (mEmptyView != null) mEmptyView.setVisibility(View.GONE);
    }

    private void togglePath(@NonNull String path) {
        if (!mSelectedPaths.remove(path)) mSelectedPaths.add(path);
        mAdapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (mInsertButton != null) mInsertButton.setEnabled(!mSelectedPaths.isEmpty());
    }

    private void insertSelectedPaths() {
        if (mSelectedPaths.isEmpty()) return;
        // Remember the folder the paths were inserted from, so it can be revisited in one tap.
        if (mCurrentDirectory != null) addBookmark(mCurrentDirectory.getAbsolutePath());
        List<String> paths = new ArrayList<>(mSelectedPaths);
        if (mDialog != null) mDialog.dismiss();
        mListener.onPathsPicked(paths);
    }

    @NonNull
    private static File resolveStartDirectory(@Nullable String initialDirectory) {
        if (!TextUtils.isEmpty(initialDirectory)) {
            File directory = new File(initialDirectory);
            if (directory.isDirectory() && directory.canRead()) return directory;
        }

        File home = TermuxConstants.TERMUX_HOME_DIR;
        if (home.isDirectory() && home.canRead()) return home;

        File externalStorage = Environment.getExternalStorageDirectory();
        if (externalStorage != null && externalStorage.isDirectory() && externalStorage.canRead()) {
            return externalStorage;
        }

        return new File("/");
    }

    private final class FileAdapter extends BaseAdapter {
        @NonNull private List<Entry> mEntries = Collections.emptyList();

        void setEntries(@NonNull List<Entry> entries) {
            mEntries = entries;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return mEntries.size(); }

        @Override public Entry getItem(int position) { return mEntries.get(position); }

        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(mContext)
                    .inflate(R.layout.item_file_path_picker, parent, false);
                Holder holder = new Holder();
                holder.icon = view.findViewById(R.id.file_path_picker_item_icon);
                holder.name = view.findViewById(R.id.file_path_picker_item_name);
                holder.check = view.findViewById(R.id.file_path_picker_item_check);
                view.setTag(holder);
            }

            Holder holder = (Holder) view.getTag();
            Entry entry = getItem(position);
            holder.icon.setImageResource(entry.directory ? R.drawable.ic_folder : R.drawable.ic_file);
            holder.name.setText(entry.directory ? entry.name + "/" : entry.name);
            holder.check.setChecked(mSelectedPaths.contains(entry.file.getAbsolutePath()));
            holder.check.setContentDescription(entry.name);
            holder.check.setOnClickListener(v -> togglePath(entry.file.getAbsolutePath()));
            return view;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView name;
        CheckBox check;
    }

    private static final class Entry {
        @NonNull final File file;
        @NonNull final String name;
        final boolean directory;

        Entry(@NonNull File file) {
            this.file = file;
            String fileName = file.getName();
            this.name = TextUtils.isEmpty(fileName) ? file.getAbsolutePath() : fileName;
            this.directory = file.isDirectory();
        }
    }
}

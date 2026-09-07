package com.termux.app.fragments.settings.termux;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.fragments.settings.TermuxPreferenceFragmentBase;
import com.termux.app.terminal.TermuxColorSchemeManager;
import com.termux.shared.termux.extrakeys.ColorSchemeUtils;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.BindingTokenizer;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.theme.ThemeUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

@Keep
public class ExtraKeysEditorFragment extends TermuxPreferenceFragmentBase {

    private static final int DIR_TAP = 0;
    private static final int DIR_UP = 1;
    private static final int DIR_DOWN = 2;
    private static final int DIR_LEFT = 3;
    private static final int DIR_RIGHT = 4;
    private static final int MAX_ROWS = 4;
    private static final int MAX_COLS = 10;
    private static final String TAG = "ExtraKeysEditor";

    /** Version of the profile export/import file format. */
    private static final int FORMAT_VERSION = 1;
    private static final int REQUEST_CODE_EXPORT_PROFILE = 2001;
    private static final int REQUEST_CODE_IMPORT_PROFILE = 2002;

    private static class KeyCell {
        // Each binding is a list of signal tokens. A single token may itself contain
        // spaces (e.g. a custom literal key like "ls -la"); multiple tokens form a macro
        // (space = token separator) such as "CTRL c".
        List<String> tap = new ArrayList<>();
        List<String> swipeUp = new ArrayList<>();
        List<String> swipeDown = new ArrayList<>();
        List<String> swipeLeft = new ArrayList<>();
        List<String> swipeRight = new ArrayList<>();
        /** Custom key label; empty = auto-generated from the main action macro. */
        String display = "";
    }

    /** One named profile: a list of prefixes + a JSON layout matrix. */
    private static class SessionProfile {
        final List<String> prefixes = new ArrayList<>();
        String layout = ""; // extra-keys JSON matrix string
    }

    private TermuxAppSharedPreferences mPrefs;
    private ExtraKeysView mPreviewView;
    private KeyCell[][] mGrid;
    private int mRows = 2;
    private int mCols = 5;
    private ExtraKeysConstants.ExtraKeyDisplayMap mDisplayMap;

    private ExtraKeysView.EditorMode mCurrentMode = ExtraKeysView.EditorMode.ASSIGN;
    @Nullable
    private TextView mHintTextView;

    @Nullable
    private Spinner mProfileSpinner;
    @Nullable
    private ImageButton mProfileDeleteBtn;
    private ImageButton mProfileExportBtn;
    private ImageButton mProfileImportBtn;
    @Nullable
    private View mPrefixRow;
    @Nullable
    private EditText mPrefixEdit;

    /** Dropdown names; index 0 is always the default. */
    private final List<String> mProfileNames = new ArrayList<>();
    /** Selected profile; null = default. */
    @Nullable
    private String mCurrentProfile;
    /** Suppresses Spinner callbacks during programmatic population. */
    private boolean mSuppressSpinnerEvents = false;

    @Nullable
    private ProgressDialog mProgressDialog;

    private int visibleRowStart() { return MAX_ROWS - mRows; }
    private int visibleColStart() { return MAX_COLS - mCols; }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.extra_keys_editor_preferences, rootKey);

        mPrefs = TermuxAppSharedPreferences.build(requireContext());
        loadCurrentExtraKeys();

        SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
        if (colsPref != null) {
            colsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int cols = (Integer) newValue;
                if (cols < 1) cols = 1;
                mCols = cols;
                rebuildPreview();
                if (mPreviewView != null) mPreviewView.requestDynamicFontUpdate();
                save();
                return true;
            });
        }

        SeekBarPreference rowsPref = findPreference("extra_keys_editor_rows");
        if (rowsPref != null) {
            rowsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                int rows = (Integer) newValue;
                if (rows < 1) rows = 1;
                mRows = rows;
                rebuildPreview();
                save();
                return true;
            });
        }

        SeekBarPreference heightPref = findPreference("terminal-toolbar-height");
        if (heightPref != null) {
            // Sync to actual value from TermuxAppSharedPreferences
            float scaleFactor = mPrefs.getTerminalToolbarHeightScaleFactor();
            heightPref.setValue(Math.round(scaleFactor * 100f));
            heightPref.setPersistent(false); // we handle persistence ourselves

            heightPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setTerminalToolbarHeightScaleFactor(((Integer) newValue) / 100f);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true; // true = accept the change in SeekBarPreference (persistent=false prevents disk save, mPrefs call above writes the actual value)
            });
        }

        SeekBarPreference cornerPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_CORNER_RADIUS);
        if (cornerPref != null) {
            cornerPref.setPersistent(false);
            cornerPref.setValue(mPrefs.getExtraKeysCornerRadius());
            cornerPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysCornerRadius((Integer) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), false);
                return true;
            });
        }

        SeekBarPreference marginPref = findPreference("extra-keys-button-margin");
        if (marginPref != null) {
            marginPref.setPersistent(false);
            marginPref.setValue(Math.round(mPrefs.getExtraKeysButtonMargin() * 10f));
            marginPref.setOnPreferenceChangeListener((preference, newValue) -> {
                float margin = ((Integer) newValue) / 10f;
                mPrefs.setExtraKeysButtonMargin(margin);
                if (mPreviewView != null) mPreviewView.setButtonMargins(margin);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        ListPreference stylePref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE);
        if (stylePref != null) {
            stylePref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysStyle((String) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SwitchPreferenceCompat capsPref = findPreference(TermuxPropertyConstants.KEY_EXTRA_KEYS_TEXT_ALL_CAPS);
        if (capsPref != null) {
            capsPref.setOnPreferenceChangeListener((preference, newValue) -> {
                mPrefs.setExtraKeysTextAllCaps((Boolean) newValue);
                rebuildPreview();
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SwitchPreferenceCompat dynFontPref = findPreference("extra-keys-dynamic-font-size");
        if (dynFontPref != null) {
            dynFontPref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                mPrefs.setExtraKeysDynamicFontSize(enabled);
                rebuildPreview();
                if (mPreviewView != null) mPreviewView.setDynamicFontSize(enabled);
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SeekBarPreference fontSizePref = findPreference("extra-keys-font-size");
        if (fontSizePref != null) {
            fontSizePref.setPersistent(false);
            fontSizePref.setValue(mPrefs.getExtraKeysFontSize());
            fontSizePref.setOnPreferenceChangeListener((preference, newValue) -> {
                int size = (Integer) newValue;
                mPrefs.setExtraKeysFontSize(size);
                if (mPreviewView != null) mPreviewView.setBaseFontSizeSp(size);
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }

        SwitchPreferenceCompat edgePref = findPreference("extra-keys-edge-indicators");
        if (edgePref != null) {
            edgePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (Boolean) newValue;
                if (mPreviewView != null) mPreviewView.setRuntimeEdgeIndicatorsEnabled(enabled);
                TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getListView() != null) {
            getListView().post(() -> {
                if (mPreviewView == null) initPreview();
            });
        }

        getChildFragmentManager().setFragmentResultListener(
            SignalPickerDialogFragment.REQUEST_KEY,
            getViewLifecycleOwner(),
            (requestKey, result) -> handleSignalPickerResult(result)
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPreviewView == null) initPreview();
    }

    private void initPreview() {
        View root = getView();
        if (root == null) return;
        mPreviewView = root.findViewById(R.id.extra_keys_editor_preview);
        if (mPreviewView == null) return;
        mPreviewView.setEditorGestureListener(mEditorGestureListener);
        mPreviewView.setEditorMoveListener(mEditorMoveListener);
        mPreviewView.setEditorLongPressListener(mEditorLongPressListener);
        applyEditorMode();

        View assignBtn = root.findViewById(R.id.mode_assign_btn);
        View moveBtn = root.findViewById(R.id.mode_move_btn);
        if (assignBtn != null && moveBtn != null) {
            assignBtn.setOnClickListener(v -> {
                if (mCurrentMode == ExtraKeysView.EditorMode.ASSIGN) return;
                mCurrentMode = ExtraKeysView.EditorMode.ASSIGN;
                applyEditorMode();
                updateModeButtons();
                updateHintText();
            });
            moveBtn.setOnClickListener(v -> {
                if (mCurrentMode == ExtraKeysView.EditorMode.MOVE) return;
                mCurrentMode = ExtraKeysView.EditorMode.MOVE;
                applyEditorMode();
                updateModeButtons();
                updateHintText();
            });
            updateModeButtons();
        }

        mHintTextView = root.findViewById(R.id.extra_keys_editor_hint_text);
        updateHintText();

        wireProfileSelector(root);

        rebuildPreview();
    }

    private void wireProfileSelector(View root) {
        mProfileSpinner = root.findViewById(R.id.profile_spinner);
        mProfileDeleteBtn = root.findViewById(R.id.profile_delete_btn);
        mProfileExportBtn = root.findViewById(R.id.profile_export_btn);
        mProfileImportBtn = root.findViewById(R.id.profile_import_btn);
        mPrefixRow = root.findViewById(R.id.prefix_row);
        mPrefixEdit = root.findViewById(R.id.profile_prefix_edit);

        View addBtn = root.findViewById(R.id.profile_add_btn);
        if (addBtn != null) addBtn.setOnClickListener(v -> showAddProfileDialog());
        if (mProfileDeleteBtn != null) mProfileDeleteBtn.setOnClickListener(v -> showDeleteProfileDialog());
        if (mProfileExportBtn != null) mProfileExportBtn.setOnClickListener(v -> startProfileExport());
        if (mProfileImportBtn != null) mProfileImportBtn.setOnClickListener(v -> startProfileImport());

        if (mPrefixEdit != null) {
            mPrefixEdit.setOnFocusChangeListener((v, hasFocus) -> {
                // Commit prefixes when the field loses focus
                if (!hasFocus && mCurrentProfile != null) save();
            });
        }

        if (mProfileSpinner != null) {
            mProfileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (mSuppressSpinnerEvents || position < 0 || position >= mProfileNames.size()) return;
                    mCurrentProfile = (position == 0) ? null : mProfileNames.get(position);
                    onProfileSelected();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        populateProfileSpinner();
    }

    /**
     * Fills the profile dropdown: default + names from {@code extra-keys-session}.
     * Keeps the current selection (mCurrentProfile) if it still exists.
     */
    private void populateProfileSpinner() {
        if (mProfileSpinner == null) return;
        mProfileNames.clear();
        mProfileNames.add(getString(R.string.extra_keys_editor_profile_default));
        mProfileNames.addAll(loadProfiles().keySet());

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, mProfileNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mSuppressSpinnerEvents = true;
        mProfileSpinner.setAdapter(adapter);
        int idx = (mCurrentProfile == null) ? 0 : mProfileNames.indexOf(mCurrentProfile);
        mProfileSpinner.setSelection(Math.max(idx, 0));
        mSuppressSpinnerEvents = false;
    }

    private void onProfileSelected() {
        if (mCurrentProfile == null) {
            // Default profile → extra-keys
            loadLayoutIntoGrid(mPrefs.getExtraKeys());
            if (mPrefixRow != null) mPrefixRow.setVisibility(View.GONE);
            setProfileRowVisibility(View.GONE);
        } else {
            SessionProfile p = loadProfiles().get(mCurrentProfile);
            loadLayoutIntoGrid(p != null ? p.layout : null);
            if (mPrefixRow != null) mPrefixRow.setVisibility(View.VISIBLE);
            if (mPrefixEdit != null)
                mPrefixEdit.setText(p != null ? TextUtils.join(", ", p.prefixes) : "");
            setProfileRowVisibility(View.VISIBLE);
        }
        rebuildPreview();
    }

    /** Export/delete are only available for non-default profiles; import is always visible. */
    private void setProfileRowVisibility(int visibility) {
        if (mProfileDeleteBtn != null) mProfileDeleteBtn.setVisibility(visibility);
        if (mProfileExportBtn != null) mProfileExportBtn.setVisibility(visibility);
    }

    /**
     * Reads {@code extra-keys-session}; understands both the new and old (legacy) format.
     * @return LinkedHashMap profile name → SessionProfile (order from JSON is preserved)
     */
    private Map<String, SessionProfile> loadProfiles() {
        Map<String, SessionProfile> profiles = new LinkedHashMap<>();
        String raw = mPrefs.getExtraKeysSession();
        if (raw == null || raw.isEmpty()) return profiles;
        try {
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = json.opt(name);
                SessionProfile p = new SessionProfile();
                if (value instanceof JSONObject) {
                    // New format: { "layout": "...", "prefixes": [...] }
                    JSONObject obj = (JSONObject) value;
                    p.layout = obj.optString("layout", "");
                    JSONArray arr = obj.optJSONArray("prefixes");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String pf = arr.optString(i, "");
                            if (!pf.isEmpty()) p.prefixes.add(pf);
                        }
                    }
                } else if (value instanceof String) {
                    // Legacy: key = prefix, value = layout
                    p.layout = (String) value;
                    p.prefixes.add(name);
                }
                profiles.put(name, p);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse extra-keys-session", e);
        }
        return profiles;
    }

    /** Serializes all profiles in one go (nothing is lost). */
    private void saveProfiles(Map<String, SessionProfile> profiles) {
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, SessionProfile> e : profiles.entrySet()) {
                JSONObject obj = new JSONObject();
                obj.put("layout", e.getValue().layout);
                obj.put("prefixes", new JSONArray(e.getValue().prefixes));
                json.put(e.getKey(), obj);
            }
            mPrefs.setExtraKeysSession(json.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to serialize extra-keys profiles", e);
        }
    }

    private void showAddProfileDialog() {
        EditText input = new EditText(requireContext());
        input.setHint(R.string.extra_keys_editor_profile_name_hint);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.extra_keys_editor_profile_add_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) return;
                Map<String, SessionProfile> profiles = loadProfiles();
                if (profiles.containsKey(name)) {
                    Toast.makeText(requireContext(),
                        R.string.extra_keys_editor_profile_exists, Toast.LENGTH_SHORT).show();
                    return;
                }
                SessionProfile p = new SessionProfile();
                // Start from the configured default panel (extra-keys), not the base constant
                String base = mPrefs.getExtraKeys();
                if (base == null || base.isEmpty()) {
                    base = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
                }
                p.layout = base;
                profiles.put(name, p);
                saveProfiles(profiles);
                mCurrentProfile = name;
                populateProfileSpinner();
                int idx = mProfileNames.indexOf(name);
                mSuppressSpinnerEvents = true;
                if (mProfileSpinner != null) mProfileSpinner.setSelection(Math.max(idx, 0));
                mSuppressSpinnerEvents = false;
                onProfileSelected();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showDeleteProfileDialog() {
        if (mCurrentProfile == null) return;
        final String toDelete = mCurrentProfile;
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.extra_keys_editor_profile_delete_title)
            .setMessage(getString(R.string.extra_keys_editor_profile_delete_confirm, toDelete))
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                Map<String, SessionProfile> profiles = loadProfiles();
                profiles.remove(toDelete);
                saveProfiles(profiles);
                mCurrentProfile = null;
                populateProfileSpinner();
                onProfileSelected(); // fall back to default
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_CODE_EXPORT_PROFILE) {
            runProfileExport(uri);
        } else if (requestCode == REQUEST_CODE_IMPORT_PROFILE) {
            runProfileImport(uri);
        }
    }

    /** Exports the selected non-default profile to a single JSON file. */
    private void startProfileExport() {
        if (mCurrentProfile == null) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "termux-extra-keys-" + mCurrentProfile + ".json");
        startActivityForResult(intent, REQUEST_CODE_EXPORT_PROFILE);
    }

    /** Writes the selected profile (name, prefixes, layout) to the chosen URI. */
    private void runProfileExport(Uri uri) {
        showProgressDialog(R.string.extra_keys_editor_profile_export_progress);
        final Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            String errorMsg = null;
            try (OutputStream os = appContext.getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new IOException("Failed to open output stream");
                SessionProfile p = loadProfiles().get(mCurrentProfile);
                JSONObject root = new JSONObject();
                root.put("format_version", FORMAT_VERSION);
                root.put("name", mCurrentProfile);
                root.put("layout", p != null ? p.layout : "");
                root.put("prefixes", p != null ? new JSONArray(p.prefixes) : new JSONArray());
                os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Failed to export extra-keys profile", e);
                errorMsg = e.getMessage();
            }
            final String finalError = errorMsg;
            new Handler(Looper.getMainLooper()).post(() -> {
                dismissProgressDialog();
                if (!isAdded()) return;
                if (finalError == null) {
                    Toast.makeText(appContext, R.string.extra_keys_editor_profile_export_success, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(appContext,
                        getString(R.string.extra_keys_editor_profile_export_failed) + ": " + finalError,
                        Toast.LENGTH_LONG).show();
                }
            });
        }, "ExtraKeysProfileExport").start();
    }

    /** Import adds the profile from a file to the list (without touching the current one). */
    private void startProfileImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_PROFILE);
    }

    /** Reads a single profile file, validates the JSON and layout, then applies it. */
    private void runProfileImport(Uri uri) {
        showProgressDialog(R.string.extra_keys_editor_profile_import_progress);
        final Context appContext = requireContext().getApplicationContext();
        new Thread(() -> {
            String errorMsg = null;
            JSONObject importedRoot = null;
            try (InputStream is = appContext.getContentResolver().openInputStream(uri)) {
                if (is == null) throw new IOException("Failed to open input stream");
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
                importedRoot = new JSONObject(new String(baos.toByteArray(), StandardCharsets.UTF_8));
                int version = importedRoot.optInt("format_version", 0);
                if (version > FORMAT_VERSION) {
                    throw new JSONException("Unsupported format version: " + version);
                }
                String layout = importedRoot.optString("layout", "");
                if (layout.isEmpty()) throw new JSONException("Missing profile layout");
                new JSONArray(layout);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse imported extra-keys profile file", e);
                errorMsg = e.getMessage();
            }
            final String finalError = errorMsg;
            final JSONObject finalRoot = importedRoot;
            new Handler(Looper.getMainLooper()).post(() -> {
                dismissProgressDialog();
                if (!isAdded()) return;
                if (finalError != null) {
                    Toast.makeText(appContext,
                        getString(R.string.extra_keys_editor_profile_import_failed) + ": " + finalError,
                        Toast.LENGTH_LONG).show();
                } else {
                    applyImportedProfile(finalRoot);
                }
            });
        }, "ExtraKeysProfileImport").start();
    }

    /** Adds the profile from the file to the list under its own name (a same-named one is overwritten). */
    private void applyImportedProfile(JSONObject importedRoot) {
        try {
            String name = importedRoot.optString("name", "").trim();
            if (name.isEmpty()) {
                name = requireContext().getString(R.string.extra_keys_editor_profile_import_default_name);
            }
            SessionProfile p = new SessionProfile();
            p.layout = importedRoot.optString("layout", "");
            JSONArray prefixes = importedRoot.optJSONArray("prefixes");
            if (prefixes != null) {
                for (int i = 0; i < prefixes.length(); i++) {
                    String pf = prefixes.optString(i, "").trim();
                    if (!pf.isEmpty()) p.prefixes.add(pf);
                }
            }

            Map<String, SessionProfile> profiles = loadProfiles();
            profiles.put(name, p);
            saveProfiles(profiles);

            mCurrentProfile = name;
            populateProfileSpinner();
            int idx = mProfileNames.indexOf(name);
            mSuppressSpinnerEvents = true;
            if (mProfileSpinner != null) mProfileSpinner.setSelection(Math.max(idx, 0));
            mSuppressSpinnerEvents = false;
            onProfileSelected();
            TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
            Toast.makeText(requireContext(),
                getString(R.string.extra_keys_editor_profile_import_success, name),
                Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to apply imported extra-keys profile", e);
            Toast.makeText(requireContext(),
                getString(R.string.extra_keys_editor_profile_import_failed) + ": " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void showProgressDialog(int msgRes) {
        dismissProgressDialog();
        mProgressDialog = new ProgressDialog(requireContext());
        mProgressDialog.setMessage(getString(msgRes));
        mProgressDialog.setCancelable(false);
        mProgressDialog.show();
    }

    private void dismissProgressDialog() {
        if (mProgressDialog != null) {
            if (mProgressDialog.isShowing()) {
                try {
                    mProgressDialog.dismiss();
                } catch (Exception ignored) {
                }
            }
            mProgressDialog = null;
        }
    }

    @Override
    public void onDestroy() {
        dismissProgressDialog();
        super.onDestroy();
    }

    private List<String> parsePrefixesFromField() {
        List<String> result = new ArrayList<>();
        if (mPrefixEdit == null) return result;
        for (String part : mPrefixEdit.getText().toString().split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result;
    }

    private void applyEditorMode() {
        if (mPreviewView != null) mPreviewView.setEditorMode(mCurrentMode);
    }

    private void updateModeButtons() {
        View root = getView();
        if (root == null) return;
        View assignBtn = root.findViewById(R.id.mode_assign_btn);
        View moveBtn = root.findViewById(R.id.mode_move_btn);
        if (assignBtn == null || moveBtn == null) return;
        boolean isAssign = mCurrentMode == ExtraKeysView.EditorMode.ASSIGN;
        assignBtn.setAlpha(isAssign ? 1.0f : 0.4f);
        moveBtn.setAlpha(isAssign ? 0.4f : 1.0f);
    }

    private void updateHintText() {
        if (mHintTextView == null) return;
        mHintTextView.setText(mCurrentMode == ExtraKeysView.EditorMode.MOVE
            ? R.string.extra_keys_editor_hint_move
            : R.string.extra_keys_editor_hint);
    }

    private final ExtraKeysView.EditorGestureListener mEditorGestureListener =
        new ExtraKeysView.EditorGestureListener() {
            @Override
            public void onKeyTap(View button, int row, int col) {
                openSignalPicker(row, col, DIR_TAP);
            }

            @Override
            public void onKeySwipe(View button, int row, int col,
                                   ExtraKeysView.SwipeDirection direction) {
                int dir;
                switch (direction) {
                    case UP: dir = DIR_UP; break;
                    case DOWN: dir = DIR_DOWN; break;
                    case LEFT: dir = DIR_LEFT; break;
                    case RIGHT: dir = DIR_RIGHT; break;
                    default: return;
                }
                openSignalPicker(row, col, dir);
            }
        };

    private final ExtraKeysView.EditorLongPressListener mEditorLongPressListener =
        (button, row, col) -> openLabelDialog(row, col);

    private final ExtraKeysView.EditorMoveListener mEditorMoveListener =
        (fromRow, fromCol, toRow, toCol) -> {
            // Tag coordinates are relative to visible grid (0..mRows-1, 0..mCols-1)
            int vr = visibleRowStart();
            int vc = visibleColStart();
            KeyCell src = mGrid[vr + fromRow][vc + fromCol];
            KeyCell dst = mGrid[vr + toRow][vc + toCol];
            if (src == null || dst == null) return;

            // Swap all 5 fields between source and destination
            List<String> tmpTap = src.tap;
            List<String> tmpSwipeUp = src.swipeUp;
            List<String> tmpSwipeDown = src.swipeDown;
            List<String> tmpSwipeLeft = src.swipeLeft;
            List<String> tmpSwipeRight = src.swipeRight;
            String tmpDisplay = src.display;

            src.tap = dst.tap;
            src.swipeUp = dst.swipeUp;
            src.swipeDown = dst.swipeDown;
            src.swipeLeft = dst.swipeLeft;
            src.swipeRight = dst.swipeRight;
            src.display = dst.display;

            dst.tap = tmpTap;
            dst.swipeUp = tmpSwipeUp;
            dst.swipeDown = tmpSwipeDown;
            dst.swipeLeft = tmpSwipeLeft;
            dst.swipeRight = tmpSwipeRight;
            dst.display = tmpDisplay;

            rebuildPreview();
            save();
        };

    private void rebuildPreview() {
        if (mPreviewView == null) return;

        String style = mPrefs.getExtraKeysStyle();
        if (style == null) style = "default";
        mDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(style);

        String json;
        try {
            json = buildJsonMatrix();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build extra-keys JSON matrix", e);
            return;
        }

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(json, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse extra-keys JSON", e);
            return;
        }

        mPreviewView.setButtonTextAllCaps(mPrefs.shouldExtraKeysTextBeAllCaps());
        mPreviewView.setDynamicFontSize(mPrefs.isExtraKeysDynamicFontSizeEnabled(requireContext()));
        mPreviewView.setBaseFontSizeSp(mPrefs.getExtraKeysFontSize());
        mPreviewView.setButtonMargins(mPrefs.getExtraKeysButtonMargin());
        mPreviewView.setSpecialButtonMode("hold".equals(mPrefs.getExtraKeysSpecialButtonMode())
            ? ExtraKeysView.SpecialButtonMode.HOLD
            : ExtraKeysView.SpecialButtonMode.STICKY);

        // Reload the terminal color scheme for the current night mode.
        // This static singleton is otherwise only updated by TermuxActivity, so
        // we must set it here to get correct preview colors after a theme switch.
        boolean isNight = ThemeUtils.isNightModeEnabled(requireContext());
        Properties lightScheme = null;
        if (!isNight) {
            lightScheme = new Properties();
            String[] keys = getResources().getStringArray(R.array.light_terminal_color_scheme_keys);
            String[] values = getResources().getStringArray(R.array.light_terminal_color_scheme_values);
            int len = Math.min(keys.length, values.length);
            for (int i = 0; i < len; i++) {
                lightScheme.setProperty(keys[i], values[i]);
            }
        }
        ColorSchemeUtils.ensureColorSchemeForTheme(isNight, lightScheme);

        TermuxColorSchemeManager cm = new TermuxColorSchemeManager();
        cm.recompute(mPrefs);

        int edgeGray = cm.isSchemeLight() ? 0xFF555555 : 0xFFAAAAAA;
        mPreviewView.setEditorEdgeColor(edgeGray);

        mPreviewView.setBackgroundColor(cm.getSchemeBackground());

        mPreviewView.setRepetitiveKeys(ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS);

        float scale = 1.0f;
        try { scale = mPrefs.getTerminalToolbarHeightScaleFactor(); } catch (Exception e) { Log.e(TAG, "Failed to get toolbar height scale", e); }
        float rowHeightPx = 37.5f * getResources().getDisplayMetrics().density * scale;
        mPreviewView.reload(info, rowHeightPx);

        int schemeBg = cm.getSchemeBackground();
        int buttonBg = TermuxColorSchemeManager.compositeColors(schemeBg, cm.getButtonBg());
        int buttonActiveBg = TermuxColorSchemeManager.compositeColors(schemeBg, cm.getButtonActiveBg());
        mPreviewView.setButtonColors(cm.getButtonText(), cm.getButtonText(),
            buttonBg, buttonActiveBg);

        int childCount = mPreviewView.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = mPreviewView.getChildAt(i);
            if (child instanceof com.google.android.material.button.MaterialButton) {
                int row = i / mCols;
                int col = i % mCols;
                KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
                int flags = (cell.swipeUp.isEmpty() ? 0 : 1)
                          | (cell.swipeDown.isEmpty() ? 0 : 2)
                          | (cell.swipeLeft.isEmpty() ? 0 : 4)
                          | (cell.swipeRight.isEmpty() ? 0 : 8);
                child.setTag(new int[]{row, col, flags});
            }
        }

        ViewGroup.LayoutParams lp = mPreviewView.getLayoutParams();
        if (lp != null) {
            lp.height = Math.round(rowHeightPx * mRows);
            mPreviewView.setLayoutParams(lp);
        }
        mPreviewView.requestLayout();
    }

    private void loadCurrentExtraKeys() {
        loadLayoutIntoGrid(mPrefs.getExtraKeys());
    }

    /** Loads the given JSON layout into mGrid (null/empty → default). */
    private void loadLayoutIntoGrid(@Nullable String layoutJson) {
        String style = mPrefs.getExtraKeysStyle();
        if (style == null) style = "default";
        mDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(style);

        // Always allocate full-size grid so data is never lost on resize
        mGrid = new KeyCell[MAX_ROWS][MAX_COLS];
        for (int r = 0; r < MAX_ROWS; r++)
            for (int c = 0; c < MAX_COLS; c++)
                mGrid[r][c] = new KeyCell();

        String current = layoutJson;
        if (current == null || current.isEmpty()) {
            current = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS;
        }

        ExtraKeysInfo info;
        try {
            info = new ExtraKeysInfo(current, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse extra-keys JSON", e);
            mRows = 2; mCols = 5;
            syncRowColSeekbars();
            return;
        }

        ExtraKeyButton[][] matrix = info.getMatrix();

        int loadedRows = Math.max(1, Math.min(matrix.length, 4));
        int loadedCols = 1;
        for (ExtraKeyButton[] rowArr : matrix) {
            if (rowArr != null && rowArr.length > loadedCols) loadedCols = rowArr.length;
        }
        loadedCols = Math.max(1, Math.min(loadedCols, 10));

        // Load data into bottom-right corner of the fixed grid
        int gridStartRow = MAX_ROWS - loadedRows;
        int gridStartCol = MAX_COLS - loadedCols;
        for (int r = 0; r < loadedRows; r++) {
            for (int c = 0; c < loadedCols; c++) {
                KeyCell cell = mGrid[gridStartRow + r][gridStartCol + c];
                if (r < matrix.length && matrix[r] != null && c < matrix[r].length && matrix[r][c] != null) {
                    ExtraKeyButton btn = matrix[r][c];
                    setCellBinding(cell.tap, btn);
                    setCellBinding(cell.swipeUp, btn.getSwipeUp());
                    setCellBinding(cell.swipeDown, btn.getSwipeDown());
                    setCellBinding(cell.swipeLeft, btn.getSwipeLeft());
                    setCellBinding(cell.swipeRight, btn.getSwipeRight());
                }
            }
        }

        // Explicit {display: ...} is only meaningful when it was authored manually. The OLD editor
// wrote auto-composed displays for macros; such entries must NOT fill the custom label, so
// ignore any display that equals the legacy composition of the main action.
try {
            JSONArray layoutArr = new JSONArray(current);
            for (int r = 0; r < loadedRows && r < layoutArr.length(); r++) {
                Object line = layoutArr.opt(r);
                if (!(line instanceof JSONArray)) continue;
                JSONArray rowArr = (JSONArray) line;
                for (int c = 0; c < loadedCols && c < rowArr.length(); c++) {
                    Object cellObj = rowArr.opt(c);
                    if (!(cellObj instanceof JSONObject)) continue;
                    JSONObject obj = (JSONObject) cellObj;
                    if (obj.has(ExtraKeyButton.KEY_DISPLAY_NAME)) {
                        String d = obj.optString(ExtraKeyButton.KEY_DISPLAY_NAME, "");
                        KeyCell cell = mGrid[gridStartRow + r][gridStartCol + c];
                        String legacyAuto = computeDisplay(cell.tap);
                        if (!d.isEmpty() && !d.equals(legacyAuto)) {
                            cell.display = d;
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse custom labels", e);
        }

        mRows = loadedRows;
        mCols = loadedCols;

        syncRowColSeekbars();
    }

    /**
     * Fills {@code target} with the signal tokens of {@code btn}. A single literal key (which may
     * itself contain spaces, e.g. a custom binding "ls -la") becomes one token; a macro becomes its
     * space-separated tokens (e.g. "CTRL c" → ["CTRL", "c"]). A {@code null} or empty button fills
     * an empty list.
     */
    private void setCellBinding(List<String> target, @Nullable ExtraKeyButton btn) {
        target.clear();
        if (btn == null) return;
        String key = btn.getKey();
        if (key == null || key.isEmpty()) return;
        if (btn.isMacro()) {
            // Tokens as parsed by ExtraKeyButton: a quoted literal such as "ls -la" is
            // already reassembled into a single token with its spaces intact.
            for (String token : btn.getParsedTokens()) {
                if (!token.isEmpty()) target.add(token);
            }
        } else {
            target.add(key);
        }
    }

    private void syncRowColSeekbars() {
        SeekBarPreference colsPref = findPreference("extra_keys_editor_columns");
        if (colsPref != null) colsPref.setValue(mCols);
        SeekBarPreference rowsPref = findPreference("extra_keys_editor_rows");
        if (rowsPref != null) rowsPref.setValue(mRows);
    }

    private String buildJsonMatrix() throws JSONException {
        JSONArray matrix = new JSONArray();
        int vr = visibleRowStart();
        int vc = visibleColStart();
        for (int r = 0; r < mRows; r++) {
            JSONArray row = new JSONArray();
            for (int c = 0; c < mCols; c++) {
                KeyCell cell = mGrid[vr + r][vc + c];

                String tapValue = joinBinding(cell.tap);
                String swipeUpValue = joinBinding(cell.swipeUp);
                String swipeDownValue = joinBinding(cell.swipeDown);
                String swipeLeftValue = joinBinding(cell.swipeLeft);
                String swipeRightValue = joinBinding(cell.swipeRight);

                if (tapValue.isEmpty() && swipeUpValue.isEmpty() && swipeDownValue.isEmpty()
                    && swipeLeftValue.isEmpty() && swipeRightValue.isEmpty()) {
                    row.put("");
                    continue;
                }

                JSONObject obj = new JSONObject();
                if (!tapValue.isEmpty()) {
                    // A single token (even with spaces) is a literal key; multiple tokens form a macro.
                    putSignal(obj, ExtraKeyButton.KEY_KEY_NAME, tapValue, cell.tap);
                    // Custom label overrides the auto-composed display of the main action.
                    if (!cell.display.isEmpty()) {
                        obj.put(ExtraKeyButton.KEY_DISPLAY_NAME, cell.display);
                    }
                }
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_UP, swipeUpValue, cell.swipeUp);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_DOWN, swipeDownValue, cell.swipeDown);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_LEFT, swipeLeftValue, cell.swipeLeft);
                putSwipe(obj, ExtraKeyButton.KEY_SWIPE_RIGHT, swipeRightValue, cell.swipeRight);

                if (!swipeUpValue.isEmpty()) {
                    putSwipe(obj, ExtraKeyButton.KEY_POPUP, swipeUpValue, cell.swipeUp);
                }

                row.put(obj);
            }
            matrix.put(row);
        }
        return matrix.toString();
    }

    private void putSignal(JSONObject obj, String key, String value, List<String> signals) throws JSONException {
        if (value == null || value.isEmpty()) return;
        if (signals != null && signals.size() > 1) {
            obj.put(ExtraKeyButton.KEY_MACRO, value);
            // Auto-composition with "+" between macro elements. The label stays
            // separate and empty — on load this display is recognized as auto
            // (see loadLayoutIntoGrid) and does not fill in the label field.
            obj.put(ExtraKeyButton.KEY_DISPLAY_NAME, computeDisplay(signals));
        } else {
            // Single literal key — may contain spaces, which are preserved verbatim
            // (e.g. custom text "ls -la" is sent as the literal text "ls -la").
            obj.put(key, value);
        }
    }

    private void putSwipe(JSONObject obj, String jsonKey, String value, List<String> signals) throws JSONException {
        if (value == null || value.isEmpty()) return;
        if (signals != null && signals.size() > 1) {
            JSONObject swipeObj = new JSONObject();
            swipeObj.put(ExtraKeyButton.KEY_MACRO, value);
            swipeObj.put(ExtraKeyButton.KEY_DISPLAY_NAME, computeDisplay(signals));
            obj.put(jsonKey, swipeObj);
        } else {
            obj.put(jsonKey, value);
        }
    }

    /** Joins signal tokens for storage. A single token is returned as-is (spaces preserved);
     *  multiple tokens are joined into a macro string, quoting any token that itself contains
     *  whitespace so the token list survives the round-trip. */
    private static String joinBinding(List<String> signals) {
        if (signals == null || signals.isEmpty()) return "";
        if (signals.size() == 1) return signals.get(0);
        return BindingTokenizer.joinMacro(signals);
    }

    /**
     * Composition that the old editor wrote into {@code display}. Used only to
     * recognize old auto-generated labels on load, so they do not fill in the
     * label field (see {@link #loadLayoutIntoGrid}).
     */
    private String computeDisplay(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return "";
        return tokens.stream()
            .map(key -> {
                if (BindingTokenizer.isDelay(key)) {
                    return "⏱" + BindingTokenizer.parseDelayMs(key) + "ms";
                }
                if (mDisplayMap == null) return key;
                String d = mDisplayMap.get(key);
                return d != null ? d : key;
            })
            .collect(Collectors.joining("+"));
    }

    private void openSignalPicker(int row, int col, int direction) {
        KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
        ArrayList<String> currentSignals;
        SignalPickerDialogFragment.BindTarget target;

        switch (direction) {
            case DIR_TAP:
                currentSignals = new ArrayList<>(cell.tap);
                target = SignalPickerDialogFragment.BindTarget.TAP;
                break;
            case DIR_UP:
                currentSignals = new ArrayList<>(cell.swipeUp);
                target = SignalPickerDialogFragment.BindTarget.SWIPE_UP;
                break;
            case DIR_DOWN:
                currentSignals = new ArrayList<>(cell.swipeDown);
                target = SignalPickerDialogFragment.BindTarget.SWIPE_DOWN;
                break;
            case DIR_LEFT:
                currentSignals = new ArrayList<>(cell.swipeLeft);
                target = SignalPickerDialogFragment.BindTarget.SWIPE_LEFT;
                break;
            case DIR_RIGHT:
                currentSignals = new ArrayList<>(cell.swipeRight);
                target = SignalPickerDialogFragment.BindTarget.SWIPE_RIGHT;
                break;
            default:
                return;
        }

        SignalPickerDialogFragment fragment = SignalPickerDialogFragment.newInstance(row, col, target, currentSignals);
        fragment.show(getChildFragmentManager(), "signal_picker");
    }

    /** Dialog for setting a key label. Empty field = auto-generated from the main action. */
    private void openLabelDialog(int row, int col) {
        KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
        EditText input = new EditText(requireContext());
        input.setMaxLines(1);
        input.setText(cell.display);
        // The hint previews what the button will show if the label is left empty:
        // the main action. A single literal key (even with spaces, e.g. "ls -la") is shown
        // verbatim; a macro is shown with its "+"-joined auto-composition. It must NOT come
        // from previewBtn.getDisplay(), which already reflects the current custom label and
        // would leak that label back into the hint after the field contents are deleted.
        input.setHint(cell.tap.size() == 1 ? cell.tap.get(0) : computeDisplay(cell.tap));
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.extra_keys_editor_label_dialog_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                cell.display = input.getText().toString().trim();
                rebuildPreview();
                save();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void handleSignalPickerResult(@NonNull Bundle result) {
        int row = result.getInt(SignalPickerDialogFragment.RESULT_ROW, -1);
        int col = result.getInt(SignalPickerDialogFragment.RESULT_COL, -1);
        String targetStr = result.getString(SignalPickerDialogFragment.RESULT_TARGET, null);
        ArrayList<String> signals = result.getStringArrayList(SignalPickerDialogFragment.RESULT_SIGNALS);

        if (row < 0 || col < 0 || row >= mRows || col >= mCols) return;
        if (targetStr == null || signals == null) return;

        SignalPickerDialogFragment.BindTarget target;
        try {
            target = SignalPickerDialogFragment.BindTarget.valueOf(targetStr);
        } catch (IllegalArgumentException e) {
            return;
        }

        KeyCell cell = mGrid[visibleRowStart() + row][visibleColStart() + col];
        ArrayList<String> newSignals = new ArrayList<>(signals);
        switch (target) {
            case TAP: cell.tap = newSignals; break;
            case SWIPE_UP: cell.swipeUp = newSignals; break;
            case SWIPE_DOWN: cell.swipeDown = newSignals; break;
            case SWIPE_LEFT: cell.swipeLeft = newSignals; break;
            case SWIPE_RIGHT: cell.swipeRight = newSignals; break;
        }

        rebuildPreview();
        save();
    }

    private void save() {
        String json;
        try {
            json = buildJsonMatrix();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build extra-keys JSON matrix in save", e);
            return;
        }
        try {
            new JSONArray(json);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to validate extra-keys JSON in save", e);
            return;
        }

        if (mCurrentProfile == null) {
            // Default profile → extra-keys
            mPrefs.setExtraKeys(json);
        } else {
            // Named profile → its slot in extra-keys-session
            Map<String, SessionProfile> profiles = loadProfiles();
            SessionProfile p = profiles.get(mCurrentProfile);
            if (p == null) p = new SessionProfile();
            p.layout = json;
            p.prefixes.clear();
            p.prefixes.addAll(parsePrefixesFromField());
            profiles.put(mCurrentProfile, p);
            saveProfiles(profiles);
        }
        TermuxActivity.updateTermuxActivityStyling(requireContext(), true);
    }

}

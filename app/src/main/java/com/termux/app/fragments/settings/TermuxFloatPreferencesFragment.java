package com.termux.app.fragments.settings;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.PreferenceDataStore;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;

@Keep
public class TermuxFloatPreferencesFragment extends TermuxPreferenceFragmentBase {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        setupPreferences(TermuxFloatPreferencesDataStore.getInstance(context), R.xml.termux_float_preferences, rootKey);
    }

}

class TermuxFloatPreferencesDataStore extends PreferenceDataStore {

    private final Context mContext;
    private final TermuxFloatAppSharedPreferences mPreferences;

    private static TermuxFloatPreferencesDataStore mInstance;

    private TermuxFloatPreferencesDataStore(Context context) {
        mContext = context;
        mPreferences = TermuxFloatAppSharedPreferences.build(context, true);
    }

    public static synchronized TermuxFloatPreferencesDataStore getInstance(Context context) {
        if (mInstance == null) {
            mInstance = new TermuxFloatPreferencesDataStore(context);
        }
        return mInstance;
    }

}

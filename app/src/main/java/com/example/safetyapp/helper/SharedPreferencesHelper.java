package com.example.safetyapp.helper;

import android.content.Context;
import android.content.SharedPreferences;

public class SharedPreferencesHelper {

    private static final String PREF_NAME = "safety_app_prefs";
    private static final String KEY_VOICE_FEATURE = "voice_feature";

    // Check if voice detection is enabled
    public static boolean isVoiceFeatureEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_VOICE_FEATURE, false);
    }

    // Set voice detection preference
    public static void setVoiceFeatureEnabled(Context context, boolean isEnabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_VOICE_FEATURE, isEnabled);
        editor.apply();
    }
}


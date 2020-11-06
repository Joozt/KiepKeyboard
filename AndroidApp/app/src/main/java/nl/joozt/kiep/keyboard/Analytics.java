package nl.joozt.kiep.keyboard;

import android.content.Context;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

public class Analytics {
    private final FirebaseAnalytics firebaseAnalytics;

    public Analytics(Context context) {
        firebaseAnalytics = FirebaseAnalytics.getInstance(context);
    }

    public void logYes() {
        firebaseAnalytics.logEvent("speak_yes", null);
    }

    public void logNo() {
        firebaseAnalytics.logEvent("speak_no", null);
    }

    public void logTts(int wordCount) {
        Bundle params = new Bundle();
        params.putInt("word_count", wordCount);
        firebaseAnalytics.logEvent("tts", params);
    }
}

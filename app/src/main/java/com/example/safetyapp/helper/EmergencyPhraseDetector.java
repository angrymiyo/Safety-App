package com.example.safetyapp.helper;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Real-time emergency phrase detection using Android Speech Recognition
 * Listens for emergency keywords and triggers callbacks
 */
public class EmergencyPhraseDetector {
    private static final String TAG = "EmergencyPhrase";

    private final Context context;
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private PhraseDetectionListener listener;
    private boolean isListening = false;
    private Handler restartHandler;

    // Comprehensive Emergency keywords organized by category
    // Category 1: Common SOS / Help
    private static final String[] COMMON_SOS_KEYWORDS = {
        // English
        "help", "save me", "please help", "i'm in danger", "sos",
        "call the police", "emergency", "someone please", "i need help", "don't hurt me",
        // Bangla
        "বাঁচাও", "সাহায্য করো", "আমাকে বাঁচাও", "আমি বিপদে আছি",
        "পুলিশ ডাকো", "কেউ আছো", "আমি ভয় পাচ্ছি", "আমার কিছু হবে",
        "দয়া করে বাঁচাও", "ওরা আমাকে মারবে"
    };

    // Category 2: Fear / Panic
    private static final String[] FEAR_PANIC_KEYWORDS = {
        // English
        "i'm scared", "i'm afraid", "please don't", "oh my god",
        "what's happening", "no no no", "i can't breathe", "stay away from me",
        // Bangla
        "আমি ভয় পেয়েছি", "দয়া করে না", "হে আল্লাহ", "এ কী হচ্ছে",
        "না না না", "দূরে থাকো", "আমি পারছি না", "আমাকে ছেড়ে দাও"
    };

    // Category 3: Attack / Threat
    private static final String[] ATTACK_THREAT_KEYWORDS = {
        // English
        "he's hitting me", "they're attacking me", "someone is following me",
        "he's trying to kidnap me", "i'm trapped",
        // Bangla
        "ওরা আমাকে মারছে", "ওরা আমার পেছনে", "আমাকে তুলে নিচ্ছে",
        "আমি আটকে গেছি", "কেউ আমাকে অনুসরণ করছে"
    };

    // Category 4: Emotional Distress
    private static final String[] EMOTIONAL_DISTRESS_KEYWORDS = {
        // English
        "i can't take it anymore", "i'm not okay", "i'm tired of everything",
        "i want to disappear", "please someone listen",
        // Bangla
        "আর পারছি না", "আমি ভালো নেই", "সব কিছু শেষ হয়ে গেছে",
        "আমি হারিয়ে যেতে চাই", "কেউ শুনবে না আমাকে"
    };

    // Category 5: Hidden / Coded SOS (context-sensitive)
    private static final String[] CODED_SOS_KEYWORDS = {
        // English
        "i'm fine", "i'm with a friend", "can you call me now", "bring my red file",
        // Bangla
        "আমি ঠিক আছি", "আমি একজনের সঙ্গে আছি", "আমাকে ফোন দাও এখনই", "লাল ফাইলটা নিয়ে আসো"
    };

    // Combined emergency keywords array for backward compatibility
    private static final String[] EMERGENCY_KEYWORDS = combineAllKeywords();

    public interface PhraseDetectionListener {
        void onEmergencyPhraseDetected(String phrase, float confidence);
        void onListeningStatusChanged(boolean isListening);
    }

    /**
     * Combine all keyword categories into a single array
     */
    private static String[] combineAllKeywords() {
        int totalLength = COMMON_SOS_KEYWORDS.length + FEAR_PANIC_KEYWORDS.length +
                         ATTACK_THREAT_KEYWORDS.length + EMOTIONAL_DISTRESS_KEYWORDS.length +
                         CODED_SOS_KEYWORDS.length;

        String[] combined = new String[totalLength];
        int index = 0;

        System.arraycopy(COMMON_SOS_KEYWORDS, 0, combined, index, COMMON_SOS_KEYWORDS.length);
        index += COMMON_SOS_KEYWORDS.length;

        System.arraycopy(FEAR_PANIC_KEYWORDS, 0, combined, index, FEAR_PANIC_KEYWORDS.length);
        index += FEAR_PANIC_KEYWORDS.length;

        System.arraycopy(ATTACK_THREAT_KEYWORDS, 0, combined, index, ATTACK_THREAT_KEYWORDS.length);
        index += ATTACK_THREAT_KEYWORDS.length;

        System.arraycopy(EMOTIONAL_DISTRESS_KEYWORDS, 0, combined, index, EMOTIONAL_DISTRESS_KEYWORDS.length);
        index += EMOTIONAL_DISTRESS_KEYWORDS.length;

        System.arraycopy(CODED_SOS_KEYWORDS, 0, combined, index, CODED_SOS_KEYWORDS.length);

        return combined;
    }

    public EmergencyPhraseDetector(Context context, PhraseDetectionListener listener) {
        this.context = context;
        this.listener = listener;
        this.restartHandler = new Handler(Looper.getMainLooper());

        initializeSpeechRecognizer();
    }

    private void initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "Ready for speech");
                isListening = true;
                if (listener != null) listener.onListeningStatusChanged(true);
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "Speech started");
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // Audio level feedback (optional)
            }

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "Speech ended");
                isListening = false;
                if (listener != null) listener.onListeningStatusChanged(false);
            }

            @Override
            public void onError(int error) {
                String errorMsg = getErrorText(error);
                Log.e(TAG, "Speech recognition error: " + errorMsg + " (code: " + error + ")");
                isListening = false;

                // Auto-restart listening after ALL errors (continuous monitoring)
                // Don't let errors stop the detection
                restartHandler.postDelayed(() -> {
                    Log.d(TAG, "Auto-restarting after error: " + errorMsg);
                    startListening();
                }, 500);  // Restart quickly (500ms)
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);

                if (matches != null && !matches.isEmpty()) {
                    processResults(matches, confidences);
                }

                // Restart listening for continuous detection
                restartHandler.postDelayed(() -> startListening(), 500);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                // Process partial results for faster response
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    processResults(matches, null);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        // Support multi-language: Default to device locale, with fallback to English
        // User can speak in English, Bangla, or Hindi - all will be recognized
        String deviceLanguage = Locale.getDefault().getLanguage();
        if (deviceLanguage.equals("bn") || deviceLanguage.equals("hi")) {
            // If device is set to Bangla or Hindi, use that
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        } else {
            // Otherwise default to English but with multi-language support
            recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        }

        // Add language preference hints for better multi-language support
        // This helps Google's speech recognizer detect Bangla/Hindi even when device is in English
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD"); // Bangla (Bangladesh)
        recognizerIntent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"bn-BD", "bn-IN", "hi-IN", "en-US"});

        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000);
    }

    /**
     * Process speech recognition results and check for emergency using semantic AI
     */
    private void processResults(ArrayList<String> matches, float[] confidences) {
        Log.i(TAG, "=== Processing " + matches.size() + " recognition results ===");

        for (int i = 0; i < matches.size(); i++) {
            String text = matches.get(i);
            float speechConfidence = (confidences != null && i < confidences.length) ? confidences[i] : 0.5f;

            Log.i(TAG, "  [" + i + "] Recognized: \"" + text + "\" (confidence: " + speechConfidence + ")");

            // Use AI-powered semantic intent classifier
            EmergencyIntentClassifier.EmergencyClassification classification =
                    EmergencyIntentClassifier.classifyIntent(text);

            // If semantic classifier detects emergency with good confidence
            if (classification.isEmergency()) {
                Log.i(TAG, "SEMANTIC EMERGENCY DETECTED: " + classification.type +
                      " (confidence: " + classification.confidence + ") in phrase: " + text);
                if (listener != null) {
                    // Use average of speech confidence and semantic confidence
                    float combinedConfidence = (speechConfidence + classification.confidence) / 2.0f;
                    listener.onEmergencyPhraseDetected(text, combinedConfidence);
                }
                return; // Stop after first match
            }

            // Fallback: Check if text contains emergency keywords (backward compatibility)
            String lowerText = text.toLowerCase();
            Log.d(TAG, "  Checking for keywords in: \"" + lowerText + "\"");

            // Check each category for better logging and context
            String detectedCategory = getKeywordCategory(lowerText);
            if (detectedCategory != null) {
                Log.i(TAG, "  ✅ EMERGENCY KEYWORD DETECTED [" + detectedCategory + "] in phrase: \"" + text + "\"");
                if (listener != null) {
                    listener.onEmergencyPhraseDetected(text, speechConfidence);
                }
                return; // Stop after first match
            } else {
                Log.d(TAG, "  ❌ No emergency keyword found in: \"" + text + "\"");
            }
        }

        Log.d(TAG, "=== No emergency keywords detected in any results ===");
    }

    /**
     * Get the category of a detected emergency keyword
     * @param lowerText The text to check (should be lowercase)
     * @return Category name or null if no keyword found
     */
    private String getKeywordCategory(String lowerText) {
        // Check Common SOS / Help category
        for (String keyword : COMMON_SOS_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return "Common SOS/Help";
            }
        }

        // Check Fear / Panic category
        for (String keyword : FEAR_PANIC_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return "Fear/Panic";
            }
        }

        // Check Attack / Threat category
        for (String keyword : ATTACK_THREAT_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return "Attack/Threat";
            }
        }

        // Check Emotional Distress category
        for (String keyword : EMOTIONAL_DISTRESS_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return "Emotional Distress";
            }
        }

        // Check Coded SOS category (these should be treated carefully)
        for (String keyword : CODED_SOS_KEYWORDS) {
            if (lowerText.contains(keyword.toLowerCase())) {
                return "Coded SOS";
            }
        }

        return null; // No keyword found
    }

    /**
     * Start continuous listening for emergency phrases
     */
    public void startListening() {
        if (speechRecognizer == null) {
            Log.e(TAG, "❌ SpeechRecognizer not initialized");
            return;
        }

        if (isListening) {
            Log.d(TAG, "Already listening, skipping restart");
            return;
        }

        try {
            Log.i(TAG, "🎙️ Starting speech recognition for emergency keywords...");
            speechRecognizer.startListening(recognizerIntent);
            Log.i(TAG, "✅ Speech recognizer started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start listening", e);
            e.printStackTrace();
        }
    }

    /**
     * Stop listening
     */
    public void stopListening() {
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.cancel();
            isListening = false;
            restartHandler.removeCallbacksAndMessages(null);
            Log.d(TAG, "Stopped listening");
        }
    }

    /**
     * Release resources
     */
    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (restartHandler != null) {
            restartHandler.removeCallbacksAndMessages(null);
        }
    }

    public boolean isListening() {
        return isListening;
    }

    /**
     * Get error message from error code
     */
    private String getErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT: return "Client side error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No speech match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognition service busy";
            case SpeechRecognizer.ERROR_SERVER: return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "No speech input";
            default: return "Unknown error";
        }
    }
}

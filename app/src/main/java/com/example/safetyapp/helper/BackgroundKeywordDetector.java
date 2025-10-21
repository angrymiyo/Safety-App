package com.example.safetyapp.helper;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
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
 * Advanced background keyword detector that works even when screen is off
 * Uses AudioRecord for continuous audio monitoring + SpeechRecognizer for confirmation
 */
public class BackgroundKeywordDetector {
    private static final String TAG = "BackgroundKeyword";

    private final Context context;
    private KeywordDetectionListener listener;
    private Handler mainHandler;

    // AudioRecord for continuous monitoring
    private AudioRecord audioRecord;
    private Thread audioThread;
    private volatile boolean isRecording = false;
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private int bufferSize;

    // SpeechRecognizer for keyword confirmation
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isSpeechRecognizerListening = false;

    // Detection parameters
    private static final double VOICE_THRESHOLD = 800.0; // Audio level threshold for voice detection (lowered for better sensitivity)
    private static final long VOICE_DURATION_MS = 200; // Minimum voice duration to trigger recognition (reduced)
    private static final long MONITORING_LOG_INTERVAL = 10000; // Log status every 10 seconds
    private long voiceStartTime = 0;
    private boolean voiceDetected = false;
    private long lastLogTime = 0;
    private int detectionAttempts = 0;

    public interface KeywordDetectionListener {
        void onKeywordDetected(String keyword, float confidence);
        void onStatusChanged(boolean isListening);
    }

    public BackgroundKeywordDetector(Context context, KeywordDetectionListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Calculate buffer size
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = SAMPLE_RATE * 2;
        }

        initializeSpeechRecognizer();
        Log.i(TAG, "BackgroundKeywordDetector initialized with buffer size: " + bufferSize);
    }

    /**
     * Initialize SpeechRecognizer for keyword confirmation
     */
    private void initializeSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available");
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "SpeechRecognizer ready");
                isSpeechRecognizerListening = true;
            }

            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "Speech detected by recognizer");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "Speech ended");
                isSpeechRecognizerListening = false;
            }

            @Override
            public void onError(int error) {
                String errorMsg = getSpeechErrorText(error);
                Log.d(TAG, "SpeechRecognizer error: " + errorMsg + " (code: " + error + ")");
                isSpeechRecognizerListening = false;

                // Don't restart on NO_MATCH or SPEECH_TIMEOUT - these are normal
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    Log.w(TAG, "Non-trivial speech recognition error");
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                float[] confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);

                if (matches != null && !matches.isEmpty()) {
                    Log.i(TAG, "Speech recognized: " + matches.toString());
                    processRecognitionResults(matches, confidences);
                }
                isSpeechRecognizerListening = false;
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    Log.d(TAG, "Partial results: " + matches.toString());
                    processRecognitionResults(matches, null);
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD");
        recognizerIntent.putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", new String[]{"bn-BD", "bn-IN", "hi-IN", "en-US"});
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000);
    }

    /**
     * Process speech recognition results and check for emergency keywords
     */
    private void processRecognitionResults(ArrayList<String> matches, float[] confidences) {
        for (int i = 0; i < matches.size(); i++) {
            String text = matches.get(i);
            float confidence = (confidences != null && i < confidences.length) ? confidences[i] : 0.5f;

            Log.i(TAG, "Checking: \"" + text + "\" (confidence: " + confidence + ")");

            // Use AI-powered semantic classifier
            EmergencyIntentClassifier.EmergencyClassification classification =
                    EmergencyIntentClassifier.classifyIntent(text);

            if (classification.isEmergency()) {
                Log.i(TAG, "🚨 EMERGENCY DETECTED: " + classification.type +
                      " (confidence: " + classification.confidence + ") - \"" + text + "\"");
                if (listener != null) {
                    float combinedConfidence = (confidence + classification.confidence) / 2.0f;
                    listener.onKeywordDetected(text, combinedConfidence);
                }
                return;
            }

            // Fallback: keyword matching
            if (containsEmergencyKeyword(text.toLowerCase())) {
                Log.i(TAG, "🚨 KEYWORD MATCH: \"" + text + "\"");
                if (listener != null) {
                    listener.onKeywordDetected(text, confidence);
                }
                return;
            }
        }
    }

    /**
     * Check if text contains emergency keywords
     */
    private boolean containsEmergencyKeyword(String text) {
        String[] keywords = {
            // English
            "help", "save me", "please help", "danger", "sos", "emergency",
            "call police", "someone help", "i need help", "don't hurt",
            "scared", "afraid", "attacking", "following me",
            // Bangla
            "বাঁচাও", "সাহায্য", "বিপদ", "পুলিশ", "ভয়", "মারছে"
        };

        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start continuous audio monitoring
     */
    public void startListening() {
        if (isRecording) {
            Log.d(TAG, "Already listening");
            return;
        }

        try {
            // Initialize AudioRecord
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            // Notify listener
            if (listener != null) {
                mainHandler.post(() -> listener.onStatusChanged(true));
            }

            // Start audio monitoring thread
            audioThread = new Thread(this::audioMonitoringLoop);
            audioThread.start();

            Log.i(TAG, "✅ Background audio monitoring started");

        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - microphone permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio monitoring", e);
        }
    }

    /**
     * Audio monitoring loop - runs continuously in background
     */
    private void audioMonitoringLoop() {
        Log.i(TAG, "=== Audio monitoring loop started ===");
        Log.i(TAG, "Voice threshold: " + VOICE_THRESHOLD + ", Duration threshold: " + VOICE_DURATION_MS + "ms");

        short[] audioBuffer = new short[bufferSize / 2];
        long loopIterations = 0;
        double maxAudioLevel = 0;
        lastLogTime = System.currentTimeMillis();

        while (isRecording && audioRecord != null) {
            try {
                int readResult = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                loopIterations++;

                if (readResult > 0) {
                    // Calculate audio level (RMS)
                    double audioLevel = calculateRMS(audioBuffer);

                    // Track max audio level for logging
                    if (audioLevel > maxAudioLevel) {
                        maxAudioLevel = audioLevel;
                    }

                    // Periodic status logging
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastLogTime >= MONITORING_LOG_INTERVAL) {
                        Log.i(TAG, "📊 Status: Monitoring active | Iterations: " + loopIterations +
                              " | Max audio level (last 10s): " + String.format("%.1f", maxAudioLevel) +
                              " | Threshold: " + VOICE_THRESHOLD +
                              " | Speech recognizer: " + (isSpeechRecognizerListening ? "ACTIVE" : "ready"));
                        lastLogTime = currentTime;
                        maxAudioLevel = 0; // Reset for next interval
                    }

                    // Check if voice is detected
                    if (audioLevel > VOICE_THRESHOLD) {
                        if (!voiceDetected) {
                            voiceDetected = true;
                            voiceStartTime = System.currentTimeMillis();
                            Log.i(TAG, "🔊 Voice activity detected! (level: " + String.format("%.1f", audioLevel) + ")");
                        } else {
                            // Voice has been continuous for required duration
                            long voiceDuration = System.currentTimeMillis() - voiceStartTime;
                            if (voiceDuration >= VOICE_DURATION_MS && !isSpeechRecognizerListening) {
                                detectionAttempts++;
                                Log.i(TAG, "🎙️ SUSTAINED VOICE DETECTED! Duration: " + voiceDuration + "ms - Starting speech recognition (attempt #" + detectionAttempts + ")");
                                mainHandler.post(this::startSpeechRecognition);
                                voiceDetected = false; // Reset to avoid repeated triggers
                            }
                        }
                    } else {
                        // Reset if silence detected
                        if (voiceDetected) {
                            long voiceDuration = System.currentTimeMillis() - voiceStartTime;
                            if (voiceDuration < VOICE_DURATION_MS) {
                                Log.d(TAG, "Voice too short (" + voiceDuration + "ms), resetting (level dropped to " + String.format("%.1f", audioLevel) + ")");
                            }
                        }
                        voiceDetected = false;
                    }
                } else if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "❌ AudioRecord error: invalid operation");
                    break;
                } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "❌ AudioRecord error: bad value");
                    break;
                }

                // Small delay to reduce CPU usage
                Thread.sleep(50);

            } catch (InterruptedException e) {
                Log.i(TAG, "Audio monitoring interrupted");
                break;
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in audio monitoring loop", e);
                break;
            }
        }

        Log.i(TAG, "=== Audio monitoring loop ended === (Total iterations: " + loopIterations + ", Detection attempts: " + detectionAttempts + ")");
    }

    /**
     * Calculate RMS (Root Mean Square) audio level
     */
    private double calculateRMS(short[] audioData) {
        long sum = 0;
        for (short sample : audioData) {
            sum += sample * sample;
        }
        double mean = sum / (double) audioData.length;
        return Math.sqrt(mean);
    }

    /**
     * Start speech recognition when voice is detected
     */
    private void startSpeechRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "❌ Cannot start speech recognition - SpeechRecognizer is null!");
            return;
        }

        if (isSpeechRecognizerListening) {
            Log.d(TAG, "Speech recognizer already listening, skipping");
            return;
        }

        try {
            Log.i(TAG, "▶️ Starting SpeechRecognizer for keyword confirmation...");
            speechRecognizer.startListening(recognizerIntent);
            Log.i(TAG, "✅ SpeechRecognizer.startListening() called successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start speech recognition", e);
            e.printStackTrace();
            isSpeechRecognizerListening = false;
        }
    }

    /**
     * Stop listening
     */
    public void stopListening() {
        Log.i(TAG, "Stopping background keyword detection");
        isRecording = false;

        if (audioThread != null) {
            audioThread.interrupt();
            try {
                audioThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error waiting for audio thread to stop", e);
            }
            audioThread = null;
        }

        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception e) {
                Log.e(TAG, "Error canceling speech recognizer", e);
            }
        }

        if (listener != null) {
            mainHandler.post(() -> listener.onStatusChanged(false));
        }
    }

    /**
     * Destroy and release all resources
     */
    public void destroy() {
        stopListening();

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }

        Log.i(TAG, "BackgroundKeywordDetector destroyed");
    }

    public boolean isListening() {
        return isRecording;
    }

    /**
     * Get speech recognition error message
     */
    private String getSpeechErrorText(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO: return "Audio error";
            case SpeechRecognizer.ERROR_CLIENT: return "Client error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK: return "Network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH: return "No match";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "Recognizer busy";
            case SpeechRecognizer.ERROR_SERVER: return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "Speech timeout";
            default: return "Unknown error";
        }
    }
}

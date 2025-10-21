package com.example.safetyapp;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.example.safetyapp.helper.EmergencyMessageHelper;
import com.example.safetyapp.helper.PersonalizedVoiceHelper;
import com.example.safetyapp.helper.EmergencyPhraseDetector;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class AIVoiceActivity extends BaseActivity {

    // Audio configuration
    private static final int LOCATION_PERMISSION_CODE = 1004;
    private static final int SAMPLE_RATE = 16000;
    private static final int DURATION_MS = 975;
    private static final int BUFFER_SIZE = SAMPLE_RATE * DURATION_MS / 1000;
    private static final int AUDIO_PERMISSION_CODE = 1002;
    private static final int SMS_PERMISSION_CODE = 1003;
    // Detection thresholds - STRICT to avoid normal voice triggering
    private static final float SCREAM_THRESHOLD = 0.5f;  // 50% distress detection threshold
    private static final float MIN_VERIFY_RMS = 0.08f;  // Minimum loudness for verification
    private static final float MIN_DISTRESS_RMS = 0.15f;  // REQUIRED: High intensity for distress trigger (filters out normal voice)
    private static final float MIN_ENROLL_RMS = 0.1f;   // For reference if needed
    private static final int CONSECUTIVE_POSITIVES_REQUIRED = 2;  // Require 2 consecutive detections (filters false positives)
    private static final long COOLDOWN_PERIOD = 30000; // 30 seconds
    // Audio recording
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private PersonalizedVoiceHelper voiceHelper;
    private boolean isUserVerified = false;
    private EmergencyPhraseDetector phraseDetector;
    // TFLite models
    private Interpreter yamnetInterpreter;
    private Interpreter screamClassifierInterpreter;
    // Reusable buffers
    private short[] audioBuffer = new short[BUFFER_SIZE];
    private float[] floatAudio = new float[BUFFER_SIZE];
    private float[][] yamnetInputArray = new float[1][BUFFER_SIZE];
    // Detection state
    private int positiveCount = 0;
    private boolean isInCooldown = false;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    // UI elements
    private TextView tvStatus;
    private ProgressBar progressBar;
    private TextView tvScreamProb;
    private TextView tvVoiceMatch;
    private TextView tvDetectionCount;
    private TextView tvEnrollmentStatus;
    private LinearLayout audioMeterContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout(R.layout.activity_aivoice, "Voice Detection", true, R.id.nav_home);

        // Initialize UI
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);
        tvScreamProb = findViewById(R.id.tv_scream_prob);
        tvVoiceMatch = findViewById(R.id.tv_voice_match);
        tvDetectionCount = findViewById(R.id.tv_detection_count);
        tvEnrollmentStatus = findViewById(R.id.tv_enrollment_status);
        audioMeterContainer = findViewById(R.id.audio_meter_container);
        Button enrollButton = findViewById(R.id.btn_enroll_voice);

        // click listener for enrollment button
        enrollButton.setOnClickListener(v -> {
            Intent intent = new Intent(AIVoiceActivity.this, VoiceEnrollmentActivity.class);
            startActivity(intent);
        });

        // Check if voice is enrolled
        updateEnrollmentStatus();

        // Initialize wake lock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SafetyApp::VoiceDetection"
        );

        // Check permission status from settings
        SharedPreferences prefs = getSharedPreferences("AppSettingsPrefs", MODE_PRIVATE);
        boolean voiceDetectionEnabled = prefs.getBoolean("voice_detection", false);

        if (!voiceDetectionEnabled) {
            tvStatus.setText("⚠️ Voice detection disabled in settings\nEnable in Settings → Voice Detection to start continuous monitoring");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    AUDIO_PERMISSION_CODE);
            tvStatus.setText("Requesting microphone permission...");
        } else {
            initializeModelsAndStartDetection();
        }
    }

    private void initializeModelsAndStartDetection() {
        new Thread(() -> {
            try {
                yamnetInterpreter = new Interpreter(loadModelFile("yamnet.tflite"));
                screamClassifierInterpreter = new Interpreter(loadModelFile("scream_classifier.tflite"));
                voiceHelper = new PersonalizedVoiceHelper(this, yamnetInterpreter);
                voiceHelper.loadStoredEmbedding();

                runOnUiThread(() -> {
                    tvStatus.setText("🎙️ KEYWORD Detection Active\nSay: \"help\" or \"emergency\" or \"save me\"\nSpeak clearly and loudly");
                    startKeywordDetection();  // Start keyword detection ONLY
                    // DO NOT start voice detection - microphone conflict with SpeechRecognizer
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.e("AI_VOICE", "Model loading failed", e);
                    tvStatus.setText("Error loading AI models");
                    Toast.makeText(this, "Voice detection unavailable", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void startKeywordDetection() {
        Log.i("KEYWORD_DETECTION", "=== Starting KEYWORD-ONLY detection mode ===");
        Log.i("KEYWORD_DETECTION", "Monitoring for 40+ emergency keywords (English + Bangla)");

        // Check if speech recognition is available
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("KEYWORD_DETECTION", "❌ Speech recognition NOT available on this device!");
            runOnUiThread(() -> {
                tvStatus.setText("❌ Speech recognition not available\nKeyword detection requires Google Speech Services");
                Toast.makeText(this, "❌ Speech recognition not available - Install Google app", Toast.LENGTH_LONG).show();
            });
            return;
        }

        Log.i("KEYWORD_DETECTION", "✅ Speech recognition available");

        // Initialize Emergency Phrase Detector - ONLY this triggers SOS
        phraseDetector = new EmergencyPhraseDetector(this, new EmergencyPhraseDetector.PhraseDetectionListener() {
            @Override
            public void onEmergencyPhraseDetected(String phrase, float confidence) {
                // Check cooldown - prevent multiple SMS for same keyword
                if (isInCooldown) {
                    Log.w("KEYWORD_DETECTION", "⏳ In cooldown - ignoring keyword: \"" + phrase + "\"");
                    runOnUiThread(() -> {
                        Toast.makeText(AIVoiceActivity.this,
                            "⏳ Already triggered - wait 30s",
                            Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                Log.i("KEYWORD_DETECTION", "🚨🚨🚨 EMERGENCY KEYWORD DETECTED: \"" + phrase + "\" 🚨🚨🚨");
                Log.i("KEYWORD_DETECTION", "Confidence: " + confidence);

                // Start cooldown immediately to prevent multiple triggers
                isInCooldown = true;
                mainHandler.postDelayed(() -> {
                    isInCooldown = false;
                    Log.i("KEYWORD_DETECTION", "✅ Cooldown ended - ready for next detection");

                    runOnUiThread(() -> {
                        tvStatus.setText("✅ Ready again!\nSay: \"help\" or \"emergency\" or \"save me\"");
                        tvScreamProb.setText("Ready");
                        tvScreamProb.setTextColor(0xFF4CAF50); // Green
                        Toast.makeText(AIVoiceActivity.this, "✅ Ready for next keyword", Toast.LENGTH_SHORT).show();
                    });
                }, COOLDOWN_PERIOD);

                Log.i("KEYWORD_DETECTION", "⏳ Cooldown started: 30 seconds");

                runOnUiThread(() -> {
                    tvStatus.setText("🚨 KEYWORD DETECTED!\n\"" + phrase + "\"\nTriggering SOS...");
                    Toast.makeText(AIVoiceActivity.this,
                        "🚨 EMERGENCY: \"" + phrase + "\"",
                        Toast.LENGTH_LONG).show();
                });

                // Trigger emergency ONCE - works EXACTLY like SOS button
                triggerEmergency();
            }

            @Override
            public void onListeningStatusChanged(boolean isListening) {
                runOnUiThread(() -> {
                    if (isListening) {
                        Log.d("KEYWORD_DETECTION", "✅ Listening for emergency keywords...");
                        if (isInCooldown) {
                            tvStatus.setText("⏳ COOLDOWN (30s)\nWaiting before next detection...");
                            tvScreamProb.setText("Wait");
                            tvScreamProb.setTextColor(0xFFFFA726); // Orange
                        } else {
                            tvStatus.setText("🎙️ LISTENING...\nSay clearly:\n\"help\" or \"emergency\" or \"save me\"");
                            tvScreamProb.setText("Ready");
                            tvScreamProb.setTextColor(0xFF4CAF50); // Green
                        }
                        tvDetectionCount.setText("🎙️");
                    } else {
                        Log.d("KEYWORD_DETECTION", "⏸️ Not listening");
                        tvStatus.setText("⏸️ Speech recognition paused\nRestarting...");
                    }
                });
            }
        });

        // Start continuous keyword listening
        phraseDetector.startListening();
        Log.i("KEYWORD_DETECTION", "✅ Keyword detection ACTIVE - monitoring 40+ keywords");
        Log.i("KEYWORD_DETECTION", "UI shows 3 main keywords: help, emergency, save me");
        Log.i("KEYWORD_DETECTION", "All 40+ keywords still work in background");
    }

    private void startVoiceDetection() {
        // Check microphone permission before proceeding
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> tvStatus.setText("Microphone permission not granted"));
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferSize, BUFFER_SIZE * 2)
            );
        } catch (SecurityException e) {
            Log.e("AI_VOICE", "SecurityException when initializing AudioRecord", e);
            runOnUiThread(() -> tvStatus.setText("Unable to access microphone"));
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            runOnUiThread(() -> tvStatus.setText("Microphone initialization failed"));
            return;
        }

        isRecording = true;
        new Thread(() -> {
            try {
                audioRecord.startRecording();
                runOnUiThread(() -> tvStatus.setText("⚡ Continuous monitoring active - Instant trigger ready"));
            } catch (SecurityException e) {
                Log.e("AI_VOICE", "Permission denied when starting recording", e);
                runOnUiThread(() -> tvStatus.setText("Microphone permission not granted"));
                return;
            }

            while (isRecording) {
                if (!isInCooldown) {
                    try {
                        int readResult = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                        if (readResult > 0) {
                            processAudioChunk(audioBuffer);
                        } else {
                            Log.e("AI_VOICE", "Failed to read audio buffer");
                        }
                    } catch (SecurityException e) {
                        Log.e("AI_VOICE", "Security exception during audio read", e);
                        runOnUiThread(() -> tvStatus.setText("Permission lost during recording"));
                        break;
                    }
                }

                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Log.e("AI_VOICE", "Thread interrupted", e);
                    break;
                }
            }
        }).start();
    }

    private void processAudioChunk(short[] audioData) {
        if (yamnetInterpreter == null || screamClassifierInterpreter == null || voiceHelper == null) {
            return;
        }

        // 1. Loudness check first - skip quiet audio
        float rms = PersonalizedVoiceHelper.calculateRMS(audioData);

        // Update audio level meter
        runOnUiThread(() -> {
            audioMeterContainer.setVisibility(View.VISIBLE);
            int audioLevel = (int) (rms * 1000);
            progressBar.setProgress(Math.min(100, audioLevel));
        });

        if (rms < MIN_VERIFY_RMS) {
            runOnUiThread(() -> tvStatus.setText("🎙️ Monitoring continuously... (audio too quiet)"));
            return;
        }

        // 2. Convert audio to float [-1.0, 1.0]
        float[] floatInput = new float[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            floatInput[i] = audioData[i] / 32767.0f;
        }

        // 3. Prepare YAMNet input/output
        Object[] yamnetInputs = new Object[]{floatInput};
        Map<Integer, Object> yamnetOutputs = new HashMap<>();
        float[][] yamnetScores = new float[1][521];
        yamnetOutputs.put(0, yamnetScores);

        // 4. Run YAMNet
        float[] averagedScores;
        try {
            yamnetInterpreter.runForMultipleInputsOutputs(yamnetInputs, yamnetOutputs);
            averagedScores = yamnetScores[0];
        } catch (Exception e) {
            Log.e("INFERENCE", "YAMNet error", e);
            return;
        }

        // 5. Voice verification (optional - for personalized AI)
        if (voiceHelper.hasStoredEmbedding()) {
            boolean isMatch = voiceHelper.verify(averagedScores, audioData);

            if (!isMatch) {
                runOnUiThread(() -> {
                    tvStatus.setText("🤖 AI monitoring - Voice not matched (analyzing distress)");
                    tvVoiceMatch.setText("✗");
                    tvVoiceMatch.setTextColor(0xFFF44336);
                });
                // Continue processing - anyone's distress can trigger at 50%
            } else {
                runOnUiThread(() -> {
                    tvStatus.setText("✅ Your voice verified - AI analyzing distress");
                    tvVoiceMatch.setText("✓");
                    tvVoiceMatch.setTextColor(0xFF4CAF50);
                });
            }
        } else {
            runOnUiThread(() -> {
                tvStatus.setText("🤖 AI monitoring ANY voice for distress\nEnroll for personalized detection (optional)");
                tvVoiceMatch.setText("—");
            });
        }

        // 6. Prepare input for scream classifier
        float[][] classifierInput = new float[1][521];
        classifierInput[0] = averagedScores;

        // 7. Run scream classifier
        float screamProbability;
        try {
            float[][] screamClassifierOutput = new float[1][1];
            screamClassifierInterpreter.run(classifierInput, screamClassifierOutput);
            screamProbability = screamClassifierOutput[0][0];
        } catch (Exception e) {
            Log.e("INFERENCE", "Classifier error", e);
            return;
        }

        // 8. Update UI (display only - keywords trigger, not distress %)
        runOnUiThread(() -> {
            // Update distress probability (DISPLAY ONLY - does NOT trigger)
            int screamPercent = (int) (screamProbability * 100);
            tvScreamProb.setText(screamPercent + "%");

            if (screamProbability >= SCREAM_THRESHOLD) {
                tvScreamProb.setTextColor(0xFFFFA726); // Orange (not red - won't trigger)
                tvStatus.setText("📊 Distress: " + screamPercent + "% (display only)\n🎙️ ONLY keywords trigger SOS - distress ignored");
            } else if (screamProbability > 0.3f) {
                tvScreamProb.setTextColor(0xFFFFA726); // Orange
                tvStatus.setText("📊 Monitoring: " + screamPercent + "% (display only)\n✅ Listening for emergency keywords");
            } else {
                tvScreamProb.setTextColor(0xFF4CAF50); // Green
                tvStatus.setText("✅ Listening for 40+ keywords\n📊 Distress % is display only");
            }

            // Always show microphone icon (listening for keywords)
            tvDetectionCount.setText("🎙️");
        });

        Log.d("ScreamDetection", "Distress: " + (int)(screamProbability * 100) + "% | Intensity (RMS): " + String.format("%.3f", rms));
        handleDetection(screamProbability, rms);
    }

    private void handleDetection(float screamProbability, float rms) {
        // KEYWORD-ONLY MODE - AI distress detection is COMPLETELY DISABLED
        // This method only logs metrics for display - NEVER triggers SOS
        // ONLY the EmergencyPhraseDetector (keywords) can trigger SOS

        // Just update counter for UI display purposes
        if (screamProbability >= SCREAM_THRESHOLD) {
            positiveCount++;
        } else {
            positiveCount = Math.max(0, positiveCount - 1);
        }

        // Log for debugging - but never trigger
        Log.d("ScreamDetection", "📊 DISPLAY ONLY - Distress: " + (int)(screamProbability*100) + "% | RMS: " + String.format("%.3f", rms) + " | Count: " + positiveCount + " (KEYWORDS-ONLY MODE - this will NOT trigger)");
    }

    private void triggerEmergency() {
        Log.i("EMERGENCY_TRIGGER", "=== Emergency triggered! Starting emergency protocol ===");

        // Acquire wake lock to keep device awake during emergency
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(COOLDOWN_PERIOD);
            Log.i("EMERGENCY_TRIGGER", "Wake lock acquired");
        }

        // Note: Cooldown is now managed in keyword detection callback
        // No need to set it again here

        // Check if user is authenticated (using BaseActivity's mAuth)
        if (mAuth.getCurrentUser() == null) {
            Log.e("EMERGENCY_TRIGGER", "User not authenticated - cannot send emergency");
            runOnUiThread(() -> {
                Toast.makeText(this, "⚠️ User not authenticated - please log in", Toast.LENGTH_LONG).show();
                tvStatus.setText("❌ Cooldown active (not authenticated)");
            });
            return;
        }

        Log.i("EMERGENCY_TRIGGER", "User authenticated: " + mAuth.getCurrentUser().getEmail());

        // Check SMS permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w("EMERGENCY_TRIGGER", "SMS permission not granted - requesting now");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
            runOnUiThread(() -> {
                Toast.makeText(this, "⚠️ Please grant SMS permission to send emergency alerts", Toast.LENGTH_LONG).show();
                tvStatus.setText("⏳ Requesting SMS permission...");
            });
            return;
        }

        Log.i("EMERGENCY_TRIGGER", "SMS permission granted");

        // Check location permission (needed for EmergencyMessageHelper)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w("EMERGENCY_TRIGGER", "Location permission not granted - requesting now");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_CODE);
            runOnUiThread(() -> {
                Toast.makeText(this, "⚠️ Please grant location permission to send location in emergency", Toast.LENGTH_LONG).show();
                tvStatus.setText("⏳ Requesting location permission...");
            });
            return;
        }

        Log.i("EMERGENCY_TRIGGER", "Location permission granted");

        // All checks passed - trigger emergency EXACTLY like SHAKE trigger
        Log.i("EMERGENCY_TRIGGER", "All permissions granted - triggering EXACTLY like shake trigger");

        runOnUiThread(() -> {
            Toast.makeText(this, "🚨 KEYWORD DETECTED! Starting emergency protocol...", Toast.LENGTH_LONG).show();
            tvStatus.setText("🚨 Emergency keyword detected!\nStarting SOS...");
        });

        // Start Evidence Recording (same as shake)
        try {
            Intent evidenceIntent = new Intent(this, EvidenceRecordingActivity.class);
            evidenceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(evidenceIntent);
            Log.i("EMERGENCY_TRIGGER", "Evidence recording started");
        } catch (Exception e) {
            Log.e("EMERGENCY_TRIGGER", "Failed to start evidence recording", e);
        }

        // Show popup countdown and send message (EXACTLY like shake trigger)
        try {
            Intent sosIntent = new Intent(this, PopupCountdownActivity.class);
            sosIntent.putExtra("method", "sms");
            sosIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(sosIntent);

            Log.i("EMERGENCY_TRIGGER", "✅ PopupCountdownActivity started - will send SMS with live location after countdown!");

            runOnUiThread(() -> {
                tvStatus.setText("✅ Emergency triggered!\nCountdown popup shown\n⏳ Cooldown: 30s");
            });

        } catch (Exception e) {
            Log.e("EMERGENCY_TRIGGER", "Failed to start PopupCountdownActivity", e);
            e.printStackTrace();

            runOnUiThread(() -> {
                Toast.makeText(this, "❌ Failed to trigger emergency: " + e.getMessage(), Toast.LENGTH_LONG).show();
                tvStatus.setText("⚠️ Failed to start countdown\n⏳ Cooldown: 30s");
            });
        }
    }

    private ByteBuffer loadModelFile(String modelName) throws IOException {
        InputStream inputStream = getAssets().open(modelName);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, length);
        }

        byte[] modelBytes = outputStream.toByteArray();
        return ByteBuffer.allocateDirect(modelBytes.length)
                .order(ByteOrder.nativeOrder())
                .put(modelBytes);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // SMS permission granted, check location permission
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                            LOCATION_PERMISSION_CODE);
                } else {
                    // Both permissions granted, trigger emergency
                    triggerEmergency();
                }
            } else {
                Toast.makeText(this, "SMS permission denied", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Location permission granted, trigger emergency
                triggerEmergency();
            } else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateEnrollmentStatus() {
        new Thread(() -> {
            try {
                PersonalizedVoiceHelper helper = new PersonalizedVoiceHelper(this, yamnetInterpreter);
                boolean hasEnrolled = helper.hasStoredEmbedding();

                runOnUiThread(() -> {
                    if (hasEnrolled) {
                        // Voice is enrolled - show success status, keep card visible for re-enrollment
                        tvEnrollmentStatus.setText("✓ Voice enrolled successfully - Active 24/7\nYou can re-enroll anytime to update");
                        tvEnrollmentStatus.setTextColor(0xFF4CAF50);
                    } else {
                        // Voice not enrolled - show enrollment prompt
                        tvEnrollmentStatus.setText("⚠ Enroll your voice for personalized AI protection\nKeyword detection active");
                        tvEnrollmentStatus.setTextColor(0xFFF44336);
                    }
                });
            } catch (Exception e) {
                Log.e("AIVoice", "Error checking enrollment status", e);
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateEnrollmentStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        isRecording = false;
        mainHandler.removeCallbacksAndMessages(null);
        tvStatus.setText("Voice detection stopped");

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        if (phraseDetector != null) {
            phraseDetector.stopListening();
            phraseDetector.destroy();
            Log.i("KEYWORD_DETECTION", "Keyword detector stopped and destroyed");
        }
        if (yamnetInterpreter != null) yamnetInterpreter.close();
        if (screamClassifierInterpreter != null) screamClassifierInterpreter.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
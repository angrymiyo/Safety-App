package com.example.safetyapp;

import android.Manifest;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.example.safetyapp.helper.EmergencyMessageHelper;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class AIVoiceActivity extends BaseActivity {

    // Audio configuration
    private static final int SAMPLE_RATE = 16000;
    private static final int DURATION_MS = 975;
    private static final int BUFFER_SIZE = SAMPLE_RATE * DURATION_MS / 1000;
    private static final int AUDIO_PERMISSION_CODE = 1002;
    private static final int SMS_PERMISSION_CODE = 1003;

    // Detection thresholds
    private static final float SCREAM_THRESHOLD = 0.8f;
    private static final int CONSECUTIVE_POSITIVES_REQUIRED = 3;
    private static final long COOLDOWN_PERIOD = 30000; // 30 seconds

    // Audio recording
    private AudioRecord audioRecord;
    private boolean isRecording = false;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupLayout(R.layout.activity_aivoice, "Voice Detection", true, R.id.nav_settings);

        // Initialize UI
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);

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
            tvStatus.setText("Voice detection disabled in settings");
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
        try {
            yamnetInterpreter = new Interpreter(loadModelFile("yamnet.tflite"));
            screamClassifierInterpreter = new Interpreter(loadModelFile("scream_classifier.tflite"));

            // Log model shapes for debugging
            Log.d("MODEL_SHAPES", "YAMNet output shape: "
                    + Arrays.toString(yamnetInterpreter.getOutputTensor(0).shape()));
            Log.d("MODEL_SHAPES", "Scream classifier input shape: "
                    + Arrays.toString(screamClassifierInterpreter.getInputTensor(0).shape()));

            startVoiceDetection();
        } catch (IOException e) {
            Log.e("AI_VOICE", "Model loading failed", e);
            tvStatus.setText("Error loading AI models");
            Toast.makeText(this, "Voice detection unavailable", Toast.LENGTH_LONG).show();
        }
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
                runOnUiThread(() -> tvStatus.setText("Listening for screams..."));
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
        if (yamnetInterpreter == null || screamClassifierInterpreter == null) return;

        // 1. Convert audio to float [-1.0, 1.0]
        float[] floatInput = new float[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            floatInput[i] = audioData[i] / 32767.0f;
        }

        // 2. Prepare YAMNet input/output
        Object[] yamnetInputs = new Object[]{floatInput};
        Map<Integer, Object> yamnetOutputs = new HashMap<>();
        float[][] yamnetScores = new float[1][521];
        yamnetOutputs.put(0, yamnetScores); // Scores output

        // 3. Run YAMNet
        try {
            yamnetInterpreter.runForMultipleInputsOutputs(yamnetInputs, yamnetOutputs);
        } catch (Exception e) {
            Log.e("INFERENCE", "YAMNet error", e);
            return;
        }

        float[] averagedScores = yamnetScores[0];

        // 5. Prepare input for scream classifier: shape [1, 521]
        float[][] classifierInput = new float[1][521];
        classifierInput[0] = averagedScores;

        // 6. Run scream classifier with proper input shape
        float[][] screamClassifierOutput = new float[1][1];
        try {
            screamClassifierInterpreter.run(classifierInput, screamClassifierOutput);
        } catch (Exception e) {
            Log.e("INFERENCE", "Classifier error", e);
            return;
        }

        // 7. Use result
        float screamProbability = screamClassifierOutput[0][0];
        if (screamProbability > 0.5f) {
            runOnUiThread(() -> Toast.makeText(this, "Scream Detected!", Toast.LENGTH_SHORT).show());
        }
        handleDetection(screamProbability);
    }

    private void handleDetection(float screamProbability) {
        if (screamProbability > SCREAM_THRESHOLD) {
            positiveCount++;

            if (positiveCount >= CONSECUTIVE_POSITIVES_REQUIRED) {
                mainHandler.post(this::triggerEmergency);
                positiveCount = 0;
            }
        } else {
            positiveCount = Math.max(0, positiveCount - 1);
        }
    }

    private void triggerEmergency() {
        // Acquire wakelock
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(COOLDOWN_PERIOD);
        }

        // Start cooldown
        isInCooldown = true;
        mainHandler.postDelayed(() -> isInCooldown = false, COOLDOWN_PERIOD);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.SEND_SMS},
                    SMS_PERMISSION_CODE);
            Toast.makeText(this, "Requesting SMS permission...", Toast.LENGTH_SHORT).show();
        } else {
            new EmergencyMessageHelper(this).sendMessage("sms");
            Toast.makeText(this, "Scream detected! Emergency SMS sent", Toast.LENGTH_LONG).show();
        }
        // Update UI
        tvStatus.setText("Cooldown active...");
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
        if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeModelsAndStartDetection();
            } else {
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_LONG).show();
                tvStatus.setText("Microphone permission required to detect scream.");
            }
        } else if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                new EmergencyMessageHelper(this).sendMessage("sms");
            } else {
                Toast.makeText(this, "SMS permission denied", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRecording = false;

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
        }
        if (yamnetInterpreter != null) yamnetInterpreter.close();
        if (screamClassifierInterpreter != null) screamClassifierInterpreter.close();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
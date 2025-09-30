package com.example.safetyapp.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.example.safetyapp.R;
import com.example.safetyapp.helper.EmergencyMessageHelperService;
import com.example.safetyapp.helper.PersonalizedVoiceHelper;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

public class VoiceDetectionService extends Service {

    private static final String TAG = "VoiceDetectionService";

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "VoiceDetectionChannel";

    private static final int SAMPLE_RATE = 16000;
    private static final int RECORD_DURATION_MS = 975;
    private static final int BUFFER_SIZE = SAMPLE_RATE * RECORD_DURATION_MS / 1000;

    // Optimized thresholds for distress-only detection (balanced for accuracy + speed)
    private static final float SCREAM_THRESHOLD = 0.73f; // High accuracy - only real distress screams
    private static final float MIN_VERIFY_RMS = 0.05f; // Lower volume required - catches quieter distress
    private static final float HIGH_INTENSITY_RMS = 0.10f; // Moderate intensity - not too loud required
    private static final int CONSECUTIVE_POSITIVES_REQUIRED = 2; // Fast response (2 confirmations = ~2 seconds)
    private static final long COOLDOWN_MS = 30_000;

    // Enhanced detection parameters
    private static final int PROCESSING_INTERVAL_MS = 30; // Reduced from 50ms for faster checking

    private AudioRecord audioRecord;
    private boolean isRecording = false;

    private Interpreter yamnetInterpreter;
    private Interpreter screamClassifierInterpreter;
    private PersonalizedVoiceHelper voiceHelper;

    private short[] audioBuffer = new short[BUFFER_SIZE];
    private Handler mainHandler;

    private int positiveCount = 0;
    private boolean isInCooldown = false;

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SafetyApp::VoiceDetectionWakelock");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Voice detection starting..."));

        // Load models asynchronously, then start recording
        loadModels();
    }

    private void loadModels() {
        new Thread(() -> {
            try {
                yamnetInterpreter = new Interpreter(loadModelFromAssets("yamnet.tflite"));
                screamClassifierInterpreter = new Interpreter(loadModelFromAssets("scream_classifier.tflite"));
                voiceHelper = new PersonalizedVoiceHelper(getApplicationContext(), yamnetInterpreter);
                boolean embeddingLoaded = voiceHelper.loadStoredEmbedding();
                Log.i(TAG, "Models loaded. Embedding loaded: " + embeddingLoaded);

                mainHandler.post(() -> {
                    startForeground(NOTIFICATION_ID, buildNotification("Voice detection running"));
                    startAudioRecording();
                });

            } catch (IOException e) {
                Log.e(TAG, "Error loading models", e);
                stopSelf();
            }
        }).start();
    }

    private ByteBuffer loadModelFromAssets(String filename) throws IOException {
        InputStream inputStream = getAssets().open(filename);
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, length);
        }
        byte[] modelBytes = byteBuffer.toByteArray();

        ByteBuffer byteBufferModel = ByteBuffer.allocateDirect(modelBytes.length);
        byteBufferModel.order(ByteOrder.nativeOrder());
        byteBufferModel.put(modelBytes);
        byteBufferModel.rewind();
        return byteBufferModel;
    }

    private void startAudioRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Microphone permission not granted");
            stopSelf();
            return;
        }

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBufferSize, BUFFER_SIZE * 2)
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed");
            stopSelf();
            return;
        }

        isRecording = true;

        // Acquire WakeLock to keep CPU running
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(COOLDOWN_MS);
            Log.i(TAG, "WakeLock acquired");
        }

        new Thread(() -> {
            audioRecord.startRecording();
            while (isRecording) {
                if (!isInCooldown) {
                    int read = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                    if (read > 0) {
                        processAudioChunk(audioBuffer);
                    }
                }
                try {
                    Thread.sleep(PROCESSING_INTERVAL_MS); // Faster processing (30ms instead of 50ms)
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
            }
            audioRecord.stop();
            audioRecord.release();

            // Release WakeLock on stop
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "WakeLock released");
            }
        }).start();
    }

    private void processAudioChunk(short[] audioData) {
        if (yamnetInterpreter == null || screamClassifierInterpreter == null || voiceHelper == null) return;

        float rms = PersonalizedVoiceHelper.calculateRMS(audioData);
        if (rms < MIN_VERIFY_RMS) {
            Log.d(TAG, "Audio too quiet for verification (RMS: " + rms + ")");
            return;
        }

        // Convert to float and apply pre-emphasis filter for better voice detection
        float[] floatInput = new float[audioData.length];
        for (int i = 0; i < audioData.length; i++) {
            floatInput[i] = audioData[i] / 32767f;
        }

        // Apply pre-emphasis to enhance high frequencies (improves distress voice detection)
        floatInput = applyPreEmphasis(floatInput);

        Object[] inputs = new Object[]{floatInput};
        Map<Integer, Object> outputs = new HashMap<>();
        float[][] yamnetScores = new float[1][521];
        outputs.put(0, yamnetScores);

        try {
            yamnetInterpreter.runForMultipleInputsOutputs(inputs, outputs);
        } catch (Exception e) {
            Log.e(TAG, "YAMNet inference failed", e);
            return;
        }

        float[] liveEmbedding = yamnetScores[0];

        if (!voiceHelper.hasStoredEmbedding()) {
            Log.d(TAG, "No stored user embedding; skipping verification");
            return;
        }

        boolean verified = voiceHelper.verify(liveEmbedding, audioData);
        if (!verified) {
            Log.d(TAG, "Voice verification failed");
            return;
        }
        Log.d(TAG, "Voice verified");

        float[][] classifierInput = new float[1][521];
        classifierInput[0] = liveEmbedding;

        float screamProb;
        try {
            float[][] screamOutput = new float[1][1];
            screamClassifierInterpreter.run(classifierInput, screamOutput);
            screamProb = screamOutput[0][0];
        } catch (Exception e) {
            Log.e(TAG, "Scream classifier failed", e);
            return;
        }

        Log.d(TAG, "Scream probability: " + screamProb + " RMS: " + rms);

        // STRICT DISTRESS DETECTION: Require BOTH high scream probability AND high intensity
        boolean isHighIntensity = rms >= HIGH_INTENSITY_RMS;
        boolean isDistressScream = screamProb > SCREAM_THRESHOLD;

        if (isDistressScream && isHighIntensity) {
            positiveCount++;
            Log.d(TAG, "DISTRESS DETECTED - Count: " + positiveCount + " (prob: " + screamProb + ", RMS: " + rms + ")");

            if (positiveCount >= CONSECUTIVE_POSITIVES_REQUIRED) {
                Log.i(TAG, "EMERGENCY: Genuine distress voice confirmed - triggering alert");
                positiveCount = 0;
                mainHandler.post(this::triggerEmergency);
            }
        } else {
            // Decay positive count if not distress
            if (positiveCount > 0) {
                positiveCount--;
                Log.d(TAG, "Not distress - reducing count: " + positiveCount + " (prob: " + screamProb + ", RMS: " + rms + ", intensity: " + isHighIntensity + ")");
            }
        }
    }

    /**
     * Apply pre-emphasis filter to enhance high-frequency components
     * This improves detection of distress voices (screaming has high-frequency energy)
     */
    private float[] applyPreEmphasis(float[] audio) {
        float preEmphasisCoeff = 0.97f;
        float[] filtered = new float[audio.length];
        filtered[0] = audio[0];
        for (int i = 1; i < audio.length; i++) {
            filtered[i] = audio[i] - preEmphasisCoeff * audio[i - 1];
        }
        return filtered;
    }

    private void triggerEmergency() {
        if (isInCooldown) {
            Log.d(TAG, "In cooldown; ignoring trigger");
            return;
        }

        isInCooldown = true;
        mainHandler.postDelayed(() -> {
            isInCooldown = false;
            Log.i(TAG, "Cooldown ended");
        }, COOLDOWN_MS);

        if (!wakeLock.isHeld()) {
            wakeLock.acquire(COOLDOWN_MS);
            Log.i(TAG, "WakeLock acquired for emergency");
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing SMS or Location permissions - cannot send emergency message");
            return;
        }

        try {
            EmergencyMessageHelperService.sendEmergencyMessages(getApplicationContext(), "Automated emergency triggered by scream detection");

            mainHandler.post(() -> Toast.makeText(getApplicationContext(), "Scream detected! Emergency SMS sent.", Toast.LENGTH_LONG).show());
        } catch (Exception e) {
            Log.e(TAG, "Failed to send emergency SMS", e);
        }
    }

    private Notification buildNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("নির্ভয় Safety App")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_sos) // Use your app's SOS icon here
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Detection Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Foreground service for voice scream detection");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service started/restarted");
        return START_STICKY; // Automatically restart if killed by system
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRecording = false;

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        if (yamnetInterpreter != null) {
            yamnetInterpreter.close();
            yamnetInterpreter = null;
        }
        if (screamClassifierInterpreter != null) {
            screamClassifierInterpreter.close();
            screamClassifierInterpreter = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WakeLock released on destroy");
        }
        Log.i(TAG, "Service destroyed");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
}

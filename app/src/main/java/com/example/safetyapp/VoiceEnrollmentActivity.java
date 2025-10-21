package com.example.safetyapp;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.example.safetyapp.helper.PersonalizedVoiceHelper;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class VoiceEnrollmentActivity extends BaseActivity {

    private static final int SAMPLE_RATE = 16000;
    private static final int RECORD_DURATION_MS = 975; // match YAMNet
    private static final int PERMISSION_CODE = 2001;
    private static final float MIN_ENROLL_RMS = 0.08f;  // Lowered for easier enrollment

    private TextView statusText;
    private Button enrollButton;
    private PersonalizedVoiceHelper voiceHelper;
    private Interpreter yamnetInterpreter;

    // New UI components
    private View pulseRing1, pulseRing2;
    private ProgressBar audioLevelProgress;
    private TextView audioPercentageText;
    private TextView qualityStatusText;
    private LinearLayout audioLevelContainer;
    private LinearLayout qualityIndicator;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup layout with toolbar, back button, and no bottom nav
        setupLayout(R.layout.activity_voice_enrollment, "Voice Enrollment", true, R.id.nav_home, false);

        statusText = findViewById(R.id.tv_enroll_instruction);
        enrollButton = findViewById(R.id.btn_start_enrollment);

        // Initialize new UI components
        pulseRing1 = findViewById(R.id.pulse_ring_1);
        pulseRing2 = findViewById(R.id.pulse_ring_2);
        audioLevelProgress = findViewById(R.id.progress_audio_level);
        audioPercentageText = findViewById(R.id.tv_audio_percentage);
        qualityStatusText = findViewById(R.id.tv_quality_status);
        audioLevelContainer = findViewById(R.id.audio_level_container);
        qualityIndicator = findViewById(R.id.quality_indicator);

        try {
            AssetFileDescriptor fileDescriptor = getAssets().openFd("yamnet.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();

            ByteBuffer modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
            yamnetInterpreter = new Interpreter(modelBuffer);

            voiceHelper = new PersonalizedVoiceHelper(this, yamnetInterpreter);
        } catch (IOException e) {
            Toast.makeText(this, "Failed to load model", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        enrollButton.setOnClickListener(v -> {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_CODE);
            } else {
                startCountdownAndRecord();
            }
        });
    }

    private void startCountdownAndRecord() {
        statusText.setText("Get ready to shout in...");
        enrollButton.setEnabled(false);

        // Start pulse animation
        startPulseAnimation();

        // Show audio level indicator
        audioLevelContainer.setVisibility(View.VISIBLE);

        new Handler().postDelayed(() -> {
            statusText.setText("3...");
            new Handler().postDelayed(() -> {
                statusText.setText("2...");
                new Handler().postDelayed(() -> {
                    statusText.setText("1...");
                    new Handler().postDelayed(() -> {
                        startVoiceRecording();
                    }, 1000);
                }, 1000);
            }, 1000);
        }, 500);
    }

    private void startPulseAnimation() {
        // Pulse animation for ring 1
        ObjectAnimator pulse1 = ObjectAnimator.ofFloat(pulseRing1, "alpha", 0f, 0.5f, 0f);
        pulse1.setDuration(1500);
        pulse1.setRepeatCount(ValueAnimator.INFINITE);
        pulse1.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse1.start();

        // Pulse animation for ring 2 (delayed)
        ObjectAnimator pulse2 = ObjectAnimator.ofFloat(pulseRing2, "alpha", 0f, 0.5f, 0f);
        pulse2.setDuration(1500);
        pulse2.setStartDelay(750);
        pulse2.setRepeatCount(ValueAnimator.INFINITE);
        pulse2.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse2.start();
    }

    private void stopPulseAnimation() {
        pulseRing1.animate().alpha(0f).setDuration(300).start();
        pulseRing2.animate().alpha(0f).setDuration(300).start();
    }

    private void startVoiceRecording() {
        statusText.setText("Recording... SHOUT NOW!");

        // 1. Check RECORD_AUDIO permission again
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Permission not granted for microphone");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_CODE);
            return;
        }

        // 2. Get recommended buffer size
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
        } catch (Exception e) {
            statusText.setText("Microphone error: " + e.getMessage());
            return;
        }

        // 3. Validate recorder initialization
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            statusText.setText("Microphone not initialized");
            recorder.release();
            return;
        }

        // 4. Prepare audio buffer
        short[] audioData = new short[SAMPLE_RATE * RECORD_DURATION_MS / 1000];
        recorder.startRecording();

        // 5. Read audio in background with real-time visualization
        new Thread(() -> {
            int totalSamples = audioData.length;
            int chunkSize = totalSamples / 10; // 10 updates during recording
            int samplesRead = 0;

            while (samplesRead < totalSamples) {
                int toRead = Math.min(chunkSize, totalSamples - samplesRead);
                recorder.read(audioData, samplesRead, toRead);

                // Calculate RMS for this chunk for visualization
                final int finalSamplesRead = samplesRead + toRead;
                final float chunkRMS = calculateRMSForRange(audioData, 0, finalSamplesRead);

                runOnUiThread(() -> {
                    int progress = (int) (chunkRMS * 1000); // Scale for visualization
                    audioLevelProgress.setProgress(Math.min(100, progress));
                    audioPercentageText.setText(Math.min(100, progress) + "%");
                });

                samplesRead += toRead;
            }

            recorder.stop();
            recorder.release();

            // Stop pulse animation
            runOnUiThread(() -> stopPulseAnimation());

            // 6. Check loudness (RMS)
            float rms = PersonalizedVoiceHelper.calculateRMS(audioData);
            if (rms < MIN_ENROLL_RMS) {
                runOnUiThread(() -> {
                    statusText.setText("Too quiet! Please shout louder");
                    audioLevelContainer.setVisibility(View.GONE);
                    qualityIndicator.setVisibility(View.GONE);
                    enrollButton.setEnabled(true);
                });
                return;
            }

            // Show quality indicator
            runOnUiThread(() -> {
                qualityIndicator.setVisibility(View.VISIBLE);
                if (rms > 0.15f) {
                    qualityStatusText.setText("Excellent");
                    qualityStatusText.setTextColor(0xFF4CAF50);
                } else if (rms > 0.10f) {
                    qualityStatusText.setText("Good");
                    qualityStatusText.setTextColor(0xFF8BC34A);
                } else {
                    qualityStatusText.setText("Fair");
                    qualityStatusText.setTextColor(0xFFFFA726);
                }
            });

            // 7. Save audio
            File wavFile = new File(getFilesDir(), "enrolled_voice.wav");
            boolean saved = saveAsWavFile(audioData, wavFile);

            // 8. Enroll using PersonalizedVoiceHelper
            runOnUiThread(() -> {
                if (!saved) {
                    statusText.setText("Failed to save WAV file");
                    audioLevelContainer.setVisibility(View.GONE);
                    qualityIndicator.setVisibility(View.GONE);
                    enrollButton.setEnabled(true);
                    return;
                }

                boolean enrolled = voiceHelper.enroll(wavFile);
                if (enrolled) {
                    statusText.setText("✓ Voice Enrolled Successfully!");
                    statusText.setTextColor(0xFF4CAF50);
                    Toast.makeText(this, "✓ Your voice is now protected!", Toast.LENGTH_LONG).show();

                    // Hide progress indicators after success
                    new Handler().postDelayed(() -> {
                        audioLevelContainer.setVisibility(View.GONE);
                        enrollButton.setText("Re-enroll Voice");
                        enrollButton.setEnabled(true);
                    }, 2000);
                } else {
                    statusText.setText("Enrollment failed");
                    statusText.setTextColor(0xFFF44336);
                    audioLevelContainer.setVisibility(View.GONE);
                    qualityIndicator.setVisibility(View.GONE);
                    enrollButton.setEnabled(true);
                }
            });

        }).start();
    }


    private float calculateRMS(short[] data) {
        double sum = 0.0;
        for (short s : data) {
            sum += s * s;
        }
        return (float) Math.sqrt(sum / data.length) / 32768f;
    }

    private float calculateRMSForRange(short[] data, int start, int end) {
        if (end <= start) return 0;
        double sum = 0.0;
        for (int i = start; i < end; i++) {
            sum += data[i] * data[i];
        }
        return (float) Math.sqrt(sum / (end - start)) / 32768f;
    }

    private boolean saveAsWavFile(short[] audioData, File file) {
        try (FileOutputStream fos = new FileOutputStream(file);
             BufferedOutputStream bos = new BufferedOutputStream(fos)) {

            int byteRate = SAMPLE_RATE * 2;
            int dataSize = audioData.length * 2;
            int chunkSize = 36 + dataSize;

            writeString(bos, "RIFF");
            writeInt(bos, chunkSize);
            writeString(bos, "WAVE");
            writeString(bos, "fmt ");
            writeInt(bos, 16); // Subchunk1Size
            writeShort(bos, (short) 1); // PCM format
            writeShort(bos, (short) 1); // Mono
            writeInt(bos, SAMPLE_RATE);
            writeInt(bos, byteRate);
            writeShort(bos, (short) 2); // BlockAlign
            writeShort(bos, (short) 16); // BitsPerSample
            writeString(bos, "data");
            writeInt(bos, dataSize);

            for (short sample : audioData) {
                bos.write(sample & 0xff);
                bos.write((sample >> 8) & 0xff);
            }

            bos.flush();
            return true;
        } catch (IOException e) {
            Log.e("WAV", "Failed to save WAV file", e);
            return false;
        }
    }

    private void writeString(OutputStream out, String value) throws IOException {
        out.write(value.getBytes("US-ASCII"));
    }

    private void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private void writeShort(OutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (yamnetInterpreter != null) {
            yamnetInterpreter.close();
        }
    }
}


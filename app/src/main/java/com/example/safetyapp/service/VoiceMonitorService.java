package com.example.safetyapp.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ActivityCompat;

import com.example.safetyapp.helper.EmergencyMessageHelperService;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VoiceMonitorService extends Service {

    private static final String CHANNEL_ID = "voice_monitor_channel";

    private static final int SAMPLE_RATE = 16000;
    private static final int AUDIO_BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
    );

    private Interpreter yamnetInterpreter;
    private Interpreter screamInterpreter;

    private AudioRecord audioRecord;
    private HandlerThread handlerThread;
    private Handler handler;

    private int screamCounter = 0;
    private long lastAlertTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService();

        handlerThread = new HandlerThread("VoiceDetectionThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        handler.post(() -> {
            try {
                yamnetInterpreter = new Interpreter(loadModelFile("yamnet.tflite"));
                screamInterpreter = new Interpreter(loadModelFile("scream_classifier.tflite"));
            } catch (IOException e) {
                e.printStackTrace();
                stopSelf();
                return;
            }

            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    AUDIO_BUFFER_SIZE
            );

            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Permission not granted, stop the service
                stopSelf();
                return;
            }

            audioRecord.startRecording();

            short[] audioBuffer = new short[AUDIO_BUFFER_SIZE / 2]; // half because PCM_16BIT uses 2 bytes/sample

            while (!Thread.currentThread().isInterrupted()) {
                int read = audioRecord.read(audioBuffer, 0, audioBuffer.length);
                if (read > 0) {
                    // Convert short[] to float[] normalized between -1 and 1
                    float[] floatInput = new float[read];
                    for (int i = 0; i < read; i++) {
                        floatInput[i] = audioBuffer[i] / 32768.0f;
                    }

                    // Prepare input/output buffers for YAMNet
                    float[][] yamnetOutput = new float[1][521]; // Adjust output size based on your model
                    yamnetInterpreter.run(floatInput, yamnetOutput);

                    // Run scream classifier using YAMNet output as input
                    float[][] screamOutput = new float[1][1];
                    screamInterpreter.run(yamnetOutput, screamOutput);

                    float screamProb = screamOutput[0][0];

                    if (screamProb > 0.7f) {
                        screamCounter++;
                    } else {
                        screamCounter = 0;
                    }

                    // Trigger emergency if scream detected consecutively and cooldown passed
                    if (screamCounter >= 2 && System.currentTimeMillis() - lastAlertTime > 10000) {
                        EmergencyMessageHelperService.sendEmergencyMessages(getApplicationContext(), "Automated emergency triggered by scream detection");

                        lastAlertTime = System.currentTimeMillis();
                        screamCounter = 0;
                    }
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    break;
                }
            }

            audioRecord.stop();
            audioRecord.release();
            yamnetInterpreter.close();
            screamInterpreter.close();
        });
    }

    private ByteBuffer loadModelFile(String assetName) throws IOException {
        InputStream inputStream = getAssets().open(assetName);
        byte[] buffer = new byte[inputStream.available()];
        int read = inputStream.read(buffer);
        inputStream.close();

        ByteBuffer bb = ByteBuffer.allocateDirect(buffer.length).order(ByteOrder.nativeOrder());
        bb.put(buffer);
        bb.rewind();
        return bb;
    }

    private void startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Voice Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Voice Detection Running")
                .setContentText("Listening for distress voice triggers...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();

        startForeground(1, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        if (audioRecord != null) {
            audioRecord.release();
        }
        if (yamnetInterpreter != null) {
            yamnetInterpreter.close();
        }
        if (screamInterpreter != null) {
            screamInterpreter.close();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

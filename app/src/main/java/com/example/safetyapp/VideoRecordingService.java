package com.example.safetyapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class VideoRecordingService extends LifecycleService {

    private static final String TAG = "VideoRecordingService";
    private static final String CHANNEL_ID = "VideoRecordingChannel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int RECORD_DURATION_MS = 60000; // 60 seconds

    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;
    private Handler handler;
    private Uri savedVideoUri;
    private long recordingStartTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        android.util.Log.i(TAG, "========================================");
        android.util.Log.i(TAG, "VideoRecordingService onCreate() called");
        android.util.Log.i(TAG, "========================================");

        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        android.util.Log.i(TAG, "========================================");
        android.util.Log.i(TAG, "onStartCommand() called");

        String action = intent != null ? intent.getStringExtra("action") : null;

        if ("STOP".equals(action)) {
            android.util.Log.i(TAG, "❌ STOP action received");
            stopRecordingAndService();
            return START_NOT_STICKY;
        }

        android.util.Log.i(TAG, "✅ Starting foreground video recording service");

        try {
            startForeground(NOTIFICATION_ID, createNotification(0));
            android.util.Log.i(TAG, "✅ Foreground notification created");
        } catch (Exception e) {
            android.util.Log.e(TAG, "❌ Failed to start foreground: " + e.getMessage());
        }

        // Initialize camera and start recording
        initializeCamera();

        android.util.Log.i(TAG, "========================================");
        return START_STICKY;
    }

    private void initializeCamera() {
        android.util.Log.i(TAG, "Initializing camera for background recording...");

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                startRecording(cameraProvider);
            } catch (Exception e) {
                android.util.Log.e(TAG, "❌ Failed to get camera provider: " + e.getMessage());
                stopRecordingAndService();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void startRecording(ProcessCameraProvider cameraProvider) {
        android.util.Log.i(TAG, "========================================");
        android.util.Log.i(TAG, "📹 STARTING VIDEO RECORDING");

        try {
            // Video capture with high quality (NO PREVIEW needed for background)
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .build();

            videoCapture = VideoCapture.withOutput(recorder);

            // Select back camera
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

            // Unbind all use cases before rebinding
            cameraProvider.unbindAll();

            // Bind ONLY video capture (no preview for background recording)
            cameraProvider.bindToLifecycle(
                    this,  // LifecycleService
                    cameraSelector,
                    videoCapture);

            android.util.Log.i(TAG, "✅ Camera bound successfully");

            // Create output file
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "SAFETY_VIDEO_" + timeStamp + ".mp4";

            ContentValues contentValues = new ContentValues();
            contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            contentValues.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.put(MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/Safety Evidence");
            }

            MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                    getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                    .setContentValues(contentValues)
                    .build();

            // Start recording with audio
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {

                currentRecording = videoCapture.getOutput()
                        .prepareRecording(this, outputOptions)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                            handleRecordingEvent(videoRecordEvent);
                        });

                android.util.Log.i(TAG, "✅ Recording started successfully");
                recordingStartTime = System.currentTimeMillis();

                // Update notification with timer
                startTimerNotification();

                // Auto-stop after 60 seconds
                handler.postDelayed(this::stopRecordingAndService, RECORD_DURATION_MS);
                android.util.Log.i(TAG, "⏰ Auto-stop scheduled for 60 seconds");

            } else {
                android.util.Log.e(TAG, "❌ Audio permission not granted");
                stopRecordingAndService();
            }

        } catch (Exception e) {
            android.util.Log.e(TAG, "❌ Recording start failed: " + e.getMessage());
            e.printStackTrace();
            stopRecordingAndService();
        }

        android.util.Log.i(TAG, "========================================");
    }

    private void handleRecordingEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            android.util.Log.i(TAG, "✅ VideoRecordEvent.Start - Recording active");

        } else if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) event;

            if (!finalizeEvent.hasError()) {
                savedVideoUri = finalizeEvent.getOutputResults().getOutputUri();
                android.util.Log.i(TAG, "✅ Video saved successfully to: " + savedVideoUri);

                // Notify user
                showCompletionNotification(savedVideoUri);

            } else {
                android.util.Log.e(TAG, "❌ Recording error: " + finalizeEvent.getError());
            }

            // Stop service after recording completes
            handler.postDelayed(this::stopSelf, 2000);
        }
    }

    private void startTimerNotification() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (currentRecording != null && recordingStartTime > 0) {
                    long elapsed = System.currentTimeMillis() - recordingStartTime;
                    int seconds = (int) (elapsed / 1000);
                    int remaining = 60 - seconds;

                    if (remaining > 0) {
                        updateNotification(remaining);
                        handler.postDelayed(this, 1000);
                    }
                }
            }
        });
    }

    private void stopRecordingAndService() {
        android.util.Log.i(TAG, "========================================");
        android.util.Log.i(TAG, "🛑 Stopping recording and service");

        if (currentRecording != null) {
            try {
                currentRecording.stop();
                currentRecording = null;
                android.util.Log.i(TAG, "✅ Recording stopped");
            } catch (Exception e) {
                android.util.Log.e(TAG, "❌ Error stopping recording: " + e.getMessage());
            }
        }

        handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        stopSelf();

        android.util.Log.i(TAG, "✅ Service stopped");
        android.util.Log.i(TAG, "========================================");
    }

    private Notification createNotification(int secondsRemaining) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, VideoRecordingService.class);
        stopIntent.putExtra("action", "STOP");
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0,
                stopIntent, PendingIntent.FLAG_IMMUTABLE);

        String contentText = secondsRemaining > 0
                ? "Recording... " + secondsRemaining + "s remaining"
                : "Recording video evidence...";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🎥 Video Recording Active")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_camera)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_stop_24, "Stop Recording", stopPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVibrate(new long[]{0L})
                .setSound(null)
                .setSilent(true)
                .build();
    }

    private void updateNotification(int secondsRemaining) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, createNotification(secondsRemaining));
        }
    }

    private void showCompletionNotification(Uri videoUri) {
        android.util.Log.i(TAG, "Showing completion notification");

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(videoUri, "video/mp4");
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(this, 0,
                viewIntent, PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("✅ Video Recording Complete")
                .setContentText("60-second evidence video saved successfully")
                .setSmallIcon(R.drawable.ic_camera)
                .setContentIntent(viewPendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVibrate(new long[]{0L})
                .setSound(null)
                .setSilent(true);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID + 1, builder.build());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Video Recording",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Background video recording for safety evidence");
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        android.util.Log.i(TAG, "onDestroy() called - cleaning up");
        handler.removeCallbacksAndMessages(null);
    }
}

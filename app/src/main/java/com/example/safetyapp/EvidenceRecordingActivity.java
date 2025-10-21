package com.example.safetyapp;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.safetyapp.helper.FaceDetectionHelper;
import com.example.safetyapp.service.EvidenceUploadService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EvidenceRecordingActivity extends AppCompatActivity {

    private static final String TAG = "EvidenceRecording";
    private static final int RECORD_DURATION_MS = 60000; // 1 minute for testing (change to 300000 for 5 min)
    private static final int REQ_PERMISSIONS = 100;

    // CameraX
    private PreviewView previewView;
    private VideoCapture<Recorder> videoCapture;
    private Recording currentRecording;
    private ExecutorService cameraExecutor;

    private String outputFilePath;
    private Uri savedVideoUri;
    private boolean isRecording = false;
    private Handler handler = new Handler();
    private Runnable autoStopRunnable;

    // UI Elements
    private TextView tvTimer;
    private TextView tvRecordingStatus;
    private TextView tvUploadPercentage;
    private TextView tvUploadStatus;
    private TextView tvFaceCount;
    private TextView tvContactsCount;
    private ProgressBar progressUpload;
    private View pulseRingOuter;
    private View pulseRingMiddle;
    private View recordingDot;
    private CardView btnStopRecording;

    // Timer
    private long recordingStartTime = 0;
    private Runnable timerRunnable;

    // Evidence data
    private int detectedFaceCount = 0;
    private String recordingLocation = "";
    private String recordingTimestamp = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup lock screen display
        setupLockScreenDisplay();

        setContentView(R.layout.activity_evidence_recording);

        // Initialize camera executor
        cameraExecutor = Executors.newSingleThreadExecutor();

        // Initialize UI elements
        initializeUI();

        // Check permissions
        if (!hasRequiredPermissions()) {
            requestPermissions();
        } else {
            initializeCamera();
        }
    }

    private void initializeUI() {
        previewView = findViewById(R.id.camera_preview);
        tvTimer = findViewById(R.id.tv_timer);
        tvRecordingStatus = findViewById(R.id.tv_recording_status);
        tvUploadPercentage = findViewById(R.id.tv_upload_percentage);
        tvUploadStatus = findViewById(R.id.tv_upload_status);
        tvFaceCount = findViewById(R.id.tv_face_count);
        tvContactsCount = findViewById(R.id.tv_contacts_count);
        progressUpload = findViewById(R.id.progress_upload);
        pulseRingOuter = findViewById(R.id.pulse_ring_outer);
        pulseRingMiddle = findViewById(R.id.pulse_ring_middle);
        recordingDot = findViewById(R.id.recording_dot);
        btnStopRecording = findViewById(R.id.btn_stop_recording);

        // Start pulse animations
        Animation pulseAnimation = AnimationUtils.loadAnimation(this, R.anim.recording_pulse);
        pulseRingOuter.startAnimation(pulseAnimation);
        pulseRingMiddle.startAnimation(pulseAnimation);

        Animation blinkAnimation = AnimationUtils.loadAnimation(this, R.anim.blink_animation);
        recordingDot.startAnimation(blinkAnimation);

        // Setup timer runnable
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long elapsedMillis = System.currentTimeMillis() - recordingStartTime;
                int seconds = (int) (elapsedMillis / 1000);
                int minutes = seconds / 60;
                seconds = seconds % 60;

                tvTimer.setText(String.format("%02d:%02d", minutes, seconds));
                handler.postDelayed(this, 1000);
            }
        };

        // Stop recording button click
        btnStopRecording.setOnClickListener(v -> stopRecording());
        btnStopRecording.setVisibility(View.VISIBLE);

        // Get emergency contacts count
        updateContactsCount();

        // Get current location and timestamp
        getCurrentLocation();
        recordingTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    /**
     * Setup activity to show on lock screen and wake up the device
     */
    private void setupLockScreenDisplay() {
        // For Android 8.1 (API 27) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);

            // Dismiss keyguard for Android 10 and below
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguardManager != null) {
                    keyguardManager.requestDismissKeyguard(this, null);
                }
            }
        } else {
            // For older Android versions
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        // Keep screen on during recording
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Wake up the device if screen is off
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "SafetyApp::EvidenceRecording"
            );
            wakeLock.acquire(5 * 60 * 1000L); // Keep screen on for 5 minutes (recording duration)
            wakeLock.release();
        }

        Log.i(TAG, "Lock screen display configured for evidence recording");
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
               ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                }, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera();
            } else {
                Toast.makeText(this, "Camera and audio permissions required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initializeCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindCamera(cameraProvider);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get camera provider: " + e.getMessage());
                Toast.makeText(this, "Failed to initialize camera", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider cameraProvider) {
        // Preview
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Video capture with high quality
        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                .build();

        videoCapture = VideoCapture.withOutput(recorder);

        // Select back camera
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            // Unbind all use cases before rebinding
            cameraProvider.unbindAll();

            // Bind use cases to camera
            cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    videoCapture);

            Log.d(TAG, "Camera bound successfully");

            // Start recording immediately after camera is ready
            startRecording();

        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed: " + e.getMessage());
            Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void startRecording() {
        if (videoCapture == null) {
            Log.e(TAG, "VideoCapture not initialized");
            return;
        }

        try {
            Log.d(TAG, "=== Starting VIDEO recording ===");

            // Create output file
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "EVIDENCE_VIDEO_" + timeStamp + ".mp4";

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

            // Start recording with audio enabled
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                currentRecording = videoCapture.getOutput()
                        .prepareRecording(this, outputOptions)
                        .withAudioEnabled()
                        .start(ContextCompat.getMainExecutor(this), videoRecordEvent -> {
                            if (videoRecordEvent instanceof VideoRecordEvent.Start) {
                                Log.d(TAG, "✓ Video recording started successfully!");
                                isRecording = true;

                                // Start timer
                                recordingStartTime = System.currentTimeMillis();
                                handler.post(timerRunnable);

                                // Update UI
                                runOnUiThread(() -> {
                                    tvRecordingStatus.setText("● Recording");
                                    tvUploadStatus.setText("Recording video evidence...");
                                    Toast.makeText(this, "Video Evidence Recording Started", Toast.LENGTH_SHORT).show();
                                });

                                // Auto-stop after max duration
                                autoStopRunnable = this::stopRecording;
                                handler.postDelayed(autoStopRunnable, RECORD_DURATION_MS);

                            } else if (videoRecordEvent instanceof VideoRecordEvent.Finalize) {
                                VideoRecordEvent.Finalize finalizeEvent = (VideoRecordEvent.Finalize) videoRecordEvent;

                                if (!finalizeEvent.hasError()) {
                                    savedVideoUri = finalizeEvent.getOutputResults().getOutputUri();
                                    Log.d(TAG, "Video saved to: " + savedVideoUri);

                                    runOnUiThread(() -> {
                                        Toast.makeText(this, "Video saved to Gallery", Toast.LENGTH_SHORT).show();
                                        tvRecordingStatus.setText("● Processing");
                                        tvUploadStatus.setText("Preparing upload...");
                                    });

                                    // Upload and share evidence
                                    uploadAndShareEvidence();

                                } else {
                                    Log.e(TAG, "Video recording error: " + finalizeEvent.getError());
                                    runOnUiThread(() -> {
                                        Toast.makeText(this, "Recording error: " + finalizeEvent.getError(),
                                                Toast.LENGTH_LONG).show();
                                    });
                                }
                            }
                        });
            }

        } catch (Exception e) {
            Log.e(TAG, "Recording start failed: " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            handler.postDelayed(this::finish, 2000);
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording called, isRecording=" + isRecording);

        if (!isRecording || currentRecording == null) {
            Log.d(TAG, "Not recording, ignoring stop request");
            return;
        }

        try {
            currentRecording.stop();
            currentRecording = null;
            isRecording = false;

            // Stop timer and auto-stop handler
            handler.removeCallbacks(timerRunnable);
            if (autoStopRunnable != null) {
                handler.removeCallbacks(autoStopRunnable);
            }

            Log.d(TAG, "Recording stopped successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void uploadAndShareEvidence() {
        if (savedVideoUri == null) {
            Log.e(TAG, "No video URI available for upload");
            return;
        }

        // Copy from MediaStore to temp file for upload
        File evidenceFile = null;
        try {
            String fileName = "temp_evidence_" + System.currentTimeMillis() + ".mp4";
            evidenceFile = new File(getCacheDir(), fileName);

            java.io.InputStream inputStream = getContentResolver().openInputStream(savedVideoUri);
            java.io.FileOutputStream outputStream = new java.io.FileOutputStream(evidenceFile);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            outputStream.close();

            outputFilePath = evidenceFile.getAbsolutePath();

        } catch (Exception e) {
            Log.e(TAG, "Failed to copy file for upload: " + e.getMessage());
            return;
        }

        if (evidenceFile != null && evidenceFile.exists()) {
            final File finalEvidenceFile = evidenceFile;

            // Update UI - start upload
            tvRecordingStatus.setText("● Uploading");
            tvUploadStatus.setText("Uploading to secure cloud storage...");

            EvidenceUploadService uploadService = new EvidenceUploadService(this);
            uploadService.uploadEvidence(finalEvidenceFile, new EvidenceUploadService.UploadCallback() {
                @Override
                public void onSuccess(String downloadUrl) {
                    Log.d(TAG, "Evidence uploaded: " + downloadUrl);

                    runOnUiThread(() -> {
                        tvRecordingStatus.setText("✓ Complete");
                        tvUploadStatus.setText("Upload complete - Evidence secured!");
                        tvUploadPercentage.setText("100%");
                        progressUpload.setProgress(100);
                    });

                    // Detect faces in the video
                    detectFacesInEvidence(finalEvidenceFile);

                    // Share with emergency contacts
                    shareWithEmergencyContacts(downloadUrl);

                    // Schedule auto-delete after 7 days
                    scheduleAutoDeletion(downloadUrl);

                    // Close activity after 5 seconds
                    handler.postDelayed(() -> finish(), 5000);
                }

                @Override
                public void onFailure(Exception e) {
                    Log.e(TAG, "Upload failed: " + e.getMessage());
                    runOnUiThread(() -> {
                        tvRecordingStatus.setText("✗ Failed");
                        tvUploadStatus.setText("Upload failed: " + e.getMessage());
                        Toast.makeText(EvidenceRecordingActivity.this, "Upload failed", Toast.LENGTH_LONG).show();
                    });
                    handler.postDelayed(() -> finish(), 2000);
                }

                @Override
                public void onProgress(int progress) {
                    Log.d(TAG, "Upload progress: " + progress + "%");
                    runOnUiThread(() -> {
                        progressUpload.setProgress(progress);
                        tvUploadPercentage.setText(progress + "%");
                    });
                }
            });
        }
    }

    private void shareWithEmergencyContacts(String downloadUrl) {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference contactsRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(userId)
                .child("emergencyContacts");

        contactsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int contactCount = 0;
                java.util.List<String> phoneNumbers = new java.util.ArrayList<>();

                for (DataSnapshot contactSnapshot : snapshot.getChildren()) {
                    String phoneNumber = contactSnapshot.child("phone").getValue(String.class);
                    if (phoneNumber != null) {
                        phoneNumbers.add(phoneNumber);
                        contactCount++;
                    }
                }

                final int finalCount = contactCount;
                runOnUiThread(() -> {
                    tvContactsCount.setText(String.valueOf(finalCount));
                });

                Log.d(TAG, "Sending evidence to " + contactCount + " contacts");

                // Send SMS and WhatsApp to all contacts
                for (String phoneNumber : phoneNumbers) {
                    sendEvidenceNotification(phoneNumber, downloadUrl);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to fetch contacts: " + error.getMessage());
            }
        });
    }

    private void updateContactsCount() {
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        DatabaseReference contactsRef = FirebaseDatabase.getInstance()
                .getReference("Users")
                .child(userId)
                .child("emergencyContacts");

        contactsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int count = (int) snapshot.getChildrenCount();
                tvContactsCount.setText(String.valueOf(count));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvContactsCount.setText("0");
            }
        });
    }

    private void sendEvidenceNotification(String phoneNumber, String downloadUrl) {
        // Create comprehensive evidence message
        StringBuilder message = new StringBuilder();
        message.append("🚨 EMERGENCY VIDEO EVIDENCE RECORDED\n\n");
        message.append("⏰ Time: ").append(recordingTimestamp).append("\n");

        if (!recordingLocation.isEmpty()) {
            message.append("📍 Location: ").append(recordingLocation).append("\n");
        }

        message.append("👤 Faces Detected: ").append(detectedFaceCount).append("\n");
        message.append("📹 Video Duration: ").append(getRecordingDuration()).append("\n\n");

        message.append("🔗 View Evidence:\n").append(downloadUrl).append("\n\n");

        message.append("⚠️ IMPORTANT:\n");
        message.append("• Evidence stored securely for 7 days\n");
        message.append("• Cannot be deleted remotely\n");
        message.append("• Share with authorities if needed\n\n");

        message.append("- নির্ভয় Safety App");

        String finalMessage = message.toString();

        // Send via SMS
        sendSMS(phoneNumber, finalMessage);

        // Also send via WhatsApp (opens WhatsApp for each contact)
        sendWhatsAppMessage(phoneNumber, finalMessage);
    }

    private void sendSMS(String phoneNumber, String message) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                    == PackageManager.PERMISSION_GRANTED) {

                android.telephony.SmsManager smsManager = android.telephony.SmsManager.getDefault();

                // Split message if too long
                java.util.ArrayList<String> parts = smsManager.divideMessage(message);

                if (parts.size() > 1) {
                    smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                } else {
                    smsManager.sendTextMessage(phoneNumber, null, message, null, null);
                }

                Log.d(TAG, "SMS sent to: " + phoneNumber);
                Toast.makeText(this, "SMS sent to " + phoneNumber, Toast.LENGTH_SHORT).show();
            } else {
                Log.w(TAG, "SMS permission not granted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendWhatsAppMessage(String phoneNumber, String message) {
        try {
            // Clean phone number
            String cleanPhone = phoneNumber.replace("+", "").replaceAll("\\s", "").replaceAll("-", "");

            // WhatsApp URL with message
            String url = "https://wa.me/" + cleanPhone + "?text=" +
                    android.net.Uri.encode(message);

            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setData(android.net.Uri.parse(url));
            intent.setPackage("com.whatsapp");

            // Check if WhatsApp is installed
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                Log.d(TAG, "WhatsApp message sent to: " + phoneNumber);
            } else {
                // WhatsApp not installed, try WhatsApp Business
                intent.setPackage("com.whatsapp.w4b");
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    Log.w(TAG, "WhatsApp not installed");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending WhatsApp message: " + e.getMessage());
        }
    }

    private String getRecordingDuration() {
        if (recordingStartTime == 0) return "00:00";

        long durationMs = System.currentTimeMillis() - recordingStartTime;
        int seconds = (int) (durationMs / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;

        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            recordingLocation = "Location not available";
            return;
        }

        com.google.android.gms.location.FusedLocationProviderClient locationClient =
                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);

        locationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        recordingLocation = "https://maps.google.com/?q=" +
                                location.getLatitude() + "," + location.getLongitude();
                    } else {
                        recordingLocation = "Location not available";
                    }
                })
                .addOnFailureListener(e -> {
                    recordingLocation = "Location not available";
                    Log.e(TAG, "Failed to get location: " + e.getMessage());
                });
    }

    private void detectFacesInEvidence(File videoFile) {
        if (videoFile == null || !videoFile.exists()) {
            Log.e(TAG, "Video file is null or doesn't exist for face detection");
            detectedFaceCount = 0;
            return;
        }

        Log.d(TAG, "Starting face detection on: " + videoFile.getAbsolutePath());
        FaceDetectionHelper faceDetector = new FaceDetectionHelper(this);

        faceDetector.detectFacesInVideo(videoFile, new FaceDetectionHelper.FaceDetectionCallback() {
            @Override
            public void onFacesDetected(int faceCount, java.util.List<com.google.mlkit.vision.face.Face> faces) {
                detectedFaceCount = faceCount;
                runOnUiThread(() -> {
                    tvFaceCount.setText(String.valueOf(faceCount));
                });
                Log.d(TAG, "Faces detected in evidence: " + faceCount);
            }

            @Override
            public void onError(Exception e) {
                Log.e(TAG, "Face detection failed: " + e.getMessage());
                e.printStackTrace();
                detectedFaceCount = 0;
                runOnUiThread(() -> {
                    tvFaceCount.setText("0");
                });
            }
        });
    }

    private void scheduleAutoDeletion(String downloadUrl) {
        // Store deletion timestamp in Firebase
        String userId = FirebaseAuth.getInstance().getCurrentUser().getUid();
        long deletionTime = System.currentTimeMillis() + (7 * 24 * 60 * 60 * 1000); // 7 days

        DatabaseReference evidenceRef = FirebaseDatabase.getInstance()
                .getReference("Evidence")
                .child(userId)
                .push();

        evidenceRef.child("url").setValue(downloadUrl);
        evidenceRef.child("createdAt").setValue(System.currentTimeMillis());
        evidenceRef.child("deleteAt").setValue(deletionTime);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Continue recording in background
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    @Override
    public void onBackPressed() {
        // Allow back button - just stop recording and exit
        if (isRecording) {
            stopRecording();
        }
        super.onBackPressed();
    }
}

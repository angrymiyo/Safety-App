package com.example.safetyapp;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.safetyapp.service.EvidenceUploadService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class EvidenceRecordingActivity extends AppCompatActivity {

    private static final String TAG = "EvidenceRecording";
    private static final int RECORD_DURATION_MS = 60000; // 1 minute for testing (change to 300000 for 5 min)
    private static final int REQ_CAMERA_AUDIO = 100;

    private MediaRecorder mediaRecorder;
    private String outputFilePath;
    private Uri galleryAudioUri;
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
        setContentView(R.layout.activity_evidence_recording);

        // Initialize UI elements
        initializeUI();

        // Check permissions
        if (!hasRequiredPermissions()) {
            requestPermissions();
        } else {
            initializeRecording();
        }
    }

    private void initializeUI() {
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
        btnStopRecording.setVisibility(View.VISIBLE); // Make button visible

        // Get emergency contacts count
        updateContactsCount();

        // Get current location and timestamp
        getCurrentLocation();
        recordingTimestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    private boolean hasRequiredPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.RECORD_AUDIO
                }, REQ_CAMERA_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeRecording();
            } else {
                Toast.makeText(this, "Audio recording permission required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initializeRecording() {
        // Start audio recording immediately
        startRecording();
    }

    private void startRecording() {
        try {
            Log.d(TAG, "=== Starting AUDIO recording ===");

            // Create output file
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String fileName = "EVIDENCE_AUDIO_" + timeStamp + ".m4a";

            // Record to app's external files directory (reliable)
            File outputDir = new File(getExternalFilesDir(null), "Evidence");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            File outputFile = new File(outputDir, fileName);
            outputFilePath = outputFile.getAbsolutePath();

            Log.d(TAG, "Recording to: " + outputFilePath);

            // Setup MediaRecorder
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(this);
            } else {
                mediaRecorder = new MediaRecorder();
            }

            // Set audio source ONLY (no video/camera)
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            Log.d(TAG, "Audio source set");

            // Set output format and encoder for audio
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            Log.d(TAG, "Format and encoder set");

            // Set audio quality
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            Log.d(TAG, "Audio quality set");

            // Set output file
            mediaRecorder.setOutputFile(outputFilePath);
            Log.d(TAG, "Output file set");

            // Set max duration
            mediaRecorder.setMaxDuration(RECORD_DURATION_MS);

            mediaRecorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.d(TAG, "Max duration reached, stopping recording");
                    stopRecording();
                }
            });

            mediaRecorder.setOnErrorListener((mr, what, extra) -> {
                Log.e(TAG, "MediaRecorder error: what=" + what + " extra=" + extra);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Recording error: " + what, Toast.LENGTH_LONG).show();
                });
                stopRecording();
            });

            // Prepare MediaRecorder
            Log.d(TAG, "Preparing MediaRecorder...");
            mediaRecorder.prepare();
            Log.d(TAG, "MediaRecorder prepared");

            // Start recording
            Log.d(TAG, "Starting MediaRecorder...");
            mediaRecorder.start();
            isRecording = true;
            Log.d(TAG, "✓ Audio recording started successfully!");

            // Start timer
            recordingStartTime = System.currentTimeMillis();
            handler.post(timerRunnable);

            // Update UI
            tvRecordingStatus.setText("● Recording");
            tvUploadStatus.setText("Recording in progress...");
            Toast.makeText(this, "Audio Evidence Recording Started", Toast.LENGTH_SHORT).show();

            Log.d(TAG, "Audio recording started: " + outputFilePath);

            // Auto-stop after max duration with stronger handler
            autoStopRunnable = this::stopRecording;
            handler.postDelayed(autoStopRunnable, RECORD_DURATION_MS + 1000);

        } catch (Exception e) {
            Log.e(TAG, "Recording start failed: " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();

            // Clean up on failure
            releaseMediaRecorder();
            handler.postDelayed(this::finish, 2000);
        }
    }

    private void stopRecording() {
        Log.d(TAG, "stopRecording called, isRecording=" + isRecording);

        if (!isRecording) {
            Log.d(TAG, "Not recording, ignoring stop request");
            return;
        }

        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                isRecording = false;
                Log.d(TAG, "MediaRecorder stopped successfully");

                // Stop timer and auto-stop handler
                handler.removeCallbacks(timerRunnable);
                if (autoStopRunnable != null) {
                    handler.removeCallbacks(autoStopRunnable);
                }

                // Update UI
                tvRecordingStatus.setText("● Processing");
                tvUploadStatus.setText("Preparing upload...");

                Log.d(TAG, "Recording stopped, file at: " + outputFilePath);

                // Copy audio to gallery
                copyAudioToGallery();

                // Upload to cloud and share with contacts
                uploadAndShareEvidence();

            } catch (Exception e) {
                Log.e(TAG, "Error stopping recording: " + e.getMessage());
                e.printStackTrace();
                isRecording = false;
            }
        }
        releaseMediaRecorder();
    }

    private void copyAudioToGallery() {
        try {
            File sourceFile = new File(outputFilePath);
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source file doesn't exist: " + outputFilePath);
                return;
            }

            String fileName = sourceFile.getName();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Use MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Safety Evidence");
                values.put(MediaStore.Audio.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);

                if (uri != null) {
                    // Copy file content
                    try (java.io.InputStream in = new java.io.FileInputStream(sourceFile);
                         java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {

                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                    }

                    // Mark as completed
                    values.clear();
                    values.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, values, null, null);

                    galleryAudioUri = uri;
                    Log.d(TAG, "Audio copied to gallery: " + uri);
                    Toast.makeText(this, "Audio saved to Music folder", Toast.LENGTH_SHORT).show();
                }
            } else {
                // Android 9 and below - Copy to public directory
                File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                File evidenceDir = new File(musicDir, "Safety Evidence");
                if (!evidenceDir.exists()) {
                    evidenceDir.mkdirs();
                }

                File destFile = new File(evidenceDir, fileName);

                try (java.io.FileInputStream in = new java.io.FileInputStream(sourceFile);
                     java.io.FileOutputStream out = new java.io.FileOutputStream(destFile)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                // Scan file to make it visible
                android.media.MediaScannerConnection.scanFile(
                    this,
                    new String[]{destFile.getAbsolutePath()},
                    new String[]{"audio/mp4"},
                    (path, uri) -> {
                        Log.d(TAG, "Audio scanned to gallery: " + uri);
                        galleryAudioUri = uri;
                    }
                );

                Toast.makeText(this, "Audio saved to Music folder", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy audio to gallery: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void uploadAndShareEvidence() {
        File evidenceFile = null;

        // Get file from Uri (Android 10+) or path (Android 9-)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && galleryAudioUri != null) {
            // For Android 10+, we need to copy from MediaStore Uri to temp file for upload
            try {
                String fileName = "temp_evidence_" + System.currentTimeMillis() + ".m4a";
                evidenceFile = new File(getCacheDir(), fileName);

                java.io.InputStream inputStream = getContentResolver().openInputStream(galleryAudioUri);
                java.io.FileOutputStream outputStream = new java.io.FileOutputStream(evidenceFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }

                inputStream.close();
                outputStream.close();

            } catch (Exception e) {
                Log.e(TAG, "Failed to copy file for upload: " + e.getMessage());
            }
        } else if (outputFilePath != null) {
            evidenceFile = new File(outputFilePath);
        }

        if (evidenceFile != null && evidenceFile.exists()) {
            // Make evidenceFile effectively final for use in callback
            final File finalEvidenceFile = evidenceFile;

            // Start upload service
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

                    // Share with emergency contacts (skip face detection for audio)
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
        message.append("🚨 EMERGENCY AUDIO EVIDENCE RECORDED\n\n");
        message.append("⏰ Time: ").append(recordingTimestamp).append("\n");

        if (!recordingLocation.isEmpty()) {
            message.append("📍 Location: ").append(recordingLocation).append("\n");
        }

        message.append("🎙️ Audio Duration: ").append(getRecordingDuration()).append("\n\n");

        message.append("🔗 Listen to Evidence:\n").append(downloadUrl).append("\n\n");

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

    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
                Log.d(TAG, "MediaRecorder released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaRecorder: " + e.getMessage());
            }
        }
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
        releaseMediaRecorder();
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

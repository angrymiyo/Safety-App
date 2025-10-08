package com.example.safetyapp.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.io.File;

public class EvidenceUploadService {

    private static final String TAG = "EvidenceUpload";
    private final Context context;
    private final FirebaseStorage storage;
    private final String userId;

    public interface UploadCallback {
        void onSuccess(String downloadUrl);
        void onFailure(Exception e);
        void onProgress(int progress);
    }

    public EvidenceUploadService(Context context) {
        this.context = context;
        this.storage = FirebaseStorage.getInstance();
        this.userId = FirebaseAuth.getInstance().getCurrentUser() != null ?
                FirebaseAuth.getInstance().getCurrentUser().getUid() : "anonymous";
    }

    public void uploadEvidence(File evidenceFile, UploadCallback callback) {
        if (!evidenceFile.exists()) {
            callback.onFailure(new Exception("Evidence file does not exist"));
            return;
        }

        // Create storage reference
        String fileName = evidenceFile.getName();
        StorageReference evidenceRef = storage.getReference()
                .child("evidence")
                .child(userId)
                .child(fileName);

        // Upload file
        Uri fileUri = Uri.fromFile(evidenceFile);
        UploadTask uploadTask = evidenceRef.putFile(fileUri);

        uploadTask.addOnProgressListener(snapshot -> {
            double progress = (100.0 * snapshot.getBytesTransferred()) / snapshot.getTotalByteCount();
            callback.onProgress((int) progress);
            Log.d(TAG, "Upload progress: " + progress + "%");
        }).addOnSuccessListener(taskSnapshot -> {
            // Get download URL
            evidenceRef.getDownloadUrl().addOnSuccessListener(uri -> {
                String downloadUrl = uri.toString();
                Log.d(TAG, "Upload successful: " + downloadUrl);
                callback.onSuccess(downloadUrl);
            }).addOnFailureListener(callback::onFailure);

        }).addOnFailureListener(e -> {
            Log.e(TAG, "Upload failed: " + e.getMessage());
            callback.onFailure(e);
        });
    }

    public void uploadAudioEvidence(File audioFile, UploadCallback callback) {
        uploadEvidence(audioFile, callback);
    }

    public void uploadVideoEvidence(File videoFile, UploadCallback callback) {
        uploadEvidence(videoFile, callback);
    }

    public void deleteEvidence(String downloadUrl) {
        try {
            StorageReference fileRef = storage.getReferenceFromUrl(downloadUrl);
            fileRef.delete()
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "Evidence deleted: " + downloadUrl))
                    .addOnFailureListener(e -> Log.e(TAG, "Failed to delete evidence: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "Error deleting evidence: " + e.getMessage());
        }
    }
}

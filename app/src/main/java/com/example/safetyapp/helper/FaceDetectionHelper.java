package com.example.safetyapp.helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.File;
import java.util.List;

public class FaceDetectionHelper {

    private static final String TAG = "FaceDetection";
    private final Context context;
    private final FaceDetector detector;

    public interface FaceDetectionCallback {
        void onFacesDetected(int faceCount, List<Face> faces);
        void onError(Exception e);
    }

    public FaceDetectionHelper(Context context) {
        this.context = context;

        // Configure face detector
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();

        this.detector = FaceDetection.getClient(options);
    }

    /**
     * Detect faces in an image file
     */
    public void detectFacesInImage(File imageFile, FaceDetectionCallback callback) {
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath());
            if (bitmap == null) {
                callback.onError(new Exception("Failed to decode image"));
                return;
            }

            InputImage image = InputImage.fromBitmap(bitmap, 0);
            detectFaces(image, callback);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting faces in image: " + e.getMessage());
            callback.onError(e);
        }
    }

    /**
     * Detect faces in video file (extracts frames)
     */
    public void detectFacesInVideo(File videoFile, FaceDetectionCallback callback) {
        try {
            if (!videoFile.exists()) {
                callback.onError(new Exception("Video file does not exist: " + videoFile.getAbsolutePath()));
                return;
            }

            Log.d(TAG, "Extracting frame from video: " + videoFile.getAbsolutePath() + " (size: " + videoFile.length() + " bytes)");

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());

            // Try multiple time points to extract frame
            Bitmap bitmap = null;
            long[] timePoints = {1000000, 2000000, 500000, 3000000, 0}; // 1s, 2s, 0.5s, 3s, start

            for (long timePoint : timePoints) {
                bitmap = retriever.getFrameAtTime(timePoint, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (bitmap != null) {
                    Log.d(TAG, "Successfully extracted frame at " + (timePoint / 1000000.0) + "s");
                    break;
                }
            }

            retriever.release();

            if (bitmap == null) {
                Log.e(TAG, "Failed to extract any frame from video");
                callback.onError(new Exception("Failed to extract video frame - video may be corrupted"));
                return;
            }

            Log.d(TAG, "Bitmap extracted successfully: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            detectFaces(image, callback);

        } catch (Exception e) {
            Log.e(TAG, "Error detecting faces in video: " + e.getMessage());
            e.printStackTrace();
            callback.onError(e);
        }
    }

    /**
     * Detect faces in bitmap
     */
    public void detectFacesInBitmap(Bitmap bitmap, FaceDetectionCallback callback) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);
            detectFaces(image, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error detecting faces in bitmap: " + e.getMessage());
            callback.onError(e);
        }
    }

    /**
     * Core face detection method
     */
    private void detectFaces(InputImage image, FaceDetectionCallback callback) {
        detector.process(image)
                .addOnSuccessListener(faces -> {
                    Log.d(TAG, "Faces detected: " + faces.size());
                    callback.onFacesDetected(faces.size(), faces);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Face detection failed: " + e.getMessage());
                    callback.onError(e);
                });
    }

    /**
     * Get face detection summary for evidence
     */
    public String getFaceDetectionSummary(List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            return "No faces detected in the evidence";
        }

        StringBuilder summary = new StringBuilder();
        summary.append(faces.size()).append(" face(s) detected:\n\n");

        for (int i = 0; i < faces.size(); i++) {
            Face face = faces.get(i);
            summary.append("Person ").append(i + 1).append(":\n");
            summary.append("- Position: (").append(face.getBoundingBox().centerX())
                    .append(", ").append(face.getBoundingBox().centerY()).append(")\n");

            if (face.getTrackingId() != null) {
                summary.append("- ID: ").append(face.getTrackingId()).append("\n");
            }

            summary.append("\n");
        }

        return summary.toString();
    }

    public void close() {
        if (detector != null) {
            detector.close();
        }
    }
}

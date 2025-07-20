package com.example.safetyapp.helper;

import android.content.Context;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;

public class PersonalizedVoiceHelper {
    private static final String TAG = "VoiceHelper";
    private static final String EMBEDDING_FILE = "user_embedding.dat";
    private static final float MATCH_THRESHOLD = 0.35f; // Optimized for shout recognition
    private static final float MIN_ENROLL_RMS = 0.1f;   // Minimum loudness for enrollment
    private static final float MIN_VERIFY_RMS = 0.08f;  // Minimum loudness for verification

    private final Context context;
    private final Interpreter yamnetInterpreter;
    private float[] storedEmbedding;

    public PersonalizedVoiceHelper(Context context, Interpreter yamnetInterpreter) {
        this.context = context;
        this.yamnetInterpreter = yamnetInterpreter;
    }

    // ==== RMS Calculation Methods ====
    public static float calculateRMS(short[] audioData) {
        double sum = 0.0;
        for (short s : audioData) {
            sum += s * s;
        }
        return (float) Math.sqrt(sum / audioData.length) / 32768f;
    }

    private float calculateRMS(float[] audioData) {
        double sum = 0.0;
        for (float sample : audioData) {
            sum += sample * sample;
        }
        return (float) Math.sqrt(sum / audioData.length);
    }

    // ==== Enrollment ====
    public boolean enroll(File wavFile) {
        float[] audioData = readWavFileMono16kPCM(wavFile);
        if (audioData == null) {
            Log.e(TAG, "Failed to read WAV file");
            return false;
        }

        // Check audio loudness
        float rms = calculateRMS(audioData);
        if (rms < MIN_ENROLL_RMS) {
            Log.e(TAG, "Enrollment failed: Audio too quiet (RMS: " + rms + ")");
            return false;
        }

        float[] embedding = runYamnet(audioData);
        if (embedding == null) {
            Log.e(TAG, "Failed to generate embedding");
            return false;
        }

        return saveEmbeddingToFile(embedding);
    }

    // ==== Verification ====
    public boolean verify(float[] liveEmbedding, short[] audioData) {
        if (storedEmbedding == null || liveEmbedding == null) {
            Log.w(TAG, "Verification failed: Missing embeddings");
            return false;
        }

        // Check audio loudness first
        float rms = calculateRMS(audioData);
        if (rms < MIN_VERIFY_RMS) {
            Log.d(TAG, "Verification skipped: Audio too quiet (RMS: " + rms + ")");
            return false;
        }

        // Calculate cosine similarity
        float similarity = calculateCosineSimilarity(storedEmbedding, liveEmbedding);
        Log.d(TAG, "Verification: Similarity=" + similarity + " RMS=" + rms);

        return similarity >= MATCH_THRESHOLD;
    }

    private float calculateCosineSimilarity(float[] a, float[] b) {
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / ((float) Math.sqrt(normA) * (float) Math.sqrt(normB));
    }

    // ==== Loading ====
    public boolean loadStoredEmbedding() {
        storedEmbedding = loadEmbeddingFromFile();
        if (storedEmbedding == null) {
            Log.d(TAG, "No stored embedding found");
            return false;
        }
        Log.d(TAG, "Stored embedding loaded successfully");
        return true;
    }

    // ==== YAMNet Inference ====
    public synchronized float[] runYamnet(float[] audioData) {
        float[][] output = new float[1][521];
        Object[] inputs = new Object[]{audioData};
        HashMap<Integer, Object> outputs = new HashMap<>();
        outputs.put(0, output);

        try {
            yamnetInterpreter.runForMultipleInputsOutputs(inputs, outputs);
            return output[0];
        } catch (Exception e) {
            Log.e(TAG, "YAMNet inference failed", e);
            return null;
        }
    }

    // ==== WAV to PCM Float Conversion ====
    private float[] readWavFileMono16kPCM(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[44];
            if (fis.read(header) != 44) {
                Log.e(TAG, "Invalid WAV header length");
                return null;
            }

            // Validate RIFF and WAVE headers
            String chunkID = new String(header, 0, 4);
            String format = new String(header, 8, 4);
            if (!"RIFF".equals(chunkID) || !"WAVE".equals(format)) {
                Log.e(TAG, "Invalid WAV file: not RIFF/WAVE");
                return null;
            }

            // Read remaining audio data
            int dataSize = fis.available();
            byte[] audioBytes = new byte[dataSize];
            if (fis.read(audioBytes) != dataSize) {
                Log.e(TAG, "Could not read WAV audio data fully");
                return null;
            }

            int numSamples = dataSize / 2;
            float[] audio = new float[numSamples];
            ByteBuffer buffer = ByteBuffer.wrap(audioBytes).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < numSamples; i++) {
                short sample = buffer.getShort();
                audio[i] = sample / 32768.0f; // normalize to [-1.0, 1.0]
            }
            return audio;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read WAV", e);
            return null;
        }
    }

    // ==== Embedding Management ====
    private boolean saveEmbeddingToFile(float[] embedding) {
        File file = new File(context.getFilesDir(), EMBEDDING_FILE);
        try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(file))) {
            for (float v : embedding) {
                dos.writeFloat(v);
            }
            Log.d(TAG, "Embedding saved successfully");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save embedding", e);
            return false;
        }
    }

    private float[] loadEmbeddingFromFile() {
        File file = new File(context.getFilesDir(), EMBEDDING_FILE);
        if (!file.exists()) {
            Log.d(TAG, "Embedding file does not exist");
            return null;
        }

        try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
            float[] embedding = new float[521];
            for (int i = 0; i < 521; i++) {
                embedding[i] = dis.readFloat();
            }
            Log.d(TAG, "Embedding loaded from file");
            return embedding;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load embedding", e);
            return null;
        }
    }

    // ==== Reset ====
    public boolean clearSavedEmbedding() {
        File file = new File(context.getFilesDir(), EMBEDDING_FILE);
        boolean deleted = file.exists() && file.delete();
        if (deleted) {
            storedEmbedding = null;
            Log.d(TAG, "Embedding cleared successfully");
        }
        return deleted;
    }

    public boolean hasStoredEmbedding() {
        File file = new File(context.getFilesDir(), EMBEDDING_FILE);
        return file.exists();
    }
}
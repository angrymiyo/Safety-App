package com.example.safetyapp.helper;

import android.util.Log;

/**
 * Detects ambient distress sounds and crowd emergencies
 * Works independently of voice enrollment - detects ANY distress
 */
public class AmbientDistressDetector {
    private static final String TAG = "AmbientDistress";

    // YAMNet class indices for ambient emergency detection
    private static final int CLASS_SCREAM = 307;
    private static final int CLASS_CRYING = 309;
    private static final int CLASS_YELL = 305;
    private static final int CLASS_GASP = 308;
    private static final int CLASS_CROWD = 74;
    private static final int CLASS_PANIC = 305;
    private static final int CLASS_GLASS_BREAK = 396;
    private static final int CLASS_CRASH = 403;
    private static final int CLASS_EXPLOSION = 427;
    private static final int CLASS_FIRE_ALARM = 388;
    private static final int CLASS_SIREN = 390;

    // Detection thresholds
    private static final float AMBIENT_SOUND_THRESHOLD = 0.25f;
    private static final float HIGH_AMBIENT_THRESHOLD = 0.5f;

    /**
     * Check if audio contains ambient distress (independent of voice matching)
     * @param yamnetScores Audio classification scores from YAMNet
     * @param rms Audio loudness level
     * @return Ambient distress score (0.0 - 1.0)
     */
    public static float detectAmbientDistress(float[] yamnetScores, float rms) {
        if (yamnetScores == null || yamnetScores.length < 521) {
            return 0.0f;
        }

        float ambientScore = 0.0f;
        int detectionCount = 0;

        // Check for distress vocalizations
        if (yamnetScores[CLASS_SCREAM] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_SCREAM] * 1.5f; // High weight for screams
            detectionCount++;
            Log.d(TAG, "Scream detected in ambient: " + yamnetScores[CLASS_SCREAM]);
        }

        if (yamnetScores[CLASS_YELL] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_YELL] * 1.2f;
            detectionCount++;
            Log.d(TAG, "Yell detected in ambient: " + yamnetScores[CLASS_YELL]);
        }

        if (yamnetScores[CLASS_CRYING] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_CRYING] * 1.0f;
            detectionCount++;
            Log.d(TAG, "Crying detected in ambient: " + yamnetScores[CLASS_CRYING]);
        }

        if (yamnetScores[CLASS_GASP] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_GASP] * 0.8f;
            detectionCount++;
        }

        // Check for crowd/panic indicators
        if (yamnetScores[CLASS_CROWD] > HIGH_AMBIENT_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_CROWD] * 1.3f;
            detectionCount++;
            Log.d(TAG, "Crowd panic detected: " + yamnetScores[CLASS_CROWD]);
        }

        // Check for emergency sounds
        if (yamnetScores[CLASS_GLASS_BREAK] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_GLASS_BREAK] * 1.0f;
            detectionCount++;
            Log.d(TAG, "Glass break detected: " + yamnetScores[CLASS_GLASS_BREAK]);
        }

        if (yamnetScores[CLASS_CRASH] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_CRASH] * 1.2f;
            detectionCount++;
            Log.d(TAG, "Crash detected: " + yamnetScores[CLASS_CRASH]);
        }

        if (yamnetScores[CLASS_EXPLOSION] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_EXPLOSION] * 1.5f;
            detectionCount++;
            Log.d(TAG, "Explosion detected: " + yamnetScores[CLASS_EXPLOSION]);
        }

        if (yamnetScores[CLASS_FIRE_ALARM] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_FIRE_ALARM] * 1.0f;
            detectionCount++;
            Log.d(TAG, "Fire alarm detected: " + yamnetScores[CLASS_FIRE_ALARM]);
        }

        if (yamnetScores[CLASS_SIREN] > AMBIENT_SOUND_THRESHOLD) {
            ambientScore += yamnetScores[CLASS_SIREN] * 0.9f;
            detectionCount++;
            Log.d(TAG, "Siren detected: " + yamnetScores[CLASS_SIREN]);
        }

        // Boost score if RMS is high (loud environment)
        if (rms > 0.15f) {
            ambientScore *= 1.2f;
        }

        // Boost if multiple distress indicators present (crowd emergency)
        if (detectionCount >= 2) {
            ambientScore *= 1.3f;
            Log.i(TAG, "Multiple ambient distress indicators detected (" + detectionCount + ")");
        }

        // Normalize to 0-1 range
        float normalizedScore = Math.min(ambientScore / 2.0f, 1.0f);

        if (normalizedScore > 0.3f) {
            Log.i(TAG, "Ambient distress score: " + normalizedScore + " (detections: " + detectionCount + ", RMS: " + rms + ")");
        }

        return normalizedScore;
    }

    /**
     * Check if crowd panic is occurring (multiple people in distress)
     */
    public static boolean isCrowdPanic(float[] yamnetScores, float rms) {
        if (yamnetScores == null || yamnetScores.length < 521) {
            return false;
        }

        // High crowd sound + high distress sounds = panic
        boolean highCrowd = yamnetScores[CLASS_CROWD] > HIGH_AMBIENT_THRESHOLD;
        boolean highDistress = yamnetScores[CLASS_SCREAM] > AMBIENT_SOUND_THRESHOLD ||
                               yamnetScores[CLASS_YELL] > AMBIENT_SOUND_THRESHOLD;
        boolean loudEnvironment = rms > 0.2f;

        return highCrowd && highDistress && loudEnvironment;
    }

    /**
     * Check if emergency sound is present (fire alarm, explosion, crash, etc.)
     */
    public static boolean hasEmergencySounds(float[] yamnetScores) {
        if (yamnetScores == null || yamnetScores.length < 521) {
            return false;
        }

        return yamnetScores[CLASS_FIRE_ALARM] > AMBIENT_SOUND_THRESHOLD ||
               yamnetScores[CLASS_EXPLOSION] > AMBIENT_SOUND_THRESHOLD ||
               yamnetScores[CLASS_CRASH] > AMBIENT_SOUND_THRESHOLD ||
               yamnetScores[CLASS_GLASS_BREAK] > AMBIENT_SOUND_THRESHOLD;
    }

    /**
     * Get description of detected ambient sounds
     */
    public static String getAmbientDescription(float[] yamnetScores, float rms) {
        if (isCrowdPanic(yamnetScores, rms)) {
            return "Crowd panic/stampede detected";
        }

        if (hasEmergencySounds(yamnetScores)) {
            if (yamnetScores[CLASS_FIRE_ALARM] > AMBIENT_SOUND_THRESHOLD) {
                return "Fire alarm detected";
            }
            if (yamnetScores[CLASS_EXPLOSION] > AMBIENT_SOUND_THRESHOLD) {
                return "Explosion sound detected";
            }
            if (yamnetScores[CLASS_CRASH] > AMBIENT_SOUND_THRESHOLD) {
                return "Crash/collision detected";
            }
            if (yamnetScores[CLASS_GLASS_BREAK] > AMBIENT_SOUND_THRESHOLD) {
                return "Glass breaking detected";
            }
        }

        if (yamnetScores[CLASS_SCREAM] > AMBIENT_SOUND_THRESHOLD) {
            return "Scream detected nearby";
        }

        if (yamnetScores[CLASS_YELL] > AMBIENT_SOUND_THRESHOLD) {
            return "Loud yelling detected";
        }

        return "Ambient distress detected";
    }
}

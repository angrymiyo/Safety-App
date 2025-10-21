package com.example.safetyapp.helper;

import android.util.Log;

/**
 * Detects vocal stress and panic in user's voice
 * Distinguishes between normal conversation and distressed speech
 */
public class VocalStressDetector {
    private static final String TAG = "VocalStress";

    // BALANCED THRESHOLDS - Detect genuine distress while blocking conversation
    private static final float EXTREME_DISTRESS_THRESHOLD = 0.55f;   // Instant trigger for genuine screams (55%-60%)
    private static final float MODERATE_DISTRESS_THRESHOLD = 0.50f;  // Moderate distress - user's requested threshold
    private static final float CONVERSATION_FILTER_THRESHOLD = 0.35f; // Below this = definitely conversation

    private static final int ANALYSIS_WINDOW = 5;                  // Analyze trends over multiple samples

    // Historical data for trend analysis
    private static float[] recentRMS = new float[ANALYSIS_WINDOW];
    private static float[] recentScreamProb = new float[ANALYSIS_WINDOW];
    private static int historyIndex = 0;

    /**
     * Analyze if user's voice shows distress/panic patterns
     * Strict filtering: Detects genuine distress while blocking conversation
     * @param audioData Raw audio samples
     * @param screamProbability Scream classifier output (PRIMARY indicator)
     * @param rms Audio loudness
     * @return Distress level (0.0 = calm, 1.0 = extreme distress)
     */
    public static float analyzeVocalStress(short[] audioData, float screamProbability, float rms) {
        // Update history
        recentRMS[historyIndex] = rms;
        recentScreamProb[historyIndex] = screamProbability;
        historyIndex = (historyIndex + 1) % ANALYSIS_WINDOW;

        // Primary filter: Scream probability - THE MOST IMPORTANT CHECK
        // The ML model is trained to detect screams/distress, trust it as primary indicator
        if (screamProbability < CONVERSATION_FILTER_THRESHOLD) {
            Log.d(TAG, "Scream too low (prob: " + screamProbability + " < 35%) - normal talking");
            return 0.0f;
        }

        // CRITICAL: ALWAYS check if sustained - this blocks single loud words like "HELLO!"
        // Even if scream is 83%, a single loud word should NEVER trigger
        float sustainedLevel = calculateSustainedDistress();
        if (sustainedLevel < 0.4f) {
            // Less than 40% of recent samples elevated = short burst = loud word/conversation
            Log.d(TAG, "NOT SUSTAINED (prob: " + screamProbability + ", sustained: " + sustainedLevel + ") - SINGLE LOUD WORD/CONVERSATION BLOCKED");
            return 0.0f;
        }

        // If scream is high (55%+) AND sustained, it's genuine scream/distress
        if (screamProbability >= EXTREME_DISTRESS_THRESHOLD) {
            Log.i(TAG, "GENUINE SCREAM DETECTED (prob: " + screamProbability + " >= 55%, sustained: " + sustainedLevel + ")");
            return screamProbability;
        }

        // For moderate scream levels (35-55%), apply additional filters to block conversation
        // Different requirements based on scream probability:
        // - 50-55%: Need only 1 more check (already passed sustained)
        // - 35-50%: Need 2 more checks (stricter for borderline cases)

        int passedChecks = 1; // Already passed sustained check

        // Check 2: Energy variance (blocks stable loud talking)
        float variance = calculateEnergyVariance(audioData);
        if (variance >= 0.02f) {
            passedChecks++;
            Log.d(TAG, "✓ Variance check passed (variance: " + variance + ")");
        }

        // Check 3: Zero-crossing rate (detects distressed voice quality)
        float zcr = calculateZeroCrossingRate(audioData);
        if (zcr >= 0.07f) {
            passedChecks++;
            Log.d(TAG, "✓ ZCR check passed (zcr: " + zcr + ")");
        }

        // Check 4: Sudden loudness increase (panic has volume spikes)
        float loudnessIncrease = calculateLoudnessIncrease();
        if (loudnessIncrease >= 0.02f) {
            passedChecks++;
            Log.d(TAG, "✓ Loudness increase check passed (increase: " + loudnessIncrease + ")");
        }

        // Determine required checks based on scream probability
        // At 50%+: need 2 total (sustained + 1 more)
        // At 35-50%: need 3 total (sustained + 2 more)
        int requiredChecks = (screamProbability >= MODERATE_DISTRESS_THRESHOLD) ? 2 : 3;

        if (passedChecks >= requiredChecks) {
            Log.i(TAG, "DISTRESS CONFIRMED (prob: " + screamProbability + ", sustained: " + sustainedLevel + ", passed " + passedChecks + "/" + requiredChecks + " required checks)");
            return screamProbability;
        } else {
            Log.d(TAG, "Likely conversation (prob: " + screamProbability + ", sustained: " + sustainedLevel + ", only passed " + passedChecks + "/" + requiredChecks + " required checks)");
            return 0.0f;
        }
    }

    /**
     * Check if detected phrase contains emergency keywords
     * Used to confirm moderate scream levels (60-75%)
     */
    public static boolean hasEmergencyPhrase(String detectedPhrase) {
        if (detectedPhrase == null || detectedPhrase.isEmpty()) {
            return false;
        }

        String phrase = detectedPhrase.toLowerCase();

        // Emergency keywords in English, Bangla, Hindi
        String[] emergencyWords = {
            "help", "emergency", "danger", "save", "attack", "fire", "accident",
            "kidnap", "assault", "harassment", "police", "ambulance",
            "সাহায্য", "বাঁচাও", "বিপদ", "জরুরি", "আক্রমণ", "আগুন",
            "मदद", "बचाओ", "खतरा"
        };

        for (String word : emergencyWords) {
            if (phrase.contains(word)) {
                Log.i(TAG, "Emergency keyword detected: " + word);
                return true;
            }
        }

        return false;
    }


    /**
     * Calculate energy variance (sudden volume changes)
     * High variance indicates emotional/distressed speech
     */
    private static float calculateEnergyVariance(short[] audioData) {
        if (audioData == null || audioData.length < 100) return 0.0f;

        // Split audio into small frames and calculate energy variance
        int frameSize = 160;  // 10ms at 16kHz
        int numFrames = audioData.length / frameSize;
        if (numFrames < 2) return 0.0f;

        float[] frameEnergies = new float[numFrames];

        for (int i = 0; i < numFrames; i++) {
            float energy = 0;
            int start = i * frameSize;
            int end = Math.min(start + frameSize, audioData.length);

            for (int j = start; j < end; j++) {
                float sample = audioData[j] / 32768.0f;
                energy += sample * sample;
            }
            frameEnergies[i] = (float) Math.sqrt(energy / (end - start));
        }

        // Calculate variance of frame energies
        float mean = 0;
        for (float energy : frameEnergies) {
            mean += energy;
        }
        mean /= frameEnergies.length;

        float variance = 0;
        for (float energy : frameEnergies) {
            float diff = energy - mean;
            variance += diff * diff;
        }
        variance /= frameEnergies.length;

        return variance;
    }

    /**
     * Calculate sudden increase in loudness (panic indicator)
     */
    private static float calculateLoudnessIncrease() {
        if (historyIndex < 2) return 0.0f;

        // Compare current RMS to average of previous samples
        float currentRMS = recentRMS[(historyIndex - 1 + ANALYSIS_WINDOW) % ANALYSIS_WINDOW];

        float previousAvg = 0;
        int count = 0;
        for (int i = 0; i < ANALYSIS_WINDOW - 1; i++) {
            previousAvg += recentRMS[i];
            count++;
        }
        previousAvg /= count;

        return Math.max(0, currentRMS - previousAvg);
    }

    /**
     * Calculate zero-crossing rate (voice quality indicator)
     * Distressed/tense voice has higher ZCR
     */
    private static float calculateZeroCrossingRate(short[] audioData) {
        if (audioData == null || audioData.length < 2) return 0.0f;

        int zeroCrossings = 0;
        for (int i = 1; i < audioData.length; i++) {
            if ((audioData[i] >= 0 && audioData[i-1] < 0) ||
                (audioData[i] < 0 && audioData[i-1] >= 0)) {
                zeroCrossings++;
            }
        }

        return (float) zeroCrossings / audioData.length;
    }

    /**
     * Check if distress is sustained over multiple samples
     * Short bursts (single loud word) will fail this check
     */
    private static float calculateSustainedDistress() {
        int distressedSamples = 0;

        for (int i = 0; i < ANALYSIS_WINDOW; i++) {
            // Count samples that show elevated distress levels (35%+)
            // Sustained pattern indicates genuine distress, not just single loud word like "HELLO!"
            if (recentScreamProb[i] >= CONVERSATION_FILTER_THRESHOLD) {
                distressedSamples++;
            }
        }

        // Return proportion of distressed samples in window
        // 0.4 (2/5 samples) = sustained pattern like "help me" or distressed talking
        // 0.2 (1/5 samples) = single loud word like "HELLO!", fails this check
        return (float) distressedSamples / ANALYSIS_WINDOW;
    }

    /**
     * Determine if audio is likely normal conversation (not distress)
     * Returns true for conversation, false for genuine distress
     */
    public static boolean isNormalConversation(short[] audioData, float screamProbability, float rms) {
        // Primary check: scream probability
        if (screamProbability < CONVERSATION_FILTER_THRESHOLD) {
            return true; // Below 35% = definitely conversation
        }

        // CRITICAL: ALWAYS check if sustained first
        // Single loud words like "HELLO!" will fail this check regardless of scream %
        float sustainedLevel = calculateSustainedDistress();
        if (sustainedLevel < 0.4f) {
            return true; // Not sustained = single loud word/conversation
        }

        // If scream is high (55%+) AND sustained, it's genuine scream (not conversation)
        if (screamProbability >= EXTREME_DISTRESS_THRESHOLD) {
            return false; // Definitely distress
        }

        // For moderate levels (35-55%), check additional filters
        // Same logic as analyzeVocalStress:
        // - 50-55%: Need 2 total checks (sustained + 1 more)
        // - 35-50%: Need 3 total checks (sustained + 2 more)

        int passedChecks = 1; // Already passed sustained

        float variance = calculateEnergyVariance(audioData);
        if (variance >= 0.02f) passedChecks++;

        float zcr = calculateZeroCrossingRate(audioData);
        if (zcr >= 0.07f) passedChecks++;

        float loudnessIncrease = calculateLoudnessIncrease();
        if (loudnessIncrease >= 0.02f) passedChecks++;

        // Determine required checks based on scream probability
        int requiredChecks = (screamProbability >= MODERATE_DISTRESS_THRESHOLD) ? 2 : 3;

        // If doesn't pass required checks, it's conversation
        return passedChecks < requiredChecks;
    }

    /**
     * Reset history (call when starting new detection session)
     */
    public static void resetHistory() {
        recentRMS = new float[ANALYSIS_WINDOW];
        recentScreamProb = new float[ANALYSIS_WINDOW];
        historyIndex = 0;
        Log.d(TAG, "Vocal stress history reset");
    }
}

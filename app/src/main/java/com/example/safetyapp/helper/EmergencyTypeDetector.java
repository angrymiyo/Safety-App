package com.example.safetyapp.helper;

import android.util.Log;
import java.util.HashMap;
import java.util.Map;

/**
 * Multi-class emergency detection system
 * Analyzes audio to detect various emergency scenarios
 */
public class EmergencyTypeDetector {
    private static final String TAG = "EmergencyDetector";

    // Emergency types
    public enum EmergencyType {
        ASSAULT_ATTACK("Physical assault/attack detected"),
        KIDNAPPING("Kidnapping attempt detected"),
        HARASSMENT("Harassment detected"),
        STALKING("Being followed/stalked"),
        ROAD_ACCIDENT("Vehicle crash/accident detected"),
        MEDICAL_EMERGENCY("Medical emergency detected"),
        FIRE("Fire outbreak detected"),
        GAS_LEAK("Gas leak/toxic air detected"),
        TRAPPED("Trapped in unsafe area"),
        CROWD_PANIC("Crowd panic/mob detected"),
        UNSAFE_TRANSPORT("Unsafe transport situation"),
        SNATCHING("Snatching attempt detected"),
        DRUNK_BEHAVIOR("Drunk/abusive behavior nearby"),
        POWER_OUTAGE("Power outage emergency"),
        LOST_STRANDED("Lost/stranded situation"),
        GENERAL_DISTRESS("General distress call"),
        NONE("No emergency detected");

        private final String description;
        EmergencyType(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }

    // YAMNet class indices for emergency sounds (from AudioSet ontology)
    private static final int CLASS_SCREAM = 307;  // Screaming
    private static final int CLASS_CRYING = 309;  // Crying, sobbing
    private static final int CLASS_GLASS_BREAK = 396;  // Glass breaking
    private static final int CLASS_CRASH = 403;  // Vehicle crash
    private static final int CLASS_EXPLOSION = 427;  // Explosion
    private static final int CLASS_FIRE_ALARM = 388;  // Smoke detector, fire alarm
    private static final int CLASS_SIREN = 390;  // Siren
    private static final int CLASS_GUNSHOT = 427;  // Gunshot, gunfire
    private static final int CLASS_CROWD = 74;   // Crowd
    private static final int CLASS_YELL = 305;   // Yell
    private static final int CLASS_GASP = 308;   // Gasp
    private static final int CLASS_WHIMPER = 310; // Whimper

    // Detection thresholds
    private static final float YAMNET_THRESHOLD = 0.3f;
    private static final float SCREAM_THRESHOLD = 0.5f;  // 50% distress detection threshold
    private static final float PHRASE_CONFIDENCE_MIN = 0.6f;

    /**
     * Detect emergency type from audio analysis
     * @param yamnetScores YAMNet 521-class output
     * @param screamProbability Scream classifier output
     * @param rms Audio loudness
     * @param detectedPhrases Emergency keywords detected (can be null)
     * @return Detected emergency type
     */
    public static EmergencyType detectEmergencyType(
            float[] yamnetScores,
            float screamProbability,
            float rms,
            String detectedPhrases) {

        // Priority 1: Check for explicit emergency phrases
        if (detectedPhrases != null && !detectedPhrases.isEmpty()) {
            EmergencyType phraseEmergency = classifyFromPhrase(detectedPhrases);
            if (phraseEmergency != EmergencyType.NONE) {
                Log.i(TAG, "Emergency detected from phrase: " + phraseEmergency);
                return phraseEmergency;
            }
        }

        // Priority 2: High-confidence scream detection
        if (screamProbability > SCREAM_THRESHOLD) {
            // Analyze context with YAMNet to determine scream type
            return classifyScreamContext(yamnetScores, rms);
        }

        // Priority 3: Ambient sound-based detection
        EmergencyType ambientEmergency = detectAmbientEmergency(yamnetScores);
        if (ambientEmergency != EmergencyType.NONE) {
            Log.i(TAG, "Ambient emergency detected: " + ambientEmergency);
            return ambientEmergency;
        }

        return EmergencyType.NONE;
    }

    /**
     * Classify emergency from detected speech phrases
     * Uses AI-powered semantic understanding (not just keyword matching)
     */
    private static EmergencyType classifyFromPhrase(String phrase) {
        // Use semantic intent classifier for intelligent detection
        EmergencyIntentClassifier.EmergencyClassification classification =
                EmergencyIntentClassifier.classifyIntent(phrase);

        // Only return if confidence is high enough
        if (classification.confidence >= 0.5f) {
            Log.i(TAG, "Semantic classification: " + classification.type +
                  " (confidence: " + classification.confidence + ")");
            return classification.type;
        }

        // Fallback to keyword matching for backward compatibility
        String lowerPhrase = phrase.toLowerCase();

        // Quick keyword checks as fallback
        if (containsAny(lowerPhrase, "attack", "hitting me", "beating", "assault")) {
            return EmergencyType.ASSAULT_ATTACK;
        }
        if (containsAny(lowerPhrase, "kidnap", "abduct", "forcing me")) {
            return EmergencyType.KIDNAPPING;
        }
        if (containsAny(lowerPhrase, "following me", "stalking", "being followed")) {
            return EmergencyType.STALKING;
        }
        if (containsAny(lowerPhrase, "harassment", "molest", "inappropriate")) {
            return EmergencyType.HARASSMENT;
        }
        if (containsAny(lowerPhrase, "help", "emergency", "danger", "save me")) {
            return EmergencyType.GENERAL_DISTRESS;
        }

        return EmergencyType.NONE;
    }

    /**
     * Classify scream type based on audio context
     */
    private static EmergencyType classifyScreamContext(float[] yamnetScores, float rms) {
        // High intensity + scream = likely assault
        if (rms > 0.15f) {
            if (yamnetScores[CLASS_GLASS_BREAK] > YAMNET_THRESHOLD) {
                return EmergencyType.ASSAULT_ATTACK;
            }
            if (yamnetScores[CLASS_CRASH] > YAMNET_THRESHOLD) {
                return EmergencyType.ROAD_ACCIDENT;
            }
            if (yamnetScores[CLASS_CROWD] > YAMNET_THRESHOLD) {
                return EmergencyType.CROWD_PANIC;
            }
        }

        // Crying/whimpering = harassment/distress
        if (yamnetScores[CLASS_CRYING] > YAMNET_THRESHOLD ||
            yamnetScores[CLASS_WHIMPER] > YAMNET_THRESHOLD) {
            return EmergencyType.HARASSMENT;
        }

        // Default high-confidence scream = assault/attack
        return EmergencyType.ASSAULT_ATTACK;
    }

    /**
     * Detect emergency from ambient sounds (non-voice)
     */
    private static EmergencyType detectAmbientEmergency(float[] yamnetScores) {
        Map<EmergencyType, Float> scores = new HashMap<>();

        // Fire alarm detection
        if (yamnetScores[CLASS_FIRE_ALARM] > YAMNET_THRESHOLD) {
            scores.put(EmergencyType.FIRE, yamnetScores[CLASS_FIRE_ALARM]);
        }

        // Crash/collision detection
        if (yamnetScores[CLASS_CRASH] > YAMNET_THRESHOLD ||
            yamnetScores[CLASS_GLASS_BREAK] > YAMNET_THRESHOLD) {
            float crashScore = Math.max(yamnetScores[CLASS_CRASH], yamnetScores[CLASS_GLASS_BREAK]);
            scores.put(EmergencyType.ROAD_ACCIDENT, crashScore);
        }

        // Explosion/gunshot detection
        if (yamnetScores[CLASS_EXPLOSION] > YAMNET_THRESHOLD ||
            yamnetScores[CLASS_GUNSHOT] > YAMNET_THRESHOLD) {
            scores.put(EmergencyType.ASSAULT_ATTACK, yamnetScores[CLASS_EXPLOSION]);
        }

        // Siren detection (could be any emergency)
        if (yamnetScores[CLASS_SIREN] > YAMNET_THRESHOLD) {
            scores.put(EmergencyType.GENERAL_DISTRESS, yamnetScores[CLASS_SIREN]);
        }

        // Crowd panic detection
        if (yamnetScores[CLASS_CROWD] > 0.5f && yamnetScores[CLASS_YELL] > YAMNET_THRESHOLD) {
            scores.put(EmergencyType.CROWD_PANIC, yamnetScores[CLASS_CROWD]);
        }

        // Return highest confidence emergency
        if (!scores.isEmpty()) {
            return scores.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .get()
                    .getKey();
        }

        return EmergencyType.NONE;
    }

    /**
     * Check if text contains any of the keywords
     */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get confidence level for emergency detection
     */
    public static float getConfidenceLevel(EmergencyType type, float screamProb, float[] yamnetScores) {
        if (type == EmergencyType.NONE) return 0.0f;

        // For scream-based emergencies, return scream probability
        if (screamProb > SCREAM_THRESHOLD) {
            return screamProb;
        }

        // For ambient sound emergencies, return max YAMNet score
        float maxScore = 0.0f;
        for (float score : yamnetScores) {
            maxScore = Math.max(maxScore, score);
        }
        return maxScore;
    }
}

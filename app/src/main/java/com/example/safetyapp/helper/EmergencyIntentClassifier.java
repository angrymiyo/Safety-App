package com.example.safetyapp.helper;

import android.util.Log;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI-powered semantic emergency intent classifier
 * Uses natural language understanding to detect emergencies beyond keyword matching
 */
public class EmergencyIntentClassifier {
    private static final String TAG = "IntentClassifier";

    // Intent patterns with semantic variations and context
    private static final Map<EmergencyTypeDetector.EmergencyType, IntentPattern[]> INTENT_PATTERNS = new HashMap<>();

    // Common distress indicators across all emergencies (English + Bangla)
    private static final String[] DISTRESS_SIGNALS = {
        // English - General Help/SOS
        "help", "please", "urgent", "emergency", "danger", "scared", "afraid",
        "quickly", "now", "fast", "immediately", "someone", "anybody",
        "save me", "i'm in danger", "sos", "call the police", "i need help",
        "don't hurt me", "someone please",
        // English - Fear/Panic
        "i'm scared", "i'm afraid", "please don't", "oh my god",
        "what's happening", "no no no", "i can't breathe", "stay away from me",
        // English - Emotional Distress
        "i can't take it anymore", "i'm not okay", "i'm tired of everything",
        "i want to disappear", "please someone listen",
        // Bangla - General Help/SOS
        "সাহায্য", "বাঁচাও", "সাহায্য করুন", "দয়া করে", "জরুরি", "জরুরী",
        "বিপদ", "ভয়", "দ্রুত", "এখনই", "কেউ", "কাউকে",
        "সাহায্য করো", "আমাকে বাঁচাও", "আমি বিপদে আছি", "পুলিশ ডাকো",
        "কেউ আছো", "আমি ভয় পাচ্ছি", "আমার কিছু হবে", "দয়া করে বাঁচাও",
        "ওরা আমাকে মারবে",
        // Bangla - Fear/Panic
        "আমি ভয় পেয়েছি", "দয়া করে না", "হে আল্লাহ", "এ কী হচ্ছে",
        "না না না", "দূরে থাকো", "আমি পারছি না", "আমাকে ছেড়ে দাও",
        // Bangla - Emotional Distress
        "আর পারছি না", "আমি ভালো নেই", "সব কিছু শেষ হয়ে গেছে",
        "আমি হারিয়ে যেতে চাই", "কেউ শুনবে না আমাকে"
    };

    // Action verbs indicating threat (English + Bangla)
    private static final String[] THREAT_VERBS = {
        // English
        "attacking", "hitting", "hurting", "forcing", "grabbing", "chasing",
        "following", "watching", "tracking", "threatening", "harassing",
        "he's hitting me", "they're attacking me", "someone is following me",
        "he's trying to kidnap me", "i'm trapped",
        // Bangla
        "আক্রমণ", "মারছে", "আঘাত", "জোর", "ধরছে", "তাড়া",
        "অনুসরণ", "দেখছে", "ট্র্যাকিং", "হুমকি", "হয়রানি",
        "ওরা আমাকে মারছে", "ওরা আমার পেছনে", "আমাকে তুলে নিচ্ছে",
        "আমি আটকে গেছি", "কেউ আমাকে অনুসরণ করছে"
    };

    static {
        initializeIntentPatterns();
    }

    /**
     * Intent pattern with semantic understanding
     */
    private static class IntentPattern {
        String[] coreTokens;        // Primary concepts
        String[] contextTokens;     // Supporting context
        String[] negationTokens;    // Words that negate this intent
        float weight;               // Pattern confidence weight

        IntentPattern(String[] core, String[] context, String[] negation, float weight) {
            this.coreTokens = core;
            this.contextTokens = context;
            this.negationTokens = negation;
            this.weight = weight;
        }
    }

    private static void initializeIntentPatterns() {
        // ASSAULT/ATTACK - Physical violence intent (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.ASSAULT_ATTACK, new IntentPattern[]{
            new IntentPattern(
                new String[]{"attack", "hitting", "beating", "assault", "punch", "kick", "hurt", "violence",
                            "আক্রমণ", "মারছে", "পিটাচ্ছে", "মারধর", "আঘাত", "লাথি", "সহিংসতা"},
                new String[]{"me", "someone", "person", "man", "woman", "physical",
                            "আমাকে", "কেউ", "লোক", "মানুষ", "শারীরিক"},
                new String[]{"movie", "game", "talking about", "saw",
                            "সিনেমা", "খেলা", "দেখেছি"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"fighting", "aggressive", "violent", "harm", "injure", "weapon",
                            "মারামারি", "আক্রমণাত্মক", "হিংস্র", "ক্ষতি", "আহত", "অস্ত্র"},
                new String[]{"trying", "attempting", "coming", "towards",
                            "চেষ্টা", "আসছে", "দিকে"},
                new String[]{"watching", "heard", "দেখছি", "শুনেছি"},
                0.9f
            )
        });

        // KIDNAPPING - Forced movement or confinement (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.KIDNAPPING, new IntentPattern[]{
            new IntentPattern(
                new String[]{"kidnap", "abduct", "taking", "dragging", "forcing",
                            "অপহরণ", "তুলে নিয়ে যাচ্ছে", "টেনে নিয়ে যাচ্ছে", "জোর করে"},
                new String[]{"me", "away", "car", "van", "somewhere", "against", "will",
                            "আমাকে", "দূরে", "গাড়ি", "কোথাও", "ইচ্ছার বিরুদ্ধে"},
                new String[]{"movie", "news", "সিনেমা", "খবর"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"grabbed", "pulling", "holding", "won't let", "trying to take",
                            "ধরেছে", "টানছে", "ধরে রেখেছে", "ছাড়ছে না"},
                new String[]{"forcefully", "against", "struggling",
                            "জোরপূর্বক", "বিরুদ্ধে", "প্রতিরোধ"},
                new String[]{},
                0.95f
            )
        });

        // STALKING/FOLLOWING (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.STALKING, new IntentPattern[]{
            new IntentPattern(
                new String[]{"following", "stalking", "tracking", "watching", "behind",
                            "অনুসরণ", "পিছু নিচ্ছে", "ট্র্যাকিং", "দেখছে", "পিছনে"},
                new String[]{"me", "someone", "person", "everywhere", "long time", "won't stop",
                            "আমাকে", "কেউ", "সব জায়গায়", "দীর্ঘ সময়", "থামছে না"},
                new String[]{"social media", "instagram", "twitter", "সোশ্যাল মিডিয়া"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"chasing", "pursuing", "coming after", "walking behind", "shadowing",
                            "তাড়া করছে", "পিছু ধাওয়া", "পিছনে আসছে", "পিছে হাঁটছে"},
                new String[]{"keeps", "constantly", "always", "everywhere",
                            "ক্রমাগত", "সবসময়", "সর্বদা"},
                new String[]{},
                0.95f
            )
        });

        // HARASSMENT - Sexual or verbal harassment (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.HARASSMENT, new IntentPattern[]{
            new IntentPattern(
                new String[]{"harassment", "harassing", "molest", "inappropriate", "touching", "groping",
                            "হয়রানি", "উত্ত্যক্ত", "শ্লীলতাহানি", "অনুপযুক্ত", "স্পর্শ", "ছোঁয়া"},
                new String[]{"me", "sexually", "unwanted", "uncomfortable",
                            "আমাকে", "যৌনভাবে", "অনাকাঙ্ক্ষিত", "অস্বস্তিকর"},
                new String[]{"complaining", "report", "অভিযোগ", "রিপোর্ট"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"bothering", "won't leave", "making uncomfortable", "advances", "catcalling",
                            "বিরক্ত করছে", "ছাড়ছে না", "অস্বস্তিতে ফেলছে", "বাজে কথা"},
                new String[]{"repeatedly", "keeps", "won't stop",
                            "বারবার", "ক্রমাগত", "থামছে না"},
                new String[]{},
                0.9f
            )
        });

        // ROAD ACCIDENT (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.ROAD_ACCIDENT, new IntentPattern[]{
            new IntentPattern(
                new String[]{"accident", "crash", "collision", "hit", "struck",
                            "দুর্ঘটনা", "দুর্ঘটনা হয়েছে", "ধাক্কা", "সংঘর্ষ", "আঘাত"},
                new String[]{"car", "vehicle", "bike", "truck", "road", "traffic",
                            "গাড়ি", "যানবাহন", "বাইক", "ট্রাক", "রাস্তা", "যানজট"},
                new String[]{"saw", "heard", "news", "দেখেছি", "শুনেছি", "খবর"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"crashed", "collided", "ran over", "knocked down",
                            "ধাক্কা খেয়েছে", "চাপা পড়েছে", "পড়ে গেছে"},
                new String[]{"injured", "hurt", "bleeding",
                            "আহত", "ব্যথা", "রক্তপাত"},
                new String[]{},
                0.95f
            )
        });

        // MEDICAL EMERGENCY (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.MEDICAL_EMERGENCY, new IntentPattern[]{
            new IntentPattern(
                new String[]{"heart attack", "chest pain", "breathe", "breathing", "faint", "fainting", "collapsed",
                            "হার্ট অ্যাটাক", "বুকে ব্যথা", "শ্বাস", "শ্বাস নিতে", "অজ্ঞান", "জ্ঞান হারানো", "পড়ে গেছে"},
                new String[]{"can't", "cannot", "unable", "difficulty", "pain",
                            "পারছি না", "অক্ষম", "কষ্ট", "ব্যথা"},
                new String[]{"yesterday", "before", "গতকাল", "আগে"},
                1.0f
            ),
            new IntentPattern(
                new String[]{"medical", "injured", "bleeding", "unconscious", "seizure", "stroke",
                            "চিকিৎসা", "আহত", "রক্তপাত", "অচেতন", "খিঁচুনি", "স্ট্রোক"},
                new String[]{"emergency", "urgent", "serious", "badly",
                            "জরুরি", "জরুরী", "গুরুতর", "খারাপ"},
                new String[]{},
                0.95f
            )
        });

        // FIRE (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.FIRE, new IntentPattern[]{
            new IntentPattern(
                new String[]{"fire", "burning", "flames", "smoke", "ablaze",
                            "আগুন", "জ্বলছে", "আগুন লেগেছে", "ধোঁয়া", "অগ্নিকাণ্ড"},
                new String[]{"building", "house", "room", "spreading", "everywhere",
                            "বিল্ডিং", "ঘর", "বাড়ি", "রুম", "ছড়িয়ে পড়ছে", "সব জায়গায়"},
                new String[]{"extinguisher", "drill", "নির্বাপক", "মহড়া"},
                1.0f
            )
        });

        // GAS LEAK (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.GAS_LEAK, new IntentPattern[]{
            new IntentPattern(
                new String[]{"gas", "leak", "smell", "fumes", "toxic", "carbon monoxide",
                            "গ্যাস", "লিক", "গন্ধ", "ধোঁয়া", "বিষাক্ত", "বিষ"},
                new String[]{"leaking", "strong", "can't breathe", "poisonous",
                            "লিক হচ্ছে", "তীব্র", "শ্বাস নিতে পারছি না", "বিষাক্ত"},
                new String[]{},
                1.0f
            )
        });

        // TRAPPED (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.TRAPPED, new IntentPattern[]{
            new IntentPattern(
                new String[]{"trapped", "stuck", "locked", "can't get out", "confined",
                            "আটকে গেছি", "আটকা", "তালাবদ্ধ", "বের হতে পারছি না", "আবদ্ধ"},
                new String[]{"in", "inside", "room", "building", "elevator", "car",
                            "ভিতরে", "রুমে", "বিল্ডিং", "লিফট", "গাড়ি"},
                new String[]{},
                1.0f
            )
        });

        // CROWD PANIC (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.CROWD_PANIC, new IntentPattern[]{
            new IntentPattern(
                new String[]{"stampede", "crowd", "mob", "riot", "panic", "crush",
                            "পদদলিত", "ভিড়", "জনতা", "দাঙ্গা", "আতঙ্ক", "পিষ্ট"},
                new String[]{"pushing", "rushing", "running", "trampled", "chaos",
                            "ধাক্কা", "ছুটছে", "দৌড়াচ্ছে", "পিষে যাচ্ছে", "বিশৃঙ্খলা"},
                new String[]{},
                1.0f
            )
        });

        // UNSAFE TRANSPORT (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.UNSAFE_TRANSPORT, new IntentPattern[]{
            new IntentPattern(
                new String[]{"unsafe", "dangerous", "driver", "driving", "speeding", "reckless",
                            "অনিরাপদ", "বিপজ্জনক", "ড্রাইভার", "গাড়ি চালাচ্ছে", "দ্রুত গতি", "বেপরোয়া"},
                new String[]{"cab", "taxi", "auto", "uber", "ola", "vehicle", "scared",
                            "ক্যাব", "ট্যাক্সি", "অটো", "রিকশা", "যানবাহন", "ভয়"},
                new String[]{},
                1.0f
            ),
            new IntentPattern(
                new String[]{"kidnapper", "wrong route", "not stopping", "ignoring",
                            "অপহরণকারী", "ভুল রাস্তা", "থামছে না", "উপেক্ষা করছে"},
                new String[]{"driver", "cab", "taxi", "ড্রাইভার", "ক্যাব", "ট্যাক্সি"},
                new String[]{},
                0.95f
            )
        });

        // SNATCHING/ROBBERY (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.SNATCHING, new IntentPattern[]{
            new IntentPattern(
                new String[]{"snatch", "steal", "rob", "thief", "grabbing", "taking",
                            "ছিনতাই", "চুরি", "ডাকাতি", "চোর", "কেড়ে নিচ্ছে", "নিয়ে যাচ্ছে"},
                new String[]{"phone", "bag", "purse", "wallet", "jewelry", "forcefully",
                            "ফোন", "ব্যাগ", "পার্স", "মানিব্যাগ", "গহনা", "জোর করে"},
                new String[]{},
                1.0f
            )
        });

        // DRUNK/ABUSIVE BEHAVIOR (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.DRUNK_BEHAVIOR, new IntentPattern[]{
            new IntentPattern(
                new String[]{"drunk", "intoxicated", "abusive", "violent", "threatening", "aggressive",
                            "মাতাল", "নেশাগ্রস্ত", "গালিগালাজ", "হিংস্র", "হুমকি", "আক্রমণাত্মক"},
                new String[]{"person", "man", "someone", "nearby", "behavior",
                            "ব্যক্তি", "লোক", "কেউ", "কাছে", "আচরণ"},
                new String[]{},
                1.0f
            )
        });

        // LOST/STRANDED (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.LOST_STRANDED, new IntentPattern[]{
            new IntentPattern(
                new String[]{"lost", "stranded", "don't know", "can't find", "unknown",
                            "হারিয়ে গেছি", "আটকে গেছি", "জানি না", "খুঁজে পাচ্ছি না", "অজানা"},
                new String[]{"where", "location", "place", "way", "alone", "dark",
                            "কোথায়", "অবস্থান", "জায়গা", "রাস্তা", "একা", "অন্ধকার"},
                new String[]{},
                1.0f
            )
        });

        // PANIC/FEAR - Extreme fear and panic situations (English + Bangla)
        INTENT_PATTERNS.put(EmergencyTypeDetector.EmergencyType.GENERAL_DISTRESS, new IntentPattern[]{
            new IntentPattern(
                new String[]{"scared", "afraid", "terrified", "panic", "fear", "frightened",
                            "can't breathe", "oh my god", "what's happening",
                            "ভয় পেয়েছি", "ভয়", "আতঙ্ক", "হে আল্লাহ", "এ কী হচ্ছে"},
                new String[]{"so", "very", "really", "too", "extremely", "please don't",
                            "খুব", "অনেক", "দয়া করে না", "না না না"},
                new String[]{"movie", "story", "telling", "সিনেমা", "গল্প"},
                0.85f
            ),
            new IntentPattern(
                new String[]{"please don't", "stay away", "leave me alone", "stop",
                            "don't hurt", "i can't take",
                            "দূরে থাকো", "আমাকে ছেড়ে দাও", "আমি পারছি না", "থামো"},
                new String[]{"me", "this", "anymore", "আমাকে", "আর"},
                new String[]{},
                0.9f
            ),
            new IntentPattern(
                new String[]{"i'm not okay", "i can't anymore", "tired of everything",
                            "want to disappear", "please someone listen",
                            "আমি ভালো নেই", "আর পারছি না", "সব কিছু শেষ",
                            "হারিয়ে যেতে চাই", "কেউ শুনবে না"},
                new String[]{"help", "need", "someone", "anybody",
                            "সাহায্য", "কেউ", "প্রয়োজন"},
                new String[]{"yesterday", "before", "was", "গতকাল", "আগে", "ছিলাম"},
                0.95f
            )
        });
    }

    /**
     * Classify emergency intent using semantic understanding
     * @param text Spoken or detected text
     * @return Detected emergency type with confidence
     */
    public static EmergencyClassification classifyIntent(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new EmergencyClassification(EmergencyTypeDetector.EmergencyType.NONE, 0.0f);
        }

        String normalizedText = normalizeText(text);
        String[] tokens = tokenize(normalizedText);

        // Check for general distress signals first
        float distressScore = calculateDistressScore(tokens);

        // Find best matching intent pattern
        EmergencyTypeDetector.EmergencyType bestType = EmergencyTypeDetector.EmergencyType.NONE;
        float bestScore = 0.0f;

        for (Map.Entry<EmergencyTypeDetector.EmergencyType, IntentPattern[]> entry : INTENT_PATTERNS.entrySet()) {
            float typeScore = matchIntentPatterns(tokens, entry.getValue(), normalizedText);

            // Boost score if distress signals present
            if (distressScore > 0.3f) {
                typeScore *= (1.0f + distressScore * 0.5f);
            }

            if (typeScore > bestScore) {
                bestScore = typeScore;
                bestType = entry.getKey();
            }
        }

        // If no specific type but high distress, return general distress
        if (bestType == EmergencyTypeDetector.EmergencyType.NONE && distressScore > 0.5f) {
            bestType = EmergencyTypeDetector.EmergencyType.GENERAL_DISTRESS;
            bestScore = distressScore;
        }

        Log.d(TAG, "Intent classification: " + bestType + " (confidence: " + bestScore + ") for text: " + text);

        return new EmergencyClassification(bestType, bestScore);
    }

    /**
     * Match text against intent patterns
     */
    private static float matchIntentPatterns(String[] tokens, IntentPattern[] patterns, String fullText) {
        float maxScore = 0.0f;

        for (IntentPattern pattern : patterns) {
            // Check for negation first
            boolean hasNegation = false;
            for (String negation : pattern.negationTokens) {
                if (containsToken(tokens, negation) || fullText.contains(negation)) {
                    hasNegation = true;
                    break;
                }
            }

            if (hasNegation) continue;

            // Calculate core token matches
            float coreScore = calculateTokenMatch(tokens, pattern.coreTokens);

            // Calculate context token matches
            float contextScore = calculateTokenMatch(tokens, pattern.contextTokens);

            // Combined score with weights
            float patternScore = (coreScore * 0.7f + contextScore * 0.3f) * pattern.weight;

            // Boost if threat verbs present
            if (containsAnyToken(tokens, THREAT_VERBS)) {
                patternScore *= 1.2f;
            }

            maxScore = Math.max(maxScore, patternScore);
        }

        return Math.min(maxScore, 1.0f);
    }

    /**
     * Calculate how well tokens match target tokens using semantic similarity
     */
    private static float calculateTokenMatch(String[] tokens, String[] targetTokens) {
        if (targetTokens == null || targetTokens.length == 0) return 0.0f;

        int matchCount = 0;
        float semanticScore = 0.0f;

        for (String target : targetTokens) {
            for (String token : tokens) {
                // Exact match
                if (token.equals(target)) {
                    matchCount++;
                    semanticScore += 1.0f;
                    break;
                }

                // Partial match (substring)
                if (token.contains(target) || target.contains(token)) {
                    float similarity = Math.min(token.length(), target.length()) /
                                      (float) Math.max(token.length(), target.length());
                    if (similarity > 0.7f) {
                        semanticScore += similarity;
                        matchCount++;
                        break;
                    }
                }

                // Fuzzy match (Levenshtein distance)
                float similarity = calculateSimilarity(token, target);
                if (similarity > 0.8f) {
                    semanticScore += similarity;
                    matchCount++;
                    break;
                }
            }
        }

        return semanticScore / targetTokens.length;
    }

    /**
     * Calculate distress level from general distress signals
     */
    private static float calculateDistressScore(String[] tokens) {
        int matchCount = 0;

        for (String signal : DISTRESS_SIGNALS) {
            if (containsToken(tokens, signal)) {
                matchCount++;
            }
        }

        // Normalize to 0-1
        return Math.min(matchCount / 3.0f, 1.0f);
    }

    /**
     * Check if tokens contain a specific token
     */
    private static boolean containsToken(String[] tokens, String target) {
        for (String token : tokens) {
            if (token.equals(target) || token.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if tokens contain any of the target tokens
     */
    private static boolean containsAnyToken(String[] tokens, String[] targets) {
        for (String target : targets) {
            if (containsToken(tokens, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Normalize text for processing (supports English, Hindi, and Bangla)
     */
    private static String normalizeText(String text) {
        return text.toLowerCase()
                   .replaceAll("[^a-z0-9\\s\\u0900-\\u097F\\u0980-\\u09FF]", " ") // Keep English, numbers, Devanagari (Hindi), and Bengali (Bangla)
                   .replaceAll("\\s+", " ")
                   .trim();
    }

    /**
     * Tokenize text into words
     */
    private static String[] tokenize(String text) {
        return text.split("\\s+");
    }

    /**
     * Calculate string similarity using Levenshtein distance
     */
    private static float calculateSimilarity(String s1, String s2) {
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0f;

        int distance = levenshteinDistance(s1, s2);
        return 1.0f - ((float) distance / maxLen);
    }

    /**
     * Calculate Levenshtein distance between two strings
     */
    private static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= s2.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1),     // insertion
                    dp[i - 1][j - 1] + cost // substitution
                );
            }
        }

        return dp[s1.length()][s2.length()];
    }

    /**
     * Result class for emergency classification
     */
    public static class EmergencyClassification {
        public final EmergencyTypeDetector.EmergencyType type;
        public final float confidence;

        public EmergencyClassification(EmergencyTypeDetector.EmergencyType type, float confidence) {
            this.type = type;
            this.confidence = confidence;
        }

        public boolean isEmergency() {
            return type != EmergencyTypeDetector.EmergencyType.NONE && confidence > 0.5f;
        }
    }
}

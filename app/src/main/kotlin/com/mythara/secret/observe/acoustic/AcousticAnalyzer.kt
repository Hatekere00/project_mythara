package com.mythara.secret.observe.acoustic

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Extracts tone-based features from a single Observe utterance's raw
 * PCM samples. The M8.5 phase 2 piece — pure DSP, no models, runs in
 * a couple of milliseconds per utterance on Tensor G4.
 *
 * Three features computed per utterance:
 *   - **Mean fundamental frequency (F0)**: pitch, in Hz. Higher
 *     pitch correlates with arousal (excited / anxious); lower with
 *     calm or sad. Computed via [PitchDetector]'s naive
 *     autocorrelation over the whole utterance.
 *   - **Mean RMS energy**: amplitude. Loud, projecting speech vs.
 *     quiet, withdrawn. Normalised to 0..1 against Int16 range.
 *   - **Speaking rate**: words per second. Derived from the Vosk
 *     transcript's word count and the utterance duration. Fast =
 *     excitement or anxiety; slow = sadness or fatigue.
 *
 * v1 doesn't fold these into the `mood:<label>` facet directly —
 * Gemma's text-based label still wins. Instead, we add separate
 * `pitch:`, `energy:`, `rate:` facets so downstream recall (and
 * future calibration work) can read them independently.
 */
@Singleton
class AcousticAnalyzer @Inject constructor() {

    data class Features(
        /** Mean fundamental frequency in Hz, or 0f if undetected. */
        val meanF0Hz: Float,
        /** RMS amplitude, normalised to ~[0, 1] vs Int16 range. */
        val meanRms: Float,
        /** Words/second; 0 if duration ≤ 0. */
        val wordsPerSec: Float,
        /** Utterance length in seconds. */
        val durationSec: Float,
    )

    /**
     * Compute features for the first [validSamples] of [pcm].
     * @param sampleRate Recorder rate in Hz (16000 for our Observe loop).
     * @param wordCount Word count from the Vosk transcript — caller
     *   computes this since AcousticAnalyzer doesn't see text.
     */
    fun analyze(
        pcm: ShortArray,
        validSamples: Int,
        sampleRate: Int,
        wordCount: Int,
    ): Features {
        val n = validSamples.coerceAtMost(pcm.size)
        if (n <= 0) return Features(0f, 0f, 0f, 0f)
        val durationSec = n.toFloat() / sampleRate
        val rms = computeRms(pcm, n)
        val f0 = PitchDetector.detect(pcm, n, sampleRate)
        val wps = if (durationSec > 0f) wordCount / durationSec else 0f
        return Features(meanF0Hz = f0, meanRms = rms, wordsPerSec = wps, durationSec = durationSec)
    }

    /**
     * Bucket the raw features into ordinal labels for facet storage.
     * Thresholds are population-level averages — gender-skewed for
     * pitch and gain-dependent for RMS, so they're best treated as
     * a starting point. M8.5+ could replace these with per-user
     * baselines learned over time.
     */
    fun bucket(features: Features): List<String> = buildList {
        // Pitch — only emit if F0 was detected (avoid bogus "low" for silence).
        if (features.meanF0Hz > 0f) {
            val pitch = when {
                features.meanF0Hz > PITCH_HIGH_HZ -> "high"
                features.meanF0Hz < PITCH_LOW_HZ -> "low"
                else -> "normal"
            }
            add("pitch:$pitch")
        }
        val energy = when {
            features.meanRms > ENERGY_HIGH -> "high"
            features.meanRms < ENERGY_LOW -> "low"
            else -> "normal"
        }
        add("energy:$energy")
        // Rate — skip when too few words to be meaningful.
        if (features.wordsPerSec > 0f && features.durationSec >= MIN_RATE_DURATION_SEC) {
            val rate = when {
                features.wordsPerSec > RATE_FAST -> "fast"
                features.wordsPerSec < RATE_SLOW -> "slow"
                else -> "normal"
            }
            add("rate:$rate")
        }
    }

    private fun computeRms(pcm: ShortArray, n: Int): Float {
        if (n == 0) return 0f
        var sumSq = 0.0
        val invMax = 1.0 / Short.MAX_VALUE
        for (i in 0 until n) {
            val s = pcm[i] * invMax
            sumSq += s * s
        }
        return sqrt(sumSq / n).toFloat()
    }

    companion object {
        /** Adult speaker pitch buckets — calibrated against ~population means. */
        const val PITCH_HIGH_HZ = 200f   // typical adult female resting + arousal
        const val PITCH_LOW_HZ = 110f    // typical adult male relaxed

        /** RMS energy buckets. Tuned for Android VOICE_RECOGNITION source gain. */
        const val ENERGY_HIGH = 0.15f
        const val ENERGY_LOW = 0.03f

        /** Speaking rate (words/sec) buckets. ~2-5 wps is conversational. */
        const val RATE_FAST = 4.0f
        const val RATE_SLOW = 1.8f

        /** Below this we don't trust rate (e.g. one-word utterances). */
        const val MIN_RATE_DURATION_SEC = 1.5f
    }
}

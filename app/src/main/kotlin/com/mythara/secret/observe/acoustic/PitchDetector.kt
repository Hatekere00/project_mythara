package com.mythara.secret.observe.acoustic

/**
 * Naive autocorrelation pitch detector. The first lag in
 * [MIN_F0_HZ, MAX_F0_HZ] whose autocorrelation peaks is taken as
 * the fundamental period; F0 = sampleRate / lag.
 *
 * Computational cost: O(N × (lagMax − lagMin)). At 16 kHz on a 1 s
 * utterance that's ~16000 × 150 ≈ 2.4M float ops, ~5 ms on Tensor
 * G4. Fine for once-per-utterance, would not work at frame
 * granularity.
 *
 * Quality is modest — naive autocorrelation gets fooled by
 * harmonics (double-period traps) and silence. We accept that for
 * a coarse "high/normal/low" bucket facet; future work can swap in
 * YIN or pYIN for cents-accurate F0 if we ever need it.
 *
 * Returns 0f when the signal looks unvoiced (RMS-normalised peak
 * autocorrelation below [VOICED_THRESHOLD]), so callers know to
 * skip the pitch bucket on this utterance.
 */
object PitchDetector {

    /** Human voice F0 range. */
    const val MIN_F0_HZ = 60
    const val MAX_F0_HZ = 500

    /** Normalised autocorrelation peak below which we declare unvoiced. */
    const val VOICED_THRESHOLD = 0.25

    fun detect(pcm: ShortArray, n: Int, sampleRate: Int): Float {
        if (n <= 0) return 0f
        val minLag = sampleRate / MAX_F0_HZ
        val maxLag = (sampleRate / MIN_F0_HZ).coerceAtMost(n - 1)
        if (maxLag <= minLag) return 0f

        // Energy (zero-lag autocorrelation) — used as the normaliser
        // so we get a voicing-confidence value in [0, 1].
        var energy = 0.0
        for (i in 0 until n) {
            val s = pcm[i].toDouble()
            energy += s * s
        }
        if (energy <= 0.0) return 0f

        var bestLag = 0
        var bestCorr = 0.0
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val limit = n - lag
            for (i in 0 until limit) {
                sum += pcm[i].toDouble() * pcm[i + lag]
            }
            if (sum > bestCorr) {
                bestCorr = sum
                bestLag = lag
            }
        }
        if (bestLag <= 0) return 0f
        val normalised = bestCorr / energy
        if (normalised < VOICED_THRESHOLD) return 0f
        return sampleRate.toFloat() / bestLag
    }
}

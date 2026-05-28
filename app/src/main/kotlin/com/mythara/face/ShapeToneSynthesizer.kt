package com.mythara.face

import com.mythara.music.Motif
import com.mythara.music.MusicToneEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.PI

/** Per-note timing of MusicToneEngine, exposed here so the
 *  continuous-tone loop on Home can pace itself without polling the
 *  AudioTrack. */
private const val NOTE_DURATION_MS = 380
private const val INTRA_NOTE_GAP_MS = 60
private const val INTER_MOTIF_GAP_MS = 500

/**
 * Maps the current [LivingShapeEngine.LivingShape] to an evolving
 * frequency sequence and plays it through [MusicToneEngine].
 *
 * **The tone evolves with every dimension of the shape:**
 *
 *  - **Length** — note count derives from family + intensity +
 *    particle count. Calm tiny blobs sing 3–4 notes; high-intensity
 *    knots sing 10–12. Same family at different intensities sings a
 *    different number of notes every time.
 *  - **Pitch (root)** — mood picks the harmonic series anchor (OM
 *    136.1 Hz family); intensity transposes up by up to a perfect
 *    fifth; social temperature shifts ±3 semitones (lonely drops,
 *    connected rises).
 *  - **Scale (tune)** — mood picks the modal colour: calm → Lydian
 *    (#4, open); sad → Dorian (minor with brightness); happy → Major
 *    pentatonic; excited → Mixolydian (♭7, bright edge); anxious →
 *    Phrygian (♭2, tension); frustrated → Blues (♭3, ♭5, ♭7);
 *    default → Major.
 *  - **Contour (melody)** — family picks the melodic shape:
 *    Supershape arcs (rise → peak → fall); SphericalHarmonic
 *    undulates around the midpoint (sine-driven); LissajousKnot
 *    leaps wide (criss-cross); MetaballBlob random-walks small steps;
 *    RandomPolytope ascends through triadic positions like a bell.
 *  - **Tune signature** — seed-driven `Random` walk inside the scale
 *    means every unique shape has its own unique melodic fingerprint.
 *    The same family + mood combo at two different seeds plays two
 *    different melodies that share the same flavour.
 *  - **Rhythm (pacing)** — high intensity / fast rotation groups
 *    notes into 3–4-note motifs (60 ms intra-gap = connected,
 *    flowing); low intensity / slow rotation = single-note motifs
 *    (500 ms inter-gap = breathing, contemplative). Same shape feels
 *    faster when the user's intensity rises.
 *
 * Always a calm, unhurried voice — pitches clamped to a vocal-
 * comfortable band, every motif separated by audible breath.
 * MusicToneEngine cancels any in-flight tone on a new play() call.
 */
@Singleton
class ShapeToneSynthesizer @Inject constructor(
    private val toneEngine: MusicToneEngine,
) {

    /** Play the current shape's evolved tone. Returns the estimated
     *  audible duration in ms so a continuous-tone loop can pace
     *  itself without polling the AudioTrack. */
    fun play(state: LivingShapeEngine.LivingShape): Long {
        val motifs = patternForShape(state)
        toneEngine.play(motifs, sourceKey = "shape-tone")
        return estimateDurationMs(motifs)
    }

    /** Hard-stop any in-flight tone. Called when the continuous-tone
     *  toggle on Home flips OFF or the composable leaves composition. */
    fun stop() {
        toneEngine.stop()
    }

    // ---------------------------------------------------------------
    //  Tone composition — pitch × scale × contour × seed × rhythm
    // ---------------------------------------------------------------

    private fun patternForShape(state: LivingShapeEngine.LivingShape): List<Motif> {
        val rng = java.util.Random(state.seed)
        val rootHz = rootForShape(state)
        val scale = scaleForMood(state.mood)
        val length = melodyLength(state.family, state, rng)
        val degrees = contourForFamily(state.family, length, rng, scale.size)
        // Map scale degree → Hz, allowing octave wrap-around at the top
        // of the scale (degree == scale.size means root × 2).
        val notes = degrees.map { d -> degreeToHz(rootHz, scale, d) }
        return groupIntoMotifs(notes, state, rng)
    }

    /** Mood → root pitch + intensity / social transposition. The OM
     *  harmonic series anchors the brand-sound palette; intensity
     *  raises pitch up to a perfect fifth; social temperature shifts
     *  ±3 semitones around the mid (lonely down, connected up). */
    private fun rootForShape(state: LivingShapeEngine.LivingShape): Float {
        val base = when (state.mood) {
            // Low + warm — deep OM fundamental
            "calm", "sad" -> 136.1f
            // Mid-low — third harmonic
            "happy", "frustrated" -> 204.15f
            // Mid — fifth harmonic / octave + 3rd
            "anxious" -> 272.2f
            // Bright — two-octave 4th harmonic
            "excited" -> 408.3f
            else -> 204.15f
        }
        val intensitySemis = (state.intensity.coerceIn(0f, 1f) * 7f).toInt()
        val socialSemis = ((state.socialTemperature.coerceIn(0f, 1f) - 0.5f) * 6f).toInt()
        val total = intensitySemis + socialSemis
        return base * 2.0.pow(total / 12.0).toFloat()
    }

    /** Mood → modal scale, expressed as semitone offsets from the
     *  root. Picked for the emotional colour, not for harmonic
     *  theory — the goal is "calm feels open, anxious feels tense"
     *  more than strict tonality. */
    private fun scaleForMood(mood: String?): IntArray = when (mood) {
        "calm" -> intArrayOf(0, 2, 4, 6, 7, 9, 11)        // Lydian — #4, open
        "sad" -> intArrayOf(0, 2, 3, 5, 7, 9, 10)         // Dorian — minor + bright
        "happy" -> intArrayOf(0, 2, 4, 7, 9)              // Major pentatonic — pure
        "excited" -> intArrayOf(0, 2, 4, 5, 7, 9, 10)     // Mixolydian — ♭7 edge
        "anxious" -> intArrayOf(0, 1, 3, 5, 7, 8, 10)     // Phrygian — ♭2 tension
        "frustrated" -> intArrayOf(0, 3, 5, 6, 7, 10)     // Blues — ♭3, ♭5, ♭7
        else -> intArrayOf(0, 2, 4, 5, 7, 9, 11)          // Major
    }

    /** Family × intensity × particle-count → melody length.
     *  Per-family base ranges plus an intensity stretch so the same
     *  family at high intensity sings longer than at low intensity. */
    private fun melodyLength(
        family: CreativeShapes.Family,
        state: LivingShapeEngine.LivingShape,
        rng: java.util.Random,
    ): Int {
        val (lo, hi) = when (family) {
            CreativeShapes.Family.Supershape -> 4 to 8
            CreativeShapes.Family.SphericalHarmonic -> 6 to 10
            CreativeShapes.Family.LissajousKnot -> 7 to 12
            CreativeShapes.Family.MetaballBlob -> 3 to 6
            CreativeShapes.Family.RandomPolytope -> 5 to 9
        }
        val base = lo + rng.nextInt(hi - lo + 1)
        // Stretch by intensity (up to +3 notes) and particle abundance
        // (very dense shapes get +1 — more material to "sing").
        val intensityStretch = (state.intensity.coerceIn(0f, 1f) * 3f).toInt()
        val particleStretch = if (state.particleCount > 900) 1 else 0
        return (base + intensityStretch + particleStretch).coerceIn(3, 14)
    }

    /** Family → melodic contour, expressed as scale-degree indices.
     *  Each family has its own characteristic shape; the seed-driven
     *  RNG adds variation so two shapes of the same family + mood
     *  play two distinct melodies with the same flavour. */
    private fun contourForFamily(
        family: CreativeShapes.Family,
        n: Int,
        rng: java.util.Random,
        scaleSize: Int,
    ): List<Int> {
        if (n <= 0 || scaleSize <= 0) return emptyList()
        val maxDeg = scaleSize  // include octave (scale.size = root×2)

        return when (family) {
            // Supershape — organic rising / falling arc. Like growth.
            CreativeShapes.Family.Supershape -> {
                val half = (n / 2).coerceAtLeast(1)
                val rising = (0 until half).map { i ->
                    (i * maxDeg / half).coerceIn(0, maxDeg)
                }
                val falling = (half until n).map { i ->
                    ((n - 1 - i) * maxDeg / half).coerceIn(0, maxDeg)
                }
                (rising + falling).map { d ->
                    (d + rng.nextInt(3) - 1).coerceIn(0, maxDeg)
                }
            }
            // SphericalHarmonic — smooth, contemplative. Undulates
            // around the midpoint, sine-wave-like.
            CreativeShapes.Family.SphericalHarmonic -> {
                val mid = maxDeg / 2
                val swing = (maxDeg * 0.35).toInt().coerceAtLeast(1)
                (0 until n).map { i ->
                    val phase = i * 2.0 * PI / n
                    val delta = (sin(phase) * swing).toInt()
                    val jitter = rng.nextInt(3) - 1
                    (mid + delta + jitter).coerceIn(0, maxDeg)
                }
            }
            // LissajousKnot — interleaved wide intervals. Criss-cross.
            CreativeShapes.Family.LissajousKnot -> {
                val pts = mutableListOf<Int>()
                var cur = 0
                repeat(n) {
                    cur = (cur + 3 + rng.nextInt(4)) % (maxDeg + 1)
                    pts.add(cur)
                }
                pts
            }
            // MetaballBlob — soft, close intervals. Random walk
            // hovering near the centre. Like swelling.
            CreativeShapes.Family.MetaballBlob -> {
                val pts = mutableListOf(maxDeg / 2)
                repeat(n - 1) {
                    val step = rng.nextInt(3) - 1   // -1, 0, +1
                    pts.add((pts.last() + step).coerceIn(0, maxDeg))
                }
                pts
            }
            // RandomPolytope — sharp, structured. Bell-like ascending
            // through triadic positions in the scale, then descending.
            CreativeShapes.Family.RandomPolytope -> {
                val anchors = intArrayOf(0, 2, 4, scaleSize - 1, scaleSize)
                (0 until n).map { i ->
                    val a = if (i < n / 2) anchors[i % anchors.size]
                    else anchors[(n - 1 - i) % anchors.size]
                    a.coerceIn(0, maxDeg)
                }
            }
        }
    }

    /** Scale-degree → Hz. Degrees beyond the scale length wrap into
     *  the next octave (so an 8-degree scale at degree 8 = root×2,
     *  degree 9 = root×2 + scale[1] semitones, etc.). */
    private fun degreeToHz(rootHz: Float, scale: IntArray, degree: Int): Float {
        val n = scale.size
        if (n == 0) return rootHz
        val octave = degree / n
        val idx = degree % n
        val semis = scale[idx] + octave * 12
        return rootHz * 2.0.pow(semis / 12.0).toFloat()
    }

    /** Group raw notes into motifs to control audible pacing.
     *
     *  Within a motif, notes are separated by 60 ms — connected,
     *  flowing. Between motifs, 500 ms of breath — phrasing, calm.
     *  High intensity / fast rotation groups more notes per motif so
     *  the same melody feels faster; low intensity → single-note
     *  motifs with long breaths between, contemplative. */
    private fun groupIntoMotifs(
        notes: List<Float>,
        state: LivingShapeEngine.LivingShape,
        rng: java.util.Random,
    ): List<Motif> {
        if (notes.isEmpty()) return emptyList()
        val intensity = state.intensity.coerceIn(0f, 1f)
        // Rotation rate hints at agitation; combine with intensity for
        // the rhythmic "energy" felt by the listener.
        val energy = (intensity + state.rotationRateHz.coerceIn(0f, 1f)) * 0.5f
        val groupSize = when {
            energy > 0.65f -> 3 + rng.nextInt(2)   // 3–4 notes / motif — fast
            energy > 0.35f -> 2 + rng.nextInt(2)   // 2–3 notes / motif — moderate
            else -> 1 + rng.nextInt(2)             // 1–2 notes / motif — calm
        }
        val motifs = mutableListOf<Motif>()
        var i = 0
        while (i < notes.size) {
            val end = (i + groupSize).coerceAtMost(notes.size)
            motifs.add(Motif(notes = notes.subList(i, end).toList()))
            i = end
        }
        return motifs
    }

    /** Total audible duration accounting for notes + intra-motif
     *  gaps + inter-motif gaps. Used by the loop on Home to delay
     *  the next iteration to land on a phrase boundary. */
    private fun estimateDurationMs(motifs: List<Motif>): Long {
        if (motifs.isEmpty()) return 0L
        val noteCount = motifs.sumOf { it.notes.size }
        val intraGaps = motifs.sumOf { (it.notes.size - 1).coerceAtLeast(0) }
        val interGaps = (motifs.size - 1).coerceAtLeast(0)
        return noteCount.toLong() * NOTE_DURATION_MS +
            intraGaps.toLong() * INTRA_NOTE_GAP_MS +
            interGaps.toLong() * INTER_MOTIF_GAP_MS
    }
}

package com.mythara.ui.face

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.camera.FaceTracker
import com.mythara.mic.Tts
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Exposes what the face avatar needs: the live TTS speaking state, and
 * the front-camera [FaceTracker.Pose] so the avatar can track the
 * user's actual head. Its own tiny ViewModel so the Face screen is a
 * standalone destination, independent of the chat surface.
 */
@HiltViewModel
class FaceViewModel @Inject constructor(
    tts: Tts,
    private val tracker: FaceTracker,
) : ViewModel() {
    val speaking: StateFlow<Boolean> =
        tts.speaking.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Smoothed front-camera head pose. `present = false` when no face. */
    val pose: StateFlow<FaceTracker.Pose> = tracker.pose

    /** Start / stop the front-camera stream — driven by the screen's
     *  composition lifetime so the camera only runs while it's open. */
    fun bindCamera() = tracker.bind()
    fun unbindCamera() = tracker.unbind()

    override fun onCleared() {
        tracker.unbind()
    }
}

// ----------------------------------------------------------- model

private enum class FGroup { HEAD, BROW, EYE_L, EYE_R, EYE_CORE, NOSE, MOUTH, MOUTH_LOWER, EAR, NECK }

/** A single point in the face cloud, in normalised face space
 *  (x right, y down, origin between the eyes). [z] is a fake depth in
 *  [0,1] — higher = closer to camera — used for parallax + shading. */
private class FPoint(
    val x: Float,
    val y: Float,
    val z: Float,
    val group: FGroup,
    val bright: Float,
)

/** Prebuilt cloud: points + the nearest-neighbour mesh edges between
 *  them (index pairs). Computed once and remembered. */
private class FModel(val points: List<FPoint>, val edges: List<IntArray>)

/** Eye centre Y in normalised face space — blink scales eye-ring
 *  points toward this line. */
private const val EYE_CY = -0.20f
private const val EYE_DX = 0.30f

private val CLOUD = Color(0xFF4FE2FF)      // electric cyan — the dots + mesh
private val EYE_GLOW = Color(0xFFE4FBFF)   // near-white cyan — the eye cores

/**
 * The Mythara face — a full-screen, alternate interface to the agent.
 *
 * A glowing point-cloud humanoid head on a pure-black field. With the
 * front-camera permission granted it **tracks the user's real face**:
 * ML Kit head euler angles drive the cloud's yaw / pitch / roll, and
 * the eye-open probabilities drive its blink — so the avatar mirrors
 * you in real time. Without the camera (or with no face in frame) it
 * falls back to a gentle idle sway + self-driven blink. The lower lip
 * still drops open while Mythara is speaking. Pure Compose Canvas.
 */
@Composable
fun FaceScreen(onBack: () -> Unit, vm: FaceViewModel = hiltViewModel()) {
    val speaking by vm.speaking.collectAsState()
    val pose by vm.pose.collectAsState()
    val model = remember { buildFaceModel() }
    val ctx = LocalContext.current

    // Front-camera permission — needed to track the user's face.
    var hasCam by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCam = granted }
    LaunchedEffect(Unit) { if (!hasCam) camLauncher.launch(Manifest.permission.CAMERA) }

    // The camera runs ONLY while this screen is composed AND the
    // permission is held — bound on enter, unbound on leave.
    DisposableEffect(hasCam) {
        if (hasCam) vm.bindCamera()
        onDispose { vm.unbindCamera() }
    }

    val infinite = rememberInfiniteTransition(label = "face")
    // Idle motion — used only when the camera isn't tracking a face.
    val sway by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing), RepeatMode.Reverse),
        label = "sway",
    )
    val bob by infinite.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3100, easing = LinearEasing), RepeatMode.Reverse),
        label = "bob",
    )
    // Soft brightness shimmer across the whole cloud.
    val shimmer by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer",
    )
    // Fast phase driving the talking mouth — only used while speaking.
    val mouthPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Restart),
        label = "mouth",
    )
    // Idle self-driven blink — only used when not camera-tracking.
    val idleBlink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2400L, 5600L))
            idleBlink.animateTo(0.06f, tween(85))
            idleBlink.animateTo(1f, tween(150))
        }
    }

    val mouthOpen = if (speaking) {
        abs(sin(mouthPhase.toDouble())).toFloat() * 0.85f + 0.15f
    } else {
        0.04f
    }

    // Pose-or-idle targets. When the camera has a face we follow it;
    // otherwise we drift on the idle animators. animateFloatAsState
    // smooths the hand-off + any residual ML Kit jitter.
    val tracking = pose.present
    val yaw by animateFloatAsState(
        if (tracking) pose.yaw else sway * 0.16f, tween(110), label = "yaw",
    )
    val pitch by animateFloatAsState(
        if (tracking) pose.pitch else bob * 0.05f, tween(110), label = "pitch",
    )
    val roll by animateFloatAsState(
        if (tracking) pose.roll else 0f, tween(110), label = "roll",
    )
    val eyeL by animateFloatAsState(
        if (tracking) pose.leftEyeOpen else idleBlink.value, tween(90), label = "eyeL",
    )
    val eyeR by animateFloatAsState(
        if (tracking) pose.rightEyeOpen else idleBlink.value, tween(90), label = "eyeR",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawFaceCloud(
                model = model,
                yaw = yaw,
                pitch = pitch,
                roll = roll,
                bob = bob,
                shimmer = shimmer,
                eyeOpenL = eyeL,
                eyeOpenR = eyeR,
                mouthOpen = mouthOpen,
                speaking = speaking,
            )
        }

        Text(
            text = "‹ chat",
            style = MaterialTheme.typography.labelLarge.copy(color = CLOUD),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp)
                .clickable(onClick = onBack),
        )

        val status = when {
            speaking -> "● speaking"
            tracking -> "● tracking you"
            hasCam -> "○ looking for you…"
            else -> "○ tap to enable face tracking"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.labelMedium.copy(
                color = if (speaking || tracking) EYE_GLOW else CLOUD.copy(alpha = 0.55f),
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp)
                .clickable(enabled = !hasCam) {
                    camLauncher.launch(Manifest.permission.CAMERA)
                },
        )
    }
}

// ----------------------------------------------------------- drawing

private fun DrawScope.drawFaceCloud(
    model: FModel,
    yaw: Float,
    pitch: Float,
    roll: Float,
    bob: Float,
    shimmer: Float,
    eyeOpenL: Float,
    eyeOpenR: Float,
    mouthOpen: Float,
    speaking: Boolean,
) {
    val scale = minOf(size.width, size.height) * 0.40f
    val cx = size.width / 2f
    val cy = size.height * 0.46f + bob * 6f

    // Head-pose transform, applied once per point: yaw + pitch parallax
    // off each node's depth, then a 2D roll about the face origin.
    val yawS = sin(yaw * 0.5f)
    val pitchS = sin(pitch * 0.42f)
    val rollA = roll * 0.5f
    val rollC = cos(rollA)
    val rollSn = sin(rollA)

    val sx = FloatArray(model.points.size)
    val sy = FloatArray(model.points.size)
    val sb = FloatArray(model.points.size) // per-point brightness this frame
    model.points.forEachIndexed { i, p ->
        // per-group animation in face space first
        var baseY = p.y
        when (p.group) {
            FGroup.EYE_L, FGroup.EYE_R, FGroup.EYE_CORE -> {
                val open = if (p.x < 0f) eyeOpenL else eyeOpenR
                baseY = EYE_CY + (baseY - EYE_CY) * open
            }
            FGroup.MOUTH_LOWER -> baseY += mouthOpen * 0.12f
            else -> {}
        }
        // depth-driven parallax (yaw left/right, pitch up/down)
        val zc = p.z - 0.45f
        val nx = p.x + zc * yawS
        val ny = baseY - zc * pitchS
        // roll — rotate the whole face about its origin
        val rx = nx * rollC - ny * rollSn
        val ry = nx * rollSn + ny * rollC
        sx[i] = cx + rx * scale
        sy[i] = cy + ry * scale
        // depth shading + a gentle travelling shimmer
        val depth = 0.5f + 0.5f * p.z
        val shim = 0.85f + 0.15f * sin(shimmer + p.x * 3f + p.y * 2f)
        sb[i] = (p.bright * depth * shim).coerceIn(0f, 1.3f)
    }

    // ---- mesh edges — cyan filaments between neighbours ----
    for (e in model.edges) {
        val a = e[0]
        val b = e[1]
        val avg = (sb[a] + sb[b]) * 0.5f
        drawLine(
            color = CLOUD.copy(alpha = (0.10f + 0.22f * avg).coerceAtMost(0.5f)),
            start = Offset(sx[a], sy[a]),
            end = Offset(sx[b], sy[b]),
            strokeWidth = 1.1f,
        )
    }

    // ---- nodes — outer bloom + halo + a bright core per point ----
    model.points.forEachIndexed { i, p ->
        if (p.group == FGroup.EYE_CORE) return@forEachIndexed // drawn separately
        val c = Offset(sx[i], sy[i])
        val b = sb[i]
        val haloR = (3.2f + 5.0f * p.z) * (0.7f + 0.5f * b)
        // wide soft bloom
        drawCircle(CLOUD.copy(alpha = (0.05f + 0.07f * b).coerceAtMost(0.4f)), haloR * 2.1f, c)
        // tight halo
        drawCircle(CLOUD.copy(alpha = (0.13f + 0.20f * b).coerceAtMost(0.6f)), haloR, c)
        // bright core
        val coreR = 1.9f + 2.8f * p.z * b
        val talking = speaking && (p.group == FGroup.MOUTH || p.group == FGroup.MOUTH_LOWER)
        val core = if (talking) EYE_GLOW else CLOUD
        drawCircle(core.copy(alpha = (0.72f + 0.28f * b).coerceAtMost(1f)), coreR, c)
    }

    // ---- eyes — bright glowing cores with heavy bloom ----
    val pulse = if (speaking) 1f + 0.12f * sin(shimmer * 2f) else 1f
    model.points.forEachIndexed { i, p ->
        if (p.group != FGroup.EYE_CORE) return@forEachIndexed
        val c = Offset(sx[i], sy[i])
        val open = (if (p.x < 0f) eyeOpenL else eyeOpenR).coerceIn(0.06f, 1f)
        val bloom = scale * 0.092f * pulse * (0.35f + 0.65f * open)
        drawCircle(CLOUD.copy(alpha = 0.20f * open), bloom, c)
        drawCircle(EYE_GLOW.copy(alpha = 0.38f * open), bloom * 0.45f, c)
        drawCircle(EYE_GLOW.copy(alpha = 0.98f * open), bloom * 0.16f + 1.4f, c)
    }
}

// ----------------------------------------------------------- model build

/**
 * Build the face point cloud + its nearest-neighbour mesh, once.
 *
 * Geometry is hand-authored in normalised face space: the head
 * silhouette + brows / eyes / nose / lips / ears / neck are traced as
 * contours, then the head interior is filled with scattered nodes so
 * the whole thing reads as a volumetric mesh rather than an outline.
 */
private fun buildFaceModel(): FModel {
    val pts = ArrayList<FPoint>(420)
    val rnd = Random(7)

    // depth of a point — a hemisphere bulge, highest at the face centre
    fun depth(x: Float, y: Float): Float {
        val d = (x / 0.82f) * (x / 0.82f) + (y / 1.18f) * (y / 1.18f)
        return sqrt((1f - d).coerceAtLeast(0f))
    }
    fun add(x: Float, y: Float, group: FGroup, bright: Float) {
        pts.add(FPoint(x, y, depth(x, y), group, bright))
    }
    // points along an elliptical arc (angles in radians, y-down)
    fun arc(cxv: Float, cyv: Float, rx: Float, ry: Float, a0: Float, a1: Float, n: Int, g: FGroup, br: Float) {
        for (k in 0 until n) {
            val t = if (n == 1) 0.5f else k / (n - 1f)
            val a = a0 + (a1 - a0) * t
            add(cxv + rx * cos(a), cyv + ry * sin(a), g, br)
        }
    }
    // points along a straight segment
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float, n: Int, g: FGroup, br: Float) {
        for (k in 0 until n) {
            val t = if (n == 1) 0.5f else k / (n - 1f)
            add(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, g, br)
        }
    }

    // ---- head silhouette — a closed loop of control points, clockwise
    //      from the crown, sampled densely between each pair ----
    val outline = listOf(
        0.00f to -1.12f, 0.34f to -1.04f, 0.58f to -0.82f, 0.71f to -0.46f,
        0.76f to -0.04f, 0.69f to 0.33f, 0.52f to 0.64f, 0.30f to 0.90f,
        0.00f to 1.06f, -0.30f to 0.90f, -0.52f to 0.64f, -0.69f to 0.33f,
        -0.76f to -0.04f, -0.71f to -0.46f, -0.58f to -0.82f, -0.34f to -1.04f,
    )
    for (i in outline.indices) {
        val (x0, y0) = outline[i]
        val (x1, y1) = outline[(i + 1) % outline.size]
        seg(x0, y0, x1, y1, 6, FGroup.HEAD, 0.92f)
    }

    // ---- volumetric fill — scattered nodes inside the head ellipse ----
    var filled = 0
    var guard = 0
    while (filled < 150 && guard < 4000) {
        guard++
        val x = (rnd.nextFloat() * 2f - 1f) * 0.74f
        val y = (rnd.nextFloat() * 2.1f - 1.05f)
        val inside = (x / 0.74f) * (x / 0.74f) + ((y + 0.02f) / 1.06f) * ((y + 0.02f) / 1.06f)
        if (inside > 0.93f) continue
        add(x, y, FGroup.HEAD, 0.42f + rnd.nextFloat() * 0.20f)
        filled++
    }

    // ---- brows ----
    arc(-EYE_DX, EYE_CY - 0.20f, 0.27f, 0.13f, (PI * 1.08).toFloat(), (PI * 1.92).toFloat(), 11, FGroup.BROW, 0.86f)
    arc(EYE_DX, EYE_CY - 0.20f, 0.27f, 0.13f, (-PI * 0.92).toFloat(), (-PI * 0.08).toFloat(), 11, FGroup.BROW, 0.86f)

    // ---- eyes — almond rings + a bright core each ----
    for ((cxv, g) in listOf(-EYE_DX to FGroup.EYE_L, EYE_DX to FGroup.EYE_R)) {
        arc(cxv, EYE_CY, 0.185f, 0.105f, 0f, (2.0 * PI).toFloat(), 18, g, 0.98f)
        // inner iris ring
        arc(cxv, EYE_CY, 0.072f, 0.062f, 0f, (2.0 * PI).toFloat(), 8, g, 1.0f)
        add(cxv, EYE_CY, FGroup.EYE_CORE, 1.25f)
    }

    // ---- nose — ridge + nostril base + wings ----
    seg(0f, EYE_CY + 0.05f, 0f, 0.20f, 7, FGroup.NOSE, 0.82f)
    arc(0f, 0.205f, 0.135f, 0.075f, (PI * 0.12).toFloat(), (PI * 0.88).toFloat(), 11, FGroup.NOSE, 0.84f)
    add(-0.135f, 0.165f, FGroup.NOSE, 0.74f)
    add(0.135f, 0.165f, FGroup.NOSE, 0.74f)
    add(-0.052f, 0.205f, FGroup.NOSE, 0.84f)
    add(0.052f, 0.205f, FGroup.NOSE, 0.84f)

    // ---- lips — upper lip arc + lower lip arc (lower one drops open) ----
    arc(0f, 0.435f, 0.215f, 0.075f, (PI * 1.05).toFloat(), (PI * 1.95).toFloat(), 13, FGroup.MOUTH, 0.90f)
    seg(-0.215f, 0.45f, 0.215f, 0.45f, 9, FGroup.MOUTH, 0.74f)
    arc(0f, 0.47f, 0.19f, 0.12f, (PI * 0.08).toFloat(), (PI * 0.92).toFloat(), 13, FGroup.MOUTH_LOWER, 0.90f)

    // ---- ears — outer arcs hugging the temples ----
    arc(-0.80f, -0.04f, 0.13f, 0.25f, (PI * 0.55).toFloat(), (PI * 1.45).toFloat(), 9, FGroup.EAR, 0.66f)
    arc(0.80f, -0.04f, 0.13f, 0.25f, (-PI * 0.45).toFloat(), (PI * 0.45).toFloat(), 9, FGroup.EAR, 0.66f)

    // ---- neck + shoulder hint ----
    seg(-0.27f, 0.92f, -0.32f, 1.46f, 8, FGroup.NECK, 0.58f)
    seg(0.27f, 0.92f, 0.32f, 1.46f, 8, FGroup.NECK, 0.58f)
    seg(-0.32f, 1.46f, -0.96f, 1.66f, 9, FGroup.NECK, 0.48f)
    seg(0.32f, 1.46f, 0.96f, 1.66f, 9, FGroup.NECK, 0.48f)
    seg(-0.55f, 1.50f, 0.55f, 1.50f, 9, FGroup.NECK, 0.34f)
    repeat(14) {
        val x = (rnd.nextFloat() * 2f - 1f) * 0.30f
        val y = 0.95f + rnd.nextFloat() * 0.5f
        add(x, y, FGroup.NECK, 0.34f + rnd.nextFloat() * 0.2f)
    }

    // ---- nearest-neighbour mesh — connect each node to its closest
    //      few neighbours within a radius, deduped ----
    val edges = ArrayList<IntArray>()
    val maxD = 0.165f
    val maxNeighbours = 4
    for (i in pts.indices) {
        val pi = pts[i]
        val near = ArrayList<Pair<Int, Float>>()
        for (j in pts.indices) {
            if (j == i) continue
            val d = hypot(pi.x - pts[j].x, pi.y - pts[j].y)
            if (d < maxD) near.add(j to d)
        }
        near.sortBy { it.second }
        for (k in 0 until minOf(maxNeighbours, near.size)) {
            val j = near[k].first
            if (i < j) edges.add(intArrayOf(i, j)) else edges.add(intArrayOf(j, i))
        }
    }
    // dedupe
    val seen = HashSet<Long>(edges.size * 2)
    val unique = ArrayList<IntArray>(edges.size)
    for (e in edges) {
        val key = e[0].toLong() * 100000L + e[1]
        if (seen.add(key)) unique.add(e)
    }

    return FModel(pts, unique)
}

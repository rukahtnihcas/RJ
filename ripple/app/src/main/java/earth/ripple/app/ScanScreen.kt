package earth.ripple.app

import android.graphics.Rect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import earth.ripple.core.Dimension
import earth.ripple.core.Game
import earth.ripple.core.Reward
import earth.ripple.core.Rubric
import earth.ripple.core.Tier
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun ScanScreen(vm: AppViewModel, onJournal: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scan by vm.scan.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val rewards by vm.rewards.collectAsStateWithLifecycle()

    val preview = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        val main = ContextCompat.getMainExecutor(context)
        val controller = LifecycleCameraController(context)
        controller.cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        // The analyzer hands back boxes already mapped into the preview's coordinates, so what we
        // draw lines up with what the user sees without any manual transform.
        controller.setImageAnalysisAnalyzer(
            main,
            MlKitAnalyzer(listOf(vm.vision.detector, vm.vision.barcodes), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                val objects = runCatching { result.getValue(vm.vision.detector) }.getOrNull() ?: emptyList()
                val codes = runCatching { result.getValue(vm.vision.barcodes) }.getOrNull() ?: emptyList()
                vm.onFrame(objects, codes, System.currentTimeMillis())
                if (vm.shouldAutoLock()) preview.bitmap?.let(vm::lock)
            },
        )
        controller.bindToLifecycle(owner)
        preview.controller = controller
        onDispose { controller.unbind() }
    }
    // A light, periodic "what is this probably?" so the reticle can name what it is on.
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            val s = vm.scan.value
            if (s.phase == Phase.SEARCHING && s.target != null) preview.bitmap?.let(vm::updateHint)
        }
    }

    Box(Modifier.fillMaxSize().background(Ink.Bg)) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        ScanOverlay(
            scan, profile.points, profile.questDay == vm.today, Game.quest(vm.today).text, settings, rewards,
            onJournal = onJournal, onSettings = onSettings, onOpen = vm::open, onGuess = vm::guess, onSkipGuess = vm::skipGuess,
            onShutter = { if (scan.phase == Phase.REVEALED) vm.reset() else preview.bitmap?.let(vm::lock) },
            onToggleGuess = { vm.updateSettings(settings.copy(guessFirst = !settings.guessFirst)) },
            onToggleAuto = { vm.updateSettings(settings.copy(autoLock = !settings.autoLock)) },
        )
    }
}

/** Everything drawn on top of the live camera picture. */
@Composable
fun ScanOverlay(
    scan: ScanState, points: Int, questDone: Boolean, quest: String, settings: Settings, rewards: List<Reward>,
    onJournal: () -> Unit, onSettings: () -> Unit, onOpen: (Entry) -> Unit, onGuess: (Dimension) -> Unit, onSkipGuess: () -> Unit,
    onShutter: () -> Unit, onToggleGuess: () -> Unit, onToggleAuto: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        // Scrims keep the controls readable over any scene.
        Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.verticalGradient(listOf(Ink.Bg.copy(alpha = 0.75f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(230.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Ink.Bg.copy(alpha = 0.88f)))))

        if (scan.phase == Phase.SEARCHING || scan.dwell > 0f) Reticle(scan.target?.box, scan.dwell)

        val entry = scan.entry
        val anchor = scan.anchor
        if (anchor != null && (scan.phase == Phase.THINKING || (scan.phase == Phase.REVEALED && entry != null))) {
            HangingTag(anchor, w, h, scan, onOpen = { entry?.let(onOpen) })
        }

        TopBar(points, quest, questDone, onJournal, onSettings)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (scan.phase == Phase.GUESSING && entry != null) {
                GuessPanel(entry.assessment.name, onGuess, onSkipGuess)
            } else {
                // Rewards rise from the controls, clear of the chart.
                RewardToasts(rewards)
                Spacer(Modifier.height(12.dp))
                Prompt(scan, settings.aiEnabled)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    Toggle("Guess first", settings.guessFirst, onToggleGuess)
                    Shutter(busy = scan.phase == Phase.THINKING, again = scan.phase == Phase.REVEALED, onClick = onShutter)
                    Toggle("Auto", settings.autoLock, onToggleAuto)
                }
            }
        }
    }
}

/** Corner brackets that hug the tracked object and close into a full frame as the dwell fills. */
@Composable
private fun Reticle(box: Rect?, dwell: Float) {
    val soft = spring<Float>(dampingRatio = 0.8f, stiffness = 260f)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        // With nothing tracked, rest in the middle so the user knows where to aim.
        val l by animateFloatAsState(box?.left?.toFloat() ?: (w * 0.2f), soft, label = "l")
        val t by animateFloatAsState(box?.top?.toFloat() ?: (h * 0.32f), soft, label = "t")
        val r by animateFloatAsState(box?.right?.toFloat() ?: (w * 0.8f), soft, label = "r")
        val b by animateFloatAsState(box?.bottom?.toFloat() ?: (h * 0.62f), soft, label = "b")
        val fill by animateFloatAsState(dwell, label = "dwell")
        Canvas(Modifier.fillMaxSize()) {
            val color = if (box == null) Ink.Text.copy(alpha = 0.45f) else androidx.compose.ui.graphics.lerp(Ink.Text, Ink.Leaf, fill)
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            val bw = r - l; val bh = b - t
            val arm = 22.dp.toPx()
            val ax = arm + (bw / 2f - arm).coerceAtLeast(0f) * fill
            val ay = arm + (bh / 2f - arm).coerceAtLeast(0f) * fill
            fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                drawPath(Path().apply { moveTo(x + dx * ax, y); lineTo(x, y); lineTo(x, y + dy * ay) }, color, style = stroke)
            }
            corner(l, t, 1f, 1f); corner(r, t, -1f, 1f); corner(l, b, 1f, -1f); corner(r, b, -1f, -1f)
        }
    }
}

/**
 * The chart, hanging from the object like a tag on a string. It follows with a spring, so it lags,
 * overshoots and settles when the object or the phone moves — which is what makes it feel attached
 * to the thing rather than painted on the screen.
 */
@Composable
private fun HangingTag(anchor: Rect, screenW: Float, screenH: Float, scan: ScanState, onOpen: () -> Unit) {
    val density = LocalDensity.current
    val thinking = scan.phase == Phase.THINKING
    val tagW = with(density) { (if (thinking) 190.dp else 236.dp).toPx() }
    val tagH = with(density) { (if (thinking) 46.dp else 318.dp).toPx() }
    val gap = with(density) { 30.dp.toPx() }
    val topSafe = with(density) { 112.dp.toPx() }
    val bottomSafe = with(density) { 190.dp.toPx() }
    val edge = with(density) { 10.dp.toPx() }

    // Prefer floating above the object; hang below when there is no headroom.
    val above = anchor.top - gap - tagH >= topSafe
    val pin = Offset(anchor.exactCenterX().coerceIn(edge, screenW - edge), if (above) anchor.top.toFloat() else anchor.bottom.toFloat())
    val rest = Offset(
        (pin.x - tagW / 2f).coerceIn(edge, (screenW - tagW - edge).coerceAtLeast(edge)),
        (if (above) pin.y - gap - tagH else pin.y + gap).coerceIn(topSafe, (screenH - bottomSafe - tagH).coerceAtLeast(topSafe)),
    )
    val target by rememberUpdatedState(rest)

    var pos by remember { mutableStateOf(rest + Offset(0f, -40f)) }
    var vel by remember { mutableStateOf(Offset.Zero) }
    var clock by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { now ->
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.033f)
            last = now
            val accel = (target - pos) * 95f - vel * 11f
            vel += accel * dt
            pos += vel * dt
            clock += dt
        }
    }
    val tilt = (vel.x / 55f).coerceIn(-11f, 11f) + sin(clock * 1.7f) * 0.9f
    val attach = Offset(pos.x + tagW / 2f, if (above) pos.y + tagH else pos.y)

    Canvas(Modifier.fillMaxSize()) {
        // The string, sagging in the direction the tag is trailing.
        val mid = Offset((pin.x + attach.x) / 2f - vel.x * 0.04f, (pin.y + attach.y) / 2f)
        drawPath(Path().apply { moveTo(pin.x, pin.y); quadraticTo(mid.x, mid.y, attach.x, attach.y) }, Ink.Text.copy(alpha = 0.85f), style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Ink.Bg, 6.dp.toPx(), pin); drawCircle(Ink.Leaf, 4.dp.toPx(), pin)
    }

    Surface(
        modifier = Modifier
            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
            .width(with(density) { tagW.toDp() })
            .graphicsLayer {
                rotationZ = tilt
                transformOrigin = TransformOrigin(0.5f, if (above) 1f else 0f)
            }
            .clip(RoundedCornerShape(22.dp))
            .clickable(enabled = !thinking, role = Role.Button, onClickLabel = "Explore this object", onClick = onOpen),
        color = Ink.Surface.copy(alpha = 0.93f),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, Ink.Line),
    ) {
        val a = scan.entry?.assessment
        if (thinking || a == null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseDot(); Spacer(Modifier.width(10.dp))
                Text("Reading its story…", color = Ink.Text, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.emoji, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Rubric.verdict(a.average), style = MaterialTheme.typography.labelSmall, color = Ink.score(a.average))
                    }
                    if (scan.refining) PulseDot() else TierMark(a.tier)
                }
                RadarChart(a.values, Modifier.fillMaxWidth().height(186.dp), animateKey = scan.entry.key + a.tier)
                Text(a.headline, style = MaterialTheme.typography.bodySmall, color = Ink.Text.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val wrong = scan.guessRight == false
                Text(
                    if (wrong) "Biggest mark: ${a.standout.label}. Tap to see why  →" else "Tap for why, and better choices  →",
                    style = MaterialTheme.typography.labelSmall, color = Ink.Leaf,
                )
            }
        }
    }
}

@Composable
fun TierMark(tier: Tier) {
    val (text, color) = when (tier) {
        Tier.OFFLINE -> "EST." to Ink.Muted
        Tier.PRODUCT_DATA -> "DATA" to Ink.Zero
        Tier.AI -> "AI" to Ink.Leaf
    }
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = tier.label },
    )
}

@Composable
fun PulseDot() {
    var t by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { t = (it / 1e9).toFloat() } }
    Box(Modifier.size(10.dp).graphicsLayer { alpha = 0.45f + 0.55f * ((sin(t * 5f) + 1f) / 2f) }.background(Ink.Leaf, CircleShape))
}

@Composable
private fun TopBar(points: Int, quest: String, questDone: Boolean, onJournal: () -> Unit, onSettings: () -> Unit) {
    val level = Game.level(points)
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(onClick = onJournal, label = "Open your journal. Level ${level.name}, $points points") {
            Text(level.glyph, color = Ink.Leaf, fontSize = 16.sp)
            Spacer(Modifier.width(6.dp))
            Text("$points", color = Ink.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Ink.Surface.copy(alpha = 0.78f)).clickable(onClick = onJournal).padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(if (questDone) "TODAY'S QUEST  ✓" else "TODAY'S QUEST", style = MaterialTheme.typography.labelSmall, color = if (questDone) Ink.Leaf else Ink.Zero)
            Text(quest, style = MaterialTheme.typography.bodySmall, color = Ink.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Pill(onClick = onSettings, label = "Settings") { Text("⚙", color = Ink.Text, fontSize = 18.sp) }
    }
}

@Composable
fun Pill(onClick: () -> Unit, label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(24.dp)).background(Ink.Surface.copy(alpha = 0.78f))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Prompt(scan: ScanState, ai: Boolean) {
    val text = when {
        scan.note != null -> scan.note
        scan.phase == Phase.THINKING -> if (ai) "Looking closely…" else "Identifying…"
        scan.phase == Phase.REVEALED && scan.refining -> "Checking better sources…"
        scan.phase == Phase.REVEALED -> "Tap the chart to explore, or aim at something else"
        scan.target == null -> "Point at anything: your shoes, your lunch, a tree"
        scan.hint != null -> "${scan.hint}?  Hold steady, or tap to scan"
        else -> "Hold steady, or tap to scan"
    }
    Text(
        text, color = Ink.Text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 28.dp),
    )
}

@Composable
private fun Shutter(busy: Boolean, again: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).border(3.dp, Ink.Text, CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = if (again) "Scan something else" else "Scan this", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(60.dp).background(if (again) Ink.Surface else if (busy) Ink.Muted else Ink.Leaf, CircleShape), contentAlignment = Alignment.Center) {
            if (again) Text("↺", color = Ink.Text, fontSize = 26.sp)
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(84.dp).clip(RoundedCornerShape(14.dp))
            .clickable(role = Role.Switch, onClickLabel = "$label, ${if (on) "on" else "off"}", onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(9.dp).background(if (on) Ink.Leaf else Ink.Muted.copy(alpha = 0.5f), CircleShape))
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (on) Ink.Text else Ink.Muted, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

/** Before the reveal: commit to an instinct. Being wrong is what makes the answer stick. */
@Composable
private fun GuessPanel(name: String, onGuess: (Dimension) -> Unit, onSkip: () -> Unit) {
    Surface(Modifier.padding(horizontal = 14.dp).fillMaxWidth(), color = Ink.Surface.copy(alpha = 0.95f), shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, Ink.Line)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("GUT CHECK", style = MaterialTheme.typography.labelSmall, color = Ink.Zero)
            Spacer(Modifier.height(6.dp))
            Text("Where does this ${name.lowercase()} leave its biggest mark?", style = MaterialTheme.typography.titleLarge, color = Ink.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dimension.entries.forEach { d ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Ink.Raised)
                            .clickable(role = Role.Button, onClickLabel = "Guess ${d.label}") { onGuess(d) }.padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) { Text(d.glyph, fontSize = 22.sp, color = Ink.Text) }
                        Text(d.short, style = MaterialTheme.typography.labelSmall, color = Ink.Text)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Just show me", color = Ink.Muted, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onSkip).padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable
fun RewardToasts(rewards: List<Reward>, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rewards.takeLast(3).forEach { r ->
            androidx.compose.runtime.key(r) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { -it }) {
                    Row(
                        Modifier.clip(RoundedCornerShape(18.dp)).background(if (r.badge != null) Ink.Zero else Ink.Surface.copy(alpha = 0.92f)).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val fg = if (r.badge != null) Ink.Bg else Ink.Text
                        if (r.badge != null) { Text(r.badge!!.glyph, color = fg, fontSize = 16.sp); Spacer(Modifier.width(8.dp)) }
                        Text(r.text, color = fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        Text("+${r.points}", color = if (r.badge != null) Ink.Bg else Ink.Leaf, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

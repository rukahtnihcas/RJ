package earth.ripple.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import earth.ripple.core.Game
import earth.ripple.core.KnowledgeBase
import earth.ripple.core.Profile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders every screen off-device so the layouts can be looked at, and fails if any of them crashes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screens").apply { mkdirs() }
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A stand-in for a camera frame or photo: no real pictures are bundled with the tests. */
    private fun scene(w: Int, h: Int, top: Int, bottom: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP) })
    }

    private fun entry(id: String): Entry {
        val file = File(context.cacheDir, "$id.jpg")
        file.outputStream().use { scene(480, 480, 0xFF6F8F86.toInt(), 0xFF2B3A44.toInt()).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return Entry(id, 0L, file.absolutePath, KnowledgeBase.byId(id)!!)
    }

    private fun shoot(name: String, settleMs: Long = 1600, content: @Composable () -> Unit) {
        // Several screens run endless animations, so the clock is advanced by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent { RippleTheme { content() } }
        rule.mainClock.advanceTimeBy(settleMs)
        rule.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private val sampleProfile: Profile get() {
        var p = Profile()
        listOf("tshirt" to "2026-11-11", "tree" to "2026-11-12", "phone" to "2026-11-13", "coffee" to "2026-11-13").forEach { (id, day) ->
            p = Game.onScan(p, KnowledgeBase.byId(id)!!, day, false).profile
        }
        val shirt = KnowledgeBase.byId("tshirt")!!
        p = Game.onPledge(p, shirt, shirt.alternatives[0], "2026-11-13").profile
        val phone = KnowledgeBase.byId("phone")!!
        return Game.onPledge(p, phone, phone.alternatives[1], "2026-11-13").profile.let { it.copy(pledges = it.pledges.mapIndexed { i, x -> x.copy(done = i == 0) }) }
    }

    @Test fun welcome() = shoot("1-welcome") { Welcome(false) {} }

    @Test fun scanRevealed() {
        val e = entry("tshirt")
        shoot("2-scan-revealed", settleMs = 2500) {
            Box(Modifier.fillMaxSize()) {
                Image(scene(1080, 2340, 0xFF8FA9A0.toInt(), 0xFF3A2F2A.toInt()).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                ScanOverlay(
                    ScanState(phase = Phase.REVEALED, entry = e, anchor = Rect(250, 1300, 830, 1800), lockedId = 1),
                    points = 85, questDone = false, quest = Game.quest("2026-11-13").text, settings = Settings(), rewards = Game.onScan(Profile(), e.assessment, "2026-11-13", false).rewards,
                    onJournal = {}, onSettings = {}, onOpen = {}, onGuess = {}, onSkipGuess = {}, onShutter = {}, onPhotos = {}, onWrong = {}, onToggleAuto = {},
                )
            }
        }
    }

    @Test fun scanSearching() = shoot("3-scan-searching") {
        Box(Modifier.fillMaxSize()) {
            Image(scene(1080, 2340, 0xFF8FA9A0.toInt(), 0xFF3A2F2A.toInt()).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            ScanOverlay(
                ScanState(target = Target(Rect(240, 800, 840, 1500), 3, "Fashion good"), hint = "Trainers", dwell = 0.55f),
                points = 0, questDone = false, quest = Game.quest("2026-11-13").text, settings = Settings(), rewards = emptyList(),
                onJournal = {}, onSettings = {}, onOpen = {}, onGuess = {}, onSkipGuess = {}, onShutter = {}, onPhotos = {}, onWrong = {}, onToggleAuto = {},
            )
        }
    }

    @Test fun scanGuessing() = shoot("4-scan-guess") {
        Box(Modifier.fillMaxSize()) {
            Image(scene(1080, 2340, 0xFF8FA9A0.toInt(), 0xFF3A2F2A.toInt()).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            ScanOverlay(
                ScanState(phase = Phase.GUESSING, entry = entry("phone"), anchor = Rect(250, 700, 830, 1300)),
                points = 120, questDone = true, quest = Game.quest("2026-11-13").text, settings = Settings(guessFirst = true), rewards = emptyList(),
                onJournal = {}, onSettings = {}, onOpen = {}, onGuess = {}, onSkipGuess = {}, onShutter = {}, onPhotos = {}, onWrong = {}, onToggleAuto = {},
            )
        }
    }

    @Test @Config(qualifiers = "w411dp-h4300dp-420dpi")
    fun detail() = shoot("5-detail") {
        DetailScreen(DetailState(entry("tshirt"), chat = listOf(Chat("Is polyester better than cotton?", KnowledgeBase.byId("tshirt")!!.questions[0].a))),
            sampleProfile, listOf(entry("tree"), entry("phone"), entry("coffee")), aiEnabled = true, {}, {}, {}, {}, {})
    }

    @Test fun detailTop() = shoot("6-detail-top") {
        DetailScreen(DetailState(entry("tree")), Profile(), emptyList(), aiEnabled = false, {}, {}, {}, {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h2500dp-420dpi")
    fun journal() = shoot("7-journal") {
        JournalScreen(sampleProfile, listOf(entry("coffee"), entry("phone"), entry("tree"), entry("tshirt")), "2026-11-13", {}, {}, {})
    }

    @Test @Config(qualifiers = "w411dp-h1900dp-420dpi")
    fun settings() = shoot("8-settings") { SettingsScreen(Settings(), {}, {}) }

    @Test fun picker() = shoot("11-picker") {
        ObjectPicker(KnowledgeBase.byId("mug")!!, listOf("coffee", "tea", "water-bottle"), aiEnabled = false, onPick = {}, onClose = {})
    }

    @Test fun shareCards() {
        val shirt = entry("tshirt"); val tree = entry("tree")
        val card = ShareCard.render(context, shirt)
        assertEquals(1080, card.width); assertEquals(1350, card.height)
        File(out, "9-share.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "10-share-versus.png").outputStream().use { ShareCard.render(context, shirt, tree).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

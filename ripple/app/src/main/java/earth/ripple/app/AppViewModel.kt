package earth.ripple.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.objects.DetectedObject
import earth.ripple.core.Alternative
import earth.ripple.core.Assessment
import earth.ripple.core.Dimension
import earth.ripple.core.Game
import earth.ripple.core.KnowledgeBase
import earth.ripple.core.Label
import earth.ripple.core.OpenFoodFacts
import earth.ripple.core.Profile
import earth.ripple.core.Reward
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class Phase { SEARCHING, THINKING, GUESSING, REVEALED }

/** The object the camera is currently following, in view (pixel) coordinates. */
data class Target(val box: Rect, val id: Int?, val coarse: String?)

data class ScanState(
    val target: Target? = null,
    /** What we think the live target is, before the user commits. */
    val hint: String? = null,
    /** 0..1: how long the same object has been held steady. At 1 it locks on by itself. */
    val dwell: Float = 0f,
    val phase: Phase = Phase.SEARCHING,
    val entry: Entry? = null,
    /** Where the chart hangs. Follows the locked object while it is visible, then stays put. */
    val anchor: Rect? = null,
    val lockedId: Int? = null,
    val refining: Boolean = false,
    val guess: Dimension? = null,
    val guessRight: Boolean? = null,
    val note: String? = null,
)

data class Chat(val question: String, val answer: String?, val failed: Boolean = false)

data class DetailState(
    val entry: Entry,
    val chat: List<Chat> = emptyList(),
    val compareWith: Entry? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store(app)
    val vision = Vision()
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<Settings> = _settings
    private val _profile = MutableStateFlow(store.loadProfile())
    val profile: StateFlow<Profile> = _profile
    private val _journal = MutableStateFlow(store.loadJournal())
    val journal: StateFlow<List<Entry>> = _journal
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail
    private val _rewards = MutableStateFlow<List<Reward>>(emptyList())
    val rewards: StateFlow<List<Reward>> = _rewards

    private var ai: Ai? = null
    private var pipeline: Job? = null
    private var dwellId: Int? = null
    private var dwellSince = 0L
    private var lastBarcode: String? = null
    private var lastBarcodeAt = 0L
    private var lostSince = 0L

    val today: String get() = LocalDate.now().toString()

    // ---- Live camera -------------------------------------------------------------------------

    /** Called for every analysed camera frame. Boxes are already in view coordinates. */
    fun onFrame(objects: List<DetectedObject>, codes: List<Barcode>, now: Long) {
        codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.let { lastBarcode = it; lastBarcodeAt = now }
        val obj = objects.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
        val target = obj?.let { Target(it.boundingBox, it.trackingId, it.labels.maxByOrNull { l -> l.confidence }?.text) }
        val s = _scan.value

        // Dwell: the same tracked object, held for a moment, is a deliberate look.
        if (target?.id != null && target.id == dwellId) Unit else { dwellId = target?.id; dwellSince = now }
        val held = if (target?.id != null) (now - dwellSince) else 0L
        val isNewObject = target?.id != null && target.id != s.lockedId
        val dwellTime = if (s.phase == Phase.REVEALED) REDWELL_MS else DWELL_MS
        val dwell = if (isNewObject && _settings.value.autoLock && s.phase != Phase.THINKING && s.phase != Phase.GUESSING)
            (held.toFloat() / dwellTime).coerceIn(0f, 1f) else 0f

        // While something is locked, its chart follows it; once it leaves the frame the chart stays where it was.
        val lockedVisible = s.lockedId != null && target?.id == s.lockedId
        if (lockedVisible) lostSince = 0L else if (lostSince == 0L) lostSince = now
        val anchor = if (lockedVisible) target!!.box else s.anchor

        _scan.value = s.copy(target = target, dwell = dwell, anchor = anchor, hint = if (target == null) null else s.hint)
    }

    /** True when the dwell ring has filled and the screen should capture a frame and call [lock]. */
    fun shouldAutoLock(): Boolean {
        val s = _scan.value
        if (s.dwell < 1f) return false
        // Don't replace a result the user may still be reading unless its object has been gone a while.
        return s.phase == Phase.SEARCHING || (s.phase == Phase.REVEALED && System.currentTimeMillis() - lostSince > 1200 && lostSince != 0L)
    }

    /** A quick, cheap guess at what the live target is, shown as a chip before the user commits. */
    fun updateHint(frame: Bitmap) {
        val s = _scan.value
        val target = s.target ?: return
        if (s.phase != Phase.SEARCHING) return
        viewModelScope.launch {
            val labels = vision.label(withContext(Dispatchers.Default) { cropTo(frame, target.box) })
            val match = KnowledgeBase.match(labels + listOfNotNull(target.coarse?.let { Label(it, 0.5f) }))
            _scan.update { if (it.phase == Phase.SEARCHING && it.target?.id == target.id) it.copy(hint = match.assessment.takeIf { m -> m.id != "unknown" }?.name) else it }
        }
    }

    // ---- Locking on and assessing ------------------------------------------------------------

    /** Commit to whatever is in view: identify it, score it, and reveal the chart. */
    fun lock(frame: Bitmap) {
        val before = _scan.value
        if (before.phase == Phase.THINKING) return
        val target = before.target
        val box = target?.box ?: Rect(frame.width / 7, frame.height / 4, frame.width * 6 / 7, frame.height * 3 / 4)
        val key = System.currentTimeMillis().toString(36)
        val barcode = lastBarcode?.takeIf { System.currentTimeMillis() - lastBarcodeAt < 2500 }
        dwellSince = System.currentTimeMillis()
        lostSince = 0L
        _scan.value = ScanState(target = target, phase = Phase.THINKING, anchor = box, lockedId = target?.id)

        pipeline?.cancel()
        pipeline = viewModelScope.launch {
            val crop = withContext(Dispatchers.Default) { cropTo(frame, target?.box) }
            val labels = vision.label(crop)
            val offline = KnowledgeBase.match(labels + listOfNotNull(target?.coarse?.let { Label(it, 0.5f) })).assessment
            val thumb = withContext(Dispatchers.IO) { store.saveThumb(key, thumbnail(crop)) }
            var entry = Entry(key, System.currentTimeMillis(), thumb, offline)
            val s = _settings.value
            val willRefine = barcode != null || s.aiEnabled

            if (s.guessFirst && offline.id != "unknown") {
                _scan.update { it.copy(phase = Phase.GUESSING, entry = entry, refining = willRefine) }
            } else if (offline.id == "unknown" && willRefine) {
                // Nothing useful to show yet: wait for the better source rather than flash a shrug.
                _scan.update { it.copy(entry = entry, refining = true) }
            } else {
                reveal(entry, viaBarcode = false, refining = willRefine)
            }
            if (!willRefine) return@launch

            // Better sources, strongest last. Each replaces what is on screen when it arrives.
            var record: String? = null
            var note: String? = null
            if (barcode != null) {
                record = withContext(Dispatchers.IO) { fetchProduct(barcode) }
                val product = record?.let { OpenFoodFacts.parse(it, barcode) }
                if (product != null) { entry = entry.copy(assessment = product); replace(entry, viaBarcode = true, stillRefining = s.aiEnabled) }
                else record = null
            }
            if (s.aiEnabled) {
                try {
                    val refined = client(s).assess(crop, labels, target?.coarse, record?.take(6000), Locale.getDefault().country, entry.assessment.id.takeIf { record != null } ?: "ai-$key")
                    // Same kind of thing, same id: so "new kind" is only awarded once per kind.
                    entry = entry.copy(assessment = if (record != null) refined else refined.copy(id = "ai:" + refined.name.trim().lowercase()))
                } catch (e: AiFailure) {
                    note = e.message
                }
            }
            replace(entry, viaBarcode = record != null, stillRefining = false, note = note)
        }
    }

    private fun reveal(entry: Entry, viaBarcode: Boolean, refining: Boolean, note: String? = null) {
        _scan.update { it.copy(phase = Phase.REVEALED, entry = entry, refining = refining, note = note) }
        _journal.update { list -> (listOf(entry) + list.filter { it.key != entry.key }) }
        apply(Game.onScan(_profile.value, entry.assessment, today, viaBarcode))
        persist()
    }

    private val counted = mutableSetOf<String>()

    private fun replace(entry: Entry, viaBarcode: Boolean, stillRefining: Boolean, note: String? = null) {
        val s = _scan.value
        if (s.entry?.key != entry.key && s.phase != Phase.THINKING) return // the user has moved on
        when (s.phase) {
            Phase.GUESSING -> _scan.update { it.copy(entry = entry, refining = stillRefining, note = note) }
            Phase.THINKING -> reveal(entry, viaBarcode, stillRefining, note)
            else -> {
                _scan.update { it.copy(entry = entry, refining = stillRefining, note = note) }
                _journal.update { list -> list.map { if (it.key == entry.key) entry else it } }
                _detail.update { d -> if (d?.entry?.key == entry.key) d.copy(entry = entry) else d }
                // The first reveal already scored the scan; reward only what the better answer newly proves.
                val barcodeFirstTime = viaBarcode && counted.add(entry.key)
                val firstWasGeneric = s.entry?.assessment?.generic == true
                apply(Game.onRefine(_profile.value, entry.assessment, today, barcodeFirstTime, firstWasGeneric))
                persist()
            }
        }
    }

    fun guess(d: Dimension) {
        val s = _scan.value
        val entry = s.entry ?: return
        if (s.phase != Phase.GUESSING) return
        val right = Game.isCorrectGuess(entry.assessment, d)
        apply(Game.onGuess(_profile.value, entry.assessment, d))
        _scan.update { it.copy(guess = d, guessRight = right) }
        reveal(entry, viaBarcode = entry.assessment.id.startsWith("off-"), refining = s.refining)
    }

    fun skipGuess() {
        val s = _scan.value
        val entry = s.entry ?: return
        if (s.phase == Phase.GUESSING) reveal(entry, entry.assessment.id.startsWith("off-"), s.refining)
    }

    fun reset() {
        pipeline?.cancel()
        dwellSince = System.currentTimeMillis()
        _scan.value = ScanState(target = _scan.value.target)
    }

    private fun fetchProduct(barcode: String): String? = runCatching {
        http.newCall(Request.Builder().url(OpenFoodFacts.url(barcode)).header("User-Agent", "Ripple-Android/0.1").build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun client(s: Settings): Ai = ai ?: Ai(s.apiKey, s.model).also { ai = it }

    private fun thumbnail(crop: Bitmap): Bitmap {
        val scale = 480f / maxOf(crop.width, crop.height)
        return if (scale < 1f) Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt().coerceAtLeast(1), (crop.height * scale).toInt().coerceAtLeast(1), true) else crop
    }

    // ---- Detail, pledges, questions ----------------------------------------------------------

    fun open(entry: Entry) { _detail.value = DetailState(entry) }
    fun closeDetail() { _detail.value = null }
    fun compare(with: Entry?) { _detail.update { it?.copy(compareWith = with) } }

    fun pledge(alt: Alternative) {
        val a = _detail.value?.entry?.assessment ?: return
        apply(Game.onPledge(_profile.value, a, alt, today)); persist()
    }

    fun togglePledge(index: Int) {
        _profile.update { p -> p.copy(pledges = p.pledges.mapIndexed { i, pl -> if (i == index) pl.copy(done = !pl.done) else pl }) }
        persist()
    }

    fun shared() { apply(Game.onShare(_profile.value)); persist() }

    fun ask(question: String) {
        val d = _detail.value ?: return
        val q = question.trim()
        if (q.isEmpty() || d.chat.any { it.answer == null && !it.failed }) return
        val canned = d.entry.assessment.questions.firstOrNull { it.q == q }?.a?.takeIf { it.isNotBlank() }
        val s = _settings.value
        when {
            canned != null -> _detail.update { it?.copy(chat = it.chat + Chat(q, canned)) }
            !s.aiEnabled -> _detail.update { it?.copy(chat = it.chat + Chat(q, "Add an API key in Settings to ask your own questions. Without one, Ripple can only answer the questions prepared for each object.", failed = true)) }
            else -> {
                _detail.update { it?.copy(chat = it.chat + Chat(q, null)) }
                viewModelScope.launch {
                    val history = d.chat.filter { it.answer != null && !it.failed }.map { it.question to it.answer!! }
                    val result = try { Chat(q, client(s).ask(d.entry.assessment, history, q)) }
                    catch (e: AiFailure) { Chat(q, e.message?.substringBefore(" Showing") ?: "Something went wrong.", failed = true) }
                    _detail.update { cur -> if (cur?.entry?.key == d.entry.key) cur.copy(chat = cur.chat.dropLast(1) + result) else cur }
                }
            }
        }
    }

    // ---- Settings and rewards ----------------------------------------------------------------

    fun updateSettings(s: Settings) {
        if (s.apiKey != _settings.value.apiKey || s.model != _settings.value.model) ai = null
        _settings.value = s
        store.saveSettings(s)
    }

    fun deleteEntry(entry: Entry) {
        _journal.update { list -> list.filter { it.key != entry.key } }
        persist()
    }

    private fun apply(o: earth.ripple.core.Outcome) {
        _profile.value = o.profile
        if (o.rewards.isEmpty()) return
        _rewards.update { it + o.rewards }
        viewModelScope.launch {
            delay(3200)
            _rewards.update { cur -> cur.drop(o.rewards.size.coerceAtMost(cur.size)) }
        }
    }

    private fun persist() {
        val p = _profile.value; val j = _journal.value
        viewModelScope.launch(Dispatchers.IO) { store.saveProfile(p); store.saveJournal(j) }
    }

    override fun onCleared() { vision.close() }

    companion object {
        const val DWELL_MS = 1300L
        const val REDWELL_MS = 1800L
    }
}

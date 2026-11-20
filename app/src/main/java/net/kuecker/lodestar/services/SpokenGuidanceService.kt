package net.kuecker.lodestar.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.pointing.GuidanceSpeech
import net.kuecker.lodestar.kit.pointing.SpokenKind
import net.kuecker.lodestar.kit.pointing.SpokenState
import net.kuecker.lodestar.model.SpokenGuidanceRate
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks the aiming guidance with the phone's text-to-speech engine: the words come from [GuidanceSpeech], which decides
 * what is worth saying and when; this class says them.
 *
 * The phone is often clamped above eye level while the photographer's hands are on the tripod head, so the screen is not
 * readable when it matters. The speech is played as navigation guidance (it follows the media volume, and other audio
 * ducks while it speaks).
 *
 * A phrase still being spoken is not cut off by the next one: while the head is moving, that would never let one finish.
 * The newer guidance is spoken once the phrase has ended; only "On target" interrupts. A phrase that was dropped does not
 * count as spoken, so the next call offers it again. Call the public functions from the main thread.
 */
class SpokenGuidanceService(private val context: Context) {
    private companion object {
        const val NORMAL_RATE = 1.0f
        const val SLOW_RATE = 0.75f

        /** Pause between failed attempts to speak (a phone call, for example, refuses the audio focus). */
        const val RETRY_DELAY_NANOS = 1_000_000_000L

        const val UTTERANCE_ID = "lodestar-guidance"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    @Volatile
    private var engine: TextToSpeech? = null

    /** Set when the engine has started and found a voice that speaks English. */
    @Volatile
    private var isReady = false

    /** How many utterances are queued or being spoken; the engine's progress callbacks count them down. */
    private val queued = AtomicInteger(0)
    private var isEnabled = false
    private var rate = SpokenGuidanceRate.NORMAL

    /** What was last spoken in this session; null at its start. */
    private var previous: SpokenState? = null
    private var retryNotBefore = 0L

    /** Turns speaking on or off and sets its rate. Cheap; called whenever settings are applied. */
    fun configure(enabled: Boolean, rate: SpokenGuidanceRate) {
        this.rate = rate
        engine?.setSpeechRate(rateValue())
        if (enabled == isEnabled) return
        isEnabled = enabled
        if (enabled) start() else stop()
    }

    /**
     * Offers the newest guidance. Call every frame while aiming; it speaks only when [GuidanceSpeech] has something to
     * say.
     */
    fun update(guidance: Guidance, now: Instant = Instant.now()) {
        if (!isEnabled) return
        val tts = engine
        if (tts == null) {
            // Released when the Aim screen went away; started again as the guidance resumes.
            start()
            return
        }
        if (!isReady) return
        val phrase = GuidanceSpeech.phrase(guidance, previous, now) ?: return
        val interrupts = phrase.state.kind == SpokenKind.ON_TARGET
        if (queued.get() > 0 && !interrupts) return
        if (System.nanoTime() < retryNotBefore) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            retryNotBefore = System.nanoTime() + RETRY_DELAY_NANOS
            return
        }
        // Counted before the call: the engine may report back before `speak` returns.
        queued.incrementAndGet()
        val mode = if (interrupts) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (tts.speak(phrase.text, mode, null, UTTERANCE_ID) == TextToSpeech.SUCCESS) {
            previous = phrase.state
        } else {
            finished()
            retryNotBefore = System.nanoTime() + RETRY_DELAY_NANOS
        }
    }

    /**
     * Forgets what was said and falls silent: the target changed or the guidance ended. The next phrase starts a new
     * session, so it says "degrees" again.
     */
    fun reset() {
        previous = null
        if (queued.get() > 0) engine?.stop()
    }

    /** Falls silent and releases the speech engine; call when the Aim screen goes away. Idempotent. */
    fun stop() {
        reset()
        engine?.shutdown()
        engine = null
        isReady = false
        if (queued.getAndSet(0) > 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun start() {
        if (engine != null) return
        // The engine reports back on the main thread, after this call has returned.
        val tts = TextToSpeech(context) { status -> onEngineStarted(status) }
        tts.setAudioAttributes(attributes)
        tts.setSpeechRate(rateValue())
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finished()

            @Deprecated("Replaced by onError(String, Int)")
            override fun onError(utteranceId: String?) = finished()

            override fun onError(utteranceId: String?, errorCode: Int) = finished()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        engine = tts
    }

    private fun onEngineStarted(status: Int) {
        val tts = engine ?: return
        if (status == TextToSpeech.SUCCESS) isReady = chooseVoice(tts)
    }

    /** The phrases are English: use the device's language if it is English, else American English. */
    private fun chooseVoice(tts: TextToSpeech): Boolean {
        val deviceLocale = Locale.getDefault()
        val candidates = if (deviceLocale.language == Locale.ENGLISH.language) listOf(deviceLocale, Locale.US) else listOf(Locale.US)
        return candidates.any {
            val result = tts.setLanguage(it)
            result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    /** One utterance is over (spoken, stopped or failed); focus is released when the last one ends. */
    private fun finished() {
        if (queued.updateAndGet { maxOf(it - 1, 0) } == 0) audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun rateValue(): Float = if (rate == SpokenGuidanceRate.SLOW) SLOW_RATE else NORMAL_RATE
}

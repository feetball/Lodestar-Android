package net.kuecker.lodestar.services

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds

/**
 * Haptic and audio cues for aiming.
 *
 * Haptics fire on the transitions the Aim screen reports (through the view's haptic feedback, so they follow the
 * system's touch-feedback setting). Audio ticks are scheduled here: the screen reports the separation every frame
 * and a coroutine on a private serial dispatcher plays ticks at the rate that separation calls for, so the rhythm
 * does not stutter with the frame rate. The tick coroutine and the audio track stop by themselves when the reports
 * stop. Call the public functions from the main thread.
 *
 * Unlike the iOS haptic generators, Android haptics need no warming up, so there is nothing to prepare.
 */
class FeedbackService(@Suppress("UNUSED_PARAMETER") context: Context) { // kept for parity with the other services
    private companion object {
        /** The audio track is kept running this long (seconds) after the chime's nominal end, so its tail is not cut. */
        const val CHIME_TAIL_ALLOWANCE = 0.2

        /** Success: the confirm effect, or the strongest generic click before Android 11. */
        val ACQUIRED_HAPTIC =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS

        /** The light tap that stands for the iOS light impact. */
        const val LOST_HAPTIC = HapticFeedbackConstants.CLOCK_TICK
    }

    /** The view haptics are performed on; installed by the activity. */
    var view: View? = null

    private var hapticsEnabled = false

    @Volatile
    private var audioEnabled = false

    private val clockOrigin = System.nanoTime()

    /** Seconds since this service was created, on a monotonic clock. */
    private fun now(): Double = (System.nanoTime() - clockOrigin) / 1e9

    // Audio state. Everything below is touched only by tasks of `audioScope` (one at a time), except the two inputs
    // the main thread writes: `latestReport` and `tickRequested`.

    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    /** The newest separation report; ticks stop when it gets older than the schedule's timeout. */
    @Volatile
    private var latestReport: FeedbackTickSchedule.Report? = null

    /** Whether a tick coroutine is running or about to start; the main thread starts one only when it is not. */
    private val tickRequested = AtomicBoolean(false)
    private val schedule = FeedbackTickSchedule()
    private var player: FeedbackAudioPlayer? = null
    private var chimeEndsAt: Double? = null
    private var tickJob: Job? = null

    /** Turns the cue types on or off. Cheap; called whenever settings are applied. */
    fun configure(haptics: Boolean, audio: Boolean) {
        hapticsEnabled = haptics
        if (!audio && audioEnabled) {
            latestReport = null
            audioScope.launch { shutDown(releaseSounds = true) }
        }
        audioEnabled = audio
    }

    /** The camera is on target: success haptic, plus the chime when audio is on. */
    fun targetAcquired() {
        if (hapticsEnabled) view?.performHapticFeedback(ACQUIRED_HAPTIC)
        if (audioEnabled) audioScope.launch { playChime() }
    }

    /** The camera left the target: a light tap. */
    fun targetLost() {
        if (hapticsEnabled) view?.performHapticFeedback(LOST_HAPTIC)
    }

    /**
     * Reports the current angular separation in degrees. Call every frame while aiming; with audio on, ticks play
     * faster and higher the closer the camera gets.
     */
    fun guidanceTick(separation: Double) {
        if (!separation.isFinite() || !audioEnabled) return
        latestReport = FeedbackTickSchedule.Report(max(separation, 0.0), now())
        if (tickRequested.compareAndSet(false, true)) audioScope.launch { startTickJobIfNeeded() }
    }

    /** Stops the ticks and releases the audio track. */
    fun stopGuidanceAudio() {
        latestReport = null
        if (tickRequested.get()) audioScope.launch { shutDown(releaseSounds = false) }
    }

    // Audio (on the audio dispatcher)

    private fun audioOutput(): FeedbackAudioPlayer = player ?: FeedbackAudioPlayer().also { player = it }

    private fun playChime() {
        if (!audioEnabled || !audioOutput().playChime()) return
        chimeEndsAt = now() + FeedbackSynthesizer.chimeDuration + CHIME_TAIL_ALLOWANCE
        tickRequested.set(true)
        startTickJobIfNeeded()
    }

    private fun startTickJobIfNeeded() {
        if (tickJob != null) return
        tickJob = audioScope.launch { runTicks() }
    }

    /** Plays ticks as the schedule asks until the reports stop, then releases the audio track. */
    private suspend fun runTicks() {
        while (true) {
            currentCoroutineContext().ensureActive()
            when (val action = schedule.next(now(), latestReport, chimeEndsAt)) {
                is FeedbackTickSchedule.Action.Tick -> {
                    audioOutput().playTick(action.pitchIndex)
                    delay(action.wait.seconds)
                }
                is FeedbackTickSchedule.Action.Wait -> delay(action.seconds.seconds)
                FeedbackTickSchedule.Action.Finished -> {
                    // Ended on its own, not through stopGuidanceAudio(): release the audio track.
                    shutDown(releaseSounds = false)
                    return
                }
            }
        }
    }

    /** Ends the tick coroutine and releases the track (and, when audio is off, the rendered sounds). Idempotent. */
    private fun shutDown(releaseSounds: Boolean) {
        tickJob?.cancel()
        tickJob = null
        chimeEndsAt = null
        schedule.reset()
        player?.stop()
        if (releaseSounds) player = null
        tickRequested.set(false)
    }
}

/**
 * How the distance to the target sounds: the closer, the faster and higher the ticks.
 *
 * Angular separations span two orders of magnitude between "far" and "on target", so the tick interval and the
 * pitch both change geometrically with the separation; every halving of the distance then sounds like the same
 * step. [separation] is an angular separation in degrees; invalid input sounds like "far".
 */
internal class FeedbackTone(separation: Double) {
    /** Seconds until the next tick. */
    val interval: Double

    /** Which pre-rendered pitch to play: 0 is the lowest (far), `PITCH_COUNT - 1` the highest (on target). */
    val pitchIndex: Int

    init {
        val clamped = if (separation.isFinite()) min(max(separation, NEAR_SEPARATION), FAR_SEPARATION) else FAR_SEPARATION
        // 0 on target … 1 far away.
        val distance = ln(clamped / NEAR_SEPARATION) / ln(FAR_SEPARATION / NEAR_SEPARATION)
        interval = FASTEST_INTERVAL * (SLOWEST_INTERVAL / FASTEST_INTERVAL).pow(distance)
        pitchIndex = ((1 - distance) * (PITCH_COUNT - 1)).roundedHalfAwayFromZero().toInt()
    }

    companion object {
        /** Separation in degrees at and beyond which the tone is slowest and lowest. */
        const val FAR_SEPARATION = 60.0

        /** Separation in degrees at and below which the tone is fastest and highest. */
        const val NEAR_SEPARATION = 0.5

        /** Seconds between ticks when far from the target. */
        const val SLOWEST_INTERVAL = 1.2

        /** Seconds between ticks when on target. */
        const val FASTEST_INTERVAL = 0.08
        const val LOWEST_FREQUENCY = 440.0
        const val HIGHEST_FREQUENCY = 1320.0

        /** The number of distinct pitches between the lowest and highest frequency (about 40 cents apart). */
        const val PITCH_COUNT = 48

        /** The frequency in hertz of a pre-rendered pitch; out-of-range indices are clamped. */
        fun frequencyOfPitch(index: Int): Double {
            val fraction = index.coerceIn(0, PITCH_COUNT - 1).toDouble() / (PITCH_COUNT - 1)
            return LOWEST_FREQUENCY * (HIGHEST_FREQUENCY / LOWEST_FREQUENCY).pow(fraction)
        }
    }
}

/**
 * Decides when the next tick is due. It sees only times in seconds from any monotonic clock, so it can be tested
 * with plain numbers.
 *
 * The rhythm follows the latest separation report: whenever the schedule is asked, the tick interval for the
 * separation reported now is measured from the previous tick, so approaching the target speeds the rhythm up at
 * once instead of after the old, longer interval has run out.
 */
internal class FeedbackTickSchedule {
    /** A separation report with the time (seconds) it was made. */
    data class Report(val degrees: Double, val time: Double)

    sealed interface Action {
        /** Play a tick of this pitch now, then ask again after [wait] seconds. */
        data class Tick(val pitchIndex: Int, val wait: Double) : Action

        /** Nothing to play yet; ask again after [seconds] seconds. */
        data class Wait(val seconds: Double) : Action

        /** Nothing is left to play. */
        data object Finished : Action
    }

    private var lastTick: Double? = null

    /** What to do at time [now], given the newest [report] and the time (seconds) the chime ends, if one is playing. */
    fun next(now: Double, report: Report?, chimeEndsAt: Double?): Action {
        if (report != null && now - report.time <= REPORT_TIMEOUT) {
            val tone = FeedbackTone(report.degrees)
            val due = lastTick?.let { it + tone.interval } ?: now
            if (now < due) return Action.Wait(min(due - now, MAXIMUM_WAIT))
            lastTick = now
            return Action.Tick(tone.pitchIndex, min(tone.interval, MAXIMUM_WAIT))
        }
        if (chimeEndsAt != null && now < chimeEndsAt) return Action.Wait(chimeEndsAt - now)
        return Action.Finished
    }

    /** Forgets the last tick; the next report starts a new run of ticks with an immediate one. */
    fun reset() {
        lastTick = null
    }

    companion object {
        /** Reports older than this many seconds mean the screen stopped aiming. */
        const val REPORT_TIMEOUT = 0.75

        /** Longest time between two decisions in seconds, so that a change of rhythm is noticed quickly. */
        const val MAXIMUM_WAIT = 0.1
    }
}

/** Renders the feedback sounds as mono samples. */
internal object FeedbackSynthesizer {
    const val SAMPLE_RATE = 44_100

    private const val TICK_DURATION = 0.025
    private const val TICK_AMPLITUDE = 0.8f
    private const val CHIME_AMPLITUDE = 0.7f
    private const val CHIME_NOTE_OFFSET = 0.11
    private const val FIRST_NOTE_DURATION = 0.2
    private const val SECOND_NOTE_DURATION = 0.35

    /** A short soft blip: a sine burst under a raised-cosine envelope, so it starts and ends without a click. */
    fun tick(frequency: Double): FloatArray {
        val count = (TICK_DURATION * SAMPLE_RATE).toInt()
        return FloatArray(count) { index ->
            val envelope = 0.5 * (1 - cos(2 * PI * index / (count - 1)))
            val time = index.toDouble() / SAMPLE_RATE
            TICK_AMPLITUDE * (envelope * sin(2 * PI * frequency * time)).toFloat()
        }
    }

    /** The "target acquired" sound: two rising notes with a decaying tail. */
    fun chime(): FloatArray {
        val first = note(frequency = 880.0, duration = FIRST_NOTE_DURATION, decay = 0.06)
        val second = note(frequency = 1320.0, duration = SECOND_NOTE_DURATION, decay = 0.09)
        val offset = (CHIME_NOTE_OFFSET * SAMPLE_RATE).toInt()
        val samples = FloatArray(max(first.size, offset + second.size))
        for (index in first.indices) samples[index] += first[index]
        for (index in second.indices) samples[offset + index] += second[index]
        val peak = samples.fold(0f) { highest, sample -> max(highest, abs(sample)) }
        val gain = if (peak > 0) CHIME_AMPLITUDE / peak else 0f
        return FloatArray(samples.size) { samples[it] * gain }
    }

    /** How long [chime] sounds, in seconds. */
    val chimeDuration: Double get() = max(FIRST_NOTE_DURATION, CHIME_NOTE_OFFSET + SECOND_NOTE_DURATION)

    /** A sine note with a 4 ms attack, exponential decay (time constant [decay]) and a 20 ms release. */
    private fun note(frequency: Double, duration: Double, decay: Double): FloatArray {
        val count = (duration * SAMPLE_RATE).toInt()
        val attack = (0.004 * SAMPLE_RATE).toInt()
        val release = (0.02 * SAMPLE_RATE).toInt()
        return FloatArray(count) { index ->
            val time = index.toDouble() / SAMPLE_RATE
            var envelope = exp(-time / decay)
            if (index < attack) envelope *= index.toDouble() / attack
            if (index >= count - release) envelope *= (count - index).toDouble() / release
            (envelope * sin(2 * PI * frequency * time)).toFloat()
        }
    }
}

/**
 * Plays the feedback sounds: ticks at a choice of pitches and the "target acquired" chime.
 *
 * The sounds are rendered once into 16-bit PCM and written to one streaming [AudioTrack] when they are due, so
 * nothing is generated on the audio path. The track uses the sonification usage: it mixes with other audio, follows
 * the system-sound volume and is silent in the phone's silent modes. It is created on first use and again on demand
 * after [stop] or a failure. Not thread safe: use it from one thread at a time.
 */
internal class FeedbackAudioPlayer {
    private companion object {
        const val VOLUME = 0.6f

        /** Pause between failed attempts to open the track (a phone call, for example, may refuse it), in nanoseconds. */
        const val RETRY_DELAY_NANOS = 1_000_000_000L
        const val BYTES_PER_SAMPLE = 2

        /** The track holds this many chimes, so the chime and the ticks queued behind it fit without waiting. */
        const val CAPACITY_IN_CHIMES = 2

        fun pcm(samples: FloatArray): ShortArray = ShortArray(samples.size) { index ->
            (samples[index] * Short.MAX_VALUE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private val tickSamples = List(FeedbackTone.PITCH_COUNT) { index ->
        pcm(FeedbackSynthesizer.tick(FeedbackTone.frequencyOfPitch(index)))
    }
    private val chimeSamples = pcm(FeedbackSynthesizer.chime())
    private var track: AudioTrack? = null
    private var retryNotBefore: Long? = null

    /** Writes one tick at [pitchIndex] (see [FeedbackTone]), opening the track if needed. */
    fun playTick(pitchIndex: Int) {
        write(tickSamples[pitchIndex.coerceIn(0, tickSamples.size - 1)])
    }

    /** Plays the chime after whatever is queued, opening the track if needed. Returns `false` if audio is unavailable. */
    fun playChime(): Boolean = write(chimeSamples)

    /** Silences and releases the track. Idempotent. */
    fun stop() {
        val current = track ?: return
        track = null
        try {
            current.pause()
            current.flush()
        } catch (_: IllegalStateException) {
            // The track already died with the audio server; there is nothing left to silence.
        }
        current.release()
    }

    private fun write(samples: ShortArray): Boolean {
        val output = openTrack() ?: return false
        val written = try {
            output.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: IllegalStateException) {
            AudioTrack.ERROR_INVALID_OPERATION
        }
        if (written >= 0) return true
        // The audio system took the track away (ERROR_DEAD_OBJECT) or refused the data: start over on demand.
        abandon()
        return false
    }

    /** The playing track, created if needed. `null` if the system refuses (it is then not asked again for a second). */
    private fun openTrack(): AudioTrack? {
        val existing = track
        if (existing != null) {
            if (existing.playState == AudioTrack.PLAYSTATE_PLAYING) return existing
            return if (play(existing)) existing else null
        }
        retryNotBefore?.let { if (System.nanoTime() - it < 0) return null }
        retryNotBefore = null
        val created = createTrack()
        if (created == null) {
            retryNotBefore = System.nanoTime() + RETRY_DELAY_NANOS
            return null
        }
        track = created
        return if (play(created)) created else null
    }

    /** Starts [output]; a track the system no longer lets play is given up. */
    private fun play(output: AudioTrack): Boolean {
        try {
            output.play()
            return true
        } catch (_: IllegalStateException) {
            abandon()
            return false
        }
    }

    /** Releases the track after a failure; the next sound opens a new one after a pause. */
    private fun abandon() {
        stop()
        retryNotBefore = System.nanoTime() + RETRY_DELAY_NANOS
    }

    private fun createTrack(): AudioTrack? {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(FeedbackSynthesizer.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            FeedbackSynthesizer.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val capacity = max(minimum, CAPACITY_IN_CHIMES * chimeSamples.size * BYTES_PER_SAMPLE)
        return try {
            val created = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(capacity)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state == AudioTrack.STATE_INITIALIZED) {
                created.setVolume(VOLUME)
                created
            } else {
                created.release()
                null
            }
        } catch (_: UnsupportedOperationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

package ai.voitta.virgil

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** What the transport controls are showing. */
enum class SpeechState { IDLE, SPEAKING, PAUSED }

/**
 * Reads a blurb aloud, with stop, pause and resume.
 *
 * TextToSpeech has no pause. It has stop, and it has a queue. So the blurb is
 * split into sentences and queued as separate utterances; pausing stops the
 * engine and remembers which sentence was in flight, and resuming re-queues
 * from that sentence onward.
 *
 * ponytail: the resolution of a pause is therefore one sentence -- resuming
 * repeats the sentence that was interrupted, rather than continuing mid-word.
 * Finer granularity would mean splitting on words, which reads badly aloud
 * because the engine re-runs its prosody on every chunk.
 *
 * [onStateChange] is what drives the controls. Android delivers these callbacks
 * on a binder thread, so the caller must marshal to the main thread before
 * touching UI state.
 */
class Speaker(
    context: Context,
    private val onStateChange: (SpeechState) -> Unit,
) {

    private var engine: TextToSpeech? = null
    private var ready = false

    /** The current blurb, one sentence per entry. Retained after it finishes so
     *  that play can replay it without another round trip. */
    private var chunks: List<String> = emptyList()

    /** Where playback resumes: the sentence that was last announced as started. */
    private var index = 0

    /** Set when speak() lost the race with initialisation. */
    private var pendingStart = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
                engine?.setOnUtteranceProgressListener(progress)
                // Initialisation is asynchronous and usually loses the race with
                // the first blurb, so anything queued meanwhile is spoken now.
                if (pendingStart) {
                    pendingStart = false
                    enqueueFrom(index)
                }
            }
        }
    }

    /**
     * Only onStart and the natural end of the last sentence move the state.
     *
     * onStop fires for every utterance that stop() flushes, which is exactly
     * what pause() does -- acting on it would flip PAUSED straight back to
     * IDLE. pause() and stop() set their own state instead.
     */
    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            index = chunkIndex(utteranceId)
            onStateChange(SpeechState.SPEAKING)
        }

        override fun onDone(utteranceId: String?) {
            if (chunkIndex(utteranceId) == chunks.lastIndex) {
                index = 0
                onStateChange(SpeechState.IDLE)
            }
        }

        @Deprecated("Required by the base class", ReplaceWith(""))
        override fun onError(utteranceId: String?) = finishOnError()
        override fun onError(utteranceId: String?, errorCode: Int) = finishOnError()
        override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
    }

    private fun finishOnError() {
        index = 0
        onStateChange(SpeechState.IDLE)
    }

    /** Loads a new blurb and speaks it from the top. */
    fun speak(text: String) {
        chunks = sentences(text)
        index = 0
        enqueueFrom(0)
    }

    /** Speaks from wherever playback left off: the top, or a paused sentence. */
    fun play() {
        if (chunks.isEmpty()) {
            return
        }
        enqueueFrom(index)
    }

    fun pause() {
        engine?.stop()
        onStateChange(SpeechState.PAUSED)
    }

    fun stop() {
        pendingStart = false
        index = 0
        engine?.stop()
        onStateChange(SpeechState.IDLE)
    }

    /** Releases the engine. A leaked TextToSpeech keeps a service bound. */
    fun shutdown() {
        pendingStart = false
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private fun enqueueFrom(start: Int) {
        if (chunks.isEmpty()) {
            return
        }
        if (!ready) {
            index = start
            pendingStart = true
            return
        }
        // The first flushes whatever was there; the rest queue behind it, so the
        // engine handles the sentence-to-sentence gap rather than a callback.
        for (i in start..chunks.lastIndex) {
            val mode = if (i == start) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine?.speak(chunks[i], mode, null, utteranceId(i))
        }
        onStateChange(SpeechState.SPEAKING)
    }

    private fun utteranceId(i: Int): String {
        val retval = "$UTTERANCE_PREFIX$i"
        return retval
    }

    private fun chunkIndex(utteranceId: String?): Int {
        val retval = utteranceId?.removePrefix(UTTERANCE_PREFIX)?.toIntOrNull() ?: 0
        return retval
    }

    private companion object {
        const val UTTERANCE_PREFIX = "virgil-blurb-"

        /**
         * Sentence boundaries. The blurb is plain spoken prose by construction --
         * no markdown, no citations, no abbreviations to speak of -- so the
         * punctuation is trustworthy here in a way it would not be on arbitrary
         * text. A blurb with no terminal punctuation stays one chunk and simply
         * cannot be paused part-way.
         */
        val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")

        fun sentences(text: String): List<String> {
            val retval = SENTENCE_BREAK.split(text.trim())
                .filter { sentence -> sentence.isNotBlank() }
            return retval
        }
    }
}

package ai.voitta.virgil

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads a blurb aloud.
 *
 * The blurb is written to be spoken -- 120 to 180 words of plain prose, no
 * markdown, no citations -- so this is a thin wrapper over the platform engine
 * rather than anything clever.
 *
 * [onStateChange] reports whether speech is in progress, which is what drives
 * the stop control. Android delivers those callbacks on a binder thread, so the
 * caller must marshal to the main thread before touching UI state.
 */
class Speaker(
    context: Context,
    private val onStateChange: (Boolean) -> Unit,
) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.US
                engine?.setOnUtteranceProgressListener(progress)
                // Initialisation is asynchronous and usually loses the race with
                // the first blurb, so anything queued meanwhile is spoken now.
                pending?.let { text ->
                    pending = null
                    speak(text)
                }
            }
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = onStateChange(true)
        override fun onDone(utteranceId: String?) = onStateChange(false)

        @Deprecated("Required by the base class", ReplaceWith(""))
        override fun onError(utteranceId: String?) = onStateChange(false)
        override fun onError(utteranceId: String?, errorCode: Int) = onStateChange(false)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = onStateChange(false)
    }

    fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        pending = null
        engine?.stop()
        onStateChange(false)
    }

    /** Releases the engine. A leaked TextToSpeech keeps a service bound. */
    fun shutdown() {
        pending = null
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "virgil-blurb"
    }
}

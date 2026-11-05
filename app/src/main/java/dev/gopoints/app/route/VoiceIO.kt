package dev.gopoints.app.route

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.gopoints.app.data.RU
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Голос: распознавание речи (Google на телефоне) + ответы вслух (TextToSpeech).
 * Все вызовы — с главного потока.
 */
class VoiceIO(ctx: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val appCtx = ctx.applicationContext
    private var ttsMade = false
    /** Синтез речи создаём только если понадобится — он тяжёлый. */
    private val tts: TextToSpeech by lazy {
        ttsMade = true
        TextToSpeech(appCtx) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    }
    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx)
    private var waiting: CancellableContinuation<String?>? = null

    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
    private var voiceChosen = false

    /** false — без озвучки: только текст на экране и короткие сигналы. */
    var speak: Boolean = true

    var onPartial: (String) -> Unit = {}
    var onLevel: (Float) -> Unit = {}

    companion object {
        fun available(ctx: Context) = SpeechRecognizer.isRecognitionAvailable(ctx)
    }

    /** Короткий сигнал: «принято» или «не расслышал». */
    suspend fun beep(ok: Boolean) {
        tones?.startTone(if (ok) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 160)
        delay(260)
    }

    /** Лучший установленный русский голос, чуть быстрее обычного. */
    private fun chooseVoice() {
        if (voiceChosen) return
        voiceChosen = true
        tts.language = RU
        tts.setSpeechRate(1.12f)
        runCatching {
            tts.voices
                ?.filter { it.locale.language == "ru" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                ?.maxByOrNull { it.quality * 10 + if (it.isNetworkConnectionRequired) 0 else 1 }
                ?.let { tts.voice = it }
        }
    }

    /** Сказать вслух и дождаться конца фразы. Без озвучки — короткая пауза. */
    suspend fun say(text: String) {
        if (!speak) {
            delay(150)
            return
        }
        val ok = withTimeoutOrNull(3000) { ready.await() } ?: false
        if (!ok) {
            delay(300)
            return
        }
        chooseVoice()
        val id = UUID.randomUUID().toString()
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { c ->
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && c.isActive) c.resume(Unit)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && c.isActive) c.resume(Unit)
                    }
                })
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                c.invokeOnCancellation { tts.stop() }
            }
        }
    }

    /** Послушать одну фразу. null — тишина или ошибка. */
    suspend fun listen(): String? = suspendCancellableCoroutine { c ->
        waiting = c
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onLevel(rmsdB)
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (c.isActive) c.resume(null)
            }
            override fun onResults(results: Bundle?) {
                val r = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (c.isActive) c.resume(r)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        recognizer.startListening(intent)
        c.invokeOnCancellation { recognizer.cancel() }
    }

    /** Ответ кнопкой вместо голоса (например, «первый»). */
    fun inject(text: String) {
        val c = waiting ?: return
        recognizer.cancel()
        if (c.isActive) c.resume(text)
    }

    fun stopAll() {
        recognizer.cancel()
        if (ttsMade) tts.stop()
    }

    fun release() {
        tones?.release()
        recognizer.destroy()
        if (ttsMade) tts.shutdown()
    }
}

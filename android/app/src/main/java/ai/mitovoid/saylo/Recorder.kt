package ai.mitovoid.saylo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit microphone capture into an in-memory WAV, like the Windows recorder. */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false

    /** Caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
        pcm.reset()
        running = true
        rec.startRecording()
        record = rec
        thread = Thread {
            val buf = ByteArray(minBuf)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
        }.also { it.start() }
    }

    /** Returns (wav bytes, seconds of audio). */
    fun stop(): Pair<ByteArray, Double> {
        running = false
        thread?.join(500)
        record?.run { stop(); release() }
        record = null
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(data) to data.size / (RATE * 2.0)
    }

    val isRecording get() = running

    private fun wav(data: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        return header.array() + data
    }

    companion object {
        const val RATE = 16_000
    }
}

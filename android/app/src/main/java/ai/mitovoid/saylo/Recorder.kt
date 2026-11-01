package ai.mitovoid.saylo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit microphone capture into memory, like the Windows recorder. */
class Recorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()
    @Volatile private var running = false
    /** Loudness of the latest chunk, 0-100, for the listening bars. */
    @Volatile var level = 0
        private set

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
                if (n > 0) {
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    level = minOf(100, peak(buf, 0, n) / 120)
                }
            }
        }.also { it.start() }
    }

    /** Returns (16-bit PCM, seconds of audio). */
    fun stop(): Pair<ByteArray, Double> {
        running = false
        thread?.join(500)
        record?.run { stop(); release() }
        record = null
        level = 0
        val data = synchronized(pcm) { pcm.toByteArray() }
        return data to data.size / (RATE * 2.0)
    }

    val isRecording get() = running

    companion object {
        const val RATE = 16_000

        private fun peak(b: ByteArray, from: Int, to: Int): Int {
            val s = ByteBuffer.wrap(b, from, to - from).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var p = 0
            while (s.hasRemaining()) p = maxOf(p, kotlin.math.abs(s.get().toInt()))
            return p
        }

        /** Milliseconds of the clip clearly louder than its own background (30 ms frames), like recorder.py:
         *  silence, steady noise and single clicks score near 0, and Whisper turns those into "Thank you." */
        fun speechMs(pcm: ByteArray): Int {
            val n = RATE * 30 / 1000 * 2
            val peaks = (0..pcm.size - n step n).map { peak(pcm, it, it + n) }.filter { it > 0 }.sorted()
            if (peaks.isEmpty()) return 0
            val background = peaks[peaks.size / 5]
            return 30 * peaks.count { it > maxOf(3 * background, 300) }
        }
    }
}

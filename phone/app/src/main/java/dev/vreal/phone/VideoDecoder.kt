package dev.vreal.phone

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Hardware decoder rendering straight into the renderer's Surface. The codec is created on the
 * first keyframe so its parameter sets can be passed as codec-specific data. Each frame's pose
 * timestamp travels as presentationTimeUs, so it comes out as the SurfaceTexture timestamp.
 */
class VideoDecoder(private val surface: Surface, private val onNeedKeyframe: () -> Unit) {
    private val thread = HandlerThread("decoder").apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on the decoder thread.
    private var codec: MediaCodec? = null
    private var config: StreamConfig? = null
    private val queued = ArrayDeque<Frame>()
    private val freeInputs = ArrayDeque<Int>()

    @Volatile var decodedFrames = 0L
        private set
    @Volatile var decoderName: String = "-"
        private set

    fun configure(newConfig: StreamConfig) = handler.post {
        if (newConfig == config) return@post
        releaseCodec()
        config = newConfig
        onNeedKeyframe()
    }

    fun submit(frame: Frame) = handler.post {
        val cfg = config ?: return@post
        if (codec == null) {
            if (!frame.keyframe) return@post
            if (!createCodec(cfg, frame)) return@post
        }
        queued.addLast(frame)
        if (queued.size > 4) Log.w(TAG, "decoder backlog: ${queued.size} frames")
        drain()
    }

    fun release() {
        handler.post { releaseCodec() }
        thread.quitSafely()
    }

    private fun createCodec(cfg: StreamConfig, keyframe: Frame): Boolean {
        val format = MediaFormat.createVideoFormat(cfg.mime, cfg.width, cfg.height)
        val paramSets = Nal.parameterSets(keyframe.data, cfg.codec)
        when (cfg.codec) {
            Protocol.CODEC_HEVC -> format.setByteBuffer("csd-0", ByteBuffer.wrap(Nal.concat(paramSets)))
            Protocol.CODEC_H264 -> {
                paramSets.firstOrNull { Nal.type(it, cfg.codec) == 7 }
                    ?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
                paramSets.firstOrNull { Nal.type(it, cfg.codec) == 8 }
                    ?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(it)) }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1) // Qualcomm; ignored elsewhere

        return try {
            val c = MediaCodec.createDecoderByType(cfg.mime)
            c.setCallback(callback, handler)
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            decoderName = c.name
            Log.i(TAG, "decoder ${c.name} started for $cfg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "decoder setup failed for $cfg", e)
            false
        }
    }

    private fun drain() {
        val c = codec ?: return
        while (queued.isNotEmpty() && freeInputs.isNotEmpty()) {
            val frame = queued.removeFirst()
            val index = freeInputs.removeFirst()
            val buf = c.getInputBuffer(index) ?: continue
            if (frame.data.size > buf.capacity()) {
                Log.w(TAG, "frame ${frame.index} too large (${frame.data.size} B), skipped")
                freeInputs.addFirst(index)
                onNeedKeyframe()
                continue
            }
            buf.clear()
            buf.put(frame.data)
            c.queueInputBuffer(index, 0, frame.data.size, frame.poseTimestampUs, 0)
        }
    }

    private fun releaseCodec() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        queued.clear()
        freeInputs.clear()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c !== codec) return
            freeInputs.addLast(index)
            drain()
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            // Render immediately; the GL thread picks up whatever is newest at its next vsync.
            c.releaseOutputBuffer(index, true)
            decodedFrames++
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "decoder error", e)
            if (c !== codec) return
            releaseCodec()
            onNeedKeyframe()
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format: $format")
        }
    }

    companion object {
        private const val TAG = "VReal.Decoder"

        /** Bitmask of decodable codecs (bit0 H.264, bit1 HEVC, bit2 AV1), for HELLO. */
        fun supportedCodecMask(): Int {
            val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .map { it.lowercase() }
                .toSet()
            var mask = 0
            if ("video/avc" in types) mask = mask or 1
            if ("video/hevc" in types) mask = mask or 2
            if ("video/av01" in types) mask = mask or 4
            return mask
        }
    }
}

/** Minimal Annex-B helpers. */
object Nal {
    /** NAL units of an Annex-B buffer, each returned with its 4-byte start code. */
    fun split(data: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>() // index of first byte after the start code
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { n, start ->
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else data.size
            if (n + 1 < starts.size && end > start && data[end - 1].toInt() == 0) end-- // 4-byte start code
            START_CODE + data.copyOfRange(start, end)
        }
    }

    fun type(nal: ByteArray, codec: Int): Int {
        val header = nal[START_CODE.size].toInt() and 0xff
        return if (codec == Protocol.CODEC_HEVC) (header shr 1) and 0x3f else header and 0x1f
    }

    /** VPS/SPS/PPS (HEVC) or SPS/PPS (H.264). */
    fun parameterSets(data: ByteArray, codec: Int): List<ByteArray> {
        val wanted = if (codec == Protocol.CODEC_HEVC) setOf(32, 33, 34) else setOf(7, 8)
        return split(data).filter { it.size > START_CODE.size && type(it, codec) in wanted }
    }

    fun concat(list: List<ByteArray>): ByteArray = list.fold(ByteArray(0)) { acc, b -> acc + b }

    private val START_CODE = byteArrayOf(0, 0, 0, 1)
}

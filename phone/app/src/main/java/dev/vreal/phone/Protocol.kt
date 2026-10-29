package dev.vreal.phone

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire format shared with the PC side. See docs/protocol.md. */
object Protocol {
    const val VERSION = 1
    const val PC_PORT = 9944
    const val PHONE_PORT = 9945

    const val TYPE_HELLO = 1
    const val TYPE_CONFIG = 2
    const val TYPE_POSE = 3
    const val TYPE_VIDEO = 4
    const val TYPE_IDR_REQUEST = 5

    const val HEADER_SIZE = 4
    const val VIDEO_HEADER_SIZE = HEADER_SIZE + 17
    const val MAX_DATAGRAM = 2048

    const val CODEC_H264 = 0
    const val CODEC_HEVC = 1
    const val CODEC_AV1 = 2

    fun allocate(bodySize: Int): ByteBuffer =
        ByteBuffer.allocate(HEADER_SIZE + bodySize).order(ByteOrder.LITTLE_ENDIAN)

    fun putHeader(buf: ByteBuffer, type: Int) {
        buf.put('V'.code.toByte()).put('R'.code.toByte()).put(VERSION.toByte()).put(type.toByte())
    }

    /** Returns the message type, or -1 if the datagram is not ours. */
    fun parseType(data: ByteArray, length: Int): Int {
        if (length < HEADER_SIZE) return -1
        if (data[0] != 'V'.code.toByte() || data[1] != 'R'.code.toByte()) return -1
        if (data[2].toInt() != VERSION) return -1
        return data[3].toInt() and 0xff
    }
}

data class StreamConfig(val codec: Int, val width: Int, val height: Int, val fps: Int) {
    val mime: String
        get() = when (codec) {
            Protocol.CODEC_H264 -> "video/avc"
            Protocol.CODEC_HEVC -> "video/hevc"
            else -> "video/av01"
        }
}

class Frame(val index: Long, val data: ByteArray, val poseTimestampUs: Long, val keyframe: Boolean)

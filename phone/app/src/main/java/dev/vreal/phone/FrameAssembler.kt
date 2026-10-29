package dev.vreal.phone

/**
 * Reassembles VIDEO fragments into frames. A frame that never completes counts as lost, and
 * every frame after a loss is held back until the next keyframe, because decoding P-frames
 * against a missing reference only produces garbage.
 */
class FrameAssembler {
    private class Pending(val count: Int, val poseTimestampUs: Long, val keyframe: Boolean) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var size = 0
    }

    private val pending = HashMap<Long, Pending>()
    private var lastDelivered = -1L
    private var waitingForKeyframe = true

    var lostFrames = 0L
        private set

    /** True when the caller should ask the PC for a keyframe. */
    val needsKeyframe: Boolean get() = waitingForKeyframe

    fun add(
        index: Long, fragment: Int, count: Int, poseTimestampUs: Long, keyframe: Boolean,
        data: ByteArray, offset: Int, length: Int,
    ): Frame? {
        if (lastDelivered >= 0 && index + 300 < lastDelivered) {
            // Big jump backwards: the PC side restarted and its frame counter reset.
            pending.clear()
            lastDelivered = -1
            waitingForKeyframe = true
        }
        if (index <= lastDelivered || count <= 0) return null
        val p = pending.getOrPut(index) { Pending(count, poseTimestampUs, keyframe) }
        if (fragment >= p.count || p.parts[fragment] != null) return null
        p.parts[fragment] = data.copyOfRange(offset, offset + length)
        p.received++
        p.size += length

        if (p.received < p.count) {
            if (pending.size > 8) pending.remove(pending.keys.min())
            return null
        }

        pending.remove(index)
        pending.keys.removeAll { it < index }
        if (lastDelivered >= 0 && index > lastDelivered + 1) {
            lostFrames += index - lastDelivered - 1
            waitingForKeyframe = true
        }
        lastDelivered = index

        if (p.keyframe) waitingForKeyframe = false
        if (waitingForKeyframe) return null

        val out = ByteArray(p.size)
        var pos = 0
        for (part in p.parts) {
            part!!.copyInto(out, pos)
            pos += part.size
        }
        return Frame(index, out, p.poseTimestampUs, p.keyframe)
    }

    /** Forces the next frames to be held back until a keyframe, e.g. after a decoder reset. */
    fun resync() {
        waitingForKeyframe = true
    }
}

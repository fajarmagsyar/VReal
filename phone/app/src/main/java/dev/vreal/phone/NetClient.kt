package dev.vreal.phone

import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * UDP link to the PC: announces the phone with HELLO, receives CONFIG and VIDEO, sends POSE.
 * One socket serves both USB tethering and Wi-Fi.
 */
class NetClient(
    private val hello: Hello,
    private val manualHost: String?,
    private val listener: Listener,
) {
    data class Hello(val width: Int, val height: Int, val refreshHz: Float, val codecMask: Int)

    interface Listener {
        fun onConfig(config: StreamConfig)
        fun onFrame(frame: Frame)
    }

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(Protocol.PHONE_PORT))
        broadcast = true
        receiveBufferSize = 4 shl 20
    }
    private val assembler = FrameAssembler()
    private val poseBuffer = Protocol.allocate(28)
    private val posePacket = DatagramPacket(poseBuffer.array(), poseBuffer.capacity())

    @Volatile private var running = true
    @Volatile private var pcAddress: InetSocketAddress? = null
    @Volatile private var linkRank = -1
    @Volatile private var lastFromPcMs = 0L
    @Volatile private var keyframeRequested = false
    private var lastIdrRequestMs = 0L
    private var poseSeq = 0

    @Volatile var bytesReceived = 0L
        private set
    val lostFrames: Long get() = assembler.lostFrames
    val connectedTo: String? get() = pcAddress?.address?.hostAddress
    val linkName: String get() = LINK_NAMES.getOrElse(linkRank) { "?" }

    private val receiveThread = Thread(::receiveLoop, "net-rx")
    private val helloThread = Thread(::helloLoop, "net-hello")

    fun start() {
        receiveThread.start()
        helloThread.start()
    }

    fun stop() {
        running = false
        socket.close()
        helloThread.interrupt()
    }

    /** Called from the sensor thread for every pose sample. */
    fun sendPose(timestampUs: Long, q: FloatArray) {
        val target = pcAddress ?: return
        synchronized(poseBuffer) {
            poseBuffer.clear()
            Protocol.putHeader(poseBuffer, Protocol.TYPE_POSE)
            poseBuffer.putInt(poseSeq++).putLong(timestampUs)
                .putFloat(q[0]).putFloat(q[1]).putFloat(q[2]).putFloat(q[3])
            posePacket.socketAddress = target
            trySend(posePacket)
        }
    }

    /** Asks for a keyframe, e.g. after the decoder was recreated. */
    fun requestKeyframe() {
        assembler.resync()
        keyframeRequested = true
    }

    private fun receiveLoop() {
        val buf = ByteArray(Protocol.MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        val view = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        while (running) {
            packet.setData(buf)
            try {
                socket.receive(packet)
            } catch (e: SocketException) {
                break
            }
            val len = packet.length
            bytesReceived += len
            when (Protocol.parseType(buf, len)) {
                Protocol.TYPE_CONFIG -> {
                    if (len < Protocol.HEADER_SIZE + 7) continue
                    val from = packet.socketAddress as InetSocketAddress
                    val rank = rankOf(from.address)
                    // Stay on the current link unless the PC answered over a better one (USB > Ethernet > Wi-Fi).
                    if (from != pcAddress && rank <= linkRank) continue
                    if (from != pcAddress) Log.i(TAG, "PC at ${from.address.hostAddress} via ${LINK_NAMES.getOrElse(rank) { "?" }}")
                    pcAddress = from
                    linkRank = rank
                    lastFromPcMs = SystemClock.elapsedRealtime()
                    view.position(Protocol.HEADER_SIZE)
                    val codec = view.get().toInt() and 0xff
                    val width = view.getShort().toInt() and 0xffff
                    val height = view.getShort().toInt() and 0xffff
                    val fps = view.getShort().toInt() and 0xffff
                    listener.onConfig(StreamConfig(codec, width, height, fps))
                }
                Protocol.TYPE_VIDEO -> {
                    if (len < Protocol.VIDEO_HEADER_SIZE) continue
                    lastFromPcMs = SystemClock.elapsedRealtime()
                    view.position(Protocol.HEADER_SIZE)
                    val index = view.getInt().toLong() and 0xffffffffL
                    val fragment = view.getShort().toInt() and 0xffff
                    val count = view.getShort().toInt() and 0xffff
                    val poseTs = view.getLong()
                    val keyframe = (view.get().toInt() and 1) != 0
                    val frame = assembler.add(
                        index, fragment, count, poseTs, keyframe,
                        buf, Protocol.VIDEO_HEADER_SIZE, len - Protocol.VIDEO_HEADER_SIZE,
                    )
                    if (frame != null) listener.onFrame(frame)
                    if (assembler.needsKeyframe || keyframeRequested) maybeRequestIdr()
                }
            }
        }
    }

    private fun maybeRequestIdr() {
        val target = pcAddress ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastIdrRequestMs < 200) return
        lastIdrRequestMs = now
        keyframeRequested = false
        val msg = Protocol.allocate(0)
        Protocol.putHeader(msg, Protocol.TYPE_IDR_REQUEST)
        trySend(DatagramPacket(msg.array(), msg.capacity(), target))
    }

    private fun helloLoop() {
        val msg = Protocol.allocate(9)
        Protocol.putHeader(msg, Protocol.TYPE_HELLO)
        msg.putShort(hello.width.toShort()).putShort(hello.height.toShort())
            .putFloat(hello.refreshHz).put(hello.codecMask.toByte())
        while (running) {
            if (pcAddress != null && SystemClock.elapsedRealtime() - lastFromPcMs > 3000) {
                Log.i(TAG, "PC silent for 3 s, searching again")
                pcAddress = null
                linkRank = -1
            }
            for (addr in helloTargets()) {
                trySend(DatagramPacket(msg.array(), msg.capacity(), addr, Protocol.PC_PORT))
            }
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /**
     * Before connecting, HELLO goes to the manual host or to the broadcast address of every
     * interface: 255.255.255.255 would only leave through the default route, which isn't the USB
     * link when tethering. Once connected it goes to the PC as a keepalive, plus to the broadcast
     * address of any better link, so plugging in the cable later moves the stream onto it.
     */
    private fun helloTargets(): List<InetAddress> {
        if (!manualHost.isNullOrBlank()) {
            return runCatching { listOf(InetAddress.getByName(manualHost)) }.getOrDefault(emptyList())
        }
        val targets = mutableListOf<InetAddress>()
        pcAddress?.let { targets += it.address }
        for (link in localLinks()) {
            if (link.rank > linkRank) link.address.broadcast?.let(targets::add)
        }
        return targets
    }

    private class Link(val address: InterfaceAddress, val rank: Int)

    private fun localLinks(): List<Link> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                val rank = when {
                    USB_PREFIXES.any(nif.name::startsWith) -> LINK_USB
                    nif.name.startsWith("eth") -> LINK_ETHERNET
                    else -> LINK_WIFI
                }
                nif.interfaceAddresses.filter { it.address is Inet4Address }.map { Link(it, rank) }
            }
    }.getOrDefault(emptyList())

    /** Rank of the local link whose subnet contains [remote]. */
    private fun rankOf(remote: InetAddress): Int {
        val r = ipv4(remote) ?: return LINK_WIFI
        return localLinks().filter { link ->
            val mask = if (link.address.networkPrefixLength.toInt() == 0) 0 else -1 shl (32 - link.address.networkPrefixLength)
            val local = ipv4(link.address.address) ?: return@filter false
            (local and mask) == (r and mask)
        }.maxOfOrNull { it.rank } ?: LINK_WIFI
    }

    private fun ipv4(a: InetAddress): Int? =
        (a as? Inet4Address)?.address?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) }

    private fun trySend(packet: DatagramPacket) {
        try {
            socket.send(packet)
        } catch (e: Exception) {
            if (running) Log.w(TAG, "send failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "VReal.Net"
        const val LINK_WIFI = 0
        const val LINK_ETHERNET = 1
        const val LINK_USB = 2
        val LINK_NAMES = listOf("Wi-Fi", "Ethernet", "USB")
        // USB tethering interface names: rndis0 (older), ncm0 (newer), usb0 (some vendors).
        val USB_PREFIXES = listOf("rndis", "ncm", "usb")
    }
}

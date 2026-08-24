package app.jingqi.guard.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import app.jingqi.guard.MainActivity
import app.jingqi.guard.R
import app.jingqi.guard.data.AppState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var queryExecutor: ExecutorService? = null
    private val active = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppState.setDesiredEnabled(false)
            stopFiltering()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        AppState.setDesiredEnabled(true)
        if (active.compareAndSet(false, true)) startFiltering()
        return START_STICKY
    }

    override fun onDestroy() {
        stopFiltering()
        super.onDestroy()
    }

    override fun onRevoke() {
        AppState.setDesiredEnabled(false)
        stopFiltering()
        stopSelf()
    }

    private fun startFiltering() {
        val builder = Builder()
            .setSession("净启")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)
            .setBlocking(true)
        AppState.bypassPackages().forEach { packageName ->
            runCatching { builder.addDisallowedApplication(packageName) }
        }
        tunnel = builder.establish()
        if (tunnel == null) {
            active.set(false)
            AppState.setDesiredEnabled(false)
            AppState.setRunning(false)
            stopSelf()
            return
        }
        AppState.setRunning(true)
        queryExecutor = ThreadPoolExecutor(
            DNS_WORKERS,
            DNS_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(DNS_QUEUE_CAPACITY),
            ThreadFactory { runnable -> Thread(runnable, "JingQiDnsQuery").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
        worker = Thread(::packetLoop, "DnsFilter").also { it.start() }
    }

    private fun packetLoop() {
        val descriptor = tunnel?.fileDescriptor ?: return
        val input = FileInputStream(descriptor)
        val output = FileOutputStream(descriptor)
        val blocker = DomainBlocker(this)
        val buffer = ByteArray(32767)
        try {
            while (active.get()) {
                val length = input.read(buffer)
                if (length <= 0) continue
                val queryPacket = buffer.copyOf(length)
                val query = DnsPacket.parseQuery(queryPacket, length) ?: continue
                val executor = queryExecutor
                if (executor == null) {
                    writeAnswer(output, DnsPacket.servfail(queryPacket, length))
                    continue
                }
                try {
                    executor.execute {
                        val answer = runCatching {
                            if (blocker.blocks(query.domain)) {
                                AppState.recordBlocked(query.domain)
                                DnsPacket.nxdomain(queryPacket, length)
                            } else {
                                forward(query.dnsPayload, currentUpstream())?.let {
                                    DnsPacket.responsePacket(queryPacket, length, it)
                                } ?: DnsPacket.servfail(queryPacket, length)
                            }
                        }.getOrElse {
                            DnsPacket.servfail(queryPacket, length)
                        }
                        writeAnswer(output, answer)
                    }
                } catch (_: RejectedExecutionException) {
                    writeAnswer(output, DnsPacket.servfail(queryPacket, length))
                }
            }
        } catch (_: Exception) {
            // Closing the tunnel interrupts the blocking read during normal shutdown.
        } finally {
            val failedWhileActive = active.getAndSet(false)
            AppState.setRunning(false)
            if (failedWhileActive) stopSelf()
        }
    }

    private fun writeAnswer(output: FileOutputStream, answer: ByteArray?) {
        if (answer == null || !active.get()) return
        synchronized(output) {
            if (active.get()) runCatching { output.write(answer) }
        }
    }

    private fun forward(payload: ByteArray, upstream: UpstreamPlan): ByteArray? {
        upstream.servers.forEach { server ->
            val udpResponse = udpQuery(payload, upstream.network, server) ?: return@forEach
            if (!isTruncated(udpResponse)) return udpResponse
            tcpQuery(payload, upstream.network, server)?.let { return it }
        }
        return null
    }

    private fun udpQuery(payload: ByteArray, network: Network?, server: InetAddress): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                if (!protect(socket)) return null
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(server, DNS_PORT))
                socket.soTimeout = UDP_TIMEOUT_MILLIS
                socket.send(DatagramPacket(payload, payload.size))
                val response = ByteArray(MAX_DNS_MESSAGE_SIZE)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                response.copyOf(packet.length).takeIf { isValidResponse(payload, it) }
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun tcpQuery(payload: ByteArray, network: Network?, server: InetAddress): ByteArray? {
        if (payload.size > 0xffff) return null
        return try {
            Socket().use { socket ->
                if (!protect(socket)) return null
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(server, DNS_PORT), TCP_CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = TCP_READ_TIMEOUT_MILLIS
                DataOutputStream(socket.getOutputStream()).use { output ->
                    output.writeShort(payload.size)
                    output.write(payload)
                    output.flush()
                    val input = DataInputStream(socket.getInputStream())
                    val length = input.readUnsignedShort()
                    if (length < DNS_HEADER_SIZE) return null
                    val response = ByteArray(length)
                    input.readFully(response)
                    response.takeIf { isValidResponse(payload, it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidResponse(query: ByteArray, response: ByteArray): Boolean =
        query.size >= DNS_HEADER_SIZE &&
            response.size >= DNS_HEADER_SIZE &&
            query[0] == response[0] &&
            query[1] == response[1] &&
            response[2].toInt() and 0x80 != 0

    private fun isTruncated(response: ByteArray): Boolean =
        response.size >= DNS_HEADER_SIZE && response[2].toInt() and 0x02 != 0

    private fun currentUpstream(): UpstreamPlan {
        val manager = getSystemService(ConnectivityManager::class.java)
        val activeNetwork = manager.activeNetwork
        val candidate = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) return@mapNotNull null
            val servers = manager.getLinkProperties(network)?.dnsServers.orEmpty().distinct()
            val score = when {
                network == activeNetwork -> 1_000
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 300
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 250
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 200
                else -> 100
            } + if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 500 else 0
            Triple(network, servers, score)
        }.maxByOrNull { it.third }

        val fallback = FALLBACK_DNS.mapNotNull { address ->
            runCatching { InetAddress.getByName(address) }.getOrNull()
        }
        return if (candidate == null) {
            UpstreamPlan(null, fallback)
        } else {
            UpstreamPlan(candidate.first, candidate.second.ifEmpty { fallback })
        }
    }

    private fun stopFiltering() {
        active.set(false)
        runCatching { tunnel?.close() }
        tunnel = null
        worker?.interrupt()
        worker = null
        queryExecutor?.shutdownNow()
        queryExecutor = null
        AppState.setRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("广告过滤正在运行")
        .setContentText("仅在本机过滤已知广告域名")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .addAction(
            0, "停止",
            PendingIntent.getService(
                this, 1, Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        ).build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.vpn_channel_description)
            }
        )
    }

    companion object {
        const val ACTION_STOP = "app.jingqi.guard.STOP"
        private const val DNS_PORT = 53
        private const val DNS_HEADER_SIZE = 12
        private const val DNS_WORKERS = 12
        private const val DNS_QUEUE_CAPACITY = 256
        private const val UDP_TIMEOUT_MILLIS = 800
        private const val TCP_CONNECT_TIMEOUT_MILLIS = 1_000
        private const val TCP_READ_TIMEOUT_MILLIS = 1_500
        private const val MAX_DNS_MESSAGE_SIZE = 65_507
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val CHANNEL_ID = "dns_filter"
        private const val NOTIFICATION_ID = 1001
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    }

    private data class UpstreamPlan(
        val network: Network?,
        val servers: List<InetAddress>
    )
}

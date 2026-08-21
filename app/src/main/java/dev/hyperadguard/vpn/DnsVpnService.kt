package dev.hyperadguard.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.hyperadguard.MainActivity
import dev.hyperadguard.R
import dev.hyperadguard.data.AppState
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
        val upstream = currentDnsServer()
        val builder = Builder()
            .setSession("澎湃净广")
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
        queryExecutor = Executors.newFixedThreadPool(4)
        worker = Thread({ packetLoop(upstream) }, "DnsFilter").also { it.start() }
    }

    private fun packetLoop(upstream: InetAddress) {
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
                queryExecutor?.execute {
                    val answer = if (blocker.blocks(query.domain)) {
                        AppState.recordBlocked(query.domain)
                        DnsPacket.nxdomain(queryPacket, length)
                    } else {
                        forward(query.dnsPayload, upstream)?.let {
                            DnsPacket.responsePacket(queryPacket, length, it)
                        }
                    }
                    if (answer != null && active.get()) synchronized(output) { output.write(answer) }
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

    private fun forward(payload: ByteArray, upstream: InetAddress): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                protect(socket)
                socket.soTimeout = 2500
                socket.send(DatagramPacket(payload, payload.size, upstream, 53))
                val response = ByteArray(4096)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                response.copyOf(packet.length)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun currentDnsServer(): InetAddress {
        val manager = getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork
        val dns = network?.let { manager.getLinkProperties(it)?.dnsServers }
        return dns?.firstOrNull() ?: InetAddress.getByName("1.1.1.1")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.vpn_channel_description)
                }
            )
        }
    }

    companion object {
        const val ACTION_STOP = "dev.hyperadguard.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val CHANNEL_ID = "dns_filter"
        private const val NOTIFICATION_ID = 1001
    }
}

package dev.hyperadguard.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DnsQuery(val domain: String, val dnsPayload: ByteArray)

object DnsPacket {
    fun parseQuery(packet: ByteArray, length: Int): DnsQuery? {
        if (length < 40 || (packet[0].toInt() ushr 4) != 4) return null
        val ihl = (packet[0].toInt() and 0x0f) * 4
        if (ihl < 20 || length < ihl + 20 || packet[9].toInt() and 0xff != 17) return null
        val destinationPort = u16(packet, ihl + 2)
        if (destinationPort != 53) return null
        val udpLength = u16(packet, ihl + 4)
        val dnsOffset = ihl + 8
        val dnsLength = minOf(udpLength - 8, length - dnsOffset)
        if (dnsLength < 17) return null
        val labels = ArrayList<String>()
        var pos = dnsOffset + 12
        while (pos < dnsOffset + dnsLength) {
            val size = packet[pos].toInt() and 0xff
            if (size == 0) break
            if (size > 63 || pos + 1 + size > dnsOffset + dnsLength) return null
            labels += packet.copyOfRange(pos + 1, pos + 1 + size).toString(Charsets.UTF_8)
            pos += size + 1
        }
        if (labels.isEmpty()) return null
        return DnsQuery(labels.joinToString("."), packet.copyOfRange(dnsOffset, dnsOffset + dnsLength))
    }

    fun nxdomain(query: ByteArray, queryLength: Int): ByteArray? {
        val parsed = parseQuery(query, queryLength) ?: return null
        val dns = parsed.dnsPayload.copyOf()
        dns[2] = (dns[2].toInt() or 0x80).toByte()
        dns[3] = ((dns[3].toInt() and 0xf0) or 0x03).toByte()
        for (i in 6..11) dns[i] = 0
        return responsePacket(query, queryLength, dns)
    }

    fun responsePacket(query: ByteArray, queryLength: Int, dns: ByteArray): ByteArray? {
        if (queryLength < 28 || dns.size > 65507) return null
        val ihl = (query[0].toInt() and 0x0f) * 4
        val totalLength = ihl + 8 + dns.size
        val out = ByteArray(totalLength)
        System.arraycopy(query, 0, out, 0, ihl + 8)
        for (i in 12..15) {
            out[i] = query[i + 4]
            out[i + 4] = query[i]
        }
        out[ihl] = query[ihl + 2]
        out[ihl + 1] = query[ihl + 3]
        out[ihl + 2] = query[ihl]
        out[ihl + 3] = query[ihl + 1]
        put16(out, 2, totalLength)
        put16(out, ihl + 4, dns.size + 8)
        put16(out, 10, 0)
        put16(out, ihl + 6, 0)
        System.arraycopy(dns, 0, out, ihl + 8, dns.size)
        put16(out, 10, checksum(out, 0, ihl))
        put16(out, ihl + 6, udpChecksum(out, ihl, dns.size + 8))
        return out
    }

    private fun udpChecksum(packet: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L
        fun add(value: Int) { sum += value.toLong() and 0xffff }
        for (i in 12 until 20 step 2) add(u16(packet, i))
        add(17)
        add(udpLength)
        var i = udpOffset
        while (i + 1 < udpOffset + udpLength) {
            add(u16(packet, i)); i += 2
        }
        if (i < udpOffset + udpLength) add((packet[i].toInt() and 0xff) shl 8)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val result = sum.inv().toInt() and 0xffff
        return if (result == 0) 0xffff else result
    }

    private fun checksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i + 1 < offset + length) {
            sum += u16(bytes, i).toLong(); i += 2
        }
        if (i < offset + length) sum += ((bytes[i].toInt() and 0xff) shl 8).toLong()
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }
}

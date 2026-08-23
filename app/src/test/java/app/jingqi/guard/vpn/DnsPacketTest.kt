package app.jingqi.guard.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsPacketTest {
    @Test
    fun parsesDnsQuestion() {
        val packet = queryPacket("ads.example.com")
        val result = DnsPacket.parseQuery(packet, packet.size)
        assertNotNull(result)
        assertEquals("ads.example.com", result!!.domain)
    }

    @Test
    fun createsNxdomainWithSwappedEndpoints() {
        val packet = queryPacket("ads.example.com")
        val response = DnsPacket.nxdomain(packet, packet.size)
        assertNotNull(response)
        response!!
        assertEquals(10, response[12].toInt() and 0xff)
        assertEquals(111, response[13].toInt() and 0xff)
        assertEquals(222, response[14].toInt() and 0xff)
        assertEquals(2, response[15].toInt() and 0xff)
        val dnsOffset = 28
        assertEquals(0x80, response[dnsOffset + 2].toInt() and 0x80)
        assertEquals(3, response[dnsOffset + 3].toInt() and 0x0f)
    }

    @Test
    fun ignoresNonDnsTraffic() {
        val packet = queryPacket("example.com")
        packet[22] = 0
        packet[23] = 80
        assertNull(DnsPacket.parseQuery(packet, packet.size))
    }

    private fun queryPacket(domain: String): ByteArray {
        val labels = domain.split('.').flatMap { label ->
            listOf(label.length.toByte()) + label.encodeToByteArray().toList()
        } + 0.toByte()
        val dns = byteArrayOf(
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        ) + labels + byteArrayOf(0x00, 0x01, 0x00, 0x01)
        val packet = ByteArray(28 + dns.size)
        packet[0] = 0x45
        packet[2] = ((packet.size ushr 8) and 0xff).toByte()
        packet[3] = packet.size.toByte()
        packet[8] = 64
        packet[9] = 17
        packet[12] = 10; packet[13] = 0; packet[14] = 0; packet[15] = 2
        packet[16] = 10; packet[17] = 111; packet[18] = 222.toByte(); packet[19] = 2
        packet[20] = 0x30; packet[21] = 0x39
        packet[22] = 0; packet[23] = 53
        val udpLength = dns.size + 8
        packet[24] = (udpLength ushr 8).toByte(); packet[25] = udpLength.toByte()
        System.arraycopy(dns, 0, packet, 28, dns.size)
        return packet
    }
}

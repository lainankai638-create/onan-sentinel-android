package cn.onan.sentinel

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EvidenceTest {
    private val header = "IPProto,SrcIP,SrcPort,DstIp,DstPort,UID,App,PackageName,Proto,Status,Info,BytesSent,BytesRcvd,PktsSent,PktsRcvd,FirstSeen,LastSeen"
    private fun row(pkg: String, uid: Int) =
        "6,10.0.0.2,1234,203.0.113.1,443,$uid,Test,$pkg,TLS,Closed,example.test,123,456,1,2,2026-11-12T20:14:40.000+08:00,2026-11-12T20:14:41.000+08:00"

    @Test fun csvFiltersPackageAndUidWithConservativeAttribution() {
        val data = listOf(header, row(SECURITY, 1234), row(GUARD, 1234), row(GUARD, 1235))
        val results = mutableListOf<Evidence>()
        assertEquals(2, readConnections(data.joinToString("\n").byteInputStream(), SECURITY, 1234, "test", results::add))
        assertTrue(results.all { it.grade == "推测" })
        assertEquals(123L, results.first().data.getLong("上传字节"))
        assertEquals(456L, results.first().data.getLong("下载字节"))
        assertEquals("example.test", results.first().data.getString("观察到的主机名"))
    }

    @Test fun csvRejectsChangedHeader() {
        assertThrows(IllegalArgumentException::class.java) {
            readConnections("IPProto,Wrong".byteInputStream(), SECURITY, 1234, "test") { fail() }
        }
    }

    private fun packet(): ByteArray {
        val ip = ByteArray(28)
        ip[0] = 0x45
        ip[3] = 28
        ip[9] = 17
        byteArrayOf(10, 0, 0, 2).copyInto(ip, 12)
        byteArrayOf(8, 8, 8, 8).copyInto(ip, 16)
        ip[20] = 4
        ip[21] = -46
        ip[23] = 53
        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putLong(0).putInt(65535).putInt(101)
        val record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(1_799_000_000).putInt(123_000).putInt(ip.size).putInt(ip.size)
        out.write(header.array())
        out.write(record.array())
        out.write(ip)
        return out.toByteArray()
    }

    @Test fun classicPcapReportsEndpointsButNotDirectionOrOwnership() {
        val results = mutableListOf<Evidence>()
        assertEquals(1, readPcap(packet().inputStream(), GUARD, "test", results::add))
        val row = results.single()
        assertEquals("8.8.8.8", row.data.getString("目的IP"))
        assertEquals(53, row.data.getInt("目的端口"))
        assertEquals(28, row.data.getInt("帧字节"))
        assertFalse(row.data.has("上传字节"))
        assertEquals("推测", row.grade)
    }

    @Test fun truncatedPcapIsRejected() {
        val bytes = packet().dropLast(2).toByteArray()
        assertThrows(java.io.EOFException::class.java) {
            readPcap(bytes.inputStream(), GUARD, "test") { fail() }
        }
    }

    @Test fun reportHidesEndpointsAndUnrelatedPackageNames() {
        val rows = mutableListOf<Evidence>()
        readConnections("$header\n${row(GUARD, 1234)}".byteInputStream(), SECURITY, 1234, "test", rows::add)
        val redacted = makeReport(SECURITY, 0L, rows.single().time, rows, true, false)
        assertFalse(redacted.contains("203.0.113.1"))
        assertFalse(redacted.contains("example.test"))
        assertFalse(redacted.contains(GUARD))
        assertTrue(redacted.contains("地址-"))
        val json = makeReport(SECURITY, 0L, rows.single().time, rows, false, true)
        assertTrue(json.contains("203.0.113.1"))
    }
}

package cn.onan.sentinel

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.OffsetDateTime

private fun String.ipLiteral(): Boolean = if (contains(':')) {
    length <= 45 && all { it in "0123456789abcdefABCDEF:." } &&
        runCatching { InetAddress.getByName(this) }.isSuccess
} else split('.').let { parts ->
    parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }
}

fun readConnections(input: InputStream, target: String, uid: Int?, session: String,
                    add: (Evidence) -> Unit): Int {
    var accepted = 0
    input.bufferedReader().use { reader ->
        val header = reader.readLine()?.trimStart('\uFEFF')?.split(',') ?: error("CSV 没有表头")
        val normalized = header.map { it.lowercase().filter(Char::isLetterOrDigit) }
        require(normalized.take(17) == listOf("ipproto", "srcip", "srcport", "dstip", "dstport",
            "uid", "app", "packagename", "proto", "status", "info", "bytessent", "bytesrcvd",
            "pktssent", "pktsrcvd", "firstseen", "lastseen")) {
            "CSV 表头不兼容。请在 PCAPdroid 导出连接记录。"
        }
        var lineNumber = 1
        while (true) {
            val line = reader.readLine() ?: break
            lineNumber++
            require(lineNumber <= 20_001 && line.length <= 8192) { "CSV 超过导入上限" }
            if (line.isBlank()) continue
            val fields = line.split(',')
            require(fields.size == header.size) { "CSV 第 $lineNumber 行列数不一致，无法安全判断字段归属" }
            val sourceUid = fields[5].toIntOrNull()
            val reportedPackage = fields[7]
            if (reportedPackage != target && (uid == null || sourceUid != uid)) continue
            val remote = fields[3]
            val port = fields[4].toIntOrNull()
            val up = fields[11].toLongOrNull()
            val down = fields[12].toLongOrNull()
            require(remote.ipLiteral() && port in 0..65535 && up != null && up >= 0 && down != null && down >= 0) {
                "CSV 第 $lineNumber 行连接字段无效"
            }
            val time = OffsetDateTime.parse(fields[15]).toInstant().toEpochMilli()
            val last = OffsetDateTime.parse(fields[16]).toInstant().toEpochMilli()
            // PCAPdroid's Info field may contain a hostname, URL, or other metadata.
            val host = fields[10].takeIf {
                it.length <= 253 && it.contains('.') && it.all { c -> c in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.-_" }
            }
            val data = JSONObject().put("会话", session).put("远程IP", remote).put("端口", port)
                .put("协议", fields[0].take(12)).put("上层协议", fields[8].filter { it.isLetterOrDigit() }.take(24))
                .put("上传字节", up).put("下载字节", down).put("最后观测", last)
                .put("来源UID", sourceUid).put("来源包名", reportedPackage.takeIf(PACKAGE_NAME::matches))
                .put("归属", "按导出包名或当前 UID 筛选，历史 UID 与共享 UID 需复核")
            host?.let { data.put("观察到的主机名", it) }
            add(Evidence(time, target, "连接", "推测", "PCAPdroid CSV", data))
            accepted++
        }
    }
    return accepted
}

private data class Packet(val time: Long, val source: String, val destination: String,
                          val sourcePort: Int?, val destinationPort: Int?, val proto: Int, val length: Long)

private fun decodePacket(bytes: ByteArray, link: Int, time: Long, size: Long): Packet? {
    fun octet(i: Int) = bytes[i].toInt() and 255
    fun word(i: Int) = (octet(i) shl 8) or octet(i + 1)
    var ip = when (link) {
        1 -> 14
        113 -> 16
        276 -> 20
        else -> 0
    }
    if (bytes.size <= ip) return null
    var ether = when (link) {
        1 -> word(12)
        113 -> word(14)
        276 -> word(0)
        else -> null
    }
    while (ether == 0x8100 || ether == 0x88a8) {
        if (bytes.size <= ip + 4) return null
        ether = word(ip + 2)
        ip += 4
    }
    if (ether != null && ether != 0x0800 && ether != 0x86dd) return null
    val version = octet(ip) shr 4
    val base = when (version) { 4 -> 20; 6 -> 40; else -> return null }
    if (bytes.size < ip + base) return null
    val from = ip + if (version == 4) 12 else 8
    val width = if (version == 4) 4 else 16
    val source = InetAddress.getByAddress(bytes.copyOfRange(from, from + width)).hostAddress.orEmpty()
    val destination = InetAddress.getByAddress(bytes.copyOfRange(from + width, from + width * 2)).hostAddress.orEmpty()
    val header = if (version == 4) (octet(ip) and 15) * 4 else base
    if (header < base) return null
    val proto = octet(ip + if (version == 4) 9 else 6)
    val firstFragment = version == 6 || (word(ip + 6) and 0x1fff) == 0
    val offset = ip + header
    val ports = firstFragment && (proto == 6 || proto == 17) && bytes.size >= offset + 4
    return Packet(time, source, destination, if (ports) word(offset) else null,
        if (ports) word(offset + 2) else null, proto, size)
}

fun readPcap(input: InputStream, target: String, session: String, add: (Evidence) -> Unit): Int {
    data class Flow(val first: Packet, var last: Long = first.time, var count: Int = 0, var bytes: Long = 0)
    val flows = linkedMapOf<List<Any?>, Flow>()
    DataInputStream(BufferedInputStream(input)).use { reader ->
        val global = ByteArray(24).also(reader::readFully)
        val magic = ByteBuffer.wrap(global).int
        val order = when (magic) {
            0xa1b2c3d4.toInt(), 0xa1b23c4d.toInt() -> ByteOrder.BIG_ENDIAN
            0xd4c3b2a1.toInt(), 0x4d3cb2a1 -> ByteOrder.LITTLE_ENDIAN
            else -> error("仅支持经典 PCAP。PCAPNG 请使用 PCAPdroid 重导为 PCAP 或导出 CSV。")
        }
        val nano = magic == 0xa1b23c4d.toInt() || magic == 0x4d3cb2a1
        val header = ByteBuffer.wrap(global).order(order)
        require(header.getShort(4).toInt() == 2 && header.getShort(6).toInt() == 4) { "PCAP 版本不支持" }
        val link = header.getInt(20) and 65535
        require(link in setOf(1, 101, 113, 228, 229, 276)) { "PCAP 链路类型不支持：$link" }
        val record = ByteArray(16)
        var total = 24L
        var packets = 0
        while (true) {
            val first = reader.read()
            if (first < 0) break
            record[0] = first.toByte()
            reader.readFully(record, 1, 15)
            val fields = ByteBuffer.wrap(record).order(order)
            val seconds = fields.int.toLong() and 0xffffffffL
            val fraction = fields.int
            val captured = fields.int
            val original = fields.int.toLong() and 0xffffffffL
            require(fraction in 0 until (if (nano) 1_000_000_000 else 1_000_000) &&
                captured in 0..262_144 && captured <= original) { "无效的 PCAP 数据包头" }
            total += captured + 16
            require(total <= 64L * 1024 * 1024 && ++packets <= 250_000) { "PCAP 超过 64 MiB 或 25 万包导入上限" }
            val frame = ByteArray(captured).also(reader::readFully)
            val packet = decodePacket(frame, link, seconds * 1000 + fraction / if (nano) 1_000_000 else 1000, original)
                ?: continue
            val key = listOf(packet.source, packet.destination, packet.sourcePort, packet.destinationPort, packet.proto)
            val flow = flows.getOrPut(key) { Flow(packet) }
            require(flows.size <= 20_000) { "PCAP 超过 2 万条流导入上限" }
            flow.last = maxOf(flow.last, packet.time)
            flow.count++
            flow.bytes += packet.length
        }
    }
    for (flow in flows.values) {
        val p = flow.first
        val data = JSONObject().put("会话", session).put("源IP", p.source).put("目的IP", p.destination)
            .put("源端口", p.sourcePort).put("目的端口", p.destinationPort).put("协议", p.proto)
            .put("最后观测", flow.last).put("包数", flow.count).put("帧字节", flow.bytes)
            .put("归属", "仅按所选组件归档，包归属、远端与上下行方向无法获取")
        add(Evidence(p.time, target, "数据包流", "推测", "离线 PCAP", data))
    }
    return flows.size
}

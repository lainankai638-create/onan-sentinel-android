package cn.onan.sentinel

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun displayTime(time: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

fun signals(rows: List<Evidence>): List<String> {
    val sessions = rows.filter { it.kind == "连接" }
        .groupBy { it.data.optString("会话") }
    val results = mutableListOf<String>()
    for ((session, connections) in sessions) {
        val groups = connections.groupBy {
            it.data.optString("观察到的主机名").ifBlank { it.data.optString("远程IP") }
        }
        for (group in groups.values) {
            val times = group.map { it.time }.distinct().sorted()
            if (times.size < 4) continue
            val gaps = times.zipWithNext { a, b -> (b - a) / 1000.0 }.filter { it >= 1 }
            if (gaps.size != times.size - 1) continue
            val median = gaps.sorted()[gaps.size / 2]
            if (gaps.all { kotlin.math.abs(it - median) <= median * 0.2 }) {
                results += "推测｜会话 $session 内有 ${times.size} 次连接间隔约 ${median.toLong()} 秒，需更长采样确认周期。"
            }
        }
        val counts = connections.groupBy { it.time / HOUR }
            .mapValues { (_, items) -> items.sumOf { it.data.optLong("上传字节") } }
        val baseline = counts.values.sorted()
        if (baseline.size >= 4) {
            val median = baseline[baseline.size / 2]
            if (median > 0) counts.filterValues { it >= median * 3 && it >= 65_536 }.forEach { (hour, amount) ->
                results += "推测｜会话 $session 在 ${displayTime(hour * HOUR)} 所在小时中，按连接开始时间分组的整条连接上传量为 $amount B，达到该会话小时中位数的三倍。长连接可能跨小时。"
            }
        }
    }
    return results.distinct().take(30)
}

fun makeReport(target: String, start: Long, end: Long, records: List<Evidence>,
               hideEndpoints: Boolean, json: Boolean,
               currentIdentity: JSONObject = JSONObject()): String {
    val rows = records.take(2000)
    val aliases = linkedMapOf<String, String>()
    val endpointFields = setOf("远程IP", "源IP", "目的IP", "观察到的主机名")
    val data = rows.map { row ->
        val cleaned = JSONObject(row.data.toString())
        if (hideEndpoints) {
            for (field in endpointFields) if (cleaned.has(field)) {
                val value = cleaned.getString(field)
                cleaned.put(field, aliases.getOrPut(value) { "地址-${aliases.size + 1}" })
            }
            cleaned.remove("同UID可见包")
            if (cleaned.optString("来源包名") != target) cleaned.remove("来源包名")
        }
        JSONObject().put("时间", displayTime(row.time)).put("时间戳毫秒", row.time)
            .put("类别", row.kind).put("证据等级", row.grade).put("来源", row.source).put("数据", cleaned)
    }
    val limitations = listOf(
        "流量快照是当天累计统计，可能延迟；不能相加。共享 UID 时无法拆到单包。",
        "CSV 整条连接按首次观测时间筛选；字节数可能包括所选时间段外的流量。同一抓包重复导入会形成独立会话。",
        "普通 PCAP 端点与字节来自文件，组件归属、远端和上下行方向需要其他证据。",
        "进程快照只证明采样时看到了匹配进程。手动标记只证明用户记录了该时间点。",
        "端点、时间关联和流量大小均不足以判断上传内容、隐私泄露或反诈上报。"
    )
    val observations = signals(rows)
    val summaries = rows.filter { it.kind == "连接" }.groupBy { it.data.optString("会话") }
        .map { (session, connections) ->
            JSONObject().put("会话", session).put("连接条数", connections.size)
                .put("上传字节", connections.sumOf { it.data.optLong("上传字节") })
                .put("下载字节", connections.sumOf { it.data.optLong("下载字节") })
        }
    val questions = listOf("共享 UID 中是否能独立归属到目标包？", "重复安装、更新或扫描操作时，是否能复现相同的连接时序？")
    val total = if (records.size > rows.size) "仅包含最近 2000 条；请缩短时间段。" else "包含 ${rows.size} 条记录。"
    if (json) return JSONObject().put("名称", label(target)).put("包名", target)
        .put("当前组件信息", currentIdentity).put("开始", displayTime(start)).put("结束", displayTime(end)).put("端点脱敏", hideEndpoints)
        .put("记录范围", total).put("限制", JSONArray(limitations)).put("特征", JSONArray(observations))
        .put("CSV会话统计", JSONArray(summaries)).put("待核实", JSONArray(questions)).put("记录", JSONArray(data)).toString(2)
    return buildString {
        appendLine("# 欧南·系统网络哨兵｜证据报告")
        appendLine()
        appendLine("- 组件 ${label(target)} `$target`")
        if (currentIdentity.length() > 0) appendLine("- 生成时组件信息 $currentIdentity")
        appendLine("- 范围 ${displayTime(start)} 至 ${displayTime(end)}")
        appendLine("- 端点脱敏 ${if (hideEndpoints) "开启" else "关闭"}。$total")
        appendLine()
        appendLine("## 权限、统计与证据边界")
        limitations.forEach { appendLine("- $it") }
        appendLine()
        if (summaries.isNotEmpty()) {
            appendLine("## 所选时间段内的 CSV 会话统计")
            summaries.forEach { appendLine("- $it") }
            appendLine()
        }
        appendLine("## 周期性和流量峰值线索")
        (observations.ifEmpty { listOf("现有连接样本未满足线索判断阈值。") }).forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 待核实")
        questions.forEach { appendLine("- $it") }
        appendLine()
        appendLine("## 证据时间线")
        data.asReversed().forEach {
            appendLine("- ${it.getString("时间")}｜${it.getString("证据等级")}｜${it.getString("类别")}｜${it.getString("来源")}")
            appendLine("  - ${it.getJSONObject("数据")}")
        }
    }
}

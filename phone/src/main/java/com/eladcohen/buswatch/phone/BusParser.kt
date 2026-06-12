package com.eladcohen.buswatch.phone

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Turns the Nearby Bus (com.mosko.bus) accessibility tree into a [StopBoard].
 *
 * The app conveniently writes full content-descriptions, e.g. an arrival button:
 *   "קו 1, מסוף המעפילים, שדרות, , בעוד 12 דק׳, , זמן אמת,, , ובעוד33 דק׳"
 * and the stop card:
 *   "תחנה:  אדמונית החורש/הרב חיים פינטו, תחנה מס׳ 14158"
 */
object BusParser {
    private val lineRe = Regex("""^קו\s*([^,\s]+)""")
    // eta: "בעוד 12 דק" but NOT the ו-prefixed "ובעוד33" (the later departure)
    private val etaRe = Regex("""(?<!ו)בעוד\s*(\d+)\s*דק""")
    private val nextRe = Regex("""ובעוד\s*(\d+)""")
    private val codeRe = Regex("""מס׳\s*(\d+)""")

    fun parse(root: AccessibilityNodeInfo?): StopBoard? {
        if (root == null) return null
        val descs = ArrayList<String>()
        collect(root, descs)

        val header = descs.firstOrNull { it.startsWith("תחנה:") }
        val stopName = header
            ?.substringAfter("תחנה:")
            ?.substringBefore(",")
            ?.trim()
            ?: ""
        val stopCode = header?.let { codeRe.find(it)?.groupValues?.get(1) } ?: ""

        val arrivals = descs
            .filter { it.startsWith("קו") && it.contains("דק") }
            .distinct()
            .mapNotNull { parseArrival(it) }

        if (arrivals.isEmpty() && stopName.isEmpty()) return null
        return StopBoard(stopName = stopName, stopCode = stopCode, arrivals = arrivals)
    }

    private fun parseArrival(desc: String): Arrival? {
        val line = lineRe.find(desc)?.groupValues?.get(1) ?: return null
        val eta = etaRe.find(desc)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        val parts = desc.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val destination = parts.getOrNull(1)?.takeUnless { it.contains("דק") || it.contains("בעוד") } ?: ""
        val area = parts.getOrNull(2)?.takeUnless { it.contains("דק") || it.contains("בעוד") || it.contains("זמן") } ?: ""
        val realtime = desc.contains("זמן אמת")
        val next = nextRe.find(desc)?.groupValues?.get(1)?.toIntOrNull()
        return Arrival(
            line = line,
            destination = destination,
            area = area,
            etaMinutes = eta,
            realtime = realtime,
            nextMinutes = next,
        )
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out)
    }
}

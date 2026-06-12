package com.eladcohen.buswatch.data

import android.content.Context
import com.eladcohen.buswatch.model.BusMode
import com.eladcohen.buswatch.model.Stop

/** Remembers the last selected stop + mode so the app can paint instantly on launch. */
class StopStore(context: Context) {

    private val sp = context.getSharedPreferences("watchmirror", Context.MODE_PRIVATE)

    fun mode(): BusMode =
        if (sp.getString(K_MODE, null) == BusMode.FIXED.name) BusMode.FIXED else BusMode.NEARBY

    fun saveMode(mode: BusMode) {
        sp.edit().putString(K_MODE, mode.name).apply()
    }

    fun last(): Stop? {
        val code = sp.getInt(K_CODE, -1)
        if (code < 0) return null
        return Stop(
            code = code,
            lat = sp.getFloat(K_LAT, 0f).toDouble(),
            lon = sp.getFloat(K_LON, 0f).toDouble(),
            name = sp.getString(K_NAME, "").orEmpty(),
        )
    }

    fun save(stop: Stop) {
        sp.edit()
            .putInt(K_CODE, stop.code)
            .putFloat(K_LAT, stop.lat.toFloat())
            .putFloat(K_LON, stop.lon.toFloat())
            .putString(K_NAME, stop.name)
            .apply()
    }

    private companion object {
        const val K_CODE = "stop_code"
        const val K_LAT = "stop_lat"
        const val K_LON = "stop_lon"
        const val K_NAME = "stop_name"
        const val K_MODE = "mode"
    }
}

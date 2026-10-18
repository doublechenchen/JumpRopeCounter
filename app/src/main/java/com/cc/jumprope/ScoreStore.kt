package com.cc.jumprope

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 成绩存储：SharedPreferences + JSON。
 * 记录每次成绩；按「运动员 + 模式 + 目标档位」追踪历史最佳。
 */
object ScoreStore {

    data class Record(
        val name: String,
        val mode: String,       // "time" | "count"
        val target: Int,        // 计时模式=目标秒数；计数模式=目标个数
        val count: Int,
        val durationMs: Long,
        val ts: Long
    )

    private const val PREF = "jump_rope_scores"
    private const val KEY_RECORDS = "records"
    private const val KEY_RECORDS_BACKUP = "records_backup"
    private const val KEY_LAST_NAME = "last_name"

    // ---------------- 运动员 ----------------

    fun lastName(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_NAME, "") ?: ""

    fun saveLastName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_LAST_NAME, name).apply()
    }

    fun players(ctx: Context): List<String> =
        records(ctx).map { it.name }.distinct()

    // ---------------- 成绩 ----------------

    /** 读取记录数组；数据损坏时把原始内容备份到 KEY_RECORDS_BACKUP 并当作空表，避免崩溃或误清 */
    private fun readArray(ctx: Context): JSONArray {
        val json = prefs(ctx).getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            // 逐条校验字段，任何一条不合格都视为损坏
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                o.getString("name"); o.getString("mode")
                o.getInt("target"); o.getInt("count")
                o.getLong("durationMs"); o.getLong("ts")
            }
            arr
        } catch (e: Exception) {
            prefs(ctx).edit()
                .putString(KEY_RECORDS_BACKUP, json)
                .putString(KEY_RECORDS, "[]")
                .apply()
            JSONArray()
        }
    }

    fun records(ctx: Context): List<Record> {
        val arr = readArray(ctx)
        val list = ArrayList<Record>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                Record(
                    name = o.getString("name"),
                    mode = o.getString("mode"),
                    target = o.getInt("target"),
                    count = o.getInt("count"),
                    durationMs = o.getLong("durationMs"),
                    ts = o.getLong("ts")
                )
            )
        }
        return list
    }

    fun addRecord(ctx: Context, rec: Record) {
        val arr = readArray(ctx)
        arr.put(
            JSONObject()
                .put("name", rec.name)
                .put("mode", rec.mode)
                .put("target", rec.target)
                .put("count", rec.count)
                .put("durationMs", rec.durationMs)
                .put("ts", rec.ts)
        )
        prefs(ctx).edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    /** 同档位历史最佳：计时模式比个数（多者胜），计数模式比用时（短者胜） */
    fun best(ctx: Context, name: String, mode: String, target: Int): Record? {
        val same = records(ctx).filter {
            it.name == name && it.mode == mode && it.target == target
        }
        if (same.isEmpty()) return null
        return if (mode == "time") same.maxByOrNull { it.count }
        else same.minByOrNull { it.durationMs }
    }

    /** 判断 rec 是否刷新同档位最佳（与保存前的记录比较） */
    fun isNewBest(ctx: Context, rec: Record): Boolean {
        val prev = best(ctx, rec.name, rec.mode, rec.target) ?: return true
        return if (rec.mode == "time") rec.count > prev.count
        else rec.durationMs < prev.durationMs
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().putString(KEY_RECORDS, "[]").apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

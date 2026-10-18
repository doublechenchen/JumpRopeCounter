package com.cc.jumprope

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.cc.jumprope.databinding.ActivityRecordsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成绩记录页：各档位最佳成绩 + 全部历史记录（新的在前）。
 */
class RecordsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecordsBinding
    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClear.setOnClickListener { confirmClear() }
        render()
    }

    private fun render() {
        val container = binding.layoutRecords
        container.removeAllViews()
        val records = ScoreStore.records(this).sortedByDescending { it.ts }

        if (records.isEmpty()) {
            addLine(container, getString(R.string.records_empty), sizeSp = 16, dim = true)
            return
        }

        // ---- 各档位最佳 ----
        addLine(container, getString(R.string.records_best_header), sizeSp = 18, bold = true)
        val combos = records
            .groupBy { Triple(it.name, it.mode, it.target) }
        for ((key, list) in combos) {
            val (name, mode, target) = key
            val best = if (mode == CountActivity.MODE_TIME) list.maxByOrNull { it.count }
            else list.minByOrNull { it.durationMs }
            if (best != null) addLine(container, "★ " + formatRecord(best), sizeSp = 15)
        }

        // ---- 全部记录 ----
        addLine(container, getString(R.string.records_all_header), sizeSp = 18, bold = true, topMarginDp = 20)
        for (r in records) {
            addLine(container, formatRecord(r), sizeSp = 15)
        }
    }

    private fun formatRecord(r: ScoreStore.Record): String {
        val targetText = if (r.mode == CountActivity.MODE_TIME)
            "计时 ${r.target / 60} 分 ${r.target % 60} 秒".replace(" 0 秒", "")
        else
            "目标 ${r.target} 个"
        val scoreText = if (r.mode == CountActivity.MODE_TIME)
            "${r.count} 个"
        else
            "${r.count} 个 / ${formatDuration(r.durationMs)}"
        return "${r.name}｜$targetText｜$scoreText｜${dateFmt.format(Date(r.ts))}"
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.US, "%d 分 %02d 秒", totalSec / 60, totalSec % 60)
            .replace("0 分 ", "")
    }

    private fun addLine(
        container: LinearLayout,
        text: String,
        sizeSp: Int,
        bold: Boolean = false,
        dim: Boolean = false,
        topMarginDp: Int = 6
    ) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = sizeSp.toFloat()
        if (bold) tv.setTypeface(null, Typeface.BOLD)
        if (dim) tv.alpha = 0.6f
        tv.gravity = Gravity.START
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (topMarginDp * resources.displayMetrics.density).toInt()
        container.addView(tv, lp)
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_clear_records)
            .setMessage(R.string.confirm_clear_records)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                ScoreStore.clear(this)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

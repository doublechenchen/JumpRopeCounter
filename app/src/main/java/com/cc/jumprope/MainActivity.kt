package com.cc.jumprope

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.cc.jumprope.databinding.ActivityMainBinding

/**
 * 首页：输入/选择运动员名字，选择计数模式，查看成绩记录。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etName.setText(ScoreStore.lastName(this))
        binding.etName.doAfterTextChanged {
            ScoreStore.saveLastName(this, it?.toString()?.trim() ?: "")
        }

        binding.btnModeTime.setOnClickListener { openCount(CountActivity.MODE_TIME) }
        binding.btnModeCount.setOnClickListener { openCount(CountActivity.MODE_COUNT) }
        binding.btnRecords.setOnClickListener {
            startActivity(Intent(this, RecordsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新运动员下拉候选
        binding.etName.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, ScoreStore.players(this))
        )
    }

    private fun openCount(mode: String) {
        val name = binding.etName.text?.toString()?.trim().orEmpty().ifBlank { "运动员" }
        ScoreStore.saveLastName(this, name)
        startActivity(
            Intent(this, CountActivity::class.java)
                .putExtra(CountActivity.EXTRA_MODE, mode)
                .putExtra(CountActivity.EXTRA_PLAYER, name)
        )
    }
}

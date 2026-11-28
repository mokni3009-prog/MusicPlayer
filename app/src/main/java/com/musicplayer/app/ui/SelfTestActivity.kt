package com.musicplayer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.musicplayer.app.R
import com.musicplayer.app.databinding.ActivitySelfTestBinding
import kotlinx.coroutines.launch

// TEMPORARY — the self-test screen (see SelfTest.kt). Delete with it.

/**
 * Runs the self-test, shows the full report (what works / what doesn't /
 * what's wrong) and sends it automatically to the coach via the
 * crash-report server (type "self-test").
 */
class SelfTestActivity : FullScreenActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SelfTestActivity::class.java))
        }
    }

    private lateinit var binding: ActivitySelfTestBinding
    private var lastReport: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.backButton.setOnClickListener { finish() }
        binding.rerunButton.setOnClickListener { runTest() }
        binding.copyButton.setOnClickListener {
            val report = lastReport ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Zeno Play self-test", report))
            Toast.makeText(this, R.string.selftest_copied, Toast.LENGTH_SHORT).show()
        }
        runTest()
    }

    private fun runTest() {
        binding.statusText.text = getString(R.string.selftest_running)
        binding.statusText.visibility = View.VISIBLE
        binding.resultsContainer.removeAllViews()
        binding.summaryText.visibility = View.GONE
        binding.copyButton.visibility = View.GONE
        binding.rerunButton.visibility = View.GONE
        lifecycleScope.launch {
            val results = SelfTest.run(this@SelfTestActivity, this@SelfTestActivity) { step ->
                binding.statusText.text = getString(R.string.selftest_checking, step)
            }
            val report = SelfTest.formatReport(this@SelfTestActivity, results)
            lastReport = report
            binding.statusText.visibility = View.GONE
            val passed = results.count { it.status == SelfTest.Status.PASS }
            val failed = results.count { it.status == SelfTest.Status.FAIL }
            val skipped = results.count { it.status == SelfTest.Status.SKIP }
            binding.summaryText.text = getString(
                R.string.selftest_summary, passed, failed, skipped
            )
            binding.summaryText.visibility = View.VISIBLE
            for (r in results) {
                val row = TextView(this@SelfTestActivity).apply {
                    text = getString(
                        R.string.selftest_row,
                        r.status.name, r.name, r.detail
                    )
                    setTextColor(
                        when (r.status) {
                            SelfTest.Status.PASS -> Color.parseColor("#3DDC97")
                            SelfTest.Status.FAIL -> Color.parseColor("#FF6B6B")
                            SelfTest.Status.SKIP -> getColor(R.color.zeno_ink3)
                            SelfTest.Status.INFO -> getColor(R.color.zeno_ink2)
                        }
                    )
                    textSize = 14f
                    setPadding(0, 8, 0, 8)
                }
                binding.resultsContainer.addView(row)
            }
            binding.copyButton.visibility = View.VISIBLE
            binding.rerunButton.visibility = View.VISIBLE
            // Automatic send to the coach (same crash-report server).
            SelfTest.sendReport(report)
            binding.sentText.visibility = View.VISIBLE
        }
    }
}

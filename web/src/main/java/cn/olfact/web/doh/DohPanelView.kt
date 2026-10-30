package cn.olfact.web.doh

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cn.olfact.web.R

/**
 * DoH 状态面板。B2 只提供 View，放进哪个设置页由 B5 决定。
 *
 * 绑定方式：
 * ```
 * panel.bind(status) { manager.rerace() } { manager.clearCache() }
 * ```
 */
class DohPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val summary: TextView
    private val modeView: TextView
    private val upstreamView: TextView
    private val latencyView: TextView
    private val hitRateView: TextView
    private val todayView: TextView
    private val failuresView: TextView
    private val reraceButton: Button
    private val clearButton: Button

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_doh_panel, this, true)
        summary = findViewById(R.id.doh_status_summary)
        modeView = findViewById(R.id.doh_mode)
        upstreamView = findViewById(R.id.doh_upstream)
        latencyView = findViewById(R.id.doh_latency)
        hitRateView = findViewById(R.id.doh_hit_rate)
        todayView = findViewById(R.id.doh_today)
        failuresView = findViewById(R.id.doh_failures)
        reraceButton = findViewById(R.id.doh_btn_rerace)
        clearButton = findViewById(R.id.doh_btn_clear)
    }

    fun bind(
        status: DohStatus,
        onRerace: (() -> Unit)? = null,
        onClearCache: (() -> Unit)? = null
    ) {
        val context = context
        val runningText = context.getString(
            if (status.running) R.string.doh_panel_running else R.string.doh_panel_stopped
        )
        val channelText = context.getString(
            if (status.proxying) R.string.doh_panel_proxying else R.string.doh_panel_app_only
        )
        summary.text = if (status.consecutiveFailures > 0) {
            context.getString(R.string.doh_panel_circuit_open)
        } else {
            "$runningText · $channelText"
        }

        modeView.text = context.getString(R.string.doh_panel_mode) + "：" + status.mode.label
        upstreamView.text = context.getString(R.string.doh_panel_upstream) + "：" +
            (status.domesticUpstream ?: "-") + " / " + (status.foreignUpstream ?: "-")
        latencyView.text = context.getString(R.string.doh_panel_latency) + "：" +
            context.getString(R.string.doh_panel_value_latency, status.avgLatencyMs)
        hitRateView.text = context.getString(R.string.doh_panel_hit_rate) + "：" +
            context.getString(R.string.doh_panel_value_percent, (status.cacheHitRate * 100).toInt())
        todayView.text = context.getString(R.string.doh_panel_today) + "：" +
            context.getString(R.string.doh_panel_value_count, status.todayQueries.toInt()) +
            " · " + context.getString(R.string.doh_panel_rule_count, status.ruleCount)
        failuresView.text = context.getString(R.string.doh_panel_failures) + "：" +
            context.getString(R.string.doh_panel_value_count, status.failures.toInt())

        reraceButton.isEnabled = onRerace != null
        clearButton.isEnabled = onClearCache != null
        reraceButton.setOnClickListener { onRerace?.invoke() }
        clearButton.setOnClickListener { onClearCache?.invoke() }
    }
}

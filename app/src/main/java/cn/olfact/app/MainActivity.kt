package cn.olfact.app

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cn.olfact.core.R as CoreR
import cn.olfact.core.util.dp

/**
 * M0 占位页：证明壳能装、能起、主题生效。
 * B5 会把这里换成首页（宫格 + 最近访问）。
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
            setTextColor(getColor(CoreR.color.olfact_text_primary))
        }
        val subtitle = TextView(this).apply {
            text = getString(R.string.m0_placeholder, BuildConfig.VERSION_NAME)
            textSize = 14f
            setTextColor(getColor(CoreR.color.olfact_text_secondary))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
            setBackgroundColor(getColor(CoreR.color.olfact_background))
            addView(title)
            addView(subtitle, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) })
        }
        setContentView(root)
    }
}

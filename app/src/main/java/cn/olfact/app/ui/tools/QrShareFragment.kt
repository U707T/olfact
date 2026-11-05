package cn.olfact.app.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.olfact.app.R
import cn.olfact.app.databinding.FragmentPageBinding
import cn.olfact.app.nav.Nav
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/** 二维码分享：把当前页面地址生成二维码，可复制/分享。 */
class QrShareFragment : Fragment(R.layout.fragment_page) {

    private var binding: FragmentPageBinding? = null

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        val b = FragmentPageBinding.bind(view)
        binding = b
        b.header.title = "二维码分享"
        b.header.onBack = { Nav.pop() }

        val ctx = requireContext()
        val url = requireArguments().getString(ARG_URL).orEmpty().ifBlank {
            cn.olfact.app.data.BrowserSession.current()?.url.orEmpty()
        }
        val qr = runCatching { renderQr(url) }.getOrNull()

        b.content.gravity = Gravity.CENTER_HORIZONTAL
        b.content.setPadding(0, dp(24), 0, dp(24))
        if (qr == null) {
            // 地址过长（二维码容量上限）或为空时不要再摆一个空白方块。
            b.content.addView(TextView(ctx).apply {
                text = if (url.isBlank()) "当前没有可分享的地址" else "地址过长，无法生成二维码"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(12), dp(24), dp(12))
            })
        } else {
            b.content.addView(ImageView(ctx).apply {
                setImageBitmap(qr)
                layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
                contentDescription = "页面二维码"
                setBackgroundResource(cn.olfact.core.R.drawable.uikit_bg_card)
            })
        }
        b.content.addView(TextView(ctx).apply {
            text = url.ifBlank { "当前没有可分享的地址" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), 0)
        })

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }
        fun button(label: String, action: () -> Unit) = TextView(ctx).apply {
            text = label
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setBackgroundResource(R.drawable.bg_button_ghost)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { action() }
        }
        row.addView(button("复制链接") {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", url))
            Nav.toast("已复制")
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
        row.addView(button("分享") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }, "分享链接"))
        })
        b.content.addView(row)
    }

    private fun renderQr(text: String): Bitmap {
        if (text.isBlank()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val size = 640
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_URL = "url"
        fun newInstance(url: String) = QrShareFragment().apply {
            arguments = android.os.Bundle().apply { putString(ARG_URL, url) }
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}

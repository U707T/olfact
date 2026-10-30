package cn.olfact.app.ui.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import cn.olfact.app.R
import cn.olfact.app.databinding.ActivityScanBinding
import cn.olfact.app.nav.Nav
import cn.olfact.app.ui.common.Dialogs

/**
 * 扫码入口。
 *
 * 说明：解码需要相机预览 + ZXing/MLKit，属于可选组件；当前版本先提供权限申请、
 * 取景框与「手动输入」，保证入口链路完整，不假装能扫。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.manual.setOnClickListener {
            Dialogs.input(this, "打开地址", hint = "https://example.com") { text ->
                if (text.isNotBlank()) {
                    startActivity(Intent(this, cn.olfact.app.MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = android.net.Uri.parse(cn.olfact.app.link.SearchEngines.urlFor(text))
                    })
                    finish()
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        } else {
            binding.hint.text = "相机已授权 · 解码组件待接入"
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        binding.hint.text = if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            "相机已授权 · 解码组件待接入"
        } else {
            "未授权相机，可手动输入地址"
        }
        Toast.makeText(this, binding.hint.text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_CAMERA = 1001
    }
}

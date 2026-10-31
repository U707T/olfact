package cn.olfact.app.ui.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import cn.olfact.app.MainActivity
import cn.olfact.app.R
import cn.olfact.app.databinding.ActivityScanBinding
import cn.olfact.app.link.SearchEngines
import cn.olfact.app.ui.common.Dialogs
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * 扫一扫：CameraX 取景 + ZXing 解码。
 *
 * 解码只吃 Y 平面（亮度），一次分析一帧、命中即停；不引第三方扫码库，
 * 复用 APK 里已有的 `com.google.zxing:core`（二维码分享也在用）。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val executor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    /** 命中一次就够，避免连拍重复打开。 */
    @Volatile
    private var handled = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.DATA_MATRIX,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.PDF_417,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.CODE_39,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8,
                    BarcodeFormat.ITF,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.UPC_E
                ),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.manual.setOnClickListener {
            Dialogs.input(this, "打开地址", hint = "https://example.com") { text ->
                if (text.isNotBlank()) open(SearchEngines.urlFor(text))
            }
        }

        if (hasCamera()) {
            startCamera()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private fun hasCamera(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            binding.hint.text = "未授权相机，可手动输入地址"
            Toast.makeText(this, binding.hint.text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCamera() {
        binding.hint.text = "将二维码 / 条形码放入框内"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull() ?: run {
                binding.hint.text = "相机不可用，可手动输入地址"
                return@addListener
            }
            provider = cameraProvider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.preview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }

            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                binding.hint.text = "相机启动失败：${it.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        if (handled) {
            image.close()
            return
        }
        val text = runCatching { decode(image) }.getOrNull()
        image.close()
        if (text.isNullOrBlank()) return
        handled = true
        runOnUiThread { onDecoded(text) }
    }

    /** YUV_420_888 只取 Y 平面 → 灰度图 → 按旋转角摆正 → ZXing。 */
    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val data = ByteArray(width * height)
        val row = ByteArray(rowStride)
        buffer.rewind()
        var out = 0
        for (y in 0 until height) {
            val toRead = minOf(rowStride, buffer.remaining())
            if (toRead <= 0) break
            buffer.get(row, 0, toRead)
            var x = 0
            while (x < width) {
                data[out++] = row[x * pixelStride]
                x++
            }
        }

        val rotated = rotate(data, width, height, image.imageInfo.rotationDegrees)
        val source = PlanarYUVLuminanceSource(
            rotated.data, rotated.width, rotated.height,
            0, 0, rotated.width, rotated.height, false
        )
        return reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    }

    private data class Frame(val data: ByteArray, val width: Int, val height: Int)

    private fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Frame {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return Frame(data, width, height)
        return when (normalized) {
            90 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = data[y * width + x]
                }
                Frame(out, height, width)
            }
            180 -> {
                val out = ByteArray(data.size)
                for (y in 0 until height) for (x in 0 until width) {
                    out[(height - 1 - y) * width + (width - 1 - x)] = data[y * width + x]
                }
                Frame(out, width, height)
            }
            else -> { // 270
                val out = ByteArray(data.size)
                for (y in 0 until height) for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = data[y * width + x]
                }
                Frame(out, height, width)
            }
        }
    }

    private fun onDecoded(text: String) {
        if (isFinishing) return
        val trimmed = text.trim()
        val url = when {
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            trimmed.startsWith("www.", true) -> "https://$trimmed"
            else -> null
        }
        if (url != null) {
            open(url)
            return
        }
        // 非网址：给「搜索 / 复制」两个出口，不硬当成链接
        Dialogs.confirm(
            this,
            "扫到内容",
            trimmed.take(300),
            danger = false
        ) {
            open(SearchEngines.urlFor(trimmed))
        }
    }

    private fun open(url: String) {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse(url)
                }
            )
        }
        finish()
    }

    override fun onDestroy() {
        provider?.unbindAll()
        provider = null
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val REQ_CAMERA = 1001
    }
}

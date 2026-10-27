package com.virtualap.app.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodeGenerator {

    /**
     * Wi-Fi join payload per the de-facto `WIFI:` URI scheme scanners understand.
     * `type` is WPA for any WPA2/WPA3 mode, nopass for open. Hidden networks add
     * `H:true`. The reserved characters `\ ; , : "` are backslash-escaped.
     */
    fun wifiPayload(ssid: String, password: String, security: String, hidden: Boolean): String {
        val type = if (security == "open") "nopass" else "WPA"
        val s = escape(ssid)
        val p = if (security == "open") "" else escape(password)
        return buildString {
            append("WIFI:T:").append(type)
            append(";S:").append(s)
            append(";P:").append(p)
            if (hidden) append(";H:true")
            append(";;")
        }
    }

    private fun escape(v: String): String =
        v.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace(":", "\\:")
            .replace("\"", "\\\"")

    /** Encode [content] into a square black-on-white QR [ImageBitmap] of [sizePx]. */
    fun encode(content: String, sizePx: Int): ImageBitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) black else white }
        val bmp = createBitmap(w, h)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp.asImageBitmap()
    }
}

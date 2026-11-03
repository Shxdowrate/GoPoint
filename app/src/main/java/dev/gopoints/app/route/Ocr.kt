package dev.gopoints.app.route

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Офлайн-распознавание русского текста (Tesseract, модель из assets). */
object Ocr {
    private var api: TessBaseAPI? = null

    private fun ensure(ctx: Context): TessBaseAPI {
        api?.let { return it }
        val root = File(ctx.filesDir, "tesseract")
        val data = File(root, "tessdata/rus.traineddata")
        if (!data.exists() || data.length() < 1_000_000) {
            data.parentFile?.mkdirs()
            ctx.assets.open("tessdata/rus.traineddata").use { input -> data.outputStream().use { input.copyTo(it) } }
        }
        val t = TessBaseAPI()
        if (!t.init(root.absolutePath, "rus")) {
            t.recycle()
            error("Не удалось запустить распознавание")
        }
        api = t
        return t
    }

    suspend fun recognize(ctx: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        val bmp = load(ctx, uri) ?: error("Не удалось открыть фото")
        val pre = preprocess(bmp)
        synchronized(this@Ocr) {
            val t = ensure(ctx)
            t.setImage(pre)
            val text = t.getUTF8Text() ?: ""
            t.clear()
            text
        }
    }

    /** Открывает фото уменьшенным до ~3000 px и с учётом поворота камеры. */
    private fun load(ctx: Context, uri: Uri): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2600) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = try {
            cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (e: Exception) {
            0
        }
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /**
     * Серый цвет, выравнивание неровного света (вычитаем размытый фон), растяжка контраста.
     * Без этого фото под лампой или в кабине распознаётся заметно хуже.
     */
    private fun preprocess(src0: Bitmap): Bitmap {
        val maxSide = maxOf(src0.width, src0.height)
        val src = if (maxSide > 2600) {
            val k = 2600f / maxSide
            Bitmap.createScaledBitmap(src0, (src0.width * k).toInt(), (src0.height * k).toInt(), true)
        } else src0
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val small = Bitmap.createScaledBitmap(src, maxOf(1, w / 24), maxOf(1, h / 24), true)
        val bgBmp = Bitmap.createScaledBitmap(small, w, h, true)
        val bg = IntArray(w * h)
        bgBmp.getPixels(bg, 0, w, 0, 0, w, h)

        val out = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val g = gray(px[i])
            val b = gray(bg[i])
            val v = 255 - minOf(255, maxOf(0, b - g) * 2)
            out[i] = v
            hist[v]++
        }
        val n = out.size
        var lo = 0
        var acc = 0
        while (lo < 255 && acc + hist[lo] < n / 100) { acc += hist[lo]; lo++ }
        var hi = 255
        acc = 0
        while (hi > lo + 1 && acc + hist[hi] < n / 100) { acc += hist[hi]; hi-- }
        val span = maxOf(1, hi - lo)
        for (i in out.indices) {
            val v = ((out[i] - lo) * 255 / span).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun gray(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

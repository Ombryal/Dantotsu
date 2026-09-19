package ani.dantotsu.media.manga.mangareader

import android.graphics.Bitmap
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class RemoveBordersTransformation(private val white: Boolean, private val threshHold: Int) :
    BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        // Get the dimensions of the input bitmap
        val width = toTransform.width
        val height = toTransform.height

        // getPixel() is a JNI call *per pixel* - scanning a full-res page this way
        // (up to 4 full edge-to-edge passes below) was seriously slow and stalled
        // page loading any time crop-borders was turned on. One bulk getPixels()
        // read plus plain array indexing does the exact same scan for a fraction
        // of the cost.
        val pixels = IntArray(width * height)
        toTransform.getPixels(pixels, 0, width, 0, 0, width, height)

        // Find the non-white area by scanning from the edges
        var left = 0
        var top = 0
        var right = width - 1
        var bottom = height - 1

        // Scan from the left edge
        scanLeft@ for (x in 0 until width) {
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    left = x
                    break@scanLeft
                }
            }
        }

        // Scan from the right edge
        scanRight@ for (x in width - 1 downTo left) {
            for (y in 0 until height) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    right = x
                    break@scanRight
                }
            }
        }

        // Scan from the top edge
        scanTop@ for (y in 0 until height) {
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    top = y
                    break@scanTop
                }
            }
        }

        // Scan from the bottom edge
        scanBottom@ for (y in height - 1 downTo top) {
            for (x in 0 until width) {
                if (isPixelNotWhite(pixels[y * width + x])) {
                    bottom = y
                    break@scanBottom
                }
            }
        }

        // Crop the bitmap to the non-white area
        // Return the cropped bitmap
        return Bitmap.createBitmap(
            toTransform,
            left,
            top,
            right - left + 1,
            bottom - top + 1
        )
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(
            "RemoveBordersTransformation(${white}_$threshHold)".toByteArray()
        )
    }

    private fun isPixelNotWhite(pixel: Int): Boolean {
        val brightness = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        return if (white) brightness < (255 - threshHold) else brightness > threshHold
    }
}

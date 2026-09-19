package ani.dantotsu.media.manga.mangareader

import android.animation.ObjectAnimator
import android.content.res.Resources.getSystem
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChapterTransitionBinding
import ani.dantotsu.databinding.ItemImageBinding
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.settings.CurrentReaderSettings.Directions.LEFT_TO_RIGHT
import ani.dantotsu.settings.CurrentReaderSettings.Directions.RIGHT_TO_LEFT
import ani.dantotsu.settings.CurrentReaderSettings.Layouts.PAGED
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

open class ImageAdapter(
    activity: MangaReaderActivity,
    chapter: MangaChapter,
    nextChapter: MangaChapter? = null,
    prevChapter: MangaChapter? = null
) : BaseImageAdapter(activity, chapter) {

    init {
        buildInitialItems(chapter, nextChapter, prevChapter)
    }

    protected open fun buildInitialItems(chap: MangaChapter, nextChap: MangaChapter?, prevChap: MangaChapter? = null) {
        items.clear()
        if (hasTransition() && settings.layout != PAGED) {
            items.add(ReaderItem.Transition(chap, prevChap, isLoading = false, isPrevious = true))
        }
        val chapImages = if (settings.layout == PAGED && settings.direction == CurrentReaderSettings.Directions.BOTTOM_TO_TOP) {
            chap.images().reversed()
        } else {
            chap.images()
        }
        val totalPages = chapImages.size
        chapImages.forEachIndexed { index, image ->
            items.add(ReaderItem.Page(image, chap, index + 1, totalPages))
        }

        if (hasTransition()) {
            val isLoading = nextChap != null && nextChap.images().isEmpty()
            items.add(ReaderItem.Transition(chap, nextChap, isLoading = isLoading, isPrevious = false))
            if (nextChap != null && nextChap.images().isNotEmpty()) {
                val nextImages = nextChap.images()
                nextImages.forEachIndexed { index, image ->
                    items.add(ReaderItem.Page(image, nextChap, index + 1, nextImages.size))
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_TRANSITION) {
            val binding = ItemChapterTransitionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            TransitionViewHolder(binding)
        } else {
            val binding = ItemImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            ImageViewHolder(binding)
        }
    }

    inner class ImageViewHolder(binding: ItemImageBinding) : RecyclerView.ViewHolder(binding.root)

    open suspend fun loadBitmap(position: Int, parent: View): Bitmap? {
        val pageItem = items.getOrNull(position) as? ReaderItem.Page ?: return null
        val link = pageItem.image.url
        if (link.url.isEmpty()) return null

        val transforms = mutableListOf<BitmapTransformation>()
        val parserTransformation = activity.getTransformation(pageItem.image)

        if (parserTransformation != null) transforms.add(parserTransformation)
        if (settings.cropBorders) {
            transforms.add(RemoveBordersTransformation(true, settings.cropBorderThreshold))
            transforms.add(RemoveBordersTransformation(false, settings.cropBorderThreshold))
        }

        return activity.loadBitmap(link, transforms)
    }

    override suspend fun loadImage(position: Int, parent: View): Boolean {
        val imageView = parent.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
            ?: return false
        val progress = parent.findViewById<View>(R.id.imgProgProgress) ?: return false

        val previousBitmap = parent.getTag(R.id.imgProgImageNoGestures) as? Bitmap
        parent.setTag(R.id.imgProgImageNoGestures, null)
        imageView.recycle()
        imageView.visibility = View.GONE
        progress.visibility = View.VISIBLE
        if (previousBitmap != null && !previousBitmap.isRecycled) {
            previousBitmap.recycle()
        }

        var sWidth = getSystem().displayMetrics.widthPixels
        var sHeight = getSystem().displayMetrics.heightPixels

        // Continuous layout sizes each row to the page's own aspect ratio - a
        // downsampled placeholder has (almost exactly) the same ratio as the
        // real page, so sizing the row for it now avoids a stretched/squashed
        // placeholder instead of leaving whatever the previous page's row
        // height happened to be.
        fun applyAspectRatioSizing(bmp: Bitmap) {
            if (settings.layout != PAGED)
                parent.updateLayoutParams {
                    if (settings.direction != LEFT_TO_RIGHT && settings.direction != RIGHT_TO_LEFT) {
                        sHeight =
                            if (settings.wrapImages) bmp.height else (sWidth * bmp.height * 1f / bmp.width).toInt()
                        height = sHeight
                    } else {
                        sWidth =
                            if (settings.wrapImages) bmp.width else (sHeight * bmp.width * 1f / bmp.height).toInt()
                        width = sWidth
                    }
                }
        }

        // Paint something immediately if we can, instead of leaving a blank page
        // behind the spinner for however long the full decode takes. Best-effort
        // only - if this is slow or fails, we just fall through to the plain
        // spinner exactly like before this existed.
        val targetItem = items.getOrNull(position)
        val placeholderBitmap: Bitmap? = try {
            when (targetItem) {
                is ReaderItem.Page -> activity.loadPlaceholderBitmap(targetItem.image.url)
                is ReaderItem.DualPage -> {
                    val link2 = targetItem.second?.url
                    // Fetch both halves at once so a spread doesn't wait twice as
                    // long as a single page for its placeholder.
                    coroutineScope {
                        val p1Deferred = async { activity.loadPlaceholderBitmap(targetItem.first.url) }
                        val p2Deferred = link2?.let { async { activity.loadPlaceholderBitmap(it) } }
                        val p1 = p1Deferred.await()
                        val p2 = p2Deferred?.await()
                        when {
                            p1 == null -> {
                                if (p2 != null && !p2.isRecycled) p2.recycle()
                                null
                            }
                            p2 == null -> p1
                            else -> {
                                val merged = if (settings.direction != LEFT_TO_RIGHT)
                                    mergeBitmap(p2, p1)
                                else
                                    mergeBitmap(p1, p2)
                                if (merged !== p1 && !p1.isRecycled) p1.recycle()
                                if (merged !== p2 && !p2.isRecycled) p2.recycle()
                                merged
                            }
                        }
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
        if (placeholderBitmap != null) {
            if (!currentCoroutineContext().isActive) {
                if (!placeholderBitmap.isRecycled) placeholderBitmap.recycle()
                return false
            }
            parent.setTag(R.id.imgProgImageNoGestures, placeholderBitmap)
            applyAspectRatioSizing(placeholderBitmap)
            imageView.visibility = View.VISIBLE
            imageView.setImage(ImageSource.cachedBitmap(placeholderBitmap))
        }

        val decodeStartedAt = System.currentTimeMillis()
        var bitmap = loadBitmap(position, parent)
        if (bitmap == null) {
            delay(350)
            bitmap = loadBitmap(position, parent)
        }
        if (bitmap == null) {
            // Nothing to upgrade to - if we managed a placeholder, leaving it up
            // beats reverting to a blank page.
            return false
        }
        activity.recordPageDecodeTime(System.currentTimeMillis() - decodeStartedAt)

        if (!currentCoroutineContext().isActive) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return false
        }

        // Swap the placeholder out for the real thing - reset the view first so
        // we're not asking it to hold two images' worth of tiling state at once.
        if (placeholderBitmap != null) {
            imageView.recycle()
            if (!placeholderBitmap.isRecycled) placeholderBitmap.recycle()
        }
        parent.setTag(R.id.imgProgImageNoGestures, bitmap)

        applyAspectRatioSizing(bitmap)

        // Apply image quality scaling when the bitmap is larger than screen and quality mode requires it.
        // FAST: skip (SSIV's own GPU bilinear is fine). BALANCED/LANCZOS: pre-scale on IO thread.
        val quality = settings.imageQuality ?: CurrentReaderSettings.ImageQuality.FAST
        if (quality != CurrentReaderSettings.ImageQuality.FAST &&
            (bitmap.width > sWidth || bitmap.height > sHeight)
        ) {
            val targetW: Int
            val targetH: Int
            val bitmapRatio = bitmap.width.toFloat() / bitmap.height
            val screenRatio = sWidth.toFloat() / sHeight
            if (bitmapRatio > screenRatio) {
                targetW = sWidth
                targetH = (sWidth / bitmapRatio).toInt().coerceAtLeast(1)
            } else {
                targetH = sHeight
                targetW = (sHeight * bitmapRatio).toInt().coerceAtLeast(1)
            }
            val scaled = withContext(Dispatchers.IO) {
                scaleBitmap(bitmap, targetW, targetH, quality)
            }
            if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
            bitmap = scaled
            if (!currentCoroutineContext().isActive) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return false
            }
            parent.setTag(R.id.imgProgImageNoGestures, bitmap)
        }

        imageView.visibility = View.VISIBLE
        imageView.setImage(ImageSource.cachedBitmap(bitmap))

        val parentArea = sWidth * sHeight * 1f
        val bitmapArea = bitmap.width * bitmap.height * 1f
        val scale =
            if (parentArea < bitmapArea) (bitmapArea / parentArea) else (parentArea / bitmapArea)

        imageView.maxScale = scale * 1.1f
        imageView.minScale = scale

        ObjectAnimator.ofFloat(parent, "alpha", 0f, 1f)
            .setDuration((400 * PrefManager.getVal<Float>(PrefName.AnimationSpeed)).toLong())
            .start()
        progress.visibility = View.GONE

        return true
    }

    override fun appendChapter(nextChap: MangaChapter, afterNextChap: MangaChapter?) {
        val alreadyHas = items.any { it is ReaderItem.Page && it.chapter.uniqueNumber() == nextChap.uniqueNumber() }
        if (alreadyHas) return

        val newImages = nextChap.images()
        if (newImages.isEmpty()) return

        val transitionIndex = items.indexOfLast {
            it is ReaderItem.Transition && it.toChapter?.uniqueNumber() == nextChap.uniqueNumber()
        }

        if (transitionIndex != -1) {
            val trans = items[transitionIndex] as ReaderItem.Transition
            trans.isLoading = false
            notifyItemChanged(transitionIndex)

            val insertPos = transitionIndex + 1
            val newItems = mutableListOf<ReaderItem>()
            newImages.forEachIndexed { index, img ->
                newItems.add(ReaderItem.Page(img, nextChap, index + 1, newImages.size))
            }
            if (hasTransition()) {
                val nextLoading = afterNextChap != null && afterNextChap.images().isEmpty()
                newItems.add(ReaderItem.Transition(nextChap, afterNextChap, isLoading = nextLoading))
            }
            items.addAll(insertPos, newItems)
            notifyItemRangeInserted(insertPos, newItems.size)
        } else {
            val start = items.size
            val newItems = mutableListOf<ReaderItem>()
            if (hasTransition()) {
                val prevChap = (items.lastOrNull() as? ReaderItem.Page)?.chapter ?: initialChapter
                newItems.add(ReaderItem.Transition(prevChap, nextChap, isLoading = false))
            }
            newImages.forEachIndexed { index, img ->
                newItems.add(ReaderItem.Page(img, nextChap, index + 1, newImages.size))
            }
            if (hasTransition()) {
                val nextLoading = afterNextChap != null && afterNextChap.images().isEmpty()
                newItems.add(ReaderItem.Transition(nextChap, afterNextChap, isLoading = nextLoading))
            }
            items.addAll(newItems)
            notifyItemRangeInserted(start, newItems.size)
        }
    }

    override fun prependChapter(prevChap: MangaChapter, beforePrevChap: MangaChapter?): Int {
        val alreadyHas = items.any { it is ReaderItem.Page && it.chapter.uniqueNumber() == prevChap.uniqueNumber() }
        if (alreadyHas) return 0

        val newImages = prevChap.images()
        if (newImages.isEmpty()) return 0

        val newItems = mutableListOf<ReaderItem>()
        if (hasTransition()) {
            val prevLoading = beforePrevChap != null && beforePrevChap.images().isEmpty()
            newItems.add(ReaderItem.Transition(prevChap, beforePrevChap, isLoading = prevLoading, isPrevious = true))
        }
        val totalPages = newImages.size
        newImages.forEachIndexed { index, img ->
            newItems.add(ReaderItem.Page(img, prevChap, index + 1, totalPages))
        }

        val transitionIndex = items.indexOfFirst {
            it is ReaderItem.Transition && it.isPrevious && it.toChapter?.uniqueNumber() == prevChap.uniqueNumber()
        }

        return if (transitionIndex != -1) {
            val oldTrans = items[transitionIndex] as ReaderItem.Transition
            items[transitionIndex] = ReaderItem.Transition(prevChap, oldTrans.fromChapter, isLoading = false, isPrevious = false)
            items.addAll(transitionIndex, newItems)
            notifyItemRangeInserted(transitionIndex, newItems.size)
            notifyItemChanged(transitionIndex + newItems.size)
            newItems.size
        } else {
            if (hasTransition()) {
                val nextChap = (items.firstOrNull() as? ReaderItem.Page)?.chapter ?: initialChapter
                newItems.add(ReaderItem.Transition(prevChap, nextChap, isLoading = false, isPrevious = false))
            }
            items.addAll(0, newItems)
            notifyItemRangeInserted(0, newItems.size)
            newItems.size
        }
    }

    open fun hasTransition(): Boolean {
        return settings.layout != PAGED || settings.alwaysShowChapterTransition
    }

    override fun isZoomed(): Boolean {
        val imageView =
            activity.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
        return imageView.scale > imageView.minScale
    }

    override fun setZoom(zoom: Float) {
        val imageView =
            activity.findViewById<SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
        imageView.setScaleAndCenter(zoom, imageView.center)
    }
}

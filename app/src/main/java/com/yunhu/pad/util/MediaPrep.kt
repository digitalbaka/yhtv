package com.yunhu.pad.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 发送前的媒体文件准备：把 SAF 选中的 Uri 落到缓存目录，
 * 图片按最长边降采样压缩，视频读取宽高与时长（用于消息气泡展示）。
 */
object MediaPrep {

    private const val IMAGE_MAX_SIDE = 1920
    private const val IMAGE_QUALITY = 85

    data class ImageResult(val file: File, val width: Int, val height: Int)
    data class VideoMeta(val width: Int, val height: Int, val durationSec: Int)

    /** 查询 Uri 的显示名（用于给缓存文件命名） */
    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** 把 Uri 复制到指定目录，返回目标文件 */
    fun copyToDir(ctx: Context, uri: Uri, dir: File, fallbackName: String): File {
        dir.mkdirs()
        val name = displayName(ctx, uri).ifBlank { fallbackName }
        val dest = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw IOException("无法读取所选文件")
        return dest
    }

    /** 图片压缩：最长边限制到 1920，输出 JPEG；返回文件与真实宽高 */
    fun compressImage(src: File, dir: File): ImageResult {
        dir.mkdirs()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("图片解码失败")

        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= IMAGE_MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(src.absolutePath, opts)
            ?: throw IOException("图片解码失败")

        // 采样后可能仍超过上限，等比再缩放一次
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val scaled = if (maxSide > IMAGE_MAX_SIDE) {
            val ratio = IMAGE_MAX_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            ).also { if (it !== bitmap) bitmap.recycle() }
        } else bitmap

        // EXIF 纠正：相机拍摄图的方向信息只在源文件里，解码后必须按方向旋转，
        // 否则对方看到的图片是横倒的；旋转发生在降采样之后，开销可接受
        val degrees = exifDegrees(src)
        val upright = if (degrees == 0) scaled else rotate(scaled, degrees)

        val out = File(dir, "img_${System.currentTimeMillis()}.jpg")
        FileOutputStream(out).use { fos ->
            upright.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, fos)
        }
        val w = upright.width
        val h = upright.height
        upright.recycle()
        return ImageResult(out, w, h)
    }

    /** 读取 EXIF 方向，返回需要顺时针旋转的角度；无方向信息或读取失败返回 0 */
    private fun exifDegrees(src: File): Int = runCatching {
        when (ExifInterface(src.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(src: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    /** 读取视频宽高与时长（秒）；失败返回 0 */
    fun videoMeta(file: File): VideoMeta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            VideoMeta(w, h, (durMs / 1000).toInt())
        } catch (_: Exception) {
            VideoMeta(0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun clearDir(dir: File) {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}

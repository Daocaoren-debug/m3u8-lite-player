package com.m3u8.liteplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 轻量缩略图加载：
 * 1) 同目录同名封面图片
 * 2) m3u8 里的 EXT-X-THUMBNAIL / EXT-X-IMAGE-STREAM-INF 标签
 * 3) 第一个本地分片的首帧
 * 全部在后台线程执行，主线程回调，带内存缓存。
 */
object ThumbnailLoader {

    private const val HEAD_LIMIT = 32 * 1024
    private const val THUMB_WIDTH = 200
    private const val HTTP_TIMEOUT = 3000

    private val cache = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val thumbnailTag = Regex("URI=\"([^\"]+)\"")
    private val imageStreamTag = Regex("#EXT-X-IMAGE-STREAM-INF:[^\\r\\n]*")

    fun get(key: String): Bitmap? = cache.get(key)

    fun load(context: Context, file: MediaFile, callback: (Bitmap?) -> Unit) {
        val key = file.uri.toString()
        val cached = cache.get(key)
        if (cached != null) {
            mainHandler.post { callback(cached) }
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val bitmap = try {
                loadSync(appContext, file)
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post { callback(bitmap) }
        }
    }

    private fun loadSync(context: Context, file: MediaFile): Bitmap? {
        // 1) 同目录同名封面（仅 file:// 可直接访问兄弟文件）
        if (file.uri.scheme == "file") {
            val baseName = file.name.substringBeforeLast('.', file.name)
            val parent = try {
                File(file.path).parentFile
            } catch (e: Exception) {
                null
            }
            if (parent != null) {
                val names = listOf(
                    "$baseName.jpg", "$baseName.jpeg", "$baseName.png",
                    "$baseName.webp", "${file.name}.jpg", "${file.name}.png"
                )
                for (n in names) {
                    val cover = File(parent, n)
                    if (cover.exists()) {
                        decodeFile(cover.absolutePath)?.let { return it }
                    }
                }
            }
        }

        val head = readHead(context, file.uri) ?: return null

        // 2) HLS 缩略图标签
        val imageLine = imageStreamTag.find(head)?.value
            ?: head.lineSequence().firstOrNull { it.startsWith("#EXT-X-THUMBNAIL") }
        val uriRef = imageLine?.let { thumbnailTag.find(it)?.groupValues?.getOrNull(1) }
        if (!uriRef.isNullOrBlank()) {
            resolve(file.uri, uriRef)?.let { uri ->
                decodeUri(context, uri)?.let { return it }
            }
        }

        // 3) 第一个本地分片的首帧（远程分片不下载，避免低端机卡顿/耗流量）
        val firstSegment = head.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        val segUri = resolve(file.uri, firstSegment) ?: return null
        return firstFrame(context, segUri)
    }

    private fun readHead(context: Context, uri: Uri): String? {
        val input = try {
            if (uri.scheme == "file") {
                FileInputStream(File(uri.path ?: return null))
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            null
        } ?: return null

        return input.use { stream ->
            val buffer = ByteArray(HEAD_LIMIT)
            var total = 0
            while (total < HEAD_LIMIT) {
                val read = stream.read(buffer, total, HEAD_LIMIT - total)
                if (read <= 0) break
                total += read
            }
            String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    private fun resolve(base: Uri, ref: String): Uri? {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return Uri.parse(trimmed)
        }
        if (trimmed.startsWith("file://", true) || trimmed.startsWith("content://", true)) {
            return Uri.parse(trimmed)
        }
        return try {
            Uri.parse(java.net.URI.create(base.toString()).resolve(trimmed).toString())
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (uri.scheme == "http" || uri.scheme == "https") {
                val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                conn.connectTimeout = HTTP_TIMEOUT
                conn.readTimeout = HTTP_TIMEOUT
                conn.inputStream.use { BitmapFactory.decodeStream(it) }?.let { resize(it) }
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }?.let { resize(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFile(path: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
            }
            BitmapFactory.decodeFile(path, options)?.let { resize(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun firstFrame(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && uri.scheme == "content") return null
        val retriever = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path ?: return null)
            } else {
                retriever.setDataSource(context, uri)
            }
            val frame = try {
                retriever.getFrameAtTime(0L)
            } catch (e: Exception) {
                null
            }
            if (frame == null) return null
            return resize(frame)
        } catch (e: Exception) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var size = 1
        val max = THUMB_WIDTH * 2
        while (width / size > max || height / size > max) size *= 2
        return size
    }

    private fun resize(source: Bitmap): Bitmap {
        if (source.width <= THUMB_WIDTH) return source
        val height = source.height * THUMB_WIDTH / source.width
        val out = Bitmap.createScaledBitmap(source, THUMB_WIDTH, height, true)
        if (out !== source) source.recycle()
        return out
    }
}

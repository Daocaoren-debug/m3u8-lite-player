package com.m3u8.liteplayer

import android.os.Environment
import java.io.File

object Scanner {

    // 跳过系统/应用数据目录，避免无意义遍历，低端机上更快
    private val SKIP_DIRS = setOf(
        "Android", "data", "obb", "cache", "temp", "tmp", "LOST.DIR", ".git"
    )

    fun scanStorage(): List<MediaFile> {
        val result = ArrayList<MediaFile>()
        traverse(Environment.getExternalStorageDirectory(), result, 0)
        return result
    }

    private fun traverse(dir: File, out: MutableList<MediaFile>, depth: Int) {
        if (depth > 12) return
        val children = try {
            dir.listFiles()
        } catch (e: Exception) {
            null
        } ?: return

        for (file in children) {
            try {
                if (file.isDirectory) {
                    val n = file.name
                    if (n.startsWith(".") || n in SKIP_DIRS) continue
                    traverse(file, out, depth + 1)
                } else {
                    val lower = file.name.lowercase()
                    if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
                        out.add(
                            MediaFile(
                                name = file.name,
                                path = file.absolutePath,
                                size = file.length(),
                                uri = android.net.Uri.fromFile(file)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // 单个文件不可读时跳过，不影响整体扫描
            }
        }
    }
}

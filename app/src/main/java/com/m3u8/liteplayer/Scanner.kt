package com.m3u8.liteplayer

import android.net.Uri
import android.os.Environment
import java.io.File

object Scanner {

    // 跳过系统/应用数据目录，避免无意义遍历，低端机上更快
    private val SKIP_DIRS = setOf(
        "Android", "data", "obb", "cache", "temp", "tmp", "LOST.DIR", ".git"
    )

    /**
     * 扫描外部存储。
     *
     * @param knownPaths 已记录过的文件路径，命中直接跳过（增量）
     * @param since      上次扫描完成的时间戳；目录修改时间早于它的整棵子树直接跳过，
     *                   因为那之后该目录里不可能新增文件
     */
    fun scanStorage(knownPaths: Set<String>, since: Long): List<MediaFile> {
        val result = ArrayList<MediaFile>()
        val root = Environment.getExternalStorageDirectory()
        traverse(root, result, 0, knownPaths, since)
        return result
    }

    private fun traverse(
        dir: File,
        out: MutableList<MediaFile>,
        depth: Int,
        knownPaths: Set<String>,
        since: Long
    ) {
        if (depth > 12) return

        if (since > 0L) {
            val modified = try {
                dir.lastModified()
            } catch (e: Exception) {
                0L
            }
            // modified 为 0 说明拿不到时间，保守起见照常遍历
            if (modified in 1 until since) return
        }

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
                    traverse(file, out, depth + 1, knownPaths, since)
                } else {
                    val lower = file.name.lowercase()
                    if (!lower.endsWith(".m3u8") && !lower.endsWith(".m3u")) continue
                    val path = file.absolutePath
                    if (path in knownPaths) continue
                    out.add(
                        MediaFile(
                            name = file.name,
                            path = path,
                            size = file.length(),
                            uri = Uri.fromFile(file),
                            lastModified = file.lastModified()
                        )
                    )
                }
            } catch (e: Exception) {
                // 单个文件不可读时跳过，不影响整体扫描
            }
        }
    }
}

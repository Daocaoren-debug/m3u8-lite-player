package com.m3u8.liteplayer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 扫描结果本地缓存。
 * 下次启动先把这份列表直接显示出来，再后台做增量扫描，只把新增文件补进列表。
 */
object ScanStore {

    private const val FILE_NAME = "scan_cache.json"
    private const val VERSION = 1

    data class Snapshot(
        val files: List<MediaFile>,
        val lastScan: Long,
        val treeUri: String?
    )

    fun load(context: Context): Snapshot {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return Snapshot(emptyList(), 0L, null)
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            if (root.optInt("version", 0) != VERSION) return Snapshot(emptyList(), 0L, null)
            val array = root.optJSONArray("files") ?: JSONArray()
            val list = ArrayList<MediaFile>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val uriText = o.optString("uri")
                if (uriText.isEmpty()) continue
                list.add(
                    MediaFile(
                        name = o.optString("name"),
                        path = o.optString("path"),
                        size = o.optLong("size"),
                        uri = Uri.parse(uriText),
                        lastModified = o.optLong("mtime")
                    )
                )
            }
            val tree = root.optString("treeUri")
            Snapshot(list, root.optLong("lastScan"), if (tree.isEmpty()) null else tree)
        } catch (e: Exception) {
            Snapshot(emptyList(), 0L, null)
        }
    }

    fun save(context: Context, snapshot: Snapshot) {
        try {
            val root = JSONObject()
            root.put("version", VERSION)
            root.put("lastScan", snapshot.lastScan)
            root.put("treeUri", snapshot.treeUri ?: "")
            val array = JSONArray()
            for (f in snapshot.files) {
                val o = JSONObject()
                o.put("name", f.name)
                o.put("path", f.path)
                o.put("size", f.size)
                o.put("mtime", f.lastModified)
                o.put("uri", f.uri.toString())
                array.put(o)
            }
            root.put("files", array)

            // 先写临时文件再替换，避免写入中途被杀导致缓存损坏
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            val target = File(context.filesDir, FILE_NAME)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        } catch (e: Exception) {
            // 缓存写失败不影响正常使用
        }
    }
}

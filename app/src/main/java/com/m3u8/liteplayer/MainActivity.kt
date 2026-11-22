package com.m3u8.liteplayer

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var countText: TextView

    private val executor = Executors.newSingleThreadExecutor()

    /** 上次扫描结果（内存中的当前列表） */
    private var cachedFiles: List<MediaFile> = emptyList()

    /** 上次扫描完成时间，用于增量跳过 */
    private var lastScan: Long = 0L

    /** 用户选过的 SAF 文件夹，优先按这个目录扫描 */
    private var savedTreeUri: String? = null

    companion object {
        private const val REQ_STORAGE = 1001
        private const val REQ_MANAGE = 1002
        private const val REQ_TREE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        emptyText = findViewById(R.id.emptyText)
        progress = findViewById(R.id.progress)
        countText = findViewById(R.id.countText)

        recyclerView.layoutManager = LinearLayoutManager(this)
        findViewById<Button>(R.id.scanButton).setOnClickListener { startScan(incremental = true) }
        findViewById<Button>(R.id.rescanButton).setOnClickListener { startScan(incremental = false) }
        findViewById<Button>(R.id.folderButton).setOnClickListener { openFolderPicker() }

        // 一进来先用上次的扫描结果渲染列表，不用干等扫描
        val snapshot = ScanStore.load(this)
        cachedFiles = snapshot.files
        lastScan = snapshot.lastScan
        savedTreeUri = snapshot.treeUri
        if (cachedFiles.isNotEmpty()) {
            renderList(cachedFiles)
            countText.text = getString(R.string.loaded_cache, cachedFiles.size)
        }

        // 再在后台只找新增的
        if (hasStoragePermission() || !savedTreeUri.isNullOrEmpty()) {
            startScan(incremental = true)
        } else {
            requestStoragePermission()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQ_MANAGE)
            } catch (e: Exception) {
                try {
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQ_MANAGE
                    )
                } catch (ignored: Exception) {
                    Toast.makeText(this, "无法打开权限设置，请用“选择文件夹”方式", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startScan(incremental = true)
            } else {
                Toast.makeText(this, "未获得存储权限，可用“选择文件夹”方式", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_MANAGE -> {
                if (hasStoragePermission()) {
                    startScan(incremental = true)
                } else {
                    Toast.makeText(this, "未开启“所有文件访问权限”，可改用“选择文件夹”", Toast.LENGTH_LONG).show()
                }
            }
            REQ_TREE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val treeUri = data?.data ?: return
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        // 部分机型不支持持久化授权，仍可在本次会话中使用
                    }
                    savedTreeUri = treeUri.toString()
                    // 换了目录就当全量重扫，列表以这个目录为准
                    startScan(incremental = false)
                }
            }
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        try {
            startActivityForResult(intent, REQ_TREE)
        } catch (e: Exception) {
            Toast.makeText(this, "此设备不支持文件夹选择", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * @param incremental true 只找新增（跳过没变化过的目录），false 全量重扫
     */
    private fun startScan(incremental: Boolean) {
        val tree = savedTreeUri
        if (tree.isNullOrEmpty() && !hasStoragePermission()) {
            requestStoragePermission()
            return
        }

        val since = if (incremental) lastScan else 0L
        val knownPaths = if (incremental) {
            cachedFiles.filter { it.uri.scheme == "file" }.map { it.path }.toHashSet()
        } else {
            HashSet<String>()
        }
        val knownUris = if (incremental) {
            cachedFiles.map { it.uri.toString() }.toHashSet()
        } else {
            HashSet<String>()
        }

        showLoading(if (incremental) getString(R.string.scanning_new) else getString(R.string.scanning_all))

        executor.execute {
            val found = ArrayList<MediaFile>()
            try {
                if (!tree.isNullOrEmpty()) {
                    val root = DocumentFile.fromTreeUri(this, Uri.parse(tree))
                    walkTree(root, found, 0, knownUris, since)
                } else {
                    found.addAll(Scanner.scanStorage(knownPaths, since))
                }
            } catch (e: Exception) {
                // 遍历异常不影响已找到的结果
            }

            val now = System.currentTimeMillis()

            // 增量时以旧列表为底，再把新增补进来；全量时直接用新结果
            val merged = ArrayList<MediaFile>()
            if (incremental) merged.addAll(cachedFiles)
            merged.addAll(found)

            // 顺手剔除已经被删掉的文件（只校验本地路径，content:// 不做逐条探测以免变慢）
            val alive = ArrayList<MediaFile>(merged.size)
            for (f in merged) {
                if (exists(f)) alive.add(f)
            }
            val sorted = alive.distinctBy { it.uri.toString() }.sortedBy { it.name.lowercase() }

            ScanStore.save(this, ScanStore.Snapshot(sorted, now, tree))

            runOnUiThread {
                cachedFiles = sorted
                lastScan = now
                renderList(sorted)
                countText.text = when {
                    sorted.isEmpty() -> getString(R.string.empty_result_short)
                    incremental && found.isEmpty() -> getString(R.string.count_no_new, sorted.size)
                    incremental -> getString(R.string.count_with_new, sorted.size, found.size)
                    else -> getString(R.string.count_rescanned, sorted.size)
                }
            }
        }
    }

    private fun exists(file: MediaFile): Boolean {
        if (file.uri.scheme != "file") return true
        return try {
            File(file.path).exists()
        } catch (e: Exception) {
            true
        }
    }

    private fun walkTree(
        dir: DocumentFile?,
        out: MutableList<MediaFile>,
        depth: Int,
        knownUris: Set<String>,
        since: Long
    ) {
        if (dir == null || depth > 12) return

        if (since > 0L) {
            val modified = try {
                dir.lastModified()
            } catch (e: Exception) {
                0L
            }
            if (modified in 1 until since) return
        }

        for (child in dir.listFiles()) {
            try {
                if (child.isDirectory) {
                    if (child.name?.startsWith(".") == true) continue
                    walkTree(child, out, depth + 1, knownUris, since)
                } else {
                    val name = child.name ?: continue
                    val lower = name.lowercase()
                    if (!lower.endsWith(".m3u8") && !lower.endsWith(".m3u")) continue
                    if (child.uri.toString() in knownUris) continue
                    val displayPath = pathFromDocumentUri(child.uri) ?: name
                    out.add(
                        MediaFile(
                            name = name,
                            path = displayPath,
                            size = child.length(),
                            uri = child.uri,
                            lastModified = child.lastModified()
                        )
                    )
                }
            } catch (e: Exception) {
                // 跳过无法访问的条目
            }
        }
    }

    /** 把外部存储的 content:// 文档 Uri 转回可读的真实路径（仅用于展示）。 */
    private fun pathFromDocumentUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            if (parts.size == 2 && parts[0].equals("primary", ignoreCase = true)) {
                "${Environment.getExternalStorageDirectory().absolutePath}/${parts[1]}"
            } else {
                docId
            }
        } catch (e: Exception) {
            uri.toString()
        }
    }

    private fun showLoading(text: String) {
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        countText.text = text
    }

    private fun renderList(files: List<MediaFile>) {
        progress.visibility = View.GONE
        emptyText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.adapter = FileAdapter(files) { file -> openPlayer(file) }
    }

    private fun openPlayer(file: MediaFile) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_URI, file.uri.toString())
        intent.putExtra(PlayerActivity.EXTRA_NAME, file.name)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

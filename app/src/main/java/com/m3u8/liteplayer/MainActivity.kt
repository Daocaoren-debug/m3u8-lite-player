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
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var countText: TextView

    private val executor = Executors.newSingleThreadExecutor()

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
        findViewById<Button>(R.id.scanButton).setOnClickListener { onScanClicked() }
        findViewById<Button>(R.id.folderButton).setOnClickListener { openFolderPicker() }

        if (hasStoragePermission()) startFullScan() else requestStoragePermission()
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

    private fun onScanClicked() {
        if (hasStoragePermission()) startFullScan() else requestStoragePermission()
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
                startFullScan()
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
                    startFullScan()
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
                    startTreeScan(treeUri)
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

    private fun startFullScan() {
        showLoading("正在扫描本机…")
        executor.execute {
            val files = try {
                Scanner.scanStorage().sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
            runOnUiThread { showResults(files) }
        }
    }

    private fun startTreeScan(treeUri: Uri) {
        showLoading("正在扫描所选文件夹…")
        executor.execute {
            val files = ArrayList<MediaFile>()
            try {
                val root = DocumentFile.fromTreeUri(this, treeUri)
                walkTree(root, files, 0)
            } catch (e: Exception) {
                // 忽略遍历异常，展示已找到的结果
            }
            runOnUiThread { showResults(files.sortedBy { it.name.lowercase() }) }
        }
    }

    private fun walkTree(dir: DocumentFile?, out: MutableList<MediaFile>, depth: Int) {
        if (dir == null || depth > 12) return
        for (child in dir.listFiles()) {
            try {
                if (child.isDirectory) {
                    if (child.name?.startsWith(".") == true) continue
                    walkTree(child, out, depth + 1)
                } else {
                    val name = child.name ?: continue
                    val lower = name.lowercase()
                    if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
                        val displayPath = pathFromDocumentUri(child.uri) ?: name
                        out.add(MediaFile(name, displayPath, child.length(), child.uri))
                    }
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

    private fun showResults(files: List<MediaFile>) {
        progress.visibility = View.GONE
        if (files.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            countText.text = "未找到 m3u8 文件"
        } else {
            emptyText.visibility = View.GONE
            countText.text = "共找到 ${files.size} 个文件，点击即可播放"
        }

        recyclerView.adapter = FileAdapter(files) { file ->
            val intent = Intent(this, PlayerActivity::class.java)
            intent.putExtra(PlayerActivity.EXTRA_URI, file.uri.toString())
            intent.putExtra(PlayerActivity.EXTRA_NAME, file.name)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }
}

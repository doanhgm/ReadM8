package com.readm8.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val booksDir by lazy { File(Environment.getExternalStorageDirectory(), "ReadM8") }
    private val downloadDir by lazy {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    private lateinit var searchInput: EditText
    private lateinit var btnWifi: TextView
    private lateinit var btnScanDl: TextView
    private lateinit var wifiBanner: TextView
    private lateinit var statusText: TextView
    private lateinit var listContainer: LinearLayout

    private var includeDownloads = true
    private var allBooks: List<File> = emptyList()
    private var wifiServer: WifiBookServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildLayout()
        checkPermissionsAndScan()
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission()) scanBooks()
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141418"))
            setPadding(dp(14), dp(14), dp(14), dp(10))
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val title = TextView(this).apply {
            text = "READ M8"
            setTextColor(Color.parseColor("#FFF59E0B"))
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnScanDl = makeChip("+ Download") {
            includeDownloads = !includeDownloads
            updateScanChip()
            scanBooks()
        }
        btnWifi = makeChip("📡 Nạp Wi-Fi") { toggleWifi() }

        header.addView(title)
        header.addView(btnScanDl)
        header.addView(btnWifi)

        wifiBanner = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = roundBg("#FF1B4D3E", "#FF22C55E")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        searchInput = EditText(this).apply {
            hint = "Tìm tên sách hoặc đuôi (.epub, .pdf, .prc...)"
            setHintTextColor(Color.parseColor("#787886"))
            setTextColor(Color.WHITE)
            textSize = 14f
            isSingleLine = true
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundBg("#FF1E1E26", "#FF30303D")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    renderBooks(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9A9AA8"))
            textSize = 12f
            setPadding(dp(2), 0, dp(2), dp(8))
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)

        root.addView(header)
        root.addView(wifiBanner)
        root.addView(searchInput)
        root.addView(statusText)
        root.addView(scroll)
        setContentView(root)
        updateScanChip()
    }

    private fun makeChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundBg("#FF22222B", "#FF3A3A48")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) }
            setOnClickListener { onClick() }
        }
    }

    private fun updateScanChip() {
        btnScanDl.background = if (includeDownloads) {
            roundBg("#FF3B2F14", "#FFF59E0B")
        } else {
            roundBg("#FF22222B", "#FF3A3A48")
        }
        btnScanDl.text = if (includeDownloads) "✓ Download" else "+ Download"
    }

    private fun toggleWifi() {
        val srv = wifiServer
        if (srv != null && srv.isRunning) {
            srv.stop()
            wifiServer = null
            wifiBanner.visibility = View.GONE
            btnWifi.text = "📡 Nạp Wi-Fi"
            btnWifi.background = roundBg("#FF22222B", "#FF3A3A48")
            return
        }
        booksDir.mkdirs()
        val server = WifiBookServer(booksDir, 8088) { name ->
            runOnUiThread {
                Toast.makeText(this, "Đã nhận sách: $name", Toast.LENGTH_SHORT).show()
                scanBooks()
            }
        }
        val ip = server.start()
        if (ip == null) {
            Toast.makeText(this, "Hãy bật Wi-Fi trên M8 trước!", Toast.LENGTH_SHORT).show()
            return
        }
        wifiServer = server
        wifiBanner.text = "📡 Mở trình duyệt trên máy tính vào:  http://$ip:8088"
        wifiBanner.visibility = View.VISIBLE
        btnWifi.text = "⏹ Tắt Wi-Fi"
        btnWifi.background = roundBg("#FF5C1D1D", "#FFEF4444")
    }

    private fun hasStoragePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissionsAndScan() {
        if (hasStoragePermission()) {
            booksDir.mkdirs()
            scanBooks()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                101
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        booksDir.mkdirs()
        scanBooks()
    }

    private fun scanBooks() {
        if (!booksDir.exists()) booksDir.mkdirs()
        val dirs = mutableListOf(booksDir)
        if (includeDownloads && downloadDir.exists()) dirs.add(downloadDir)

        val prefs = getSharedPreferences("readm8_prefs", MODE_PRIVATE)
        val found = LinkedHashMap<String, File>()
        dirs.forEach { dir ->
            dir.walkTopDown().maxDepth(3).forEach { f ->
                if (f.isFile && f.extension.lowercase() in BookParsers.SUPPORTED_EXTS) {
                    found[f.absolutePath] = f
                }
            }
        }

        // Sắp xếp sách mới đọc gần nhất lên đầu, sau đó theo tên
        allBooks = found.values.sortedWith(
            compareByDescending<File> { prefs.getLong("last_${it.absolutePath}", 0L) }
                .thenBy { it.name.lowercase() }
        )
        renderBooks(searchInput.text?.toString().orEmpty())
    }

    private fun renderBooks(query: String) {
        listContainer.removeAllViews()
        val q = query.trim().lowercase()
        val list = if (q.isEmpty()) allBooks else allBooks.filter {
            it.name.lowercase().contains(q)
        }

        val scope = if (includeDownloads) "/ReadM8 + /Download" else "/ReadM8"
        statusText.text = "Thư mục: $scope  •  ${list.size} cuốn sách"

        if (list.isEmpty()) {
            val hint = TextView(this).apply {
                text = "Chưa thấy sách nào.\n\n• Bấm '📡 Nạp Wi-Fi' góc trên phải để gửi sách từ máy tính sang.\n• Hoặc chép trực tiếp file .epub, .pdf, .mobi, .prc, .txt, .cbz vào thư mục /ReadM8."
                setTextColor(Color.parseColor("#A8A8B6"))
                textSize = 14f
                setPadding(dp(8), dp(24), dp(8), dp(12))
            }
            listContainer.addView(hint)
            return
        }

        val prefs = getSharedPreferences("readm8_prefs", MODE_PRIVATE)
        list.forEach { book ->
            val ext = book.extension.uppercase(Locale.US)
            val badgeColor = when (ext) {
                "EPUB" -> "#FF22C55E"
                "PDF" -> "#FFEF4444"
                "MOBI", "PRC", "AZW3" -> "#FFF59E0B"
                "CBZ" -> "#FFA855F7"
                else -> "#FF3B82F6"
            }

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = roundBg("#FF1E1E26", "#FF2C2C38")
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    val intent = Intent(this@MainActivity, ReaderActivity::class.java).apply {
                        putExtra("BOOK_PATH", book.absolutePath)
                    }
                    startActivity(intent)
                }
            }

            val badge = TextView(this).apply {
                text = ext
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = roundBg(badgeColor)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(34)).apply { rightMargin = dp(12) }
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = book.nameWithoutExtension
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 2
            }

            val savedLabel = prefs.getString("label_${book.absolutePath}", null)
            val meta = TextView(this).apply {
                val sizeStr = formatSize(book.length())
                text = if (savedLabel != null) "$sizeStr  •  Đang đọc: $savedLabel" else sizeStr
                setTextColor(Color.parseColor("#9A9AA8"))
                textSize = 12f
                setPadding(0, dp(3), 0, 0)
            }

            infoCol.addView(nameView)
            infoCol.addView(meta)
            card.addView(badge)
            card.addView(infoCol)
            listContainer.addView(card)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

    private fun roundBg(fillHex: String, strokeHex: String? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(10).toFloat()
            if (strokeHex != null) setStroke(2, Color.parseColor(strokeHex))
        }
    }

    override fun onDestroy() {
        wifiServer?.stop()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

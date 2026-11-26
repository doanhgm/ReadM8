package com.readm8.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class ReaderActivity : AppCompatActivity() {

    private lateinit var bookFile: File
    private val prefs by lazy { getSharedPreferences("readm8_prefs", MODE_PRIVATE) }

    // Trạng thái đọc chung
    private var isPageMode = false
    private var menuVisible = false
    private var themeIndex = 1 // 0: Sáng, 1: Giấy vàng, 2: Xám tối, 3: Đen đêm
    private var fontSizePx = 19
    private var useSerif = true
    private var pdfNightMode = false

    // Chế độ sách chữ
    private var parsedBook: ParsedBook? = null
    private var chapterIndex = 0
    private var pendingScrollRatio = 0f
    private var currentScrollRatio = 0f
    private var autoStartTtsNextChapter = false
    private lateinit var webView: WebView

    // Nghe đọc sách (TTS)
    private var ttsEngine: BookTtsEngine? = null
    private var ttsBar: LinearLayout? = null
    private var ttsStatusText: TextView? = null
    private var btnTtsPlayPause: TextView? = null
    private var btnTtsVoice: TextView? = null
    private var btnTtsSpeed: TextView? = null
    private var btnTtsTimer: TextView? = null

    // Chế độ trang (PDF / CBZ)
    private val renderExecutor = Executors.newSingleThreadExecutor()
    private var pdfFd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var cbzZip: ZipFile? = null
    private var cbzEntries: List<String> = emptyList()
    private var totalPages = 0
    private var currentPage = 0
    private lateinit var pageImageView: PageImageView

    // Thanh công cụ
    private lateinit var rootFrame: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var progressText: TextView
    private lateinit var pageSeekBar: SeekBar
    private lateinit var btnTheme: TextView
    private lateinit var btnFont: TextView
    private lateinit var btnSizeDec: TextView
    private lateinit var btnSizeInc: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val path = intent.getStringExtra("BOOK_PATH") ?: return finish()
        bookFile = File(path)
        if (!bookFile.exists()) return finish()

        themeIndex = prefs.getInt("pref_theme", 1)
        fontSizePx = prefs.getInt("pref_font_size", 19)
        useSerif = prefs.getBoolean("pref_serif", true)
        pdfNightMode = prefs.getBoolean("pref_pdf_night", false)

        isPageMode = BookParsers.isPageBased(bookFile)
        buildUi()
        hideSystemUI()

        if (isPageMode) {
            openPageBook()
        } else {
            initTtsEngine()
            openTextBook()
        }
    }

    private fun hideSystemUI() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )
    }

    private fun initTtsEngine() {
        val savedVoiceIdx = prefs.getInt("pref_tts_voice", 0)
            .coerceIn(0, VoiceOption.entries.lastIndex)
        val savedSpeed = prefs.getFloat("pref_tts_speed", 1.0f)

        ttsEngine = BookTtsEngine(
            context = this,
            onSegmentActive = { domIdx, _, _ ->
                webView.evaluateJavascript("window.readM8Highlight($domIdx);", null)
            },
            onStateChanged = { playing, status ->
                btnTtsPlayPause?.text = if (playing) "⏸" else "▶"
                if (status.isNotEmpty()) ttsStatusText?.text = status
            },
            onChapterFinished = {
                val book = parsedBook
                if (book != null && chapterIndex + 1 < book.chapters.size) {
                    autoStartTtsNextChapter = true
                    loadChapter(chapterIndex + 1, 0f)
                } else {
                    Toast.makeText(this, "Đã đọc xong cuốn sách!", Toast.LENGTH_SHORT).show()
                    stopTtsAndHideBar()
                }
            },
            onFallbackNotice = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        ).apply {
            voice = VoiceOption.entries[savedVoiceIdx]
            speed = savedSpeed
        }
        updateTtsChips()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#141418"))
        }

        if (isPageMode) {
            pageImageView = PageImageView(
                context = this,
                onTapLeft = { goToPage(currentPage - 1) },
                onTapRight = { goToPage(currentPage + 1) },
                onTapCenter = { toggleMenu() }
            ).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            rootFrame.addView(pageImageView)
        } else {
            webView = WebView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                settings.builtInZoomControls = false
                addJavascriptInterface(ReaderJsBridge(), "ReadM8")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        applyWebStyle()
                        if (autoStartTtsNextChapter) {
                            autoStartTtsNextChapter = false
                            postDelayed({
                                evaluateJavascript("window.readM8CollectTts(true);", null)
                            }, 250)
                        } else if (pendingScrollRatio > 0f) {
                            val r = pendingScrollRatio
                            pendingScrollRatio = 0f
                            postDelayed({
                                evaluateJavascript(
                                    "var maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight); window.scrollTo(0, maxY * $r);",
                                    null
                                )
                            }, 80)
                        }
                    }
                }
            }
            rootFrame.addView(webView)
        }

        // Thanh nghe đọc sách mini (hiện ở đáy màn hình khi bật Nghe)
        if (!isPageMode) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#F0181822"))
                setPadding(dp(10), dp(8), dp(10), dp(10))
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            }

            val topInfoRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }

            ttsStatusText = TextView(this).apply {
                text = "Sẵn sàng đọc"
                setTextColor(Color.parseColor("#FFF59E0B"))
                textSize = 12f
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnCloseTts = makeBarButton("✕") { stopTtsAndHideBar() }
            topInfoRow.addView(ttsStatusText)
            topInfoRow.addView(btnCloseTts)

            val ctrlRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val btnPrev = makeBarButton("⏮") { ttsEngine?.prevSegment() }
            btnTtsPlayPause = makeBarButton("⏸") { ttsEngine?.togglePlayPause() }
            val btnNext = makeBarButton("⏭") { ttsEngine?.nextSegment() }
            btnTtsVoice = makeBarButton("Hoài My") { showVoicePicker() }
            btnTtsSpeed = makeBarButton("1.0x") { cycleSpeed() }
            btnTtsTimer = makeBarButton("⏱") { showSleepTimerPicker() }

            ctrlRow.addView(btnPrev)
            ctrlRow.addView(btnTtsPlayPause)
            ctrlRow.addView(btnNext)
            ctrlRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            ctrlRow.addView(btnTtsVoice)
            ctrlRow.addView(btnTtsSpeed)
            ctrlRow.addView(btnTtsTimer)

            bar.addView(topInfoRow)
            bar.addView(ctrlRow)
            ttsBar = bar
            rootFrame.addView(bar)
        }

        // Thanh trên cùng
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#EE181820"))
            setPadding(dp(8), dp(12), dp(8), dp(12))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        }

        val btnBack = makeBarButton("←") {
            ttsEngine?.stop()
            saveProgress()
            finish()
        }
        titleText = TextView(this).apply {
            text = bookFile.nameWithoutExtension
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(btnBack)
        topBar.addView(titleText)

        if (!isPageMode) {
            val btnListen = makeBarButton("🎧 Nghe") {
                if (menuVisible) toggleMenu()
                startTtsFromCurrentView()
            }
            topBar.addView(btnListen)
        }

        val btnToc = makeBarButton(if (isPageMode) "Trang" else "Mục lục") {
            if (isPageMode) showJumpPageDialog() else showTocDialog()
        }
        topBar.addView(btnToc)

        // Thanh dưới cùng
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE181820"))
            setPadding(dp(12), dp(10), dp(12), dp(14))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        progressText = TextView(this).apply {
            setTextColor(Color.parseColor("#D0D0DC"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        bottomBar.addView(progressText)

        pageSeekBar = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && isPageMode) {
                        progressText.text = "Trang ${p + 1} / $totalPages"
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val p = sb?.progress ?: return
                    if (isPageMode) goToPage(p) else {
                        ttsEngine?.stop()
                        ttsBar?.visibility = View.GONE
                        loadChapter(p, 0f)
                    }
                }
            })
        }
        bottomBar.addView(pageSeekBar)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        if (isPageMode) {
            controlsRow.addView(makeBarButton("◄ Trước") { goToPage(currentPage - 1) })
            btnTheme = makeBarButton(if (pdfNightMode) "☀ Ban ngày" else "🌙 Đọc đêm") {
                pdfNightMode = !pdfNightMode
                prefs.edit().putBoolean("pref_pdf_night", pdfNightMode).apply()
                btnTheme.text = if (pdfNightMode) "☀ Ban ngày" else "🌙 Đọc đêm"
                applyPdfNightFilter()
            }
            controlsRow.addView(btnTheme)
            controlsRow.addView(makeBarButton("Sau ►") { goToPage(currentPage + 1) })
        } else {
            btnSizeDec = makeBarButton("A-") { changeFontSize(-2) }
            btnSizeInc = makeBarButton("A+") { changeFontSize(2) }
            btnTheme = makeBarButton(themeName()) {
                themeIndex = (themeIndex + 1) % 4
                prefs.edit().putInt("pref_theme", themeIndex).apply()
                btnTheme.text = themeName()
                applyWebStyle()
            }
            btnFont = makeBarButton(if (useSerif) "Có chân" else "Không chân") {
                useSerif = !useSerif
                prefs.edit().putBoolean("pref_serif", useSerif).apply()
                btnFont.text = if (useSerif) "Có chân" else "Không chân"
                applyWebStyle()
            }
            controlsRow.addView(btnSizeDec)
            controlsRow.addView(btnSizeInc)
            controlsRow.addView(btnTheme)
            controlsRow.addView(btnFont)
        }

        bottomBar.addView(controlsRow)
        rootFrame.addView(topBar)
        rootFrame.addView(bottomBar)
        setContentView(rootFrame)
    }

    private fun makeBarButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FF2A2A35"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(3), 0, dp(3), 0) }
            setOnClickListener { onClick() }
        }
    }

    private fun toggleMenu() {
        menuVisible = !menuVisible
        topBar.visibility = if (menuVisible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (menuVisible) View.VISIBLE else View.GONE
        if (!menuVisible) hideSystemUI()
    }

    // ==================== ĐIỀU KHIỂN NGHE ĐỌC SÁCH ====================
    private fun startTtsFromCurrentView() {
        ttsBar?.visibility = View.VISIBLE
        ttsStatusText?.text = "Đang chuẩn bị giọng đọc..."
        webView.evaluateJavascript("window.readM8CollectTts(false);", null)
    }

    private fun stopTtsAndHideBar() {
        ttsEngine?.stop()
        ttsBar?.visibility = View.GONE
        webView.evaluateJavascript("window.readM8ClearHighlight();", null)
    }

    private fun showVoicePicker() {
        val engine = ttsEngine ?: return
        val options = VoiceOption.entries
        val labels = options.map { it.label }.toTypedArray()
        val curIdx = options.indexOf(engine.voice)
        AlertDialog.Builder(this)
            .setTitle("Chọn giọng đọc")
            .setSingleChoiceItems(labels, curIdx) { dialog, which ->
                val chosen = options[which]
                prefs.edit().putInt("pref_tts_voice", which).apply()
                engine.updateVoiceOrSpeed(chosen, engine.speed)
                updateTtsChips()
                dialog.dismiss()
            }
            .show()
    }

    private fun cycleSpeed() {
        val engine = ttsEngine ?: return
        val speeds = listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f)
        val curIdx = speeds.indexOfFirst { kotlin.math.abs(it - engine.speed) < 0.05f }
        val nextSpeed = speeds[(curIdx + 1) % speeds.size]
        prefs.edit().putFloat("pref_tts_speed", nextSpeed).apply()
        engine.updateVoiceOrSpeed(engine.voice, nextSpeed)
        updateTtsChips()
    }

    private fun showSleepTimerPicker() {
        val engine = ttsEngine ?: return
        val choices = arrayOf("Tắt hẹn giờ", "15 phút", "30 phút", "60 phút")
        val mins = intArrayOf(0, 15, 30, 60)
        AlertDialog.Builder(this)
            .setTitle("Hẹn giờ tắt đọc sách")
            .setItems(choices) { _, which ->
                val m = mins[which]
                engine.setSleepTimer(m)
                btnTtsTimer?.text = if (m > 0) "⏱${m}p" else "⏱"
                val msg = if (m > 0) "Sẽ tự dừng sau $m phút" else "Đã tắt hẹn giờ"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun updateTtsChips() {
        val engine = ttsEngine ?: return
        btnTtsVoice?.text = engine.voice.shortLabel
        btnTtsSpeed?.text = "${engine.speed}x"
    }

    // ==================== CHẾ ĐỘ SÁCH CHỮ ====================
    private fun openTextBook() {
        Toast.makeText(this, "Đang mở sách...", Toast.LENGTH_SHORT).show()
        Thread {
            val book = runCatching { BookParsers.parseTextBook(this, bookFile) }.getOrElse {
                ParsedBook(bookFile.nameWithoutExtension, listOf(
                    BookChapter("Lỗi", "<p>Không mở được file: ${it.message}</p>", "file:///")
                ))
            }
            runOnUiThread {
                parsedBook = book
                titleText.text = book.title
                pageSeekBar.max = (book.chapters.size - 1).coerceAtLeast(0)
                val savedCh = prefs.getInt("ch_${bookFile.absolutePath}", 0)
                    .coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
                val savedRatio = prefs.getFloat("ratio_${bookFile.absolutePath}", 0f)
                loadChapter(savedCh, savedRatio)
            }
        }.start()
    }

    private fun loadChapter(index: Int, scrollRatio: Float) {
        val book = parsedBook ?: return
        if (index !in book.chapters.indices) return
        chapterIndex = index
        pendingScrollRatio = scrollRatio
        currentScrollRatio = scrollRatio

        val ch = book.chapters[index]
        pageSeekBar.progress = index
        progressText.text = "${ch.title}  (${index + 1}/${book.chapters.size})"

        val wrapped = injectReaderAssets(ch.htmlContent)
        webView.loadDataWithBaseURL(ch.baseUrl, wrapped, "text/html", "UTF-8", null)
        saveProgress()
    }

    private fun injectReaderAssets(rawHtml: String): String {
        val injectHead = """
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <style id="readm8-style">
              html, body {
                margin: 0 !important;
                padding: 18px 18px 96px 18px !important;
                line-height: 1.65 !important;
                text-align: justify;
                word-wrap: break-word;
                overflow-x: hidden;
              }
              p { margin: 0 0 0.75em 0; }
              img, svg { max-width: 100% !important; height: auto !important; display: block; margin: 10px auto; }
              a { color: #f59e0b !important; }
            </style>
        """.trimIndent()

        val injectScript = """
            <script>
              (function() {
                var sx = 0, sy = 0;
                document.addEventListener('touchstart', function(e) {
                  if (e.touches.length === 1) { sx = e.touches[0].clientX; sy = e.touches[0].clientY; }
                }, {passive: true});
                document.addEventListener('touchend', function(e) {
                  if (e.changedTouches.length !== 1) return;
                  var t = e.changedTouches[0];
                  if (Math.abs(t.clientX - sx) > 18 || Math.abs(t.clientY - sy) > 18) return;
                  if (e.target && e.target.closest && e.target.closest('a')) return;
                  var sel = window.getSelection ? window.getSelection().toString() : '';
                  if (sel && sel.length > 0) return;
                  var w = window.innerWidth;
                  if (t.clientX < w * 0.22) ReadM8.onTapZone('left');
                  else if (t.clientX > w * 0.78) ReadM8.onTapZone('right');
                  else ReadM8.onTapZone('center');
                }, {passive: true});

                window.readM8Step = function(dir) {
                  var step = Math.round(window.innerHeight * 0.88);
                  var cur = window.scrollY || document.documentElement.scrollTop || 0;
                  var maxY = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
                  if (dir > 0) {
                    if (cur >= maxY - 12) ReadM8.onEdgeReached('next');
                    else window.scrollBy({top: step, behavior: 'smooth'});
                  } else {
                    if (cur <= 12) ReadM8.onEdgeReached('prev');
                    else window.scrollBy({top: -step, behavior: 'smooth'});
                  }
                  setTimeout(function() {
                    var c = window.scrollY || document.documentElement.scrollTop || 0;
                    var m = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
                    ReadM8.onProgress(Math.min(1, Math.max(0, c / m)));
                  }, 320);
                };

                window.readM8CollectTts = function(fromTop) {
                  var prev = document.querySelectorAll('[data-tts-idx]');
                  for (var k = 0; k < prev.length; k++) prev[k].removeAttribute('data-tts-idx');

                  var nodes = Array.prototype.slice.call(document.querySelectorAll('p, h1, h2, h3, h4, h5, h6, li, blockquote'));
                  var filtered = [];
                  for (var i = 0; i < nodes.length; i++) {
                    var n = nodes[i];
                    if (n.querySelector('p, h1, h2, h3, h4, li, blockquote')) continue;
                    var txt = (n.innerText || n.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt.length >= 2) filtered.push({el: n, txt: txt});
                  }
                  if (filtered.length === 0) {
                    var divs = Array.prototype.slice.call(document.querySelectorAll('div'));
                    for (var d = 0; d < divs.length; d++) {
                      if (divs[d].querySelector('div, p')) continue;
                      var dt = (divs[d].innerText || '').replace(/\s+/g, ' ').trim();
                      if (dt.length >= 2) filtered.push({el: divs[d], txt: dt});
                    }
                  }
                  if (filtered.length === 0) {
                    var allTxt = (document.body.innerText || '').replace(/\s+/g, ' ').trim();
                    if (allTxt.length >= 2) filtered.push({el: document.body, txt: allTxt});
                  }

                  var texts = [];
                  var startIdx = 0;
                  var foundVisible = false;
                  for (var j = 0; j < filtered.length; j++) {
                    filtered[j].el.setAttribute('data-tts-idx', String(j));
                    texts.push(filtered[j].txt);
                    if (!fromTop && !foundVisible) {
                      var rect = filtered[j].el.getBoundingClientRect();
                      if (rect.bottom > 24 && rect.top < window.innerHeight) {
                        startIdx = j;
                        foundVisible = true;
                      }
                    }
                  }
                  ReadM8.onTtsData(startIdx, JSON.stringify(texts));
                };

                window.readM8Highlight = function(idx) {
                  var old = document.querySelectorAll('.readm8-speaking');
                  for (var i = 0; i < old.length; i++) old[i].classList.remove('readm8-speaking');
                  var el = document.querySelector('[data-tts-idx="' + idx + '"]');
                  if (el) {
                    el.classList.add('readm8-speaking');
                    el.scrollIntoView({behavior: 'smooth', block: 'center'});
                  }
                };

                window.readM8ClearHighlight = function() {
                  var old = document.querySelectorAll('.readm8-speaking');
                  for (var i = 0; i < old.length; i++) old[i].classList.remove('readm8-speaking');
                };

                var timer = null;
                window.addEventListener('scroll', function() {
                  clearTimeout(timer);
                  timer = setTimeout(function() {
                    var c = window.scrollY || document.documentElement.scrollTop || 0;
                    var m = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
                    ReadM8.onProgress(Math.min(1, Math.max(0, c / m)));
                  }, 300);
                }, {passive: true});
              })();
            </script>
        """.trimIndent()

        return if (rawHtml.contains("</head>", true) && rawHtml.contains("</body>", true)) {
            rawHtml
                .replaceFirst(Regex("</head>", RegexOption.IGNORE_CASE), "$injectHead</head>")
                .replaceFirst(Regex("</body>", RegexOption.IGNORE_CASE), "$injectScript</body>")
        } else {
            "<!DOCTYPE html><html><head><meta charset='utf-8'>$injectHead</head><body>$rawHtml$injectScript</body></html>"
        }
    }

    private fun themeColors(): Pair<String, String> = when (themeIndex) {
        0 -> "#FFFFFF" to "#1F1F24"
        1 -> "#F4ECD8" to "#3B2E1E"
        2 -> "#202124" to "#D8D8DF"
        else -> "#000000" to "#B8B8C0"
    }

    private fun themeName(): String = when (themeIndex) {
        0 -> "☀ Sáng"
        1 -> "📜 Giấy vàng"
        2 -> "🌗 Tối"
        else -> "🌑 Đêm"
    }

    private fun changeFontSize(delta: Int) {
        fontSizePx = (fontSizePx + delta).coerceIn(13, 34)
        prefs.edit().putInt("pref_font_size", fontSizePx).apply()
        applyWebStyle()
    }

    private fun applyWebStyle() {
        val (bg, fg) = themeColors()
        val font = if (useSerif) "Georgia, 'Times New Roman', serif" else "sans-serif"
        rootFrame.setBackgroundColor(Color.parseColor(bg))
        webView.setBackgroundColor(Color.parseColor(bg))
        val js = """
            (function(){
              var css = "html, body { background-color: $bg !important; color: $fg !important; font-family: $font !important; font-size: ${fontSizePx}px !important; } " +
                        "body * { background-color: transparent !important; color: $fg !important; font-family: $font !important; } " +
                        "body .readm8-speaking { background-color: rgba(245, 158, 11, 0.28) !important; border-radius: 6px; padding: 2px 4px; } " +
                        "body .readm8-speaking * { background-color: transparent !important; }";
              var st = document.getElementById('readm8-dynamic');
              if (!st) { st = document.createElement('style'); st.id = 'readm8-dynamic'; document.head.appendChild(st); }
              st.textContent = css;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun showTocDialog() {
        val book = parsedBook ?: return
        val titles = book.chapters.mapIndexed { i, c -> "${i + 1}. ${c.title}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Mục lục (${book.chapters.size})")
            .setSingleChoiceItems(titles, chapterIndex) { dialog, which ->
                ttsEngine?.stop()
                ttsBar?.visibility = View.GONE
                loadChapter(which, 0f)
                dialog.dismiss()
                if (menuVisible) toggleMenu()
            }
            .show()
    }

    inner class ReaderJsBridge {
        @JavascriptInterface
        fun onTapZone(zone: String) {
            runOnUiThread {
                when (zone) {
                    "center" -> toggleMenu()
                    "right" -> if (menuVisible) toggleMenu() else webView.evaluateJavascript("window.readM8Step(1);", null)
                    "left" -> if (menuVisible) toggleMenu() else webView.evaluateJavascript("window.readM8Step(-1);", null)
                }
            }
        }

        @JavascriptInterface
        fun onEdgeReached(dir: String) {
            runOnUiThread {
                val book = parsedBook ?: return@runOnUiThread
                if (dir == "next") {
                    if (chapterIndex + 1 < book.chapters.size) loadChapter(chapterIndex + 1, 0f)
                    else Toast.makeText(this@ReaderActivity, "Đã đến cuối sách", Toast.LENGTH_SHORT).show()
                } else if (dir == "prev") {
                    if (chapterIndex > 0) loadChapter(chapterIndex - 1, 0.98f)
                    else Toast.makeText(this@ReaderActivity, "Đang ở đầu sách", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun onProgress(ratio: Float) {
            currentScrollRatio = ratio
        }

        @JavascriptInterface
        fun onTtsData(startIdx: Int, jsonArrayStr: String) {
            runOnUiThread {
                val paragraphs = runCatching {
                    val arr = JSONArray(jsonArrayStr)
                    List(arr.length()) { i -> arr.optString(i, "") }
                }.getOrDefault(emptyList())
                if (paragraphs.isEmpty()) {
                    Toast.makeText(this@ReaderActivity, "Chương này không có văn bản để đọc", Toast.LENGTH_SHORT).show()
                    stopTtsAndHideBar()
                } else {
                    ttsEngine?.start(paragraphs, startIdx)
                }
            }
        }
    }

    // ==================== CHẾ ĐỘ TRANG (PDF / CBZ) ====================
    private fun openPageBook() {
        applyPdfNightFilter()
        renderExecutor.execute {
            val isPdf = bookFile.extension.equals("pdf", true)
            val count = runCatching {
                if (isPdf) {
                    pdfFd = ParcelFileDescriptor.open(bookFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    pdfRenderer = PdfRenderer(pdfFd!!)
                    pdfRenderer!!.pageCount
                } else {
                    cbzEntries = BookParsers.listCbzEntries(bookFile)
                    cbzZip = ZipFile(bookFile)
                    cbzEntries.size
                }
            }.getOrDefault(0)

            runOnUiThread {
                totalPages = count
                if (totalPages <= 0) {
                    Toast.makeText(this, "Không đọc được trang nào trong file này", Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                pageSeekBar.max = (totalPages - 1).coerceAtLeast(0)
                val savedPage = prefs.getInt("page_${bookFile.absolutePath}", 0)
                    .coerceIn(0, totalPages - 1)
                goToPage(savedPage)
            }
        }
    }

    private fun goToPage(index: Int) {
        if (totalPages <= 0) return
        if (index < 0) {
            Toast.makeText(this, "Đang ở trang đầu", Toast.LENGTH_SHORT).show()
            return
        }
        if (index >= totalPages) {
            Toast.makeText(this, "Đã đến trang cuối", Toast.LENGTH_SHORT).show()
            return
        }
        currentPage = index
        pageSeekBar.progress = index
        progressText.text = "Trang ${index + 1} / $totalPages"
        saveProgress()

        renderExecutor.execute {
            val bmp = runCatching {
                val renderer = pdfRenderer
                if (renderer != null) {
                    renderer.openPage(index).use { page ->
                        val targetW = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceAtMost(1600)
                        val scale = targetW.toFloat() / page.width.coerceAtLeast(1)
                        val targetH = (page.height * scale).toInt().coerceAtLeast(1)
                        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        out.eraseColor(Color.WHITE)
                        page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out
                    }
                } else {
                    val zip = cbzZip ?: return@runCatching null
                    val entry = zip.getEntry(cbzEntries[index]) ?: return@runCatching null
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                }
            }.getOrNull()

            runOnUiThread {
                if (bmp != null) pageImageView.setPageBitmap(bmp)
            }
        }
    }

    private fun applyPdfNightFilter() {
        if (!isPageMode) return
        if (pdfNightMode) {
            val inv = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 240f,
                    0f, -1f, 0f, 0f, 240f,
                    0f, 0f, -1f, 0f, 240f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            pageImageView.colorFilter = ColorMatrixColorFilter(inv)
            rootFrame.setBackgroundColor(Color.BLACK)
        } else {
            pageImageView.colorFilter = null
            rootFrame.setBackgroundColor(Color.parseColor("#141418"))
        }
    }

    private fun showJumpPageDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Nhập số trang (1 - $totalPages)"
        }
        AlertDialog.Builder(this)
            .setTitle("Nhảy tới trang")
            .setView(input)
            .setPositiveButton("Đi tới") { _, _ ->
                val p = input.text.toString().toIntOrNull()
                if (p != null && p in 1..totalPages) {
                    goToPage(p - 1)
                    if (menuVisible) toggleMenu()
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ==================== PHÍM ÂM LƯỢNG ====================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Khi đang nghe đọc sách thì trả phím âm lượng về tăng/giảm loa bình thường
        if (ttsEngine?.isPlaying == true) {
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isPageMode) goToPage(currentPage + 1)
            else webView.evaluateJavascript("window.readM8Step(1);", null)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (isPageMode) goToPage(currentPage - 1)
            else webView.evaluateJavascript("window.readM8Step(-1);", null)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun saveProgress() {
        val edit = prefs.edit().putLong("last_${bookFile.absolutePath}", System.currentTimeMillis())
        if (isPageMode && totalPages > 0) {
            edit.putInt("page_${bookFile.absolutePath}", currentPage)
            edit.putString("label_${bookFile.absolutePath}", "Trang ${currentPage + 1}/$totalPages")
        } else {
            val totalCh = parsedBook?.chapters?.size ?: 1
            edit.putInt("ch_${bookFile.absolutePath}", chapterIndex)
            edit.putFloat("ratio_${bookFile.absolutePath}", currentScrollRatio)
            edit.putString("label_${bookFile.absolutePath}", "Chương ${chapterIndex + 1}/$totalCh")
        }
        edit.apply()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        ttsEngine?.release()
        renderExecutor.execute {
            runCatching { pdfRenderer?.close() }
            runCatching { pdfFd?.close() }
            runCatching { cbzZip?.close() }
        }
        renderExecutor.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

class PageImageView(
    context: Context,
    private val onTapLeft: () -> Unit,
    private val onTapRight: () -> Unit,
    private val onTapCenter: () -> Unit
) : androidx.appcompat.widget.AppCompatImageView(context) {

    private val imgMatrix = Matrix()
    private var currentBmp: Bitmap? = null
    private var baseScale = 1f
    private var curScale = 1f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val newScale = (curScale * factor).coerceIn(baseScale, baseScale * 4f)
            val realFactor = newScale / curScale
            curScale = newScale
            imgMatrix.postScale(realFactor, realFactor, detector.focusX, detector.focusY)
            imageMatrix = imgMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            when {
                e.x < width * 0.25f -> onTapLeft()
                e.x > width * 0.75f -> onTapRight()
                else -> onTapCenter()
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (curScale > baseScale * 1.15f) {
                fitToScreen()
            } else {
                val factor = 2.2f
                curScale *= factor
                imgMatrix.postScale(factor, factor, e.x, e.y)
                imageMatrix = imgMatrix
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (curScale > baseScale * 1.02f) {
                imgMatrix.postTranslate(-dx, -dy)
                imageMatrix = imgMatrix
                return true
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setPageBitmap(bmp: Bitmap) {
        val old = currentBmp
        currentBmp = bmp
        setImageBitmap(bmp)
        fitToScreen()
        if (old != null && old != bmp && !old.isRecycled) old.recycle()
    }

    private fun fitToScreen() {
        val bmp = currentBmp ?: return
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / bmp.width
        val sy = height.toFloat() / bmp.height
        baseScale = minOf(sx, sy)
        curScale = baseScale
        val tx = (width - bmp.width * baseScale) / 2f
        val ty = (height - bmp.height * baseScale) / 2f
        imgMatrix.reset()
        imgMatrix.postScale(baseScale, baseScale)
        imgMatrix.postTranslate(tx, ty)
        imageMatrix = imgMatrix
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToScreen()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}

package com.readm8.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

class WifiBookServer(
    private val rootDir: File,
    private val port: Int = 8088,
    private val onUploaded: (fileName: String) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    var isRunning = false
        private set

    fun start(): String? {
        if (isRunning) return getLocalIpAddress()
        val ip = getLocalIpAddress() ?: return null
        return runCatching {
            val ss = ServerSocket(port)
            serverSocket = ss
            isRunning = true
            executor.execute {
                while (isRunning) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    executor.execute { handleClient(client) }
                }
            }
            ip
        }.getOrNull()
    }

    fun stop() {
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = s.getInputStream()
            val output = s.getOutputStream()

            val headerText = readHeaders(input)
            if (headerText.isEmpty()) return

            val lines = headerText.split("\r\n")
            val reqParts = lines.firstOrNull()?.split(" ") ?: return
            if (reqParts.size < 2) return

            val method = reqParts[0].uppercase()
            val fullPath = reqParts[1]
            val pathOnly = fullPath.substringBefore("?")
            val queryStr = fullPath.substringAfter("?", "")

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(":")
                if (idx > 0) {
                    headers[lines[i].substring(0, idx).trim().lowercase()] =
                        lines[i].substring(idx + 1).trim()
                }
            }

            if (headers["expect"]?.contains("100-continue", true) == true) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                output.flush()
            }

            when {
                method == "GET" && (pathOnly == "/" || pathOnly == "/index.html") -> {
                    val body = htmlPage().toByteArray(Charsets.UTF_8)
                    val resp = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    output.write(resp.toByteArray())
                    output.write(body)
                    output.flush()
                }

                method == "POST" && pathOnly == "/upload" -> {
                    val params = parseQuery(queryStr)
                    val rawName = params["name"] ?: ""
                    val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L
                    if (rawName.isBlank() || contentLength < 0) {
                        sendText(output, 400, "Bad Request")
                        return
                    }
                    val safeName = File(rawName).name.replace("/", "_").replace("\\", "_")
                    rootDir.mkdirs()
                    val outFile = File(rootDir, safeName)

                    val ok = runCatching {
                        val buf = ByteArray(16384)
                        var remaining = contentLength
                        FileOutputStream(outFile).use { fos ->
                            while (remaining > 0) {
                                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                                val read = input.read(buf, 0, toRead)
                                if (read == -1) break
                                fos.write(buf, 0, read)
                                remaining -= read
                            }
                        }
                        remaining == 0L
                    }.getOrDefault(false)

                    if (ok) {
                        sendText(output, 200, "OK")
                        onUploaded(safeName)
                    } else {
                        outFile.delete()
                        sendText(output, 500, "Error")
                    }
                }

                else -> sendText(output, 404, "Not Found")
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            baos.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> 4
                b == 13 -> 1
                else -> 0
            }
            if (state == 4 || baos.size() > 65536) break
        }
        return baos.toString("UTF-8")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query.isBlank()) return map
        query.split("&").forEach { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2) {
                val k = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrDefault(parts[0])
                val v = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrDefault(parts[1])
                map[k] = v
            }
        }
        return map
    }

    private fun sendText(output: java.io.OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "OK" else "Error"
        val resp = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(resp.toByteArray())
        output.write(body)
        output.flush()
    }

    private fun getLocalIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip
                        fallback = ip
                    }
                }
            }
            fallback
        }.getOrNull()
    }

    private fun htmlPage(): String = """
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ReadM8 - Nạp Sách Wi-Fi</title>
<style>
  body { background:#141418; color:#f2f2f7; font-family:system-ui,sans-serif; max-width:520px; margin:32px auto; padding:0 16px; }
  h1 { color:#f59e0b; margin:0 0 6px; font-size:24px; }
  p { color:#a0a0ae; margin:0 0 20px; font-size:14px; }
  .card { background:#1e1e26; border:1px solid #30303d; border-radius:14px; padding:20px; }
  input[type=file] { width:100%; padding:12px; border-radius:10px; border:1px solid #3c3c4c; background:#141418; color:#fff; margin-bottom:16px; box-sizing:border-box; }
  button { width:100%; padding:12px; border:none; border-radius:10px; background:#f59e0b; color:#141418; font-weight:700; font-size:15px; cursor:pointer; }
  button:disabled { opacity:0.5; }
  #barWrap { margin-top:16px; background:#141418; border-radius:8px; height:10px; overflow:hidden; display:none; }
  #bar { height:100%; width:0%; background:#22c55e; transition:width 0.1s; }
  #log { margin-top:14px; font-size:13px; color:#d4d4df; white-space:pre-line; line-height:1.5; }
</style>
</head>
<body>
  <h1>READ M8 • NẠP SÁCH WI-FI</h1>
  <p>Hỗ trợ EPUB, PDF, MOBI, PRC, AZW3, TXT, MD, HTML, FB2, CBZ.</p>
  <div class="card">
    <input id="files" type="file" multiple>
    <button id="btn" onclick="go()">Gửi sách sang HTC M8</button>
    <div id="barWrap"><div id="bar"></div></div>
    <div id="log"></div>
  </div>
<script>
async function go() {
  var inp = document.getElementById('files');
  var btn = document.getElementById('btn');
  var bw = document.getElementById('barWrap');
  var bar = document.getElementById('bar');
  var log = document.getElementById('log');
  if (!inp.files.length) { log.textContent = 'Chưa chọn file sách nào.'; return; }
  btn.disabled = true; bw.style.display = 'block'; log.textContent = '';
  for (var i = 0; i < inp.files.length; i++) {
    var f = inp.files[i];
    log.textContent += 'Đang gửi (' + (i + 1) + '/' + inp.files.length + '): ' + f.name + '...\n';
    var ok = await sendOne(f, function(p) { bar.style.width = p + '%'; });
    log.textContent += ok ? '✓ Đã nhận: ' + f.name + '\n' : '✗ Lỗi: ' + f.name + '\n';
  }
  btn.disabled = false; inp.value = '';
}
function sendOne(file, onPct) {
  return new Promise(function(res) {
    var x = new XMLHttpRequest();
    x.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);
    x.upload.onprogress = function(e) { if (e.lengthComputable) onPct(Math.round(e.loaded * 100 / e.total)); };
    x.onload = function() { res(x.status === 200); };
    x.onerror = function() { res(false); };
    x.send(file);
  });
}
</script>
</body>
</html>
    """.trimIndent()
}

package com.fireairplay.receiver.server

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser

class AirPlayVideoServer(
    private val port: Int = 7000,
    private val onVideoUrlReceived: (String, Float) -> Unit
) {
    companion object {
        private const val TAG = "AirPlayVideoServer"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (serverJob != null) return

        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "AirPlay Video Server started on port $port")

                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server: ${e.message}")
        }
        serverSocket = null
        Log.i(TAG, "AirPlay Video Server stopped")
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use {
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val writer = OutputStreamWriter(it.getOutputStream())

                // Parse Request Line
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 3) return
                val method = parts[0]
                val path = parts[1]

                Log.i(TAG, ">> Video Command: $method $path")

                // Parse Headers
                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val headerParts = line.split(": ", limit = 2)
                    if (headerParts.size == 2) {
                        headers[headerParts[0].lowercase()] = headerParts[1]
                        if (headerParts[0].lowercase() == "content-length") {
                            contentLength = headerParts[1].toIntOrNull() ?: 0
                        }
                    }
                }

                // Parse Body if present
                var bodyBytes = ByteArray(0)
                if (contentLength > 0) {
                    val charArray = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val result = reader.read(charArray, read, contentLength - read)
                        if (result == -1) break
                        read += result
                    }
                    bodyBytes = String(charArray).toByteArray()
                }

                // Handle Routes
                when (path) {
                    "/server-info" -> handleServerInfo(writer)
                    "/play" -> handlePlay(bodyBytes, writer)
                    "/stop" -> handleStop(writer)
                    "/rate" -> handleRate(writer)
                    "/scrub" -> handleScrub(writer)
                    else -> respond(writer, "404 Not Found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling video client: ${e.message}")
        }
    }

    private fun handleServerInfo(writer: OutputStreamWriter) {
        // Send a basic Mac/AppleTV server info plist to satisfy the client
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>deviceid</key>
                <string>00:11:22:33:44:55</string>
                <key>features</key>
                <integer>119</integer>
                <key>model</key>
                <string>AppleTV5,3</string>
                <key>protovers</key>
                <string>1.0</string>
                <key>srcvers</key>
                <string>220.68</string>
            </dict>
            </plist>
        """.trimIndent()
        
        respond(writer, "200 OK", "text/x-apple-plist+xml", plist)
    }

    private fun handlePlay(body: ByteArray, writer: OutputStreamWriter) {
        try {
            // Body is usually a Plist or pure text/URL
            val parsed = PropertyListParser.parse(body) as? NSDictionary
            val contentLocation = parsed?.objectForKey("Content-Location")?.toString()
            val startPosition = parsed?.objectForKey("Start-Position")?.toString()?.toFloatOrNull() ?: 0f

            if (contentLocation != null) {
                Log.i(TAG, "Video URL received: ${contentLocation}")
                onVideoUrlReceived(contentLocation, startPosition)
            } else {
                // Sometimes it's just a raw text URL in the body
                val rawBody = String(body).trim()
                if (rawBody.startsWith("http")) {
                    Log.i(TAG, "Raw Video URL received: ${rawBody}")
                    onVideoUrlReceived(rawBody, 0f)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing /play body: ${e.message}")
            // Fallback for raw HTTP body
            val rawBody = String(body).trim()
            if (rawBody.startsWith("http")) {
                onVideoUrlReceived(rawBody, 0f)
            }
        }
        
        respond(writer, "200 OK")
    }

    private fun handleStop(writer: OutputStreamWriter) {
        Log.i(TAG, "Stop video requested")
        // To be implemented: send broadcast to VideoActivity
        respond(writer, "200 OK")
    }

    private fun handleRate(writer: OutputStreamWriter) {
        // Pauses or resumes the video
        respond(writer, "200 OK")
    }

    private fun handleScrub(writer: OutputStreamWriter) {
        // Seeks to a position
        respond(writer, "200 OK")
    }

    private fun respond(writer: OutputStreamWriter, status: String, contentType: String = "text/html", body: String = "") {
        val response = StringBuilder()
        response.append("HTTP/1.1 $status\r\n")
        response.append("Content-Type: $contentType\r\n")
        response.append("Content-Length: ${body.toByteArray().size}\r\n")
        response.append("Connection: keep-alive\r\n")
        response.append("\r\n")
        response.append(body)

        writer.write(response.toString())
        writer.flush()
    }
}

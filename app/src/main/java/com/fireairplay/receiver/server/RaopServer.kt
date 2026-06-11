package com.fireairplay.receiver.server

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.fireairplay.receiver.audio.AlacDecoder
import com.fireairplay.receiver.audio.AudioPlayer
import com.fireairplay.receiver.model.TrackMetadata
import kotlinx.coroutines.*
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * AirPlay 1 (RAOP) server implementation in pure Kotlin.
 *
 * Handles the complete lifecycle of an AirPlay audio session:
 * 1. Listens for incoming RTSP connections from Apple devices
 * 2. Processes RTSP commands (OPTIONS, ANNOUNCE, SETUP, RECORD, SET_PARAMETER, FLUSH, TEARDOWN)
 * 3. Negotiates UDP ports for audio/control/timing data
 * 4. Receives RTP audio packets, decrypts AES, decodes ALAC, plays via AudioPlayer
 * 5. Extracts and publishes track metadata (title, artist, artwork, progress)
 *
 * Thread safety:
 * - RTSP runs on its own coroutine
 * - Audio UDP reception runs on a dedicated thread
 * - Metadata updates are posted via the [onMetadataUpdate] callback
 */
class RaopServer(
    private val audioPlayer: AudioPlayer,
    private val alacDecoder: AlacDecoder
) {
    companion object {
        private const val TAG = "RaopServer"
        const val DEFAULT_RTSP_PORT = 5000
        private const val AUDIO_BUFFER_SIZE = 2048
        private const val RTP_HEADER_SIZE = 12

        // Real AirPort Express RSA private key in PKCS#1 DER format (Base64).
        // Extracted from AirPort Express firmware; used by shairport, shairport-sync, etc.
        // This is the key Apple devices use to encrypt the AES session key.
        private const val AIRPORT_KEY_PKCS1_B64 =
            "MIIEpQIBAAKCAQEA59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Q" +
            "g90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6" +
            "DZHJ2YCCLlDRKSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLv" +
            "qr+boEjXuBOitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M" +
            "+5qL71yJQ+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEp" +
            "VviYnhimNVvYFZeCXg/IdTQ+x4IRdiXNv5hEewIDAQABAoIBAQDl8Axy9XfWBLmkzk" +
            "EiqoSwF0PsmVrPzH9KsnwLGH+QZlvjWd8SWYGN7u1507HvhF5N3drJoVU3O14nDY4" +
            "TFQAaLlJ9VM35AApXaLyY1ERrN7u9ALKd2LUwYhM7Km539O4yUFYikE2nIPscEsA5" +
            "ltpxOgUGCY7b7ez5NtD6nL1ZKauw7aNXmVAvmJTcuPxWmoktF3gDJKK2wxZuNGcJE" +
            "0uFQEG4Z3BrWP7yoNuSK3dii2jmlpPHr0O/KnPQtzI3eguhe0TwUem/eYSdyzMyVx" +
            "/YpwkzwtYL3sR5k0o9rKQLtvLzfAqdBxBurcizaaA/L0HIgAmOit1GJA2saMxTVPNh" +
            "AoGBAPfgv1oeZxgxmotiCcMXFEQEWflzhWYTsXrhUIuz5jFua39GLS99ZEErhLdrwj" +
            "8rDDViRVJ5skOp9zFvlYAHs0xh92ji1E7V/ysnKBfsMrPkk5KSKPrnjndMoPdevWnV" +
            "kgJ5jxFuNgxkOLMuG9i53B4yMvDTCRiIPMQ++N2iLDaRAoGBAO9v//mU8eVkQaoANf" +
            "0ZoMjW8CN4xwWA2cSEIHkd9AfFkftuv8oyLDCG3ZAf0vrhrrtkrfa7ef+AUb69DNgg" +
            "q4mHQAYBp7L+k5DKzJrKuO0r+R0YbY9pZD1+/g9dVt91d6LQNepUE/yY2PP5CNoFm" +
            "jedpLHMOPFdVgqDzDFxU8hLAoGBANDrr7xAJbqBjHVwIzQ4To9pb4BNeqDndk5Qe7" +
            "fT3+/H1njGaC0/rXE0Qb7q5ySgnsCb3DvAcJyRM9SJ7OKlGt0FMSdJD5KG0XPIpAV" +
            "NwgpXXH5MDJg09KHeh0kXo+QA6viFBi21y340NonnEfdf54PX4ZGS/Xac1UK+pLkBB" +
            "+zRAoGAf0AY3H3qKS2lMEI4bzEFoHeK3G895pDaK3TFBVmD7fV0Zhov17fegFPMwOI" +
            "I8MisYm9ZfT2Z0s5Ro3s5rkt+nvLAdfC/PYPKzTLalpGSwomSNYJcB9HNMlmhkGzc1" +
            "JnLYT4iyUyx6pcZBmCd8bD0iwY/FzcgNDaUmbX9+XDvRA0CgYEAkE7pIPlE71qvfJQ" +
            "goA9em0gILAuE4Pu13aKiJnfft7hIjbK+5kyb3TysZvoyDnb3HOKvInK7vXbKuU4IS" +
            "gxB2bB3HcYzQMGsz1qJ2gG0N5hvJpzwwhbhXqFKA4zaaSrw622wDniAK5MlIE0tIAK" +
            "KP4yxNGjoD2QYjhBGuhvkWKY="

        /**
         * Sequence of RTSP CSeq responses.
         */
        private var cseq = 0
    }

    // Server state
    private var rtspServerSocket: ServerSocket? = null
    private var activeClientSocket: Socket? = null
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    private var serverJob: Job? = null
    private var audioReceiveJob: Job? = null
    private var scope: CoroutineScope? = null

    // Session encryption
    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var aesCipher: Cipher? = null

    // RTP Jitter Buffer
    private val packetBuffer = arrayOfNulls<ByteArray>(256)
    private var nextPlaySeqNo = -1

    // Ports
    var rtspPort: Int = DEFAULT_RTSP_PORT
        private set
    private var audioPort: Int = 0
    private var controlPort: Int = 0
    private var timingPort: Int = 0

    // Metadata callback — called on background threads
    var onMetadataUpdate: ((TrackMetadata) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    // Current metadata (mutable, updated by SET_PARAMETER)
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentAlbum: String = ""
    private var currentArtwork: android.graphics.Bitmap? = null
    private var currentDuration: Double = 0.0
    private var currentPosition: Double = 0.0

    /**
     * Starts the RAOP RTSP server.
     * Opens a TCP socket on [rtspPort] and waits for connections from Apple devices.
     */
    fun start() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        serverJob = scope?.launch {
            try {
                // Try the default port, fall back to an ephemeral port if unavailable
                rtspServerSocket = try {
                    ServerSocket(DEFAULT_RTSP_PORT)
                } catch (e: Exception) {
                    Log.w(TAG, "Port $DEFAULT_RTSP_PORT unavailable, using ephemeral port")
                    ServerSocket(0)
                }

                rtspPort = rtspServerSocket!!.localPort
                Log.i(TAG, "🎵 RAOP server started on port $rtspPort")
                onStatusUpdate?.invoke("Esperando conexión AirPlay…")

                while (isActive) {
                    // Accept incoming RTSP connections (one at a time for RAOP)
                    val clientSocket = rtspServerSocket!!.accept()
                    Log.i(TAG, "📱 Client connected: ${clientSocket.inetAddress.hostAddress}")
                    onStatusUpdate?.invoke("Conectando…")

                    // Take over previous client connection if any
                    activeClientSocket?.let { oldSocket ->
                        Log.i(TAG, "🔌 Taking over connection: closing previous client")
                        try { oldSocket.close() } catch (_: Exception) {}
                    }
                    activeClientSocket = clientSocket

                    // Handle this client in a child coroutine
                    launch {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error: ${e.message}")
                }
            }
        }
    }

    /**
     * Reads a single line ending in \r\n or \n from the raw InputStream.
     * Avoids BufferedReader so we don't accidentally consume binary payload data.
     */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var c: Int
        val maxLineLength = 4096
        while (true) {
            c = input.read()
            if (c == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (c == '\n'.code) {
                break
            }
            if (c != '\r'.code) {
                if (sb.length < maxLineLength) {
                    sb.append(c.toChar())
                } else {
                    throw IOException("RTSP line length limit exceeded")
                }
            }
        }
        return sb.toString()
    }

    /**
     * Handles a single RTSP client connection.
     * Reads RTSP requests line by line and dispatches to the appropriate handler.
     */
    private suspend fun handleClient(socket: Socket) {
        try {
            val input = socket.getInputStream()
            val output = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))

            socket.soTimeout = 30_000 // 30s timeout so we don't hang

            while (socket.isConnected && !socket.isClosed) {
                // Read the request line (e.g., "OPTIONS * RTSP/1.0")
                val requestLine = readLine(input) ?: break
                Log.i(TAG, ">> $requestLine")

                // Read headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (true) {
                    line = readLine(input)
                    if (line == null || line.isEmpty()) break
                    val colonIndex = line.indexOf(':')
                    if (colonIndex > 0) {
                        val key = line.substring(0, colonIndex).trim()
                        val value = line.substring(colonIndex + 1).trim()
                        headers[key] = value
                        Log.i(TAG, "  H: $key = $value")
                    }
                }

                // Read binary body if Content-Length is specified
                var binaryBody: ByteArray? = null
                var body = ""
                val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
                if (contentLength > 10 * 1024 * 1024) {
                    throw IOException("Content-Length exceeds maximum limit ($contentLength bytes)")
                }
                if (contentLength > 0) {
                    binaryBody = ByteArray(contentLength)
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = input.read(binaryBody, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    
                    // If it's a text payload (e.g. text/parameters), convert to string
                    val contentType = headers["Content-Type"] ?: ""
                    if (contentType.contains("text/parameters") || contentType.contains("application/sdp")) {
                        body = String(binaryBody, Charsets.UTF_8)
                    }
                }

                // Parse the RTSP method
                val parts = requestLine.split(" ")
                val method = parts.firstOrNull() ?: continue
                val uri = if (parts.size > 1) parts[1] else ""
                val cseqStr = headers["CSeq"] ?: "${++cseq}"

                // Dispatch to handler
                val response = when (method) {
                    "OPTIONS" -> handleOptions(cseqStr, headers, socket)
                    "ANNOUNCE" -> handleAnnounce(cseqStr, body, headers)
                    "SETUP" -> handleSetup(cseqStr, headers)
                    "RECORD" -> handleRecord(cseqStr)
                    "SET_PARAMETER" -> handleSetParameter(cseqStr, headers, body, binaryBody)
                    "FLUSH" -> handleFlush(cseqStr)
                    "TEARDOWN" -> handleTeardown(cseqStr)
                    "GET_PARAMETER" -> handleGetParameter(cseqStr, body)
                    "POST" -> {
                        Log.w(TAG, "Rejecting POST request to $uri to force non-paired fallback")
                        buildResponse("RTSP/1.0 501 Not Implemented", cseqStr)
                    }
                    else -> {
                        Log.w(TAG, "Unknown method: $method")
                        buildResponse("RTSP/1.0 400 Bad Request", cseqStr)
                    }
                }

                // Send response
                output.write(response)
                output.flush()
                Log.d(TAG, "<< ${response.lines().first()}")

                // If TEARDOWN, close the connection
                if (method == "TEARDOWN") break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client handler error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
            if (activeClientSocket == socket) {
                activeClientSocket = null
                onStatusUpdate?.invoke("Esperando conexión AirPlay…")
            }
        }
    }

    // =====================================================================
    // RTSP Request Handlers
    // =====================================================================

    /**
     * Converts a PKCS#1 RSA private key (raw DER) to PKCS#8 format so that
     * Java's KeyFactory can load it. Android does not support PKCS#1 directly.
     */
    private fun pkcs1ToPkcs8(pkcs1Der: ByteArray): ByteArray {
        // RSA algorithm identifier: SEQUENCE { OID rsaEncryption, NULL }
        val algId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
            0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)

        fun encodeLength(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }

        val octetContent = byteArrayOf(0x04) + encodeLength(pkcs1Der.size) + pkcs1Der
        val seqContent = version + algId + octetContent
        return byteArrayOf(0x30) + encodeLength(seqContent.size) + seqContent
    }

    /**
     * Loads the AirPort Express RSA private key from the embedded PKCS#1 base64.
     */
    private fun loadAirportPrivateKey(): java.security.PrivateKey {
        val pkcs1Der = Base64.decode(AIRPORT_KEY_PKCS1_B64, Base64.DEFAULT)
        val pkcs8Der = pkcs1ToPkcs8(pkcs1Der)
        val keyFactory = java.security.KeyFactory.getInstance("RSA")
        return keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8Der))
    }

    /**
     * Signs the Apple-Challenge using RSA PKCS#1 v1.5 raw (no hash) encryption.
     */
    private fun buildAppleResponse(challenge: String, localIp: InetAddress, macAddress: ByteArray): String {
        return try {
            val hdr = challenge.trim()
            val padded64 = hdr + "=".repeat((4 - hdr.length % 4) % 4)
            val challengeBytes = Base64.decode(padded64, Base64.DEFAULT)

            val buf = ByteArray(48)
            var bp = 0
            val chLen = minOf(challengeBytes.size, 16)
            System.arraycopy(challengeBytes, 0, buf, bp, chLen); bp += chLen

            val rawAddr = localIp.address
            if (rawAddr.size == 16) {
                System.arraycopy(rawAddr, 0, buf, bp, 16); bp += 16
            } else {
                System.arraycopy(rawAddr, 0, buf, bp, 4); bp += 4
            }
            val macLen = minOf(macAddress.size, 6)
            System.arraycopy(macAddress, 0, buf, bp, macLen); bp += macLen

            var bufLen = bp
            if (bufLen < 32) bufLen = 32

            val data = buf.copyOf(bufLen)
            val privateKey = loadAirportPrivateKey()
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            val sig = cipher.doFinal(data)

            Base64.encodeToString(sig, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            Log.e(TAG, "Apple-Challenge signing failed: ${e.message}", e)
            ""
        }
    }

    private fun handleOptions(cseq: String, headers: Map<String, String> = emptyMap(), socket: Socket? = null): String {
        val challenge = headers["Apple-Challenge"]
        return if (challenge != null && socket != null) {
            val mac = try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                var macBytes = byteArrayOf(0,0,0,0,0,0)
                while (interfaces.hasMoreElements()) {
                    val ni = interfaces.nextElement()
                    if (ni.name.equals("wlan0", ignoreCase = true)) {
                        macBytes = ni.hardwareAddress ?: macBytes; break
                    }
                }
                macBytes
            } catch (e: Exception) { byteArrayOf(0,0,0,0,0,0) }
            val appleResponse = buildAppleResponse(challenge, socket.localAddress, mac)
            buildResponse(
                "RTSP/1.0 200 OK", cseq,
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER",
                "Apple-Response" to appleResponse
            )
        } else {
            buildResponse(
                "RTSP/1.0 200 OK", cseq,
                "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        }
    }

    private fun handleAnnounce(cseq: String, body: String, headers: Map<String, String>): String {
        val lines = body.lines()

        for (line in lines) {
            when {
                line.startsWith("a=fmtp:") -> {
                    val fmtpStr = line.substringAfter("a=fmtp:").trim()
                    val fmtpValues = fmtpStr.split(" ").mapNotNull { it.toIntOrNull() }
                    if (fmtpValues.isNotEmpty()) {
                        alacDecoder.initialize(fmtpValues)
                    }
                }
                line.startsWith("a=rsaaeskey:") -> {
                    val encryptedKeyB64 = line.substringAfter("a=rsaaeskey:").trim()
                    aesKey = decryptRsaAesKey(encryptedKeyB64)
                }
                line.startsWith("a=aesiv:") -> {
                    val ivB64 = line.substringAfter("a=aesiv:").trim()
                    aesIv = Base64.decode(ivB64, Base64.DEFAULT)
                }
            }
        }

        if (aesKey != null && aesIv != null) {
            try {
                aesCipher = Cipher.getInstance("AES/CBC/NoPadding")
                val keySpec = SecretKeySpec(aesKey, "AES")
                val ivSpec = IvParameterSpec(aesIv)
                aesCipher?.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            } catch (e: Exception) {
                aesCipher = null
            }
        }

        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    private fun handleSetup(cseq: String, headers: Map<String, String>): String {
        val transport = headers["Transport"] ?: ""
        val clientControlPort = extractPort(transport, "control_port")
        val clientTimingPort = extractPort(transport, "timing_port")

        // Close existing sockets to prevent port leaks
        try { audioSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        try { timingSocket?.close() } catch (_: Exception) {}

        audioSocket = DatagramSocket(0)
        controlSocket = DatagramSocket(0)
        timingSocket = DatagramSocket(0)

        audioPort = audioSocket!!.localPort
        controlPort = controlSocket!!.localPort
        timingPort = timingSocket!!.localPort

        val transportResponse = "RTP/AVP/UDP;unicast;mode=record;" +
                "server_port=$audioPort;" +
                "control_port=$controlPort;" +
                "timing_port=$timingPort"

        return buildResponse(
            "RTSP/1.0 200 OK", cseq,
            "Transport" to transportResponse,
            "Session" to "1",
            "Audio-Jack-Status" to "connected"
        )
    }

    private fun handleRecord(cseq: String): String {
        audioPlayer.initialize()
        audioPlayer.start()
        startAudioReceiver()
        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    private fun handleSetParameter(
        cseq: String,
        headers: Map<String, String>,
        body: String,
        binaryBody: ByteArray?
    ): String {
        val contentType = headers["Content-Type"] ?: ""
        when {
            contentType.contains("text/parameters") -> parseTextParameters(body)
            contentType.contains("application/x-dmap-tagged") -> binaryBody?.let { parseDmapMetadata(it) }
            contentType.startsWith("image/") -> binaryBody?.let { parseArtwork(it) }
        }
        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    private fun handleFlush(cseq: String): String {
        audioPlayer.flush()
        packetBuffer.fill(null)
        nextPlaySeqNo = -1
        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    private fun handleTeardown(cseq: String): String {
        Log.i(TAG, "TEARDOWN — Session ended")
        stopAudioReceiver()
        audioPlayer.stop()

        // Reset metadata
        currentTitle = ""
        currentArtist = ""
        currentAlbum = ""
        currentArtwork = null
        currentDuration = 0.0
        currentPosition = 0.0
        publishMetadata()

        onStatusUpdate?.invoke("Esperando conexión AirPlay…")

        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    /**
     * GET_PARAMETER — keep-alive heartbeat or parameter request from the client.
     * Often asks for "volume\r\n", to which we must respond with "volume: 0.0\r\n".
     */
    private fun handleGetParameter(cseq: String, body: String): String {
        Log.i(TAG, "GET_PARAMETER requested: $body")
        if (body.contains("volume")) {
            val responseBody = "volume: 0.0\r\n"
            val sb = StringBuilder()
            sb.append("RTSP/1.0 200 OK\r\n")
            sb.append("CSeq: $cseq\r\n")
            sb.append("Server: FireAirPlay/1.0\r\n")
            sb.append("Content-Type: text/parameters\r\n")
            sb.append("Content-Length: ${responseBody.length}\r\n")
            sb.append("\r\n")
            sb.append(responseBody)
            return sb.toString()
        }
        return buildResponse("RTSP/1.0 200 OK", cseq)
    }

    // =====================================================================
    // Audio Reception
    // =====================================================================

    /**
     * Starts a coroutine that continuously receives RTP audio packets from the
     * UDP audio socket, decrypts them (AES-CBC), decodes ALAC, and sends PCM
     * to the AudioPlayer.
     */
    private fun startAudioReceiver() {
        stopAudioReceiver() // Cancel any active audio receiver job first to prevent leaks
        audioReceiveJob = scope?.launch(Dispatchers.IO) {
            val buffer = ByteArray(AUDIO_BUFFER_SIZE)
            val packet = DatagramPacket(buffer, buffer.size)

            Log.i(TAG, "Audio receiver started on UDP port $audioPort")

            while (isActive) {
                try {
                    // CRITICAL: Reset packet length before receive, otherwise it shrinks
                    // to the size of the smallest received packet and truncates future data!
                    packet.length = buffer.size
                    audioSocket?.receive(packet)

                    if (packet.length >= 12) {
                        val header = packet.data
                        val offset = packet.offset

                        // Payload Type is the lower 7 bits of the second byte
                        val payloadType = header[offset + 1].toInt() and 0x7F

                        // AirPlay audio is payload type 96. Resent audio is payload type 86.
                        if (payloadType == 96 || payloadType == 86) {
                            var headerSize = 12
                            var rtpOffset = offset
                            if (payloadType == 86) {
                                // Resent packets have an extra 4-byte header
                                headerSize = 16
                                rtpOffset = offset + 4
                            }

                            if (packet.length > headerSize) {
                                val audioData = ByteArray(packet.length - headerSize)
                                System.arraycopy(header, offset + headerSize, audioData, 0, audioData.size)

                                // Extract sequence number
                                val seqNo = ((header[rtpOffset + 2].toInt() and 0xFF) shl 8) or 
                                            (header[rtpOffset + 3].toInt() and 0xFF)

                                // Decrypt if AES is configured
                                val decryptedData = decryptAudio(audioData)

                                if (nextPlaySeqNo == -1) {
                                    nextPlaySeqNo = seqNo
                                }

                                var distance = seqNo - nextPlaySeqNo
                                if (distance < -32768) distance += 65536
                                else if (distance > 32768) distance -= 65536

                                if (distance < 0) {
                                    // Late packet, drop it
                                } else if (distance >= 256) {
                                    // Massive jump, reset buffer
                                    packetBuffer.fill(null)
                                    nextPlaySeqNo = seqNo
                                    packetBuffer[seqNo % 256] = decryptedData
                                } else {
                                    packetBuffer[seqNo % 256] = decryptedData
                                }

                                 // Force advance if gap is too large to prevent indefinite stalls (3 packets ≈ 24ms)
                                 if (distance > 3) {
                                     while (packetBuffer[nextPlaySeqNo % 256] == null && nextPlaySeqNo != seqNo) {
                                         nextPlaySeqNo = (nextPlaySeqNo + 1) and 0xFFFF
                                     }
                                 }

                                // Play all contiguous available packets sequentially
                                while (packetBuffer[nextPlaySeqNo % 256] != null) {
                                    val data = packetBuffer[nextPlaySeqNo % 256]!!
                                    packetBuffer[nextPlaySeqNo % 256] = null

                                    val pcmSamples = alacDecoder.decode(data)
                                    if (pcmSamples != null && pcmSamples.isNotEmpty()) {
                                        // Enqueue PCM to the AudioPlayer channel
                                        audioPlayer.enqueuePcmBlocking(pcmSamples)
                                    }

                                    nextPlaySeqNo = (nextPlaySeqNo + 1) and 0xFFFF
                                }
                            }
                        } else {
                            // Ignore non-audio packets (e.g. RTCP or sync on this port)
                            // Log.v(TAG, "Ignored non-audio packet: PT=$payloadType")
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Audio receive error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Stops the audio receiver coroutine.
     */
    private fun stopAudioReceiver() {
        audioReceiveJob?.cancel()
        audioReceiveJob = null
    }

    /**
     * Decrypts an AES-128-CBC encrypted audio frame.
     * AirPlay encrypts in 16-byte blocks. Any remaining bytes (< 16) at the end
     * are left unencrypted.
     */
    private fun decryptAudio(data: ByteArray): ByteArray {
        if (aesCipher == null || aesKey == null || aesIv == null) {
            return data // No encryption configured
        }

        try {
            // Re-initialize cipher with the original IV for each frame
            val keySpec = SecretKeySpec(aesKey, "AES")
            val ivSpec = IvParameterSpec(aesIv)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

            // Only decrypt full 16-byte blocks
            val encryptedLength = (data.size / 16) * 16
            val decrypted = ByteArray(data.size)

            if (encryptedLength > 0) {
                val decryptedBlocks = cipher.doFinal(data, 0, encryptedLength)
                System.arraycopy(decryptedBlocks, 0, decrypted, 0, decryptedBlocks.size)
            }

            // Copy remaining unencrypted bytes
            if (encryptedLength < data.size) {
                System.arraycopy(data, encryptedLength, decrypted, encryptedLength, data.size - encryptedLength)
            }

            return decrypted
        } catch (e: Exception) {
            Log.w(TAG, "AES decryption error: ${e.message}")
            return data
        }
    }

    /**
     * Decrypts the RSA-encrypted AES session key using the AirPort Express private key.
     */
    private fun decryptRsaAesKey(encryptedKeyB64: String): ByteArray? {
        return try {
            val encryptedKey = Base64.decode(encryptedKeyB64, Base64.DEFAULT)
            val privateKey = loadAirportPrivateKey()
            // AirPlay encrypts the AES key with RSA OAEP (SHA-1)
            val rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding")
            rsaCipher.init(Cipher.DECRYPT_MODE, privateKey)
            rsaCipher.doFinal(encryptedKey)
        } catch (e: Exception) {
            Log.e(TAG, "RSA decryption failed: ${e.message}")
            null
        }
    }

    // =====================================================================
    // Metadata Parsing
    // =====================================================================

    /**
     * Parses text/parameters content from SET_PARAMETER.
     * Contains progress info in the format: "progress: RTP_START/RTP_CURRENT/RTP_END"
     */
    private fun parseTextParameters(body: String) {
        val lines = body.lines()
        for (line in lines) {
            when {
                line.startsWith("progress:") -> {
                    // Format: "progress: start/current/end" (in RTP timestamp units)
                    val progressStr = line.substringAfter("progress:").trim()
                    val parts = progressStr.split("/")
                    if (parts.size == 3) {
                        val start = parts[0].toLongOrNull() ?: 0L
                        val current = parts[1].toLongOrNull() ?: 0L
                        val end = parts[2].toLongOrNull() ?: 0L

                        // Convert RTP timestamps to seconds (44100 Hz sample rate)
                        val sampleRate = alacDecoder.sampleRate.toDouble()
                        currentDuration = (end - start) / sampleRate
                        currentPosition = (current - start) / sampleRate

                        Log.d(TAG, "Progress: ${currentPosition}s / ${currentDuration}s")
                        publishMetadata()
                    }
                }

                line.startsWith("volume:") -> {
                    val volumeDb = line.substringAfter("volume:").trim().toDoubleOrNull()
                    if (volumeDb != null) {
                        Log.d(TAG, "Volume: $volumeDb dB")
                        // Volume is in dB, typically -144 = mute, -30 = min, 0 = max
                        if (volumeDb <= -144.0) {
                            audioPlayer.setVolume(0.0f)
                        } else {
                            // Map the -30dB (min) to 0dB (max) range to a 0.0 to 1.0 linear scale.
                            // The true logarithmic conversion drops the amplitude too drastically for TV speakers.
                            val normalized = 1.0f - (volumeDb / -30.0).toFloat()
                            
                            // Apply a slight curve to make the volume slider feel natural
                            val linear = normalized * normalized * normalized
                            
                            audioPlayer.setVolume(linear.coerceIn(0.0f, 1.0f))
                        }
                    }
                }
            }
        }
    }

    /**
     * Parses DMAP/DAAP binary metadata from SET_PARAMETER.
     *
     * DMAP format: Each entry is [4-byte tag][4-byte size][data]
     * Common tags:
     * - "minm" = track title
     * - "asar" = artist name
     * - "asal" = album name
     * - "asgn" = genre
     */
    private fun parseDmapMetadata(data: ByteArray) {
        var offset = 0

        try {
            while (offset + 8 <= data.size) {
                // Read tag (4 bytes ASCII)
                val tag = String(data, offset, 4, Charsets.US_ASCII)
                offset += 4

                // Read size (4 bytes big-endian)
                val size = ((data[offset].toInt() and 0xFF) shl 24) or
                        ((data[offset + 1].toInt() and 0xFF) shl 16) or
                        ((data[offset + 2].toInt() and 0xFF) shl 8) or
                        (data[offset + 3].toInt() and 0xFF)
                offset += 4

                if (size < 0 || size > data.size - offset) {
                    Log.w(TAG, "DMAP parse: invalid tag size $size at offset $offset")
                    break
                }

                // Extract string value for known tags
                val value = String(data, offset, size, Charsets.UTF_8)

                when (tag) {
                    "minm" -> {
                        currentTitle = value
                        Log.i(TAG, "🎵 Title: $currentTitle")
                    }
                    "asar" -> {
                        currentArtist = value
                        Log.i(TAG, "🎤 Artist: $currentArtist")
                    }
                    "asal" -> {
                        currentAlbum = value
                        Log.i(TAG, "💿 Album: $currentAlbum")
                    }
                    "mlit", "mcon" -> {
                        // Container tags — their content contains nested tags
                        // Don't skip the content; let the loop parse the inner tags
                        offset -= size // Rewind so the loop processes nested entries
                        offset += 0 // No-op, just documenting the intent
                    }
                }

                offset += size
            }
        } catch (e: Exception) {
            Log.w(TAG, "DMAP parse error: ${e.message}")
        }

        publishMetadata()
    }

    /**
     * Decodes album artwork from binary image data (JPEG or PNG).
     */
    private fun parseArtwork(data: ByteArray) {
        try {
            val options = BitmapFactory.Options().apply {
                // First pass: get dimensions only
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)

            // Calculate sample size for efficient loading (target 600px)
            val targetSize = 600
            var sampleSize = 1
            if (options.outHeight > targetSize || options.outWidth > targetSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / sampleSize) >= targetSize && (halfWidth / sampleSize) >= targetSize) {
                    sampleSize *= 2
                }
            }

            // Second pass: decode at sampled size
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, decodeOptions)

            if (bitmap != null) {
                currentArtwork = bitmap
                Log.i(TAG, "🖼️ Artwork received: ${bitmap.width}x${bitmap.height}")
                publishMetadata()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Artwork decode error: ${e.message}")
        }
    }

    /**
     * Publishes the current metadata state to the UI via callback.
     */
    private fun publishMetadata() {
        val metadata = TrackMetadata(
            title = currentTitle,
            artist = currentArtist,
            album = currentAlbum,
            artwork = currentArtwork,
            durationSeconds = currentDuration,
            positionSeconds = currentPosition,
            isPlaying = audioPlayer.isCurrentlyPlaying(),
            sampleRate = alacDecoder.sampleRate,
            sampleSize = alacDecoder.sampleSize,
            numChannels = alacDecoder.numChannels
        )
        onMetadataUpdate?.invoke(metadata)
    }

    // =====================================================================
    // Utility
    // =====================================================================

    /**
     * Builds an RTSP response string with the given status, CSeq, and optional headers.
     */
    private fun buildResponse(statusLine: String, cseq: String, vararg headers: Pair<String, String>): String {
        val sb = StringBuilder()
        sb.appendLine(statusLine)
        sb.appendLine("CSeq: $cseq")
        sb.appendLine("Server: FireAirPlay/1.0")
        for ((key, value) in headers) {
            sb.appendLine("$key: $value")
        }
        sb.appendLine() // Empty line to end headers
        return sb.toString()
    }

    /**
     * Extracts a port number from the Transport header string.
     */
    private fun extractPort(transport: String, key: String): Int {
        val regex = Regex("$key=(\\d+)")
        return regex.find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Stops the server and releases all resources.
     */
    fun stop() {
        Log.i(TAG, "Stopping RAOP server")
        stopAudioReceiver()
        serverJob?.cancel()
        scope?.cancel()

        audioSocket?.close()
        controlSocket?.close()
        timingSocket?.close()
        rtspServerSocket?.close()

        audioSocket = null
        controlSocket = null
        timingSocket = null
        rtspServerSocket = null
        scope = null

        Log.i(TAG, "RAOP server stopped")
    }
}

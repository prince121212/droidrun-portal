package com.droidrun.portal

import android.util.Log
import android.net.Uri
import android.content.ContentValues
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SocketServer(private val accessibilityService: DroidrunAccessibilityService) {
    companion object {
        private const val TAG = "DroidrunSocketServer"
        private const val DEFAULT_PORT = 8080
        private const val THREAD_POOL_SIZE = 5
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = AtomicBoolean(false)
    private val executorService: ExecutorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
    private var port: Int = DEFAULT_PORT

    fun start(port: Int = DEFAULT_PORT): Boolean {
        if (isRunning.get()) {
            Log.w(TAG, "Server already running on port ${this.port}")
            return true
        }

        this.port = port
        Log.i(TAG, "Starting socket server on port $port...")
        
        return try {
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            
            Log.i(TAG, "ServerSocket created successfully on port $port")
            
            // Start accepting connections in background
            executorService.submit {
                Log.i(TAG, "Starting connection acceptor thread")
                acceptConnections()
            }
            
            Log.i(TAG, "Socket server started successfully on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start socket server on port $port", e)
            isRunning.set(false)
            false
        }
    }

    fun stop() {
        if (!isRunning.get()) return

        isRunning.set(false)
        
        try {
            serverSocket?.close()
            executorService.shutdown()
            Log.i(TAG, "Socket server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping socket server", e)
        }
    }

    fun isRunning(): Boolean = isRunning.get()
    fun getPort(): Int = port

    private fun acceptConnections() {
        Log.i(TAG, "acceptConnections() started, waiting for clients...")
        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for client connection...")
                val clientSocket = serverSocket?.accept() ?: break
                Log.i(TAG, "Client connected: ${clientSocket.remoteSocketAddress}")
                executorService.submit {
                    handleClient(clientSocket)
                }
            } catch (e: SocketException) {
                if (isRunning.get()) {
                    Log.e(TAG, "Socket exception while accepting connections", e)
                } else {
                    Log.i(TAG, "Socket closed normally during shutdown")
                }
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting connection", e)
            }
        }
        Log.i(TAG, "acceptConnections() stopped")
    }

    private fun handleClient(clientSocket: Socket) {
        try {
            Log.d(TAG, "Handling client connection from: ${clientSocket.remoteSocketAddress}")
            clientSocket.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val writer = OutputStreamWriter(socket.getOutputStream())

                // Read HTTP request line
                val requestLine = reader.readLine()
                Log.d(TAG, "Request line: $requestLine")
                
                if (requestLine == null) {
                    Log.w(TAG, "No request line received")
                    return
                }
                
                val parts = requestLine.split(" ")
                
                if (parts.size < 2) {
                    Log.w(TAG, "Invalid request line format: $requestLine")
                    sendErrorResponse(writer, 400, "Bad Request")
                    return
                }

                val method = parts[0]
                val path = parts[1]
                Log.d(TAG, "Processing request: $method $path")

                // Skip headers for simplicity
                var line: String?
                do {
                    line = reader.readLine()
                    if (line != null) Log.v(TAG, "Header: $line")
                } while (line?.isNotEmpty() == true)

                // Handle the request based on path and method
                val response = when (method) {
                    "GET" -> {
                        Log.d(TAG, "Handling GET request for: $path")
                        handleGetRequest(path)
                    }
                    "POST" -> {
                        Log.d(TAG, "Handling POST request for: $path")
                        handlePostRequest(path, reader)
                    }
                    else -> {
                        Log.w(TAG, "Unsupported method: $method")
                        createErrorResponse("Method not allowed: $method")
                    }
                }

                Log.d(TAG, "Sending response: ${response.take(100)}...")
                sendHttpResponse(writer, response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun handleGetRequest(path: String): String {
        Log.d(TAG, "Handling GET request for path: $path")
        return when {
            path.startsWith("/a11y_tree") -> {
                Log.d(TAG, "Processing /a11y_tree request")
                getAccessibilityTree()
            }
            path.startsWith("/phone_state") -> {
                Log.d(TAG, "Processing /phone_state request")
                getPhoneState()
            }
            path.startsWith("/state") -> {
                Log.d(TAG, "Processing /state request")
                getCombinedState()
            }
            path.startsWith("/ping") -> {
                Log.d(TAG, "Processing /ping request")
                createSuccessResponse("pong")
            }
            else -> {
                Log.w(TAG, "Unknown endpoint requested: $path")
                createErrorResponse("Unknown endpoint: $path")
            }
        }
    }

    private fun handlePostRequest(path: String, reader: BufferedReader): String {
        return when {
            path.startsWith("/keyboard/") -> {
                val action = path.substringAfterLast("/")
                handleKeyboardAction(action, reader)
            }
            path.startsWith("/overlay_offset") -> {
                handleOverlayOffset(reader)
            }
            else -> createErrorResponse("Unknown POST endpoint: $path")
        }
    }

    private fun handleKeyboardAction(action: String, reader: BufferedReader): String {
        return try {
            // Read POST data if present
            val contentLength = 0 // We'll parse this if needed
            val postData = StringBuilder()
            if (reader.ready()) {
                val char = CharArray(1024)
                val bytesRead = reader.read(char)
                if (bytesRead > 0) {
                    postData.append(char, 0, bytesRead)
                }
            }

            // Parse URL-encoded or JSON data
            val values = parsePostData(postData.toString())

            when (action) {
                "input" -> performKeyboardInputBase64(values)
                "clear" -> performKeyboardClear()
                "key" -> performKeyboardKey(values)
                else -> "error: Unknown keyboard action: $action"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling keyboard action", e)
            "error: Failed to handle keyboard action: ${e.message}"
        }
    }

    private fun handleOverlayOffset(reader: BufferedReader): String {
        return try {
            val postData = StringBuilder()
            if (reader.ready()) {
                val char = CharArray(1024)
                val bytesRead = reader.read(char)
                if (bytesRead > 0) {
                    postData.append(char, 0, bytesRead)
                }
            }

            val values = parsePostData(postData.toString())
            val offset = values.getAsInteger("offset") 
                ?: return "error: No offset provided"

            val success = accessibilityService.setOverlayOffset(offset)
            
            if (success) {
                "success: Overlay offset updated to $offset"
            } else {
                "error: Failed to update overlay offset"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling overlay offset", e)
            "error: Failed to handle overlay offset: ${e.message}"
        }
    }

    private fun parsePostData(data: String): ContentValues {
        val values = ContentValues()
        
        if (data.isBlank()) return values

        try {
            // Try parsing as JSON first
            if (data.trim().startsWith("{")) {
                val json = JSONObject(data)
                json.keys().forEach { key ->
                    val value = json.get(key)
                    when (value) {
                        is String -> values.put(key, value)
                        is Int -> values.put(key, value)
                        is Boolean -> values.put(key, value)
                        else -> values.put(key, value.toString())
                    }
                }
            } else {
                // Parse as URL-encoded data
                data.split("&").forEach { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = Uri.decode(parts[0])
                        val value = Uri.decode(parts[1])
                        
                        // Try to parse as integer
                        val intValue = value.toIntOrNull()
                        if (intValue != null) {
                            values.put(key, intValue)
                        } else {
                            // Try to parse as boolean
                            when (value.lowercase()) {
                                "true" -> values.put(key, true)
                                "false" -> values.put(key, false)
                                else -> values.put(key, value)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing POST data", e)
        }

        return values
    }

    private fun sendHttpResponse(writer: OutputStreamWriter, response: String) {
        try {
            val responseBytes = response.toByteArray(Charsets.UTF_8)
            val httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Content-Type\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    response

            writer.write(httpResponse)
            writer.flush()
            Log.d(TAG, "HTTP response sent: ${response.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending HTTP response", e)
        }
    }

    private fun sendErrorResponse(writer: OutputStreamWriter, code: Int, message: String) {
        try {
            val errorResponse = createErrorResponse(message)
            val responseBytes = errorResponse.toByteArray(Charsets.UTF_8)
            val httpResponse = "HTTP/1.1 $code $message\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    errorResponse

            writer.write(httpResponse)
            writer.flush()
            Log.d(TAG, "HTTP error response sent: $code $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending error response", e)
        }
    }

    // Mirror content provider methods
    private fun getAccessibilityTree(): String {
        return try {
            Log.d(TAG, "Getting accessibility tree...")
            val elements = accessibilityService.getVisibleElements()
            Log.d(TAG, "Found ${elements.size} visible elements")
            
            val treeJson = elements.map { element ->
                buildElementNodeJson(element)
            }
            val response = createSuccessResponse(treeJson.toString())
            Log.d(TAG, "Accessibility tree response created: ${response.length} chars")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get accessibility tree", e)
            createErrorResponse("Failed to get accessibility tree: ${e.message}")
        }
    }

    private fun getPhoneState(): String {
        return try {
            Log.d(TAG, "Getting phone state...")
            val phoneState = buildPhoneStateJson(accessibilityService.getPhoneState())
            val response = createSuccessResponse(phoneState.toString())
            Log.d(TAG, "Phone state response created: ${response.length} chars")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get phone state", e)
            createErrorResponse("Failed to get phone state: ${e.message}")
        }
    }

    private fun getCombinedState(): String {
        return try {
            // Get accessibility tree
            val treeJson = accessibilityService.getVisibleElements().map { element ->
                buildElementNodeJson(element)
            }
            
            // Get phone state
            val phoneStateJson = buildPhoneStateJson(accessibilityService.getPhoneState())
            
            // Combine both in a single response
            val combinedState = JSONObject().apply {
                put("a11y_tree", org.json.JSONArray(treeJson))
                put("phone_state", phoneStateJson)
            }
            
            createSuccessResponse(combinedState.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get combined state", e)
            createErrorResponse("Failed to get combined state: ${e.message}")
        }
    }

    private fun buildElementNodeJson(element: com.droidrun.portal.model.ElementNode): JSONObject {
        return JSONObject().apply {
            put("index", element.overlayIndex)
            put("resourceId", element.nodeInfo.viewIdResourceName ?: "")
            put("className", element.className)
            put("text", element.text)
            put("bounds", "${element.rect.left}, ${element.rect.top}, ${element.rect.right}, ${element.rect.bottom}")

            // Recursively build children JSON
            val childrenArray = org.json.JSONArray()
            element.children.forEach { child ->
                childrenArray.put(buildElementNodeJson(child))
            }
            put("children", childrenArray)
        }
    }

    private fun buildPhoneStateJson(phoneState: com.droidrun.portal.model.PhoneState) =
        JSONObject().apply {
            put("currentApp", phoneState.appName)
            put("packageName", phoneState.packageName)
            put("keyboardVisible", phoneState.keyboardVisible)
            put("focusedElement", JSONObject().apply {
                put("text", phoneState.focusedElement?.text)
                put("className", phoneState.focusedElement?.className)
                put("resourceId", phoneState.focusedElement?.viewIdResourceName ?: "")
            })
        }

    private fun performKeyboardInputBase64(values: ContentValues): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        val base64Text = values.getAsString("base64_text")
            ?: return "error: No base64_text provided"

        return try {
            if (keyboardIME.inputB64Text(base64Text)) {
                val decoded = android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT)
                val decodedText = String(decoded, Charsets.UTF_8)
                "success: Base64 text input via keyboard - '$decodedText'"
            } else {
                "error: Failed to input base64 text via keyboard"
            }
        } catch (e: Exception) {
            "error: Invalid base64 encoding: ${e.message}"
        }
    }

    private fun performKeyboardClear(): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        return if (keyboardIME.clearText()) {
            "success: Text cleared via keyboard"
        } else {
            "error: Failed to clear text via keyboard"
        }
    }

    private fun performKeyboardKey(values: ContentValues): String {
        val keyboardIME = DroidrunKeyboardIME.getInstance()
            ?: return "error: DroidrunKeyboardIME not active or available"

        if (!keyboardIME.hasInputConnection()) {
            return "error: No input connection available - keyboard may not be focused on an input field"
        }

        val keyCode = values.getAsInteger("key_code")
            ?: return "error: No key_code provided"

        return if (keyboardIME.sendKeyEventDirect(keyCode)) {
            "success: Key event sent via keyboard - code: $keyCode"
        } else {
            "error: Failed to send key event via keyboard"
        }
    }

    private fun createSuccessResponse(data: String): String {
        return JSONObject().apply {
            put("status", "success")
            put("data", data)
        }.toString()
    }

    private fun createErrorResponse(error: String): String {
        return JSONObject().apply {
            put("status", "error")
            put("error", error)
        }.toString()
    }
}
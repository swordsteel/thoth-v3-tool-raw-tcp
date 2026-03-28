package ltd.lulz.thoth.tool

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ltd.lulz.thoth.library.event.ThothLogger
import ltd.lulz.thoth.library.plugin.PluginType
import ltd.lulz.thoth.library.tool.ToolProvider
import ltd.lulz.thoth.library.tool.model.ThothTool
import ltd.lulz.thoth.library.tool.model.ThothToolProperty
import ltd.lulz.thoth.library.tool.model.ThothToolResponse

private val logger = ThothLogger.logger("TcpSocketTool")

class TcpSocket : ToolProvider {

    override val type: PluginType = PluginType.TOOL_PROVIDER

    override val id: String = "tcp-socket"

    override val name: String = "tcp_socket"

    override val description: String = """
        Raw TCP socket tool with background line collection.

        Operations:
        - connect     → open connection, returns session_id
        - send        → send raw text (no transformations)
        - receive     → returns collected lines (waits for first line, then collects until silence)
        - disconnect  → close connection

        This is a RAW TCP socket - data is sent exactly as provided.
        No CRLF conversion, no HTTP auto-detection, no protocol-specific handling.
        Use \\n for LF, \\r for CR, or \\r\\n for CRLF as needed by your protocol.
    """.trimIndent()

    override fun getDetails(): String = """
        ## Tool: tcp_socket
        
        Raw TCP socket for network communication. This tool provides low-level TCP 
        connectivity with session management and intelligent line collection.
        
        ### When to use this tool
        - Connecting to TCP-based services (SMTP, IRC, custom protocols, telnet)
        - Raw socket communication for debugging or testing
        - Interacting with systems that require raw TCP connections
        - When you need complete control over what's sent
        
        ### Operations
        
        | Operation | Description |
        |-----------|-------------|
        | `connect` | Open connection to host:port, returns session_id |
        | `send` | Send raw text exactly as provided |
        | `receive` | Get collected lines (waits for silence to determine end) |
        | `disconnect` | Close connection and clean up session |
        
        ### Parameters
        
        | Parameter | Type | Required | Description |
        |-----------|------|----------|-------------|
        | `operation` | string | yes | connect, send, receive, disconnect |
        | `host` | string | conditional | Hostname or IP (for connect) |
        | `port` | integer | conditional | TCP port 1-65535 (for connect) |
        | `session_id` | string | conditional | Session ID (for send/receive/disconnect) |
        | `message` | string | conditional | Raw text to send (for send) |
        | `initial_wait_seconds` | integer | no | Max wait for first line (default: 30) |
        | `silence_timeout_seconds` | integer | no | Silence timeout (default: 10) |
        
        ### Raw Socket Behavior
        
        This is a RAW TCP socket tool:
        - Data is sent EXACTLY as provided - no transformations
        - No CRLF auto-conversion
        - No HTTP detection
        - Use \\n for LF, \\r for CR, \\r\\n for CRLF as your protocol requires
        - For HTTP: manually include proper CRLF line endings
        - For telnet-style: just use \\n
        
        ### Best Practices
        
        - Always disconnect sessions when done to free resources
        - Use appropriate timeouts for your use case
        - Check response for error messages before proceeding
        - Know your protocol's line ending requirements
        
        ### Examples
        
        Connect to a server:
        ```json
        {"operation": "connect", "host": "example.com", "port": 80}
        ```
        
        Send raw HTTP request (manual CRLF):
        ```json
        {
          "operation": "send",
          "session_id": "abc-123",
          "message": "GET / HTTP/1.1\\r\\nHost: example.com\\r\\n\\r\\n"
        }
        ```
        
        Send telnet-style command:
        ```json
        {
          "operation": "send",
          "session_id": "abc-123",
          "message": "HELLO\\n"
        }
        ```
        
        Receive response:
        ```json
        {"operation": "receive", "session_id": "abc-123"}
        ```
        
        Disconnect:
        ```json
        {"operation": "disconnect", "session_id": "abc-123"}
        ```
        
        ### Error Handling
        
        - Connection errors return descriptive messages
        - Session not found errors indicate expired/invalid session IDs
        - Timeout errors indicate server didn't respond within wait period
    """.trimIndent()

    override fun getSchema(): ThothTool = ThothTool(
        name = name,
        description = description,
        parameters = listOf(
            ThothToolProperty(
                name = "operation",
                type = "string",
                description = "connect, send, receive, or disconnect",
                required = true
            ),
            ThothToolProperty(
                name = "host",
                type = "string",
                description = "Hostname or IP (for connect)",
                required = false
            ),
            ThothToolProperty(
                name = "port",
                type = "integer",
                description = "TCP port 1-65535 (for connect)",
                required = false
            ),
            ThothToolProperty(
                name = "session_id",
                type = "string",
                description = "Session ID (for send/receive/disconnect)",
                required = false
            ),
            ThothToolProperty(
                name = "message",
                type = "string",
                description = "Raw text to send - sent exactly as provided, no transformations",
                required = false
            ),
            ThothToolProperty(
                name = "initial_wait_seconds",
                type = "integer",
                description = "Max seconds to wait for first line (default 30, for receive)",
                required = false
            ),
            ThothToolProperty(
                name = "silence_timeout_seconds",
                type = "integer",
                description = "Seconds of silence to stop collecting (default 10, for receive)",
                required = false
            )
        )
    )

    override suspend fun initialize(config: String?) {
        logger.info { "TcpSocketTool initialized" }
    }

    private val sessions = ConcurrentHashMap<String, SocketSession>()

    override suspend fun execute(args: Map<String, String>): ThothToolResponse {
        val op = args["operation"]?.lowercase()?.trim()
            ?: return ThothToolResponse("Error: 'operation' required. Use: connect, send, receive, disconnect.")

        logger.trace { "Executing tcp_socket operation: $op" }

        return when (op) {
            "connect" -> connect(args)
            "send" -> send(args)
            "receive" -> receive(args)
            "disconnect" -> disconnect(args)
            else -> ThothToolResponse("Error: Unknown operation '$op'. Use: connect, send, receive, disconnect.")
        }
    }

    private fun connect(args: Map<String, String>): ThothToolResponse {
        val host = args["host"]
            ?: return ThothToolResponse("Error: 'host' required for connect.")

        val port = args["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return ThothToolResponse("Error: 'port' must be 1-65535.")

        return try {
            logger.debug { "Connecting to $host:$port" }

            val socket = Socket().apply {
                connect(java.net.InetSocketAddress(host, port), 8000)
                tcpNoDelay = true
                keepAlive = true
            }

            val sessionId = UUID.randomUUID().toString()
            sessions[sessionId] = SocketSession(socket)

            logger.info { "Connected to $host:$port, session: $sessionId" }

            ThothToolResponse(buildString {
                appendLine("Connected to $host:$port")
                appendLine("Session ID: $sessionId")
            })
        } catch (e: Exception) {
            logger.error { "Connection failed to $host:$port - ${e.message}" }
            ThothToolResponse("Error connecting to $host:$port - ${e.message}")
        }
    }

    private fun send(args: Map<String, String>): ThothToolResponse {
        val sessionId = args["session_id"]
            ?: return ThothToolResponse("Error: 'session_id' required for send.")

        val msg = args["message"]
            ?: return ThothToolResponse("Error: 'message' required for send.")

        val session = sessions[sessionId]
            ?: return ThothToolResponse("Error: Session '$sessionId' not found.")

        return try {
            logger.trace { "Sending ${msg.length} bytes on session $sessionId" }

            session.writer.print(msg)
            session.writer.flush()

            ThothToolResponse("Sent ${msg.length} bytes")
        } catch (e: Exception) {
            logger.error { "Send failed on session $sessionId - ${e.message}" }
            ThothToolResponse("Error sending: ${e.message}")
        }
    }

    private suspend fun receive(args: Map<String, String>): ThothToolResponse {
        val sessionId = args["session_id"]
            ?: return ThothToolResponse("Error: 'session_id' required for receive.")

        val initialWaitSec = args["initial_wait_seconds"]?.toIntOrNull()?.coerceIn(5, 60) ?: 30
        val silenceSec = args["silence_timeout_seconds"]?.toIntOrNull()?.coerceIn(3, 30) ?: 10

        val session = sessions[sessionId]
            ?: return ThothToolResponse("Error: Session '$sessionId' not found.")

        logger.trace { "Receiving on session $sessionId (initial: ${initialWaitSec}s, silence: ${silenceSec}s)" }

        return session.drainWithSilenceDetection(initialWaitSec, silenceSec)
    }

    private fun disconnect(args: Map<String, String>): ThothToolResponse {
        val sessionId = args["session_id"]
            ?: return ThothToolResponse("Error: 'session_id' required for disconnect.")

        val session = sessions.remove(sessionId)
            ?: return ThothToolResponse("Error: Session '$sessionId' not found.")

        session.close()
        logger.info { "Disconnected session: $sessionId" }

        return ThothToolResponse("Disconnected session: $sessionId")
    }

    fun closeAll() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        logger.info { "All TCP sessions closed" }
    }

    private class SocketSession(val socket: Socket) {
        val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
        val linesQueue = ConcurrentLinkedQueue<String>()
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val readerJob: Job

        init {
            readerJob = scope.launch {
                BufferedReader(InputStreamReader(socket.getInputStream())).use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        linesQueue.add(line)
                    }
                }
            }
        }

        suspend fun drainWithSilenceDetection(initialWaitSec: Int, silenceSec: Int): ThothToolResponse {
            val initialWaitMs = initialWaitSec * 1000L
            val silenceMs = silenceSec * 1000L
            val maxTotalMs = 90000L

            val start = System.currentTimeMillis()
            var lastReceived = start
            var gotFirstData = false
            val collected = mutableListOf<String>()

            // Phase 1: wait for first data
            while (true) {
                val now = System.currentTimeMillis()
                val elapsed = now - start

                if (elapsed > initialWaitMs) {
                    if (collected.isEmpty()) {
                        return ThothToolResponse("(timeout - no data after $initialWaitSec seconds)")
                    }
                    break
                }

                val line = linesQueue.poll()
                if (line != null) {
                    collected.add(line)
                    lastReceived = now
                    gotFirstData = true
                } else {
                    if (gotFirstData && (now - lastReceived) > silenceMs) {
                        break
                    }
                    delay(50)
                }
            }

            // Phase 2: collect until silence
            if (gotFirstData && collected.isNotEmpty()) {
                while (true) {
                    val now = System.currentTimeMillis()
                    val totalElapsed = now - start

                    if (totalElapsed > maxTotalMs) {
                        break
                    }

                    val line = linesQueue.poll()
                    if (line != null) {
                        collected.add(line)
                        lastReceived = now
                    } else {
                        if ((now - lastReceived) > silenceMs) {
                            break
                        }
                        delay(50)
                    }
                }
            }

            return if (collected.isEmpty()) {
                ThothToolResponse("(no data received)")
            } else {
                ThothToolResponse(buildString {
                    appendLine("Collected ${collected.size} lines:")
                    collected.forEachIndexed { index, line ->
                        appendLine("  ${index + 1}. $line")
                    }
                })
            }
        }

        fun close() {
            readerJob.cancel()
            try {
                socket.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

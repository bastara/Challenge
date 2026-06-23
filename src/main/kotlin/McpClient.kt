import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintStream
import java.util.UUID

class McpClient(private val serverCommand: List<String>) {
    private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var reader: java.io.BufferedReader? = null
    private var initialized = false

    fun connect(): Boolean {
        return try {
            val pb = ProcessBuilder(serverCommand)
            pb.redirectErrorStream(true)
            process = pb.start()
            writer = process!!.outputStream.bufferedWriter()
            reader = process!!.inputStream.bufferedReader()

            // Отправляем запрос initialize
            val initRequest = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", UUID.randomUUID().toString())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "kotlin-stdio-client")
                        put("version", "1.0.0")
                    })
                })
            }
            sendRequest(initRequest)

            // Читаем ответ (пропускаем текстовую строку приветствия)
            val response = readJsonResponse()
            if (response != null && response.has("result")) {
                initialized = true
                println("Сессия инициализирована (stdio).")
                return true
            } else {
                println("Ошибка инициализации: ${response?.toString() ?: "нет ответа"}")
                return false
            }
        } catch (e: Exception) {
            println("Ошибка подключения: ${e.message}")
            false
        }
    }

    fun listTools(): List<McpTool> {
        if (!initialized) {
            println("Клиент не инициализирован.")
            return emptyList()
        }

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", "tools/list")
            put("params", JSONObject())
        }
        sendRequest(request)

        val response = readJsonResponse()
        if (response == null) return emptyList()

        val result = response.optJSONObject("result")
        if (result == null) {
            println("Ошибка в ответе: $response")
            return emptyList()
        }

        val toolsArray = result.optJSONArray("tools") ?: JSONArray()
        val tools = mutableListOf<McpTool>()
        for (i in 0 until toolsArray.length()) {
            val toolJson = toolsArray.getJSONObject(i)
            val name = toolJson.getString("name")
            val description = toolJson.optString("description", "")
            tools.add(McpTool(name, description))
        }
        return tools
    }

    private fun sendRequest(json: JSONObject) {
        writer?.let {
            it.write(json.toString())
            it.newLine()
            it.flush()
        }
    }

    private fun readJsonResponse(): JSONObject? {
        var line: String?
        do {
            line = reader?.readLine()
            if (line != null && line.startsWith("{")) {
                return JSONObject(line)
            }
        } while (line != null)
        return null
    }

    fun disconnect() {
        process?.destroy()
    }
}

data class McpTool(val name: String, val description: String)

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    // Полный путь к серверу (получен командой where mcp-server-filesystem)
    val serverPath = "C:\\Users\\uubas\\AppData\\Roaming\\npm\\mcp-server-filesystem.cmd"
    val command = listOf(serverPath, "C:\\MCP\\TEST\\mcp-test")
    val client = McpClient(command)

    println("Подключение к MCP-серверу (stdio)...")
    if (client.connect()) {
        println("Получение списка инструментов...")
        val tools = client.listTools()
        if (tools.isNotEmpty()) {
            println("Доступные инструменты:")
            tools.forEach { tool ->
                println("- ${tool.name}: ${tool.description}")
            }
        } else {
            println("Инструменты не найдены.")
        }
    } else {
        println("Не удалось подключиться к серверу.")
    }
    client.disconnect()
}
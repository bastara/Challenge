import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.time.LocalDateTime

private val summaryFile = File("summary_output.txt")

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val server = HttpServer.create(InetSocketAddress(8083), 0)
    server.createContext("/mcp") { exchange ->
        if (exchange.requestMethod == "POST") {
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = JSONObject(body)
            val response = handleFileRequest(request)
            val responseBytes = response.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        } else {
            exchange.sendResponseHeaders(405, -1)
        }
    }
    server.start()
    println("Файловый MCP-сервер запущен на http://localhost:8083/mcp")
}

fun handleFileRequest(request: JSONObject): JSONObject {
    val method = request.getString("method")
    val id = request.opt("id")

    return when (method) {
        "initialize" -> JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", JSONObject().apply {
                put("capabilities", JSONObject())
                put("serverInfo", JSONObject().apply {
                    put("name", "file-server"); put("version", "1.0.0")
                })
            })
        }
        "tools/list" -> {
            val tools = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "save_to_file")
                    put("description", "Сохраняет текст в файл summary_output.txt (добавляет в конец)")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("content", JSONObject().apply {
                                put("type", "string"); put("description", "Текст для сохранения")
                            })
                        })
                        put("required", JSONArray().apply { put("content") })
                    })
                })
            }
            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", JSONObject().apply { put("tools", tools) })
            }
        }
        "tools/call" -> {
            val params = request.getJSONObject("params")
            val toolName = params.getString("name")
            val arguments = params.optJSONObject("arguments") ?: JSONObject()

            val result = when (toolName) {
                "save_to_file" -> {
                    val content = arguments.getString("content")
                    val timestamp = LocalDateTime.now().toString()
                    summaryFile.appendText("\n=== $timestamp ===\n$content\n")
                    println("Сохранено в ${summaryFile.absolutePath}")
                    JSONObject().apply {
                        put("status", "saved")
                        put("file", summaryFile.absolutePath)
                    }
                }
                else -> JSONObject().apply { put("error", "Unknown tool: $toolName") }
            }

            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text"); put("text", result.toString())
                        })
                    })
                })
            }
        }
        else -> JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", JSONObject().apply {
                put("code", -32601); put("message", "Method not found")
            })
        }
    }
}
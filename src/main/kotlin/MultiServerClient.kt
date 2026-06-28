import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Scanner

data class McpServer(val name: String, val url: String, val tools: MutableList<String> = mutableListOf())

class McpRouter(private val servers: List<McpServer>) {
    private val toolToServer = mutableMapOf<String, McpServer>()
    init { for (s in servers) for (t in s.tools) toolToServer[t] = s }
    fun getServer(tool: String) = toolToServer[tool]
    fun allTools() = toolToServer.keys.toList()
}

fun callMcpTool(client: HttpClient, serverUrl: String, toolName: String, arguments: JSONObject): String {
    val request = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", "1"); put("method", "tools/call")
        put("params", JSONObject().apply { put("name", toolName); put("arguments", arguments) })
    }
    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(serverUrl))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
        .build()
    val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) return """{"error":"HTTP ${response.statusCode()}"}"""
    val json = JSONObject(response.body())
    return json.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
}

fun fetchTools(client: HttpClient, serverUrl: String): List<String> {
    try {
        val initBody = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", "init"); put("method", "initialize")
            put("params", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply { put("name", "multi-client"); put("version", "1.0.0") })
            })
        }
        val initRequest = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(initBody.toString()))
            .build()
        client.send(initRequest, HttpResponse.BodyHandlers.ofString())

        val listBody = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", "list"); put("method", "tools/list")
            put("params", JSONObject())
        }
        val listRequest = HttpRequest.newBuilder()
            .uri(URI.create(serverUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(listBody.toString()))
            .build()
        val listResponse = client.send(listRequest, HttpResponse.BodyHandlers.ofString())
        if (listResponse.statusCode() == 200) {
            val json = JSONObject(listResponse.body())
            val result = json.optJSONObject("result")
            val toolsArray = result?.optJSONArray("tools")
            if (toolsArray != null) {
                return (0 until toolsArray.length()).map {
                    toolsArray.getJSONObject(it).getString("name")
                }
            }
        }
    } catch (e: Exception) {
        println("  Ошибка при получении инструментов: ${e.message}")
    }
    return emptyList()
}

fun executeComplexFlow(client: HttpClient, router: McpRouter, city: String, threshold: Double) {
    println("\n=== Длинный флоу для города: $city (порог: ${threshold}°C) ===")

    // 1. Погода
    val weatherServer = router.getServer("get_weather")
    if (weatherServer == null) {
        println("❌ Инструмент get_weather не найден ни на одном сервере.")
        return
    }
    println("1. Запрос погоды на сервере '${weatherServer.name}' (${weatherServer.url})")
    val weatherJson = JSONObject(callMcpTool(client, weatherServer.url, "get_weather", JSONObject().apply { put("city", city) }))
    if (weatherJson.has("error")) { println("   Ошибка: ${weatherJson.getString("error")}"); return }
    val temp = weatherJson.optJSONObject("main")?.optDouble("temp") ?: 0.0
    val desc = weatherJson.optJSONArray("weather")?.optJSONObject(0)?.optString("description") ?: ""
    println("   Погода в $city: $temp°C, $desc")
    if (temp <= threshold) {
        println("Температура ($temp°C) не выше порога (${threshold}°C), флоу прерван.")
        return
    }

    // 2. Поиск отеля
    val searchServer = router.getServer("web_search")
    if (searchServer == null) { println("❌ Инструмент web_search не найден."); return }
    println("2. Поиск отелей на сервере '${searchServer.name}' (${searchServer.url})")
    val searchJson = JSONObject(callMcpTool(client, searchServer.url, "web_search", JSONObject().apply { put("query", "лучший отель в $city") }))
    if (searchJson.has("error")) { println("   Ошибка: ${searchJson.getString("error")}"); return }
    val results = searchJson.optJSONArray("results")
    if (results == null || results.length() == 0) { println("   Ничего не найдено."); return }
    println("   Найдено ${results.length()} вариантов отелей.")

    // 3. Анализ
    val summarizeServer = router.getServer("summarize")
    if (summarizeServer == null) { println("❌ Инструмент summarize не найден."); return }
    println("3. Анализ и выбор лучшего отеля на сервере '${summarizeServer.name}' (${summarizeServer.url})")
    val summaryJson = JSONObject(callMcpTool(client, summarizeServer.url, "summarize", JSONObject().apply { put("items", results); put("criterion", "best") }))
    if (summaryJson.has("error")) { println("   Ошибка: ${summaryJson.getString("error")}"); return }
    val selected = summaryJson.optString("selected", "?")
    val rating = summaryJson.optInt("rating", 0)
    val pos = summaryJson.optInt("positives", 0)
    val neg = summaryJson.optInt("negatives", 0)
    val summaryText = summaryJson.optString("summary", "")
    println("   Лучший отель: $selected (рейтинг $rating, +$pos/-$neg)")

    // 4. Сохранение через файловый сервер
    val saveServer = router.getServer("save_to_file")
    if (saveServer == null) { println("❌ Инструмент save_to_file не найден."); return }
    println("4. Сохранение результата на сервере '${saveServer.name}' (${saveServer.url})")
    val saveJson = JSONObject(callMcpTool(client, saveServer.url, "save_to_file", JSONObject().apply { put("content", summaryText) }))
    if (saveJson.has("status")) {
        println("   Результат сохранён в файл: ${saveJson.optString("file", "summary_output.txt")}")
    } else {
        println("   Ошибка сохранения: $saveJson")
    }
    println("=== Длинный флоу завершён ===\n")
}

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    val servers = listOf(
        McpServer("Погодный", "http://localhost:8081/mcp"),
        McpServer("Пайплайн", "http://localhost:8082/mcp"),
        McpServer("Файловый", "http://localhost:8083/mcp")
    )

    println("Обнаружение MCP-серверов...")
    for (server in servers) {
        print("  ${server.name} (${server.url}): ")
        try {
            val tools = fetchTools(client, server.url)
            if (tools.isNotEmpty()) {
                server.tools.addAll(tools)
                println("найдено инструментов: ${tools.size} → ${tools.joinToString(", ")}")
            } else {
                println("сервер не ответил или не предоставил инструменты")
            }
        } catch (e: Exception) {
            println("ошибка подключения: ${e.message}")
        }
    }

    val router = McpRouter(servers)
    val allTools = router.allTools()
    if (allTools.isEmpty()) {
        println("\n❌ Ни одного инструмента не обнаружено. Проверьте, что серверы запущены на портах 8081, 8082 и 8083.")
    } else {
        println("\n✅ Всего доступно инструментов: ${allTools.size} → ${allTools.joinToString(", ")}")
    }

    val scanner = Scanner(System.`in`, "UTF-8")
    println("\nКоманды:")
    println("  complex <город> [порог] – выполнить длинный флоу (порог температуры по умолчанию 25°C)")
    println("  exit                     – выход")
    println("  help                     – показать список инструментов по серверам")

    while (true) {
        print("> ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) -> {
                for (server in servers) {
                    println("  ${server.name}: ${server.tools.ifEmpty { "нет" }}")
                }
            }
            input.startsWith("complex ", true) -> {
                if (router.allTools().isEmpty()) {
                    println("Нет доступных инструментов. Запустите серверы и перезапустите клиент.")
                } else {
                    val parts = input.removePrefix("complex ").trim().split(" ", limit = 2)
                    val city = parts[0]
                    val threshold = if (parts.size > 1) parts[1].toDoubleOrNull() ?: 25.0 else 25.0
                    executeComplexFlow(client, router, city, threshold)
                }
            }
            input.isEmpty() -> continue
            else -> println("Неизвестная команда. Используйте 'complex <город>' или 'help'.")
        }
    }
}
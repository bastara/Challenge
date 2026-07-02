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
        .timeout(Duration.ofSeconds(120))
        .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
        .build()
    val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        return """{"error": "HTTP ${response.statusCode()}"}"""
    }
    return response.body()
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
            .timeout(Duration.ofSeconds(120))
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
            .timeout(Duration.ofSeconds(120))
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

    val weatherServer = router.getServer("get_weather")
    if (weatherServer == null) {
        println("❌ Инструмент get_weather не найден ни на одном сервере.")
        return
    }
    println("1. Запрос погоды на сервере '${weatherServer.name}' (${weatherServer.url})")
    val weatherJson = JSONObject(callMcpTool(client, weatherServer.url, "get_weather", JSONObject().apply { put("city", city) }))
    if (weatherJson.has("error")) { println("   Ошибка: ${weatherJson.opt("error")}"); return }
    val temp = weatherJson.optJSONObject("main")?.optDouble("temp") ?: 0.0
    val desc = weatherJson.optJSONArray("weather")?.optJSONObject(0)?.optString("description") ?: ""
    println("   Погода в $city: $temp°C, $desc")
    if (temp <= threshold) {
        println("Температура ($temp°C) не выше порога (${threshold}°C), флоу прерван.")
        return
    }

    val searchServer = router.getServer("web_search")
    if (searchServer == null) { println("❌ Инструмент web_search не найден."); return }
    println("2. Поиск отелей на сервере '${searchServer.name}' (${searchServer.url})")
    val searchJson = JSONObject(callMcpTool(client, searchServer.url, "web_search", JSONObject().apply { put("query", "лучший отель в $city") }))
    if (searchJson.has("error")) { println("   Ошибка: ${searchJson.opt("error")}"); return }
    val results = searchJson.optJSONArray("results")
    if (results == null || results.length() == 0) { println("   Ничего не найдено."); return }
    println("   Найдено ${results.length()} вариантов отелей.")

    val summarizeServer = router.getServer("summarize")
    if (summarizeServer == null) { println("❌ Инструмент summarize не найден."); return }
    println("3. Анализ и выбор лучшего отеля на сервере '${summarizeServer.name}' (${summarizeServer.url})")
    val summaryJson = JSONObject(callMcpTool(client, summarizeServer.url, "summarize", JSONObject().apply { put("items", results); put("criterion", "best") }))
    if (summaryJson.has("error")) { println("   Ошибка: ${summaryJson.opt("error")}"); return }
    val selected = summaryJson.optString("selected", "?")
    val rating = summaryJson.optInt("rating", 0)
    val pos = summaryJson.optInt("positives", 0)
    val neg = summaryJson.optInt("negatives", 0)
    val summaryText = summaryJson.optString("summary", "")
    println("   Лучший отель: $selected (рейтинг $rating, +$pos/-$neg)")

    val saveServer = router.getServer("save_to_file")
    if (saveServer == null) { println("❌ Инструмент save_to_file не найден."); return }
    println("4. Сохранение результата на сервере '${saveServer.name}' (${saveServer.url})")
    val saveJson = JSONObject(callMcpTool(client, saveServer.url, "save_to_file", JSONObject().apply { put("content", summaryText) }))
    if (saveJson.has("status")) {
        println("   Результат сохранён в файл: ${saveJson.optString("file", "summary_output.txt")}")
    } else {
        println("   Ошибка сохранения: ${saveJson.opt("error") ?: saveJson}")
    }
    println("=== Длинный флоу завершён ===\n")
}

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build()

    val servers = listOf(
        McpServer("Погодный", "http://localhost:8081/mcp"),
        McpServer("Пайплайн", "http://localhost:8082/mcp"),
        McpServer("Файловый", "http://localhost:8083/mcp"),
        McpServer("Индексатор", "http://localhost:8084/mcp")
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
        println("\n❌ Ни одного инструмента не обнаружено. Проверьте, что серверы запущены на портах 8081, 8082, 8083 и 8084.")
    } else {
        println("\n✅ Всего доступно инструментов: ${allTools.size} → ${allTools.joinToString(", ")}")
    }

    val scanner = Scanner(System.`in`, "UTF-8")
    println("\nКоманды:")
    println("  complex <город> [порог]    – выполнить длинный флоу (порог температуры по умолчанию 25°C)")
    println("  index <путь> [max_tokens] [overlap] – проиндексировать .txt файлы в папке")
    println("  search <стратегия> <запрос> – поиск по индексу (fixed или structural)")
    println("  compare <путь>             – сравнить две стратегии чанкинга")
    println("  ask <вопрос>               – задать вопрос LLM без контекста")
    println("  rag [min_score=X] [rewrite] [стратегия] <вопрос> – задать вопрос с RAG")
    println("  both [min_score=X] [rewrite] [стратегия] <вопрос> – ask + rag одновременно")
    println("  exit                       – выход")
    println("  help                       – показать список инструментов по серверам")

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
            input.startsWith("index ", true) -> {
                val parts = input.removePrefix("index ").trim().split(" ")
                if (parts.isEmpty()) {
                    println("Укажите путь к папке: index <путь> [max_tokens] [overlap]")
                    continue
                }
                val path = parts[0]
                val maxTokens = parts.getOrNull(1)?.toIntOrNull() ?: 500
                val overlap = parts.getOrNull(2)?.toIntOrNull() ?: 50
                val server = router.getServer("index_folder")
                if (server == null) {
                    println("❌ Инструмент index_folder не найден.")
                } else {
                    val result = callMcpTool(client, server.url, "index_folder",
                        JSONObject().apply { put("path", path); put("max_tokens", maxTokens); put("overlap", overlap) })
                    val json = JSONObject(result)
                    if (json.has("error")) {
                        println("Ошибка: ${json.opt("error")}")
                    } else {
                        println(result)
                    }
                }
            }
            input.startsWith("search ", true) -> {
                val parts = input.removePrefix("search ").trim().split(" ", limit = 2)
                if (parts.size < 2) {
                    println("Формат: search <fixed|structural> <запрос>")
                } else {
                    val strategy = parts[0]
                    val query = parts[1]
                    val server = router.getServer("search")
                    if (server == null) {
                        println("❌ Инструмент search не найден.")
                    } else {
                        val result = callMcpTool(client, server.url, "search",
                            JSONObject().apply { put("query", query); put("strategy", strategy) })
                        val json = JSONObject(result)
                        if (json.has("error")) {
                            println("Ошибка: ${json.opt("error")}")
                        } else {
                            val resultsArray = json.optJSONArray("results")
                            if (resultsArray == null || resultsArray.length() == 0) {
                                println("Ничего не найдено.")
                            } else {
                                println("\nРезультаты поиска по запросу \"$query\" (стратегия: $strategy):")
                                println("─".repeat(60))
                                for (i in 0 until resultsArray.length()) {
                                    val item = resultsArray.getJSONObject(i)
                                    val score = item.optDouble("score", 0.0)
                                    val text = item.optString("text", "").replace("\r\n", " ").replace("\n", " ")
                                    val chunkId = item.optString("chunk_id", "?")
                                    val metadata = item.optJSONObject("metadata")
                                    val section = metadata?.optString("section", "") ?: ""
                                    println("${i + 1}. [$chunkId] (score: ${"%.4f".format(score)})")
                                    if (section.isNotEmpty()) println("   Раздел: $section")
                                    println("   $text")
                                    println()
                                }
                            }
                        }
                    }
                }
            }
            input.startsWith("compare ", true) -> {
                val path = input.removePrefix("compare ").trim()
                val server = router.getServer("compare_strategies")
                if (server == null) {
                    println("❌ Инструмент compare_strategies не найден.")
                } else {
                    val result = callMcpTool(client, server.url, "compare_strategies",
                        JSONObject().apply { put("path", path) })
                    val json = JSONObject(result)
                    if (json.has("error")) {
                        println("Ошибка: ${json.opt("error")}")
                    } else {
                        println("\nСравнение стратегий чанкинга для папки \"$path\"")
                        println("=".repeat(60))
                        println("▶ Фиксированная (Fixed)")
                        println("   Чанков: ${json.optInt("fixed_chunks", 0)}")
                        println("   Средний размер (слов): ${"%.1f".format(json.optDouble("fixed_avg_words", 0.0))}")
                        println("   Мин./Макс. размер (слов): ${json.optInt("fixed_min_words", 0)} / ${json.optInt("fixed_max_words", 0)}")
                        println()
                        println("▶ Структурная (Structural)")
                        println("   Чанков: ${json.optInt("structural_chunks", 0)}")
                        println("   Средний размер (слов): ${"%.1f".format(json.optDouble("structural_avg_words", 0.0))}")
                        println("   Мин./Макс. размер (слов): ${json.optInt("structural_min_words", 0)} / ${json.optInt("structural_max_words", 0)}")
                        println()
                        val fixedSamples = json.optJSONArray("fixed_sample_boundaries")
                        val structSamples = json.optJSONArray("structural_sample_boundaries")
                        if (fixedSamples != null && fixedSamples.length() > 0) {
                            println("▶ Примеры границ чанков (Fixed):")
                            for (i in 0 until fixedSamples.length()) {
                                println("   ${fixedSamples.getString(i)}")
                            }
                            println()
                        }
                        if (structSamples != null && structSamples.length() > 0) {
                            println("▶ Примеры границ чанков (Structural):")
                            for (i in 0 until structSamples.length()) {
                                println("   ${structSamples.getString(i)}")
                            }
                            println()
                        }
                    }
                }
            }
            input.startsWith("ask ", true) -> {
                val question = input.removePrefix("ask ").trim()
                val server = router.getServer("ask_llm")
                if (server == null) println("❌ Инструмент ask_llm не найден.")
                else {
                    val raw = callMcpTool(client, server.url, "ask_llm", JSONObject().apply { put("question", question) })
                    try {
                        val json = JSONObject(raw)
                        if (json.has("error")) {
                            println("Ошибка: ${json.opt("error")}")
                        } else {
                            val result = json.getJSONObject("result")
                            val contentArray = result.getJSONArray("content")
                            val innerText = contentArray.getJSONObject(0).getString("text")
                            val innerJson = JSONObject(innerText)
                            if (innerJson.has("answer")) {
                                println("\nОтвет LLM (без RAG):\n${innerJson.getString("answer")}")
                            } else if (innerJson.has("error")) {
                                println("Ошибка LLM: ${innerJson.opt("error")}")
                            } else {
                                println("Неожиданный формат ответа: $raw")
                            }
                        }
                    } catch (e: Exception) {
                        println("Ошибка разбора ответа: ${e.message}")
                        println("Сырой ответ: $raw")
                    }
                }
            }
            input.startsWith("rag ", true) || input.startsWith("both ", true) -> {
                val isBoth = input.startsWith("both ", true)
                val cmd = if (isBoth) "both" else "rag"
                var remaining = input.removePrefix("$cmd ").trim()
                var minScore = 0.0
                var useRewrite = false
                var strategy: String? = null

                while (remaining.isNotEmpty()) {
                    when {
                        remaining.startsWith("min_score=") -> {
                            val value = remaining.substringBefore(" ").removePrefix("min_score=").toDoubleOrNull()
                            if (value != null) minScore = value
                            remaining = remaining.substringAfter(" ", "")
                        }
                        remaining.startsWith("rewrite") -> {
                            useRewrite = true
                            remaining = remaining.removePrefix("rewrite").trim()
                        }
                        remaining.startsWith("structural") || remaining.startsWith("fixed") -> {
                            strategy = remaining.substringBefore(" ")
                            remaining = remaining.substringAfter(" ", "")
                        }
                        else -> break
                    }
                }
                val question = remaining.trim()

                if (isBoth) {
                    println("\n=== Ответ без RAG (ask) ===")
                    val askServer = router.getServer("ask_llm")
                    if (askServer != null) {
                        val raw = callMcpTool(client, askServer.url, "ask_llm", JSONObject().apply { put("question", question) })
                        try {
                            val json = JSONObject(raw)
                            if (json.has("error")) {
                                println("Ошибка: ${json.opt("error")}")
                            } else {
                                val result = json.getJSONObject("result")
                                val contentArray = result.getJSONArray("content")
                                val innerText = contentArray.getJSONObject(0).getString("text")
                                val innerJson = JSONObject(innerText)
                                if (innerJson.has("answer")) {
                                    println(innerJson.getString("answer"))
                                } else if (innerJson.has("error")) {
                                    println("Ошибка LLM: ${innerJson.opt("error")}")
                                } else {
                                    println("Неожиданный формат ответа: $raw")
                                }
                            }
                        } catch (e: Exception) {
                            println("Ошибка разбора ответа: ${e.message}")
                        }
                    } else {
                        println("❌ Инструмент ask_llm не найден.")
                    }
                    println()
                }

                println(if (isBoth) "=== Ответ с RAG ===" else "\nОтвет RAG:")
                val ragServer = router.getServer("rag_query")
                if (ragServer == null) {
                    println("❌ Инструмент rag_query не найден.")
                } else {
                    fun tryRag(strat: String): JSONObject? {
                        val raw = callMcpTool(client, ragServer.url, "rag_query",
                            JSONObject().apply {
                                put("question", question)
                                put("strategy", strat)
                                put("top_k", 7)
                                if (minScore > 0.0) put("min_score", minScore)
                                if (useRewrite) put("use_rewrite", true)
                            })
                        val json = JSONObject(raw)
                        if (json.has("error")) return null
                        val result = json.getJSONObject("result")
                        val contentArray = result.getJSONArray("content")
                        val innerText = contentArray.getJSONObject(0).getString("text")
                        val innerJson = JSONObject(innerText)
                        val answer = innerJson.optString("answer", "")
                        if (answer.contains("Недостаточно информации") || answer.isBlank()) return null
                        return innerJson
                    }

                    var innerJson: JSONObject? = null
                    if (strategy != null) {
                        innerJson = tryRag(strategy)
                    } else {
                        innerJson = tryRag("structural") ?: tryRag("fixed")
                    }

                    if (innerJson != null) {
                        val answer = innerJson.getString("answer")
                        println(answer)
                        val originalTopK = innerJson.optInt("original_top_k", -1)
                        val filteredCount = innerJson.optInt("filtered_count", -1)
                        if (originalTopK >= 0) {
                            println("\nСтатистика фильтрации:")
                            println("   Найдено чанков (top‑k): $originalTopK")
                            println("   После фильтрации (min_score=${"%.2f".format(minScore)}): $filteredCount")
                        }
                        if (innerJson.has("rewritten_query")) {
                            println("   Переформулированный запрос: ${innerJson.getString("rewritten_query")}")
                        }
                    } else {
                        println("Недостаточно информации ни в одной из стратегий.")
                    }
                }
                if (isBoth) {
                    println("\n=== Сравнение: ask даёт общий ответ, RAG — основанный на документах (с фильтрацией) ===")
                }
            }
            input.isEmpty() -> continue
            else -> println("Неизвестная команда. Используйте 'complex', 'index', 'search', 'compare', 'ask', 'rag', 'both' или 'help'.")
        }
    }
}
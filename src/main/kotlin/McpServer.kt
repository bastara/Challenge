import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val summaryFile = File("summary_output.txt")

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Ошибка: переменная окружения DEEPSEEK_API_KEY не задана.")
        return
    }

    val server = HttpServer.create(InetSocketAddress(8081), 0)
    server.createContext("/mcp") { exchange ->
        if (exchange.requestMethod == "POST") {
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = JSONObject(body)
            val response = handleRequest(request, apiKey)
            val responseBytes = response.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        } else {
            exchange.sendResponseHeaders(405, -1)
        }
    }
    server.start()
    println("MCP-сервер пайплайна (DeepSeek) запущен на http://localhost:8081/mcp")
}

fun handleRequest(request: JSONObject, apiKey: String): JSONObject {
    val method = request.getString("method")
    val id = request.opt("id")

    return when (method) {
        "initialize" -> JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", JSONObject().apply {
                put("capabilities", JSONObject())
                put("serverInfo", JSONObject().apply {
                    put("name", "pipeline-mcp-server"); put("version", "3.0.0")
                })
            })
        }
        "tools/list" -> {
            val tools = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "web_search")
                    put("description", "Поиск до 5 вариантов по запросу через DeepSeek")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("query", JSONObject().apply {
                                put("type", "string"); put("description", "Поисковый запрос")
                            })
                        })
                        put("required", JSONArray().apply { put("query") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "summarize")
                    put("description", "Анализирует варианты, считает рейтинг по тональности и возвращает лучший/худший")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("items", JSONObject().apply {
                                put("type", "array"); put("description", "Список вариантов (JSON array)")
                            })
                            put("criterion", JSONObject().apply {
                                put("type", "string"); put("description", "best или worst (по умолчанию best)")
                            })
                        })
                        put("required", JSONArray().apply { put("items") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "save_to_file")
                    put("description", "Сохраняет текст в файл summary_output.txt")
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
                "web_search" -> {
                    val query = arguments.getString("query")
                    println("Поиск: '$query'")
                    webSearch(query, apiKey)
                }
                "summarize" -> {
                    val itemsArray = arguments.getJSONArray("items")
                    val criterion = arguments.optString("criterion", "best")
                    println("Анализ ${itemsArray.length()} вариантов (критерий: $criterion)")
                    summarizeItems(itemsArray, criterion)
                }
                "save_to_file" -> {
                    val content = arguments.getString("content")
                    val timestamp = java.time.LocalDateTime.now().toString()
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

fun webSearch(query: String, apiKey: String): JSONObject {
    val prompt = """
        Ты — поисковый ассистент. Найди информацию по запросу "$query" и верни до 5 результатов.
        Для каждого результата ОБЯЗАТЕЛЬНО укажи:
        - name: название места/сервиса
        - description: краткое описание (3-5 предложений)
        - reviews: массив из 2-4 отзывов, каждый с полями text (текст) и sentiment (1 для положительного, -1 для отрицательного)
        Количество отзывов и соотношение положительных/отрицательных должно быть разным для каждого варианта и соответствовать реальной репутации.
        Не делай всем одинаковые оценки. Например, для популярного места может быть 4 положительных и 1 отрицательный, для среднего — 2 и 2, для плохого — 1 и 4.
        Ответ оформи строго как JSON:
        {
          "results": [
            {
              "name": "...",
              "description": "...",
              "reviews": [
                {"text": "...", "sentiment": 1},
                {"text": "...", "sentiment": -1}
              ]
            }
          ]
        }
    """.trimIndent()

    val body = JSONObject().apply {
        put("model", "deepseek-chat")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
        put("temperature", 0.7)
        put("max_tokens", 2500)
    }.toString()

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        return JSONObject().apply { put("error", "DeepSeek API error ${response.statusCode()}") }
    }

    val json = JSONObject(response.body())
    val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    val cleanContent = content.replace("```json", "").replace("```", "").trim()

    return try {
        val parsed = JSONObject(cleanContent)
        val results = parsed.getJSONArray("results")
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val reviews = item.optJSONArray("reviews")
            if (reviews == null || reviews.length() == 0) {
                val desc = item.optString("description", "")
                val sentiment = analyzeSentiment(desc)
                item.put("reviews", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", desc)
                        put("sentiment", sentiment)
                    })
                })
            }
        }
        println("Найдено вариантов: ${results.length()}")
        parsed
    } catch (e: Exception) {
        JSONObject().apply { put("error", "Failed to parse search results: ${e.message}") }
    }
}

fun analyzeSentiment(text: String): Int {
    val positive = listOf("отличный", "прекрасный", "хороший", "великолепный", "потрясающий", "рекомендую", "уютный", "чистый", "красивый", "вкусный", "понравился", "комфортный", "удобный", "живописный", "замечательный")
    val negative = listOf("плохой", "ужасный", "отвратительный", "грязный", "шумный", "дорогой", "скучный", "неудобный", "разочарование", "не рекомендую", "ужасно", "тесный", "старый")
    val lower = text.lowercase()
    val pos = positive.count { it in lower }
    val neg = negative.count { it in lower }
    return when {
        pos > neg -> 1
        neg > pos -> -1
        else -> 0
    }
}

fun summarizeItems(itemsArray: JSONArray, criterion: String): JSONObject {
    if (itemsArray.length() == 0) {
        return JSONObject().apply { put("error", "Пустой список вариантов") }
    }

    val items = mutableListOf<JSONObject>()
    for (i in 0 until itemsArray.length()) {
        items.add(itemsArray.getJSONObject(i))
    }

    data class ItemScore(val name: String, val total: Int, val positives: Int, val negatives: Int)
    val scores = mutableListOf<ItemScore>()
    var bestItem: JSONObject? = null
    var bestScore = if (criterion == "worst") Int.MAX_VALUE else Int.MIN_VALUE
    var bestPositives = 0
    var bestNegatives = 0

    val logLines = mutableListOf("Промежуточные оценки:")
    for (item in items) {
        val name = item.optString("name", "Без названия")
        val reviews = item.optJSONArray("reviews") ?: JSONArray()
        var pos = 0
        var neg = 0
        for (j in 0 until reviews.length()) {
            val s = reviews.getJSONObject(j).optInt("sentiment", 0)
            if (s > 0) pos++ else if (s < 0) neg++
        }
        val total = pos - neg
        scores.add(ItemScore(name, total, pos, neg))
        logLines.add("- $name: $total баллов (+$pos / -$neg)")

        val isBetter = if (criterion == "worst") total < bestScore else total > bestScore
        if (isBetter) {
            bestScore = total
            bestItem = item
            bestPositives = pos
            bestNegatives = neg
        }
    }

    logLines.forEach { println(it) }

    if (bestItem == null) {
        return JSONObject().apply { put("error", "Не удалось выбрать вариант") }
    }

    val bestName = bestItem.getString("name")
    val bestDesc = bestItem.optString("description", "Описание отсутствует")
    val summary = buildString {
        val label = if (criterion == "worst") "Худший вариант" else "Лучший вариант"
        appendLine("$label: $bestName")
        appendLine("Описание: $bestDesc")
        appendLine("Рейтинг: $bestScore баллов (+$bestPositives / -$bestNegatives)")
        appendLine()
        appendLine("Все оценки:")
        scores.forEach { (n, t, p, ng) -> appendLine("  $n: $t баллов (+$p / -$ng)") }
    }

    val truncated = truncateToTokens(summary, 300)
    println("Выбран: $bestName ($bestScore баллов, +$bestPositives/-$bestNegatives)")
    return JSONObject().apply {
        put("selected", bestName)
        put("rating", bestScore)
        put("positives", bestPositives)
        put("negatives", bestNegatives)
        put("summary", truncated)
    }
}

fun truncateToTokens(text: String, maxTokens: Int = 300): String {
    val maxChars = maxTokens * 3.5
    return if (text.length <= maxChars) text else text.take(maxChars.toInt()) + "..."
}
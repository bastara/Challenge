import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Scanner
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ChatAgent(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    systemPrompt: String = "Ты — лаконичный ассистент. Отвечай одним коротким предложением без вступлений и перечислений. Сразу результат.",
    private val maxTokens: Int = 1000,
    private val historyFile: File = File("chat_history.json")
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    // Полная история (включая system prompt)
    private val history = mutableListOf<Map<String, String>>()

    // Параметры сжатия
    var compressionEnabled = true
    var keepLastMessages = 8           // последние 8 сообщений (4 пары) не сжимаем
    var compressThreshold = 12         // сжимать, когда сообщений (без system) > 12

    // Текущий саммари
    private var summary: String? = null
    // Индекс в history, до которого уже сжато (для внутреннего использования)
    private var lastCompressedIndex = 0

    init {
        loadHistory(systemPrompt)
    }

    private fun loadHistory(systemPrompt: String) {
        if (historyFile.exists()) {
            try {
                val jsonStr = historyFile.readText(Charsets.UTF_8)
                val jsonArray = JSONArray(jsonStr)
                history.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    history.add(mapOf(
                        "role" to obj.getString("role"),
                        "content" to obj.getString("content")
                    ))
                }
                println("📂 Загружена полная история из ${historyFile.name} (${history.size} сообщений)")
                return
            } catch (e: Exception) {
                println("⚠️ Не удалось загрузить историю: ${e.message}. Начинаем с чистого листа.")
            }
        }
        history.add(mapOf("role" to "system", "content" to systemPrompt))
        saveHistory()
        println("🆕 Создана новая история с системным промптом")
    }

    private fun saveHistory() {
        try {
            val jsonArray = JSONArray()
            for (msg in history) {
                jsonArray.put(JSONObject().apply {
                    put("role", msg["role"])
                    put("content", msg["content"])
                })
            }
            historyFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            println("❌ Ошибка сохранения истории: ${e.message}")
        }
    }

    /** Обычный диалог с ответом модели и выводом статистики */
    fun sendMessage(userMessage: String): String {
        history.add(mapOf("role" to "user", "content" to userMessage))
        val result: String = try {
            val (responseText, usage) = chatWithHistory()
            var trimmed = responseText
                .replace(Regex("^[\\s\\S]*?[.!?](\\s|\$)"), { it.value.trim() })
                .ifBlank { responseText.take(100) }
            history.add(mapOf("role" to "assistant", "content" to trimmed))
            // Попытка сжатия, если включено
            if (compressionEnabled) {
                maybeCompress()
            }
            // Обрезаем полную историю только если сжатие выключено (старая логика)
            if (!compressionEnabled) {
                val systemMsg = history.firstOrNull { it["role"] == "system" }
                if (systemMsg != null && history.size > 9) {
                    val newHistory = mutableListOf(systemMsg)
                    newHistory.addAll(history.takeLast(8))
                    history.clear()
                    history.addAll(newHistory)
                }
            }
            saveHistory()
            // Вывод статистики токенов (всегда после ответа)
            if (usage != null) {
                println("Статистика токенов:")
                println("  - Вход (вся история + промпт): ${usage.optInt("prompt_tokens")} токенов")
                println("  - Сгенерировано (ответ модели):  ${usage.optInt("completion_tokens")} токенов")
                println("  - Всего за запрос:              ${usage.optInt("total_tokens")} токенов")
            }
            trimmed
        } catch (e: TimeoutException) {
            "Таймаут (30 с) – модель не успела ответить."
        } catch (e: Exception) {
            "Ошибка API: ${e.message ?: e.javaClass.simpleName}"
        }
        return result
    }

    /** Строит список messages для отправки с учётом сжатия */
    private fun chatWithHistory(): Pair<String, JSONObject?> {
        val messages = JSONArray()
        if (compressionEnabled && summary != null) {
            // Добавляем саммари как системное сообщение
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", "Previous conversation summary:\n$summary")
            })
        }
        // Добавляем последние keepLastMessages сообщений (или все, если их меньше)
        val startIdx = if (compressionEnabled && keepLastMessages > 0) {
            maxOf(0, history.size - keepLastMessages)
        } else 0
        for (i in startIdx until history.size) {
            val msg = history[i]
            messages.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)
            put("max_tokens", maxTokens)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val future: CompletableFuture<HttpResponse<String>> =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = future.get(30, TimeUnit.SECONDS)

        if (response.statusCode() != 200) {
            val errorBody = response.body()
            throw RuntimeException("API error ${response.statusCode()}: $errorBody")
        }

        val json = JSONObject(response.body())
        val answer = json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val usage = json.optJSONObject("usage")
        return answer to usage
    }

    /** Проверяет, не пора ли сжать историю, и если да — запрашивает summary у модели */
    private fun maybeCompress() {
        val nonSystemMessages = history.filter { it["role"] != "system" }
        if (nonSystemMessages.size < compressThreshold) return

        // Сообщения, которые ещё не сжаты и находятся за пределами окна keepLastMessages
        val compressEndIdx = maxOf(1, history.size - keepLastMessages) // не трогаем system
        if (compressEndIdx <= lastCompressedIndex) return // уже сжато достаточно

        // Собираем текст для суммаризации
        val toCompress = history.subList(lastCompressedIndex, compressEndIdx)
            .filter { it["role"] != "system" }
            .joinToString("\n") { "${it["role"]}: ${it["content"]}" }

        if (toCompress.isBlank()) return

        // Запрашиваем summary
        val prompt = "Summarize the following conversation fragment in a concise way (2-3 sentences), " +
                "keeping all important facts, names, decisions and context:\n\n$toCompress\n\nSummary:"

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.0)
            put("max_tokens", 200)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            val future: CompletableFuture<HttpResponse<String>> =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            val response = future.get(30, TimeUnit.SECONDS)
            if (response.statusCode() == 200) {
                val json = JSONObject(response.body())
                val newSummary = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                summary = if (summary != null) "$summary\n$newSummary" else newSummary
                lastCompressedIndex = compressEndIdx
                println("📝 История сжата (сообщений до индекса $lastCompressedIndex)")
            } else {
                println("⚠️ Не удалось сжать историю: ${response.statusCode()}")
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка при сжатии истории: ${e.message}")
        }
    }

    /** Быстрый подсчёт токенов для текста (без ответа модели) */
    fun countTokensAndSave(text: String): Int {
        history.add(mapOf("role" to "user", "content" to text))
        saveHistory()

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", text)
        })

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)
            put("max_tokens", 1)
            put("stop", JSONArray().apply {
                put("\n")
                put(".")
                put(" ")
                put("Token")
                put("Подсчёт")
            })
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val future: CompletableFuture<HttpResponse<String>> =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = future.get(30, TimeUnit.SECONDS)

        if (response.statusCode() != 200) {
            val errorBody = response.body()
            throw RuntimeException("API error ${response.statusCode()}: $errorBody")
        }

        val json = JSONObject(response.body())
        val usage = json.getJSONObject("usage")
        return usage.getInt("prompt_tokens")
    }

    fun reset() {
        val systemMsg = history.firstOrNull { it["role"] == "system" }
        history.clear()
        if (systemMsg != null) history.add(systemMsg)
        summary = null
        lastCompressedIndex = 1 // после system
        saveHistory()
        println("🔄 История и саммари сброшены.")
    }

    fun save() {
        saveHistory()
        println("💾 Полная история сохранена в ${historyFile.name}")
    }

    fun getSummary(): String? = summary
    fun getFullHistorySize() = history.size
}

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("DEEPSEEK_API_KEY")
        ?: error("Установите переменную окружения DEEPSEEK_API_KEY")

    val agent = ChatAgent(
        apiKey = apiKey,
        model = "deepseek-chat",
        systemPrompt = "Ты — лаконичный ассистент. Отвечай одним коротким предложением без вступлений и перечислений. Сразу результат.",
        maxTokens = 1000
    )

    val scanner = Scanner(System.`in`, "UTF-8")
    println("ChatAgent (DeepSeek) со сжатием истории")
    println("─".repeat(60))
    println("Дополнительные команды:")
    println("  'compress on'/'compress off' – включить/выключить сжатие")
    println("  'summary'                   – показать текущее саммари")
    println("  Остальные команды: MULTI...END, tokens, reset, save, exit")
    println()

    val buffer = StringBuilder()
    var multiLineMode = false

    fun processBuffer() {
        val content = buffer.toString().trim()
        buffer.clear()
        multiLineMode = false
        if (content.isEmpty()) return

        when {
            content.startsWith("compress ", ignoreCase = true) -> {
                val cmd = content.removePrefix("compress ").trim().lowercase()
                when (cmd) {
                    "on" -> {
                        agent.compressionEnabled = true
                        println("✅ Сжатие включено. keepLastMessages=${agent.keepLastMessages}")
                    }
                    "off" -> {
                        agent.compressionEnabled = false
                        println("✅ Сжатие выключено. Будет использоваться полная история.")
                    }
                    else -> println("Используйте: compress on / compress off")
                }
            }
            content.equals("summary", ignoreCase = true) -> {
                val s = agent.getSummary()
                if (s != null) {
                    println("Текущий саммари:\n$s")
                } else {
                    println("Саммари пока нет.")
                }
            }
            content.startsWith("tokens ", ignoreCase = true) -> {
                val text = content.removePrefix("tokens ").trim()
                if (text.isEmpty()) {
                    println("Укажите текст после 'tokens'.")
                    return
                }
                print("Подсчёт токенов... ")
                val start = System.currentTimeMillis()
                try {
                    val tokens = agent.countTokensAndSave(text)
                    val elapsed = (System.currentTimeMillis() - start) / 1000.0
                    println("${tokens} токенов (за ${"%.1f".format(elapsed)} с)")
                    println("Текст сохранён в истории.")
                } catch (e: Exception) {
                    println("Ошибка: ${e.message}")
                }
            }
            else -> {
                print("Агент: ")
                val start = System.currentTimeMillis()
                val answer = agent.sendMessage(content)
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                println(answer)
                println("(ответ получен за ${"%.1f".format(elapsed)} с)")
            }
        }
        println("─".repeat(60))
    }

    while (true) {
        if (!multiLineMode) {
            print("Вы: ")
        }
        val line = scanner.nextLine().trimEnd()
        when {
            line.equals("END", ignoreCase = true) && multiLineMode -> {
                processBuffer()
                continue
            }
            line.startsWith("MULTI", ignoreCase = true) && !multiLineMode -> {
                multiLineMode = true
                val rest = line.removePrefix("MULTI").trim()
                if (rest.isNotEmpty()) {
                    buffer.append(rest)
                }
                println("Многострочный режим. Введите текст. Для отправки введите END.")
                continue
            }
            line.equals("exit", ignoreCase = true) && !multiLineMode && buffer.isEmpty() -> {
                agent.save()
                println("Пока!")
                break
            }
            line.equals("reset", ignoreCase = true) && !multiLineMode && buffer.isEmpty() -> {
                buffer.clear()
                agent.reset()
                continue
            }
            line.equals("save", ignoreCase = true) && !multiLineMode && buffer.isEmpty() -> {
                agent.save()
                continue
            }
            multiLineMode -> {
                buffer.appendLine(line)
            }
            else -> {
                if (line.isNotEmpty()) {
                    buffer.append(line)
                    processBuffer()
                }
            }
        }
    }
}
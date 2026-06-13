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
    private val history = mutableListOf<Map<String, String>>()

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
                    history.add(
                        mapOf(
                            "role" to obj.getString("role"),
                            "content" to obj.getString("content")
                        )
                    )
                }
                println("📂 Загружена история из ${historyFile.name} (${history.size} сообщений)")
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
                jsonArray.put(
                    JSONObject().apply {
                        put("role", msg["role"])
                        put("content", msg["content"])
                    }
                )
            }
            historyFile.writeText(jsonArray.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            println("❌ Ошибка сохранения истории: ${e.message}")
        }
    }

    /** Обычный диалог: отправляет сообщение и возвращает ответ модели */
    fun sendMessage(userMessage: String): String {
        history.add(mapOf("role" to "user", "content" to userMessage))
        val result: String = try {
            val (responseText, usage) = chatWithHistory()
            var trimmed = responseText
                .replace(Regex("^[\\s\\S]*?[.!?](\\s|\$)"), { it.value.trim() })
                .ifBlank { responseText.take(100) }
            history.add(mapOf("role" to "assistant", "content" to trimmed))
            // Обрезаем историю до system + последние 8 сообщений
            val systemMsg = history.firstOrNull { it["role"] == "system" }
            if (systemMsg != null && history.size > 9) {
                val newHistory = mutableListOf(systemMsg)
                newHistory.addAll(history.takeLast(8))
                history.clear()
                history.addAll(newHistory)
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

    private fun chatWithHistory(): Pair<String, JSONObject?> {
        val messages = JSONArray()
        for (msg in history) {
            messages.put(
                JSONObject().apply {
                    put("role", msg["role"])
                    put("content", msg["content"])
                }
            )
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

    /**
     * Быстрый подсчёт токенов для переданного текста.
     * Текст сохраняется в историю как сообщение пользователя.
     */
    fun countTokensAndSave(text: String): Int {
        history.add(mapOf("role" to "user", "content" to text))
        saveHistory()

        val messages = JSONArray()
        messages.put(
            JSONObject().apply {
                put("role", "user")
                put("content", text)
            }
        )

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)
            put("max_tokens", 1)
            put(
                "stop", JSONArray().apply {
                    put("\n")
                    put(".")
                    put(" ")
                    put("Token")
                    put("Подсчёт")
                }
            )
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
        saveHistory()
        println("🔄 История сброшена.")
    }

    fun save() {
        saveHistory()
        println("💾 История сохранена в ${historyFile.name}")
    }
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
    println("ChatAgent (DeepSeek) — диалог с автоматической статистикой токенов")
    println("─".repeat(50))
    println("Команды:")
    println("  Обычное сообщение  – отправить агенту")
    println("  MULTI ... END      – многострочный ввод (для длинных текстов)")
    println("  tokens <текст>     – подсчитать токены для текста (без ответа)")
    println("  reset, save, exit")
    println()

    val buffer = StringBuilder()
    var multiLineMode = false

    fun processBuffer() {
        val content = buffer.toString().trim()
        buffer.clear()
        multiLineMode = false
        if (content.isEmpty()) return

        // Команда tokens обрабатывается отдельно
        if (content.startsWith("tokens ", ignoreCase = true)) {
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
        } else {
            // Обычный диалог
            print("Агент: ")
            val start = System.currentTimeMillis()
            val answer = agent.sendMessage(content)
            val elapsed = (System.currentTimeMillis() - start) / 1000.0
            println(answer)
            println("(ответ получен за ${"%.1f".format(elapsed)} с)")
        }
        println("─".repeat(50))
    }

    while (true) {
        if (!multiLineMode) {
            print("Вы: ")
        }
        val line = scanner.nextLine().trimEnd()

        when {
            // Завершение многострочного ввода
            line.equals("END", ignoreCase = true) && multiLineMode -> {
                processBuffer()
                continue
            }

            // Начало многострочного ввода
            line.startsWith("MULTI", ignoreCase = true) && !multiLineMode -> {
                multiLineMode = true
                val rest = line.removePrefix("MULTI").trim()
                if (rest.isNotEmpty()) {
                    buffer.append(rest)
                }
                println("Многострочный режим. Введите текст. Для отправки введите END.")
                continue
            }

            // Команды управления (только когда буфер пуст и не в многострочном режиме)
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

            // Добавление строки в многострочном режиме
            multiLineMode -> {
                buffer.appendLine(line)
            }

            // Однострочный ввод
            else -> {
                if (line.isNotEmpty()) {
                    buffer.append(line)
                    processBuffer()
                }
            }
        }
    }
}
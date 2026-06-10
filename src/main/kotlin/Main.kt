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
    private val model: String = "open-mistral-nemo",
    systemPrompt: String = "Ты — лаконичный ассистент. Отвечай одним коротким предложением (не более 15 слов) без вступлений и перечислений. Сразу результат.",
    private val maxTokens: Int = 80,
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
                    history.add(mapOf(
                        "role" to obj.getString("role"),
                        "content" to obj.getString("content")
                    ))
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

    fun sendMessage(userMessage: String): String {
        history.add(mapOf("role" to "user", "content" to userMessage))
        return try {
            var responseText = chatWithHistory()
            // Жёсткая обрезка до первого предложения (точка, воскл., вопрос)
            responseText = responseText
                .replace(Regex("^[\\s\\S]*?[.!?](\\s|\$)"), { it.value.trim() })
                .ifBlank { responseText.take(100) } // fallback
            history.add(mapOf("role" to "assistant", "content" to responseText))
            // Оставляем system + последние 4 сообщения, чтобы не раздувать контекст
            val systemMsg = history.firstOrNull { it["role"] == "system" }
            if (systemMsg != null && history.size > 5) {
                val newHistory = mutableListOf(systemMsg)
                newHistory.addAll(history.takeLast(4))
                history.clear()
                history.addAll(newHistory)
            }
            saveHistory()
            responseText
        } catch (e: TimeoutException) {
            "Таймаут (30 с) – модель не успела ответить."
        } catch (e: Exception) {
            "Ошибка API: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun chatWithHistory(): String {
        val messages = JSONArray()
        for (msg in history) {
            messages.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.0)
            put("top_p", 1.0)
            put("max_tokens", maxTokens)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
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
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
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

    val apiKey = System.getenv("MISTRAL_API_KEY")
        ?: error("Установите переменную окружения MISTRAL_API_KEY")

    val agent = ChatAgent(
        apiKey = apiKey,
        model = "open-mistral-nemo",   // более послушная модель
        systemPrompt = "Ты — лаконичный ассистент. Отвечай одним коротким предложением (не более 15 слов) без вступлений и перечислений. Сразу результат.",
        maxTokens = 80
    )

    val scanner = Scanner(System.`in`, "UTF-8")
    println("ChatAgent (короткие ответы, Nemo 12B)")
    println("─".repeat(40))
    println("Команды: 'exit' — выход, 'reset' — сброс истории, 'save' — принудительное сохранение")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", ignoreCase = true) -> {
                agent.save()
                println("Пока!")
                break
            }
            input.equals("reset", ignoreCase = true) -> {
                agent.reset()
                continue
            }
            input.equals("save", ignoreCase = true) -> {
                agent.save()
                continue
            }
            input.isEmpty() -> continue
            else -> {
                print("Агент: ")
                val start = System.currentTimeMillis()
                val answer = agent.sendMessage(input)
                val elapsed = (System.currentTimeMillis() - start) / 1000.0
                println(answer)
                println("(ответ получен за ${"%.1f".format(elapsed)} с)")
                println("─".repeat(40))
            }
        }
    }
}
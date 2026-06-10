import org.json.JSONObject
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
    systemPrompt: String = "Ты — полезный и дружелюбный ассистент. Отвечай подробно и по делу.",
    private val maxTokens: Int = 1000,
    private val debug: Boolean = true  // флаг для включения/отключения вывода промпта
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private val history = mutableListOf<Map<String, String>>()

    init {
        history.add(mapOf("role" to "system", "content" to systemPrompt))
    }

    fun sendMessage(userMessage: String): String {
        history.add(mapOf("role" to "user", "content" to userMessage))
        return try {
            val responseText = chatWithHistory()
            history.add(mapOf("role" to "assistant", "content" to responseText))
            responseText
        } catch (e: TimeoutException) {
            "Таймаут (120 с) – модель не успела ответить. Попробуйте уменьшить max_tokens или упростить запрос."
        } catch (e: Exception) {
            "Ошибка API: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun chatWithHistory(): String {
        val messages = org.json.JSONArray()
        for (msg in history) {
            messages.put(JSONObject().apply {
                put("role", msg["role"])
                put("content", msg["content"])
            })
        }

        // Вывод промпта перед отправкой
        if (debug) {
            println("\n=== Отправляемый промпт (${history.size} сообщений) ===")
            for ((i, msg) in history.withIndex()) {
                val role = msg["role"]?.uppercase() ?: "???"
                val content = msg["content"] ?: ""
                // Для читаемости обрезаем длинные сообщения, но лучше выводить полностью
                println("$i. [$role] $content")
            }
            println("=========================================")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            put("max_tokens", maxTokens)
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val future: CompletableFuture<HttpResponse<String>> =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = future.get(120, TimeUnit.SECONDS)

        if (response.statusCode() != 200) {
            throw RuntimeException("API error ${response.statusCode()}: ${response.body()}")
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
    }
}

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("MISTRAL_API_KEY")
        ?: error("Установите переменную окружения MISTRAL_API_KEY")

    val agent = ChatAgent(apiKey, model = "open-mistral-nemo", maxTokens = 1000, debug = true)
    val scanner = Scanner(System.`in`, "UTF-8")

    println("ChatAgent с Mistral AI (max_tokens=1000, таймаут=120с)")
    println("─".repeat(40))
    println("Введите сообщение ('exit' для выхода, 'reset' для сброса истории)")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", ignoreCase = true) -> {
                println("Пока!")
                break
            }
            input.equals("reset", ignoreCase = true) -> {
                agent.reset()
                println("История сброшена.")
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
import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*

// Конфигурация
const val TELEGRAM_API = "https://api.telegram.org"
const val OLLAMA_API = "http://81.26.184.66:11434/api/generate"
var BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN") ?: error("Установите TELEGRAM_BOT_TOKEN")
var MODEL_NAME = "deepseek-r1:7b"

// HTTP-клиенты
val httpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .build()

fun main() = runBlocking {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    println("Запуск Telegram-бота с локальной LLM ($MODEL_NAME)")
    var lastUpdateId = 0L
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    while (isActive) {
        try {
            val updates = getUpdates(lastUpdateId + 1)
            for (update in updates) {
                val message = update.optJSONObject("message") ?: continue
                val chatId = message.getJSONObject("chat").getLong("id")
                val text = message.optString("text", "") ?: continue
                val updateId = update.getLong("update_id")
                lastUpdateId = maxOf(lastUpdateId, updateId)

                // Логирование: начало обработки
                val startTime = System.currentTimeMillis()
                val timestamp = LocalDateTime.now().format(timeFormatter)
                println("[$timestamp] Chat $chatId: запрос \"${text.take(100)}\" ...")

                sendChatAction(chatId, "typing")

                // Генерация ответа локальной моделью
                val responseText = generateResponse(text)
                sendMessage(chatId, responseText)

                // Логирование: завершение обработки
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                println("[$timestamp] Chat $chatId: ответ за ${"%.1f".format(elapsed)}с — \"${responseText.take(100)}\"")
            }
        } catch (e: Exception) {
            println("Ошибка: ${e.message}")
        }
        delay(1000)
    }
}

// ---------- Telegram API ----------

suspend fun getUpdates(offset: Long): List<JSONObject> = withContext(Dispatchers.IO) {
    val url = "$TELEGRAM_API/bot$BOT_TOKEN/getUpdates?timeout=30&offset=$offset"
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .GET()
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 200) {
        val json = JSONObject(response.body())
        if (json.getBoolean("ok")) {
            val arr = json.getJSONArray("result")
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } else emptyList()
    } else emptyList()
}

suspend fun sendMessage(chatId: Long, text: String) = withContext(Dispatchers.IO) {
    val url = "$TELEGRAM_API/bot$BOT_TOKEN/sendMessage"
    val body = JSONObject()
        .put("chat_id", chatId)
        .put("text", text.take(4096))
        .toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}

suspend fun sendChatAction(chatId: Long, action: String) = withContext(Dispatchers.IO) {
    val url = "$TELEGRAM_API/bot$BOT_TOKEN/sendChatAction"
    val body = JSONObject()
        .put("chat_id", chatId)
        .put("action", action)
        .toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    httpClient.send(request, HttpResponse.BodyHandlers.ofString())
}

// ---------- Локальная LLM через Ollama ----------

suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
    val body = JSONObject()
        .put("model", MODEL_NAME)
        .put("prompt", prompt)
        .put("stream", false)
        .toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(OLLAMA_API))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(120))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 200) {
        val json = JSONObject(response.body())
        json.optString("response", "Пустой ответ")
    } else {
        "Ошибка модели: ${response.statusCode()}"
    }
}
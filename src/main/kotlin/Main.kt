import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Scanner

fun main() {
    // Фикс кодировки для корректного отображения кириллицы
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val accountId = System.getenv("CLOUDFLARE_ACCOUNT_ID")
        ?: error("Установите переменную окружения CLOUDFLARE_ACCOUNT_ID")
    val apiToken = System.getenv("CLOUDFLARE_API_TOKEN")
        ?: error("Установите переменную окружения CLOUDFLARE_API_TOKEN")

    val client = HttpClient.newHttpClient()
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Сравнение ответов LLM: без ограничений и с ограничениями")
    println("─".repeat(50))

    while (true) {
        print("\nВведите ваш вопрос (или 'exit' для выхода): ")
        val userInput = scanner.nextLine()
        if (userInput.equals("exit", ignoreCase = true)) break

        // ────────────────────────────────────────────
        // 1. Запрос БЕЗ ограничений
        // ────────────────────────────────────────────
        val requestBodyNoLimit = JSONObject().apply {
            put("model", "@cf/meta/llama-3.1-8b-instruct")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)   // оригинальный вопрос
                })
            })
            put("temperature", 0.7)
            // max_tokens и stop не передаём – модель сама решает длину
        }.toString()

        val responseNoLimit = sendRequest(client, accountId, apiToken, requestBodyNoLimit)
        val answerNoLimit = extractAnswer(responseNoLimit)

        // ────────────────────────────────────────────
        // 2. Запрос С ограничениями
        // ────────────────────────────────────────────
        val constrainedPrompt = userInput +
                " (Отвечай строго кратко: не более 40 слов, ровно 2 предложения, закончи ответ словом СТОП.)"

        val requestBodyWithLimit = JSONObject().apply {
            put("model", "@cf/meta/llama-3.1-8b-instruct")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", constrainedPrompt)
                })
            })
            put("temperature", 0.7)
            put("max_tokens", 60)               // ограничение по токенам
            put("stop", JSONArray().apply {     // стоп-слова
                put("СТОП")
                put("###")
            })
        }.toString()

        val responseWithLimit = sendRequest(client, accountId, apiToken, requestBodyWithLimit)
        val answerWithLimit = extractAnswer(responseWithLimit)

        // ────────────────────────────────────────────
        // Вывод обоих ответов
        // ────────────────────────────────────────────
        println("\n=== ОТВЕТ БЕЗ ОГРАНИЧЕНИЙ ===")
        println(answerNoLimit)

        println("\n=== ОТВЕТ С ОГРАНИЧЕНИЯМИ ===")
        println(answerWithLimit)

        // Простое сравнение
        println("\n--- Сравнение ---")
        println("Длина без ограничений : ${answerNoLimit.length} символов")
        println("Длина с ограничениями: ${answerWithLimit.length} символов")
        println("Содержит ли ответ 'СТОП'? ${if (answerWithLimit.contains("СТОП")) "Да" else "Нет"}")
        println("Уложился в 2 предложения? (проверьте визуально)")

        println("─".repeat(50))
    }
    println("Работа завершена.")
}

// ─── Вспомогательные функции ─────────────────────────────
private fun sendRequest(
    client: HttpClient,
    accountId: String,
    apiToken: String,
    body: String
): String {
    val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions"
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiToken")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        println("Ошибка API: ${response.statusCode()} ${response.body()}")
        return """{"choices":[{"message":{"content":"ОШИБКА"}}]}"""
    }
    return response.body()
}

private fun extractAnswer(jsonStr: String): String {
    return try {
        val json = JSONObject(jsonStr)
        json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    } catch (e: Exception) {
        "Не удалось разобрать ответ: ${e.message}"
    }
}
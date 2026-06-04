import org.json.JSONArray
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

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val accountId = System.getenv("CLOUDFLARE_ACCOUNT_ID")
        ?: error("Установите CLOUDFLARE_ACCOUNT_ID")
    val apiToken = System.getenv("CLOUDFLARE_API_TOKEN")
        ?: error("Установите CLOUDFLARE_API_TOKEN")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .build()
    val model = "@cf/meta/llama-3.1-8b-instruct"
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Эксперимент с параметром temperature (с таймаутами)")
    println("─".repeat(60))

    val defaultTask = """
В одной комнате 3 выключателя, в другой 3 лампочки.
Можно зайти в комнату с лампочками только один раз.
Как определить, какой выключатель включает каждую лампочку?
""".trimIndent()

    print("Введите задачу (Enter для задачи про выключатели, 'exit' для выхода): ")
    val input = scanner.nextLine().trim()
    if (input.equals("exit", ignoreCase = true)) return
    val task = input.ifEmpty { defaultTask }

    val temperatures = listOf(0.0, 0.7, 1.2)
    for (temp in temperatures) {
        println("\n--- Temperature = $temp ---")
        print("Запрос... ")
        val startTime = System.currentTimeMillis()
        val answer = chatWithTimeout(client, accountId, apiToken, model, task, temp, 30) // таймаут 30 секунд
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        println("готово (${"%.1f".format(elapsed)} с)")
        println(answer)
        println("─".repeat(60))
    }
    println("Эксперимент завершён. Сравните стиль и содержание ответов.")
}

/**
 * Отправляет запрос с заданным таймаутом в секундах.
 * Если ответ не получен за указанное время, возвращает сообщение о превышении таймаута.
 */
fun chatWithTimeout(
    client: HttpClient,
    accountId: String,
    apiToken: String,
    model: String,
    userMessage: String,
    temperature: Double,
    timeoutSeconds: Long
): String {
    return try {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
            put("temperature", temperature)
        }.toString()

        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .timeout(Duration.ofSeconds(timeoutSeconds))   // таймаут на HTTP-уровне
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        // Отправляем асинхронно, чтобы можно было ждать с таймаутом
        val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = future.get(timeoutSeconds, TimeUnit.SECONDS)

        if (response.statusCode() != 200) {
            "Ошибка API: ${response.statusCode()} ${response.body()}"
        } else {
            JSONObject(response.body())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    } catch (e: java.util.concurrent.TimeoutException) {
        "⏱️ Превышен таймаут ($timeoutSeconds с). Модель не успела ответить."
    } catch (e: Exception) {
        "Ошибка при запросе: ${e.message}"
    }
}
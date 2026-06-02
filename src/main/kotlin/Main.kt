import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Scanner

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val accountId = System.getenv("CLOUDFLARE_ACCOUNT_ID")
        ?: error("Установите переменную окружения CLOUDFLARE_ACCOUNT_ID")
    val apiToken = System.getenv("CLOUDFLARE_API_TOKEN")
        ?: error("Установите переменную окружения CLOUDFLARE_API_TOKEN")

    val client = HttpClient.newHttpClient()
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Чат с Cloudflare AI (бесплатный)")
    println("Модель: Llama 3.1 8B")
    println("Для выхода введите 'exit'")
    println("─".repeat(40))

    while (true) {
        print("Вы: ")
        val userInput = scanner.nextLine()
        if (userInput.equals("exit", ignoreCase = true)) break

        val requestBody = JSONObject().apply {
            // модель можно менять: @cf/meta/llama-3.1-8b-instruct,
            // @cf/deepseek-ai/deepseek-r1-distill-qwen-32b и др.
            put("model", "@cf/meta/llama-3.1-8b-instruct")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            })
            put("temperature", 0.7)
        }.toString()

        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            println("Ошибка: ${response.statusCode()} ${response.body()}")
            continue
        }

        val json = JSONObject(response.body())
        val answer = json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        println("Бот: $answer")
        println("─".repeat(40))
    }
    println("До свидания!")
}
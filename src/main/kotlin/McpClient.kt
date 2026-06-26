import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Scanner

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val scanner = Scanner(System.`in`, "UTF-8")
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    println("Команды:")
    println("  best <запрос>   – найти лучшее")
    println("  worst <запрос>  – найти худшее")
    println("  exit            – выход")

    while (true) {
        print("> ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.startsWith("best ", true) || input.startsWith("worst ", true) -> {
                val parts = input.split(" ", limit = 2)
                val criterion = if (parts[0] == "worst") "worst" else "best"
                val query = parts[1]

                // Шаг 1: поиск
                val searchResultJson = callTool(httpClient, "web_search", JSONObject().apply { put("query", query) })
                val searchResult = JSONObject(searchResultJson)
                if (searchResult.has("error")) {
                    println("Ошибка поиска: ${searchResult.getString("error")}")
                    continue
                }
                val resultsArray = searchResult.optJSONArray("results")
                if (resultsArray == null || resultsArray.length() == 0) {
                    println("Поиск не дал результатов.")
                    continue
                }

                println("\nНайдено ${resultsArray.length()} вариантов:")
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.getJSONObject(i)
                    val name = item.optString("name", "Без названия")
                    val desc = item.optString("description", "")
                    println("${i + 1}. $name – $desc")
                }

                // Шаг 2: анализ
                val summaryResultJson = callTool(httpClient, "summarize", JSONObject().apply {
                    put("items", resultsArray)
                    put("criterion", criterion)
                })
                val summaryResult = JSONObject(summaryResultJson)
                if (summaryResult.has("error")) {
                    println("Ошибка анализа: ${summaryResult.getString("error")}")
                    continue
                }
                val selected = summaryResult.optString("selected", "?")
                val rating = summaryResult.optInt("rating", 0)
                val positives = summaryResult.optInt("positives", 0)
                val negatives = summaryResult.optInt("negatives", 0)
                val summaryText = summaryResult.optString("summary", "")
                println("\n${if (criterion == "worst") "Худший" else "Лучший"} вариант: $selected (рейтинг $rating баллов, +$positives / -$negatives)")
                println(summaryText)

                // Шаг 3: сохранение
                val saveResultJson = callTool(httpClient, "save_to_file", JSONObject().apply {
                    put("content", summaryText)
                })
                val saveResult = JSONObject(saveResultJson)
                if (saveResult.has("status")) {
                    println("\nРезультат сохранён в файл: ${saveResult.optString("file", "summary_output.txt")}")
                } else {
                    println("\nОшибка сохранения: $saveResultJson")
                }
            }
            else -> println("Неизвестная команда")
        }
    }
}

fun callTool(client: HttpClient, toolName: String, arguments: JSONObject): String {
    val request = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", "1")
        put("method", "tools/call")
        put("params", JSONObject().apply {
            put("name", toolName)
            put("arguments", arguments)
        })
    }

    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8081/mcp"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
        .build()

    val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    return if (response.statusCode() == 200) {
        val json = JSONObject(response.body())
        val result = json.getJSONObject("result")
        result.getJSONArray("content").getJSONObject(0).getString("text")
    } else {
        "Ошибка: ${response.statusCode()} ${response.body()}"
    }
}
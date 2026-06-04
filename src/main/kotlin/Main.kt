import org.json.JSONArray
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
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val accountId = System.getenv("CLOUDFLARE_ACCOUNT_ID")
        ?: error("Установите CLOUDFLARE_ACCOUNT_ID")
    val apiToken = System.getenv("CLOUDFLARE_API_TOKEN")
        ?: error("Установите CLOUDFLARE_API_TOKEN")

    // Увеличенный таймаут (60 секунд)
    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(60))
        .build()

    val model = "@cf/meta/llama-3.1-8b-instruct"  // или более быстрая "@cf/meta/llama-2-7b-chat-int8"
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Решение задач четырьмя способами (Cloudflare AI)")
    println("─".repeat(60))

    while (true) {
        println("\nВведите задачу (или 'exit' для выхода):")
        val task = scanner.nextLine().trim()
        if (task.equals("exit", ignoreCase = true)) break
        if (task.isEmpty()) {
            println("Пустая задача, попробуйте снова.")
            continue
        }

        println("\nОтправляю запросы...")

        // 1. Прямой запрос
        println("[1/4] Прямой запрос...")
        val answer1 = chat(client, accountId, apiToken, model, task)

        // 2. Пошаговое решение
        println("[2/4] Пошаговое решение...")
        val prompt2 = "$task\nРешай пошагово, подробно объясняя каждый этап."
        val answer2 = chat(client, accountId, apiToken, model, prompt2)

        // 3. Авто-промптинг
        println("[3/4] Генерация промпта...")
        val promptGen = """
Ты — эксперт по составлению промптов для LLM. На основе следующей задачи создай оптимальный промпт,
который приведёт к правильному и полному решению. Выведи только текст самого промпта, без лишних комментариев.

Задача:
$task
""".trimIndent()
        val generatedPrompt = chat(client, accountId, apiToken, model, promptGen)
        println("Получен промпт. Отправляю запрос с ним...")
        val answer3 = chat(client, accountId, apiToken, model, generatedPrompt)

        // 4. Группа экспертов
        println("[4/4] Группа экспертов...")
        val prompt4 = """
Твоя задача — решить следующую проблему от лица трёх экспертов.
Дай сначала решение от лица Аналитика (логический разбор), затем от Инженера (практический, пошаговый план),
а затем от Критика (оценка правильности и замечания). Запиши мнение каждого эксперта, чётко указывая роль.

Задача:
$task
""".trimIndent()
        val answer4 = chat(client, accountId, apiToken, model, prompt4)

        // Вывод результатов
        println("\n" + "=".repeat(60))
        println("=== 1. ПРЯМОЙ ОТВЕТ ===")
        println(answer1)

        println("\n=== 2. ПОШАГОВОЕ РЕШЕНИЕ ===")
        println(answer2)

        println("\n=== 3. АВТО-ПРОМПТИНГ ===")
        println("Сгенерированный промпт:\n$generatedPrompt")
        println("Решение:\n$answer3")

        println("\n=== 4. ГРУППА ЭКСПЕРТОВ ===")
        println(answer4)

        println("\n--- Сравнение длин ---")
        println("Прямой:           ${answer1.length} символов")
        println("Пошаговый:        ${answer2.length} символов")
        println("Авто-промптинг:   ${answer3.length} символов")
        println("Группа экспертов: ${answer4.length} символов")
        println("─".repeat(60))
    }

    println("Программа завершена.")
}

fun chat(
    client: HttpClient,
    accountId: String,
    apiToken: String,
    model: String,
    userMessage: String
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
            put("temperature", 0.7)
        }.toString()

        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/v1/chat/completions"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .timeout(Duration.ofSeconds(60))   // таймаут для каждого запроса
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            "Ошибка API: ${response.statusCode()} ${response.body()}"
        } else {
            JSONObject(response.body())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    } catch (e: Exception) {
        "Ошибка при запросе: ${e.message}"
    }
}
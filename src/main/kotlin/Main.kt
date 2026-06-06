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

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("MISTRAL_API_KEY")
        ?: error("Установите переменную окружения MISTRAL_API_KEY")

    val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    val scanner = Scanner(System.`in`, "UTF-8")

    // Усиленный промпт для слабой и средней моделей
    val systemPromptStrict = "Ты — эксперт по решению задач. Твоя задача — дать ПОЛНЫЙ и ЗАКОНЧЕННЫЙ ответ. " +
            "Решай задачу пошагово, объясняя каждый этап. Напиши не менее 5 предложений. Не задавай уточняющих вопросов."

    // Обычный промпт для сильной модели
    val systemPromptStrong = "Ты — эксперт по решению задач. Решай задачу пошагово, объясняя каждый этап рассуждений. " +
            "В конце дай окончательный ответ. Не задавай уточняющих вопросов, используй все доступные данные."

    data class ModelConfig(
        val name: String,
        val modelId: String,
        val description: String,
        val maxTokens: Int?,
        val timeoutSec: Long,
        val systemPrompt: String
    )

    // Конфигурация с усиленными параметрами для 7B и 12B
    val models = listOf(
        ModelConfig("Слабая", "open-mistral-7b", "Mistral 7B", 400, 90, systemPromptStrict),
        ModelConfig("Средняя", "open-mistral-nemo", "Mistral Nemo 12B", 500, 90, systemPromptStrict),
        ModelConfig("Сильная", "open-mixtral-8x7b", "Mixtral 8x7B", null, 120, systemPromptStrong)
    )

    println("Сравнение моделей (Mistral AI) — пошаговое решение с усиленными инструкциями")
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

    data class Result(
        val model: String,
        val answer: String,
        val timeSec: Double,
        val totalTokens: Int,
        val completionTokens: Int
    )

    val results = mutableListOf<Result>()

    for (modelConfig in models) {
        println("\n▶️  Модель: ${modelConfig.description} (${modelConfig.modelId})")
        print("   Запрос... ")
        val startTime = System.currentTimeMillis()

        val (answer, usage) = chatWithTimeout(
            client, apiKey, modelConfig.modelId, task, modelConfig.systemPrompt,
            modelConfig.maxTokens, modelConfig.timeoutSec
        )

        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0

        if (answer.startsWith("Ошибка") || answer.isBlank()) {
            println("⚠️ Модель не дала ответа: ${answer}")
            continue
        }

        println("готово (${"%.1f".format(elapsed)} с)")

        val totalTokens = usage?.optInt("total_tokens", -1) ?: -1
        val completionTokens = usage?.optInt("completion_tokens", -1) ?: -1

        println("   ────────────────────────────────")
        println("   Ответ:")
        println(answer)
        println("   ────────────────────────────────")
        println("   Токенов всего: $totalTokens, генерация: $completionTokens")
        println("   Время ответа: ${"%.1f".format(elapsed)} с")
        println()

        results.add(Result(modelConfig.name, answer, elapsed, totalTokens, completionTokens))
    }

    if (results.isEmpty()) {
        println("\nНи одна модель не смогла ответить. Проверьте ключ и интернет.")
    } else {
        println("\n" + "=".repeat(70))
        println("СРАВНЕНИЕ МОДЕЛЕЙ")
        println("=".repeat(70))
        System.out.printf("%-10s %-10s %-12s %-12s%n", "Модель", "Время(с)", "Всего токен.", "Токен. ответ")
        println("─".repeat(70))
        for (r in results) {
            System.out.printf("%-10s %-10.1f %-12d %-12d%n",
                r.model, r.timeSec, r.totalTokens, r.completionTokens)
        }
        println("\nТеперь все модели дают законченные рассуждения. Сравните качество ответов.")
    }
}

fun chatWithTimeout(
    client: HttpClient,
    apiKey: String,
    model: String,
    userMessage: String,
    systemPrompt: String,
    maxTokens: Int?,
    timeoutSeconds: Long
): Pair<String, JSONObject?> {
    return try {
        val messages = org.json.JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
            if (maxTokens != null) {
                put("max_tokens", maxTokens)
            }
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val future: CompletableFuture<HttpResponse<String>> =
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
        val response = future.get(timeoutSeconds, TimeUnit.SECONDS)

        if (response.statusCode() != 200) {
            "Ошибка API: ${response.statusCode()} ${response.body()}" to null
        } else {
            val json = JSONObject(response.body())
            val answer = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val usage = json.optJSONObject("usage")
            answer to usage
        }
    } catch (e: TimeoutException) {
        "Ошибка: превышен таймаут ($timeoutSeconds с)" to null
    } catch (e: Exception) {
        "Ошибка: ${e.message}" to null
    }
}
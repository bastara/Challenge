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

// ----- Состояние задачи (конечный автомат) -----
data class TaskState(
    var phase: String = "idle",          // idle, planning, execution, validation, done
    var step: String = "",               // текущий шаг
    var expectedAction: String = "",     // что ожидается от пользователя
    val context: MutableList<String> = mutableListOf()  // накопленные результаты/заметки
)

class TaskAgent(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val systemPrompt: String = "Ты — ассистент-исполнитель задач. Следуй инструкциям состояния задачи.",
    private val maxTokens: Int = 1000
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    // Краткосрочная память (история диалога)
    private val shortTermHistory = mutableListOf<Map<String, String>>()
    private val windowSize = 12

    // Состояние задачи
    private val taskState = TaskState()

    // Файлы
    private val historyFile = File("task_history.json")
    private val stateFile = File("task_state.json")

    init {
        loadAll()
        if (shortTermHistory.isEmpty()) {
            shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        }
    }

    // ----- Загрузка и сохранение -----
    private fun loadAll() {
        loadHistory()
        loadTaskState()
    }

    private fun saveAll() {
        saveHistory()
        saveTaskState()
    }

    private fun loadHistory() {
        if (historyFile.exists()) {
            try {
                shortTermHistory.clear()
                JSONArray(historyFile.readText()).forEach { obj ->
                    obj as JSONObject
                    shortTermHistory.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content")))
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveHistory() {
        historyFile.writeText(JSONArray().apply {
            shortTermHistory.forEach { put(JSONObject(it)) }
        }.toString(2))
    }

    private fun loadTaskState() {
        if (stateFile.exists()) {
            try {
                val json = JSONObject(stateFile.readText())
                taskState.phase = json.getString("phase")
                taskState.step = json.optString("step", "")
                taskState.expectedAction = json.optString("expectedAction", "")
                taskState.context.clear()
                json.getJSONArray("context").forEach { taskState.context.add(it as String) }
            } catch (_: Exception) {
                resetTaskState()
            }
        }
    }

    private fun saveTaskState() {
        val json = JSONObject()
        json.put("phase", taskState.phase)
        json.put("step", taskState.step)
        json.put("expectedAction", taskState.expectedAction)
        json.put("context", JSONArray(taskState.context))
        stateFile.writeText(json.toString(2))
    }

    private fun resetTaskState() {
        taskState.phase = "idle"
        taskState.step = ""
        taskState.expectedAction = ""
        taskState.context.clear()
        saveTaskState()
    }

    // ----- Управление задачей -----
    fun startTask(description: String): String {
        resetTaskState()
        taskState.phase = "planning"
        taskState.step = "Определение требований"
        taskState.expectedAction = "Опишите детали задачи или введите 'next' для продолжения"
        taskState.context.add("Задача: $description")
        saveAll()
        return "Задача создана. Этап: planning. Текущий шаг: ${taskState.step}. ${taskState.expectedAction}"
    }

    fun nextStep(): String {
        when (taskState.phase) {
            "idle" -> return "Нет активной задачи. Введите 'start <описание>'."
            "planning" -> {
                taskState.phase = "execution"
                taskState.step = "Выполнение шага 1"
                taskState.expectedAction = "Выполните действие или введите 'step <результат>'"
            }

            "execution" -> {
                // Можно добавить логику перехода к validation после N шагов
                taskState.phase = "validation"
                taskState.step = "Проверка результатов"
                taskState.expectedAction =
                    "Проверьте результат и введите 'complete' для завершения или 'step <замечание>' для доработки"
            }

            "validation" -> {
                taskState.phase = "done"
                taskState.step = "Задача завершена"
                taskState.expectedAction = ""
            }

            "done" -> return "Задача уже завершена. Введите 'start <описание>' для новой."
        }
        saveAll()
        return "Этап: ${taskState.phase}. Текущий шаг: ${taskState.step}. ${taskState.expectedAction}"
    }

    fun recordStep(description: String): String {
        if (taskState.phase == "idle" || taskState.phase == "done") {
            return "Нет активной задачи. Введите 'start <описание>'."
        }
        taskState.context.add("Шаг (${taskState.phase}): $description")
        saveAll()
        return "Шаг записан. Введите 'next' для следующего шага или 'pause' для паузы."
    }

    fun pauseTask(): String {
        if (taskState.phase == "idle" || taskState.phase == "done") {
            return "Нет активной задачи для паузы."
        }
        saveAll()
        return "Задача приостановлена на этапе '${taskState.phase}'. Для продолжения введите 'resume'."
    }

    fun resumeTask(): String {
        if (taskState.phase == "idle") {
            return "Нет сохранённой задачи. Введите 'start <описание>'."
        }
        if (taskState.phase == "done") {
            return "Сохранённая задача уже завершена. Введите 'start <описание>' для новой."
        }
        // Всё уже загружено в taskState, просто информируем
        return "Задача возобновлена. Этап: ${taskState.phase}, шаг: '${taskState.step}'. ${taskState.expectedAction}"
    }

    fun completeTask(): String {
        if (taskState.phase == "idle" || taskState.phase == "done") {
            return "Нет активной задачи."
        }
        taskState.phase = "done"
        taskState.step = "Задача завершена"
        taskState.expectedAction = ""
        saveAll()
        return "Задача переведена в статус 'done'."
    }

    fun showStatus(): String {
        if (taskState.phase == "idle") return "Нет активной задачи."
        return "Этап: ${taskState.phase}\nШаг: ${taskState.step}\nОжидаемое действие: ${taskState.expectedAction}\nКонтекст: ${
            taskState.context.joinToString(
                "; "
            )
        }"
    }

    // ----- Отправка сообщения (с учётом состояния задачи) -----
    fun sendMessage(userMessage: String): String {
        // Если задача не активна, работаем как обычный чат (без состояния)
        if (taskState.phase == "idle" || taskState.phase == "done") {
            shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
            val messages = shortTermHistory.toList()
            val (answer, _) = callApi(messages)
            shortTermHistory.add(mapOf("role" to "assistant", "content" to answer))
            trimHistory()
            saveHistory()
            return answer
        }

        // Иначе добавляем в промпт описание состояния задачи
        shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
        val taskContextMessage = buildTaskContextMessage()
        val messages = mutableListOf<Map<String, String>>()
        messages.add(shortTermHistory.first { it["role"] == "system" })
        messages.add(taskContextMessage)
        messages.addAll(shortTermHistory.drop(1).takeLast(windowSize - 2)) // оставляем место под system и task
        val (answer, _) = callApi(messages)
        shortTermHistory.add(mapOf("role" to "assistant", "content" to answer))
        trimHistory()
        saveAll()
        return answer
    }

    private fun buildTaskContextMessage(): Map<String, String> {
        val ctx = """
            |Current task state:
            |- Phase: ${taskState.phase}
            |- Current step: ${taskState.step}
            |- Expected user action: ${taskState.expectedAction}
            |- Context from previous steps: ${taskState.context.joinToString(" | ")}
        """.trimMargin()
        return mapOf("role" to "system", "content" to ctx)
    }

    private fun trimHistory() {
        if (shortTermHistory.size > windowSize + 2) {
            val systemMsg = shortTermHistory.first { it["role"] == "system" }
            shortTermHistory.removeAll { it["role"] != "system" && it != systemMsg }
            shortTermHistory.add(0, systemMsg)
            shortTermHistory.addAll(shortTermHistory.drop(1).takeLast(windowSize))
        }
    }

    private fun callApi(
        messages: List<Map<String, String>>,
        maxTokens: Int = this.maxTokens
    ): Pair<String, JSONObject?> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { messages.forEach { put(JSONObject(it)) } })
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
        val response = CompletableFuture.supplyAsync { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .get(30, TimeUnit.SECONDS)
        if (response.statusCode() != 200) throw RuntimeException("API error ${response.statusCode()}: ${response.body()}")
        val json = JSONObject(response.body())
        val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return answer to json.optJSONObject("usage")
    }

    // В классе TaskAgent
    fun reset() {
        resetTaskState()
        shortTermHistory.clear()
        shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        saveAll()
    }
}

// ----- CLI -----
fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY не задан")
    val agent = TaskAgent(apiKey)
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Агент с конечным автоматом задачи (Task FSM)")
    println("Команды:")
    println("  start <описание>      – начать новую задачу")
    println("  step <описание>       – зафиксировать выполненный шаг")
    println("  next                  – перейти к следующему шагу")
    println("  pause                 – приостановить задачу")
    println("  resume                – продолжить задачу")
    println("  status                – показать состояние задачи")
    println("  complete              – завершить задачу")
    println("  reset                 – сбросить задачу и историю")
    println("  help / ?              – справка")
    println("  exit                  – выход")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) || input.equals("?") -> {
                println("start/step/next/pause/resume/status/complete/reset")
            }

            input.startsWith("start ", true) -> {
                println(agent.startTask(input.removePrefix("start ").trim()))
            }

            input.startsWith("step ", true) -> {
                println(agent.recordStep(input.removePrefix("step ").trim()))
            }

            input.equals("next", true) -> {
                println(agent.nextStep())
            }

            input.equals("pause", true) -> {
                println(agent.pauseTask())
            }

            input.equals("resume", true) -> {
                println(agent.resumeTask())
            }

            input.equals("status", true) -> {
                println(agent.showStatus())
            }

            input.equals("complete", true) -> {
                println(agent.completeTask())
            }

            input.equals("reset", true) -> {
                agent.reset()
                println("Задача и история сброшены.")
            }

            input.isEmpty() -> continue
            else -> {
                print("Агент: ")
                val start = System.currentTimeMillis()
                val answer = agent.sendMessage(input)
                println(answer)
                println("(за ${"%.1f".format((System.currentTimeMillis() - start) / 1000.0)} с)")
            }
        }
    }
}
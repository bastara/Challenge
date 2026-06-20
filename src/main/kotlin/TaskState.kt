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

    // Инварианты
    private val invariants = mutableListOf<String>()

    // Файлы
    private val historyFile = File("task_history.json")
    private val stateFile = File("task_state.json")
    private val invariantsFile = File("invariants.json")

    init {
        loadAll()
        if (shortTermHistory.isEmpty()) {
            shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        }
    }

    // ----- Разрешённые переходы -----
    private val validTransitions: Map<String, Set<String>> = mapOf(
        "idle"      to setOf("start"),
        "planning"  to setOf("next", "pause"),
        "execution" to setOf("next", "pause"),
        "validation" to setOf("complete", "pause"),
        "done"      to setOf("start")   // начать новую задачу
    )

    // Действия, которые не меняют состояние, но фиксируют прогресс (step)
    private val stepAllowedIn: Set<String> = setOf("planning", "execution", "validation")

    private fun isValidTransition(currentPhase: String, action: String): Boolean {
        return validTransitions[currentPhase]?.contains(action) == true
    }

    // ----- Загрузка и сохранение -----
    private fun loadAll() {
        loadHistory()
        loadTaskState()
        loadInvariants()
    }

    private fun saveAll() {
        saveHistory()
        saveTaskState()
        saveInvariants()
    }

    private fun loadHistory() {
        if (historyFile.exists()) {
            try {
                shortTermHistory.clear()
                JSONArray(historyFile.readText()).forEach { obj ->
                    obj as JSONObject
                    shortTermHistory.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content")))
                }
            } catch (_: Exception) { }
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

    private fun loadInvariants() {
        if (invariantsFile.exists()) {
            try {
                invariants.clear()
                JSONArray(invariantsFile.readText()).forEach { invariants.add(it as String) }
            } catch (_: Exception) { }
        }
    }

    private fun saveInvariants() {
        invariantsFile.writeText(JSONArray(invariants).toString(2))
    }

    private fun resetTaskState() {
        taskState.phase = "idle"
        taskState.step = ""
        taskState.expectedAction = ""
        taskState.context.clear()
        saveTaskState()
    }

    // ----- Управление инвариантами (без изменений) -----
    fun addInvariant(rule: String): String {
        invariants.add(rule)
        saveInvariants()
        return "Инвариант добавлен (всего ${invariants.size})."
    }

    fun removeInvariant(index: Int): String {
        return if (index in 0 until invariants.size) {
            val removed = invariants.removeAt(index)
            saveInvariants()
            "Удалён инвариант: $removed"
        } else {
            "Неверный индекс. Используйте invariant list, чтобы увидеть номера."
        }
    }

    fun listInvariants(): String {
        if (invariants.isEmpty()) return "Инварианты не заданы."
        return invariants.withIndex().joinToString("\n") { "${it.index}: ${it.value}" }
    }

    fun clearInvariants(): String {
        invariants.clear()
        saveInvariants()
        return "Инварианты очищены."
    }

    // ----- Управление задачей с явными переходами -----
    fun startTask(description: String): String {
        // Разрешено только из idle или done
        if (!isValidTransition(taskState.phase, "start")) {
            return "Невозможно начать задачу. Текущее состояние: '${taskState.phase}'. Сначала завершите текущую задачу или сбросьте её."
        }
        resetTaskState()
        taskState.phase = "planning"
        taskState.step = "Определение требований"
        taskState.expectedAction = "Опишите детали задачи или введите 'next' для продолжения"
        taskState.context.add("Задача: $description")
        saveAll()
        return "Задача создана. Этап: planning. Текущий шаг: ${taskState.step}. ${taskState.expectedAction}"
    }

    fun nextStep(): String {
        if (!isValidTransition(taskState.phase, "next")) {
            return "Невозможно перейти к следующему шагу. Текущее состояние: '${taskState.phase}'. Ожидаемые действия: ${validTransitions[taskState.phase]?.joinToString()}"
        }
        // Логика перехода
        when (taskState.phase) {
            "planning" -> {
                taskState.phase = "execution"
                taskState.step = "Выполнение шага 1"
                taskState.expectedAction = "Выполните действие или введите 'step <результат>'"
            }
            "execution" -> {
                taskState.phase = "validation"
                taskState.step = "Проверка результатов"
                taskState.expectedAction = "Проверьте результат и введите 'complete' для завершения или 'step <замечание>' для доработки"
            }
            // validation и idle сюда не попадают из-за проверки isValidTransition
            else -> return "Невозможно перейти к следующему шагу из состояния '${taskState.phase}'."
        }
        saveAll()
        return "Этап: ${taskState.phase}. Текущий шаг: ${taskState.step}. ${taskState.expectedAction}"
    }

    fun recordStep(description: String): String {
        if (!stepAllowedIn.contains(taskState.phase)) {
            return "Шаг не может быть записан в текущем состоянии '${taskState.phase}'. Шаги разрешены в: planning, execution, validation."
        }
        taskState.context.add("Шаг (${taskState.phase}): $description")
        saveAll()
        return "Шаг записан. Введите 'next' для следующего шага или 'pause' для паузы."
    }

    fun pauseTask(): String {
        if (!isValidTransition(taskState.phase, "pause")) {
            return "Невозможно приостановить задачу в состоянии '${taskState.phase}'."
        }
        saveAll()
        return "Задача приостановлена на этапе '${taskState.phase}'. Для продолжения введите 'resume'."
    }

    fun resumeTask(): String {
        // resume разрешён из любого состояния, кроме idle, если задача была сохранена
        if (taskState.phase == "idle") {
            return "Нет сохранённой задачи. Введите 'start <описание>'."
        }
        // Для остальных состояний просто продолжаем
        return "Задача возобновлена. Этап: ${taskState.phase}, шаг: '${taskState.step}'. ${taskState.expectedAction}"
    }

    fun completeTask(): String {
        if (!isValidTransition(taskState.phase, "complete")) {
            return "Невозможно завершить задачу. Текущее состояние: '${taskState.phase}'. Завершение разрешено только после validation."
        }
        taskState.phase = "done"
        taskState.step = "Задача завершена"
        taskState.expectedAction = ""
        saveAll()
        return "Задача переведена в статус 'done'."
    }

    fun showStatus(): String {
        if (taskState.phase == "idle") return "Нет активной задачи."
        return """
            Этап: ${taskState.phase}
            Шаг: ${taskState.step}
            Ожидаемое действие: ${taskState.expectedAction}
            Контекст: ${taskState.context.joinToString("; ")}
        """.trimIndent()
    }

    // ----- Отправка сообщения с учётом состояния задачи и инвариантов -----
    fun sendMessage(userMessage: String): String {
        if (taskState.phase == "idle" || taskState.phase == "done") {
            shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
            val messages = buildMessages()
            val (answer, _) = callApi(messages)
            shortTermHistory.add(mapOf("role" to "assistant", "content" to answer))
            trimHistory()
            saveHistory()
            return answer
        }

        shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
        val taskContextMessage = buildTaskContextMessage()
        val invariantMessage = buildInvariantMessage()
        val messages = mutableListOf<Map<String, String>>()
        messages.add(shortTermHistory.first { it["role"] == "system" })
        messages.add(taskContextMessage)
        if (invariantMessage != null) messages.add(invariantMessage)
        messages.addAll(shortTermHistory.drop(1).takeLast(windowSize - 2 - (if (invariantMessage != null) 1 else 0)))
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

    private fun buildInvariantMessage(): Map<String, String>? {
        if (invariants.isEmpty()) return null
        val text = """
            |Invariants (must not be violated under any circumstances):
            |${invariants.joinToString("\n") { "- $it" }}
            |If the user's request conflicts with any of these invariants, refuse politely and explain why.
        """.trimMargin()
        return mapOf("role" to "system", "content" to text)
    }

    private fun buildMessages(): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        result.add(shortTermHistory.first { it["role"] == "system" })
        val invariantMsg = buildInvariantMessage()
        if (invariantMsg != null) result.add(invariantMsg)
        result.addAll(shortTermHistory.drop(1))
        return result
    }

    private fun trimHistory() {
        if (shortTermHistory.size > windowSize + 2) {
            val systemMsg = shortTermHistory.first { it["role"] == "system" }
            shortTermHistory.removeAll { it["role"] != "system" && it != systemMsg }
            shortTermHistory.add(0, systemMsg)
            shortTermHistory.addAll(shortTermHistory.drop(1).takeLast(windowSize))
        }
    }

    private fun callApi(messages: List<Map<String, String>>, maxTokens: Int = this.maxTokens): Pair<String, JSONObject?> {
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

    println("Агент с конечным автоматом, явными переходами и инвариантами")
    println("Команды:")
    println("  start <описание>          – начать новую задачу")
    println("  step <описание>           – зафиксировать выполненный шаг")
    println("  next                      – перейти к следующему этапу")
    println("  pause                     – приостановить задачу")
    println("  resume                    – продолжить задачу")
    println("  status                    – показать состояние задачи")
    println("  complete                  – завершить задачу (только после validation)")
    println("  reset                     – сбросить задачу и историю")
    println("  invariant add/remove/list/clear – управление инвариантами")
    println("  help / ?                  – справка")
    println("  exit                      – выход")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) || input.equals("?") -> {
                println("start/step/next/pause/resume/status/complete/reset")
                println("invariant add/remove/list/clear")
            }
            input.startsWith("start ", true) -> println(agent.startTask(input.removePrefix("start ").trim()))
            input.startsWith("step ", true) -> println(agent.recordStep(input.removePrefix("step ").trim()))
            input.equals("next", true) -> println(agent.nextStep())
            input.equals("pause", true) -> println(agent.pauseTask())
            input.equals("resume", true) -> println(agent.resumeTask())
            input.equals("status", true) -> println(agent.showStatus())
            input.equals("complete", true) -> println(agent.completeTask())
            input.equals("reset", true) -> {
                agent.reset()
                println("Задача и история сброшены.")
            }
            input.startsWith("invariant ", true) -> {
                val parts = input.removePrefix("invariant ").trim().split(" ", limit = 2)
                when (parts.getOrElse(0) { "" }) {
                    "add" -> {
                        if (parts.size >= 2) println(agent.addInvariant(parts[1]))
                        else println("Укажите правило: invariant add <текст>")
                    }
                    "remove" -> {
                        val index = parts.getOrElse(1) { "" }.toIntOrNull()
                        if (index != null) println(agent.removeInvariant(index))
                        else println("Укажите номер: invariant remove <число>")
                    }
                    "list" -> println(agent.listInvariants())
                    "clear" -> println(agent.clearInvariants())
                    else -> println("Неизвестная команда. Используйте add, remove, list, clear.")
                }
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
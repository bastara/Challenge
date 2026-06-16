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

class MemoryAgent(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val systemPrompt: String = "Ты — полезный ассистент с многослойной памятью.",
    private val maxTokens: Int = 1000,
    private val windowSize: Int = 10
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    // Три слоя памяти
    private val shortTermHistory = mutableListOf<Map<String, String>>()   // краткосрочная история
    private val workingMemory = mutableListOf<String>()                  // рабочая память (факты задачи)
    private val longTermMemory = mutableListOf<String>()                 // долговременная память (профиль, знания)

    // Файлы для каждого слоя
    private val shortTermFile = File("short_term_memory.json")
    private val workingFile = File("working_memory.json")
    private val longTermFile = File("long_term_memory.json")

    init {
        loadAll()
        if (shortTermHistory.isEmpty()) {
            shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        }
    }

    // ----- Загрузка и сохранение -----
    private fun loadAll() {
        loadShortTerm()
        loadWorking()
        loadLongTerm()
    }

    private fun saveAll() {
        saveShortTerm()
        saveWorking()
        saveLongTerm()
    }

    private fun loadShortTerm() {
        if (shortTermFile.exists()) {
            try {
                shortTermHistory.clear()
                JSONArray(shortTermFile.readText()).forEach { obj ->
                    obj as JSONObject
                    shortTermHistory.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content")))
                }
            } catch (_: Exception) { }
        }
    }

    private fun saveShortTerm() {
        shortTermFile.writeText(JSONArray().apply {
            shortTermHistory.forEach { put(JSONObject(it)) }
        }.toString(2))
    }

    private fun loadWorking() {
        if (workingFile.exists()) {
            try {
                workingMemory.clear()
                JSONArray(workingFile.readText()).forEach { workingMemory.add(it as String) }
            } catch (_: Exception) { }
        }
    }

    private fun saveWorking() {
        workingFile.writeText(JSONArray(workingMemory).toString(2))
    }

    private fun loadLongTerm() {
        if (longTermFile.exists()) {
            try {
                longTermMemory.clear()
                JSONArray(longTermFile.readText()).forEach { longTermMemory.add(it as String) }
            } catch (_: Exception) { }
        }
    }

    private fun saveLongTerm() {
        longTermFile.writeText(JSONArray(longTermMemory).toString(2))
    }

    // ----- Управление памятью (команды) -----
    fun addToShortTerm(fact: String) {
        shortTermHistory.add(mapOf("role" to "system", "content" to "Short-term note: $fact"))
        saveShortTerm()
    }

    fun addToWorkingMemory(fact: String) {
        workingMemory.add(fact)
        saveWorking()
    }

    fun addToLongTermMemory(fact: String) {
        longTermMemory.add(fact)
        saveLongTerm()
    }

    fun showShortTerm() = shortTermHistory.forEach { println("${it["role"]}: ${it["content"]}") }
    fun showWorkingMemory() = if (workingMemory.isEmpty()) println("Рабочая память пуста.") else workingMemory.forEach { println("- $it") }
    fun showLongTermMemory() = if (longTermMemory.isEmpty()) println("Долговременная память пуста.") else longTermMemory.forEach { println("- $it") }

    fun clearShortTerm() {
        shortTermHistory.clear()
        shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        saveShortTerm()
    }

    fun clearWorkingMemory() {
        workingMemory.clear()
        saveWorking()
    }

    fun clearLongTermMemory() {
        longTermMemory.clear()
        saveLongTerm()
    }

    fun resetAll() {
        shortTermHistory.clear()
        workingMemory.clear()
        longTermMemory.clear()
        shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        saveAll()
    }

    // ----- Отправка сообщения -----
    fun sendMessage(userMessage: String): String {
        shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
        val messages = buildMessages()
        val (answer, _) = callApi(messages)
        shortTermHistory.add(mapOf("role" to "assistant", "content" to answer))
        // Обрезаем краткосрочную память до окна
        if (shortTermHistory.size > windowSize + 2) { // system + recent
            val systemMsg = shortTermHistory.first { it["role"] == "system" }
            shortTermHistory.removeAll { it["role"] != "system" && it != systemMsg }
            shortTermHistory.add(0, systemMsg)
            shortTermHistory.addAll(shortTermHistory.drop(1).takeLast(windowSize))
        }
        saveAll()
        return answer
    }

    private fun buildMessages(): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        // системный промпт
        result.add(shortTermHistory.first { it["role"] == "system" })
        // долговременная память
        if (longTermMemory.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to "Long-term profile/knowledge:\n${longTermMemory.joinToString("\n") { "- $it" }}"))
        }
        // рабочая память
        if (workingMemory.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to "Working memory (current task):\n${workingMemory.joinToString("\n") { "- $it" }}"))
        }
        // краткосрочная история (последние сообщения)
        result.addAll(shortTermHistory.drop(1)) // всё кроме первого system (уже добавлен)
        return result
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
        val response = CompletableFuture.supplyAsync { client.send(request, HttpResponse.BodyHandlers.ofString()) }.get(30, TimeUnit.SECONDS)
        if (response.statusCode() != 200) throw RuntimeException("API error ${response.statusCode()}: ${response.body()}")
        val json = JSONObject(response.body())
        val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return answer to json.optJSONObject("usage")
    }
}

// ----- CLI -----
fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY не задан")
    val agent = MemoryAgent(apiKey, systemPrompt = "Ты — ассистент с памятью. Используй краткосрочную, рабочую и долговременную память.")
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Агент с многослойной памятью (краткосрочная / рабочая / долговременная)")
    println("Команды:")
    println("  remember short <текст>   – добавить в краткосрочную память")
    println("  remember task <факт>     – добавить в рабочую память")
    println("  remember profile <факт>  – сохранить в долговременную память")
    println("  show short / task / profile  – показать содержимое слоя")
    println("  clear short / task / profile – очистить слой")
    println("  reset                     – очистить ВСЕ слои")
    println("  help                      – справка")
    println("  exit                      – выход")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) -> {
                println("remember short/task/profile <текст> - сохранить в память")
                println("show/clear short/task/profile - просмотр/очистка слоя")
                println("reset - сбросить всё")
            }
            input.startsWith("remember short ", true) -> {
                agent.addToShortTerm(input.removePrefix("remember short ").trim())
                println("Добавлено в краткосрочную память.")
            }
            input.startsWith("remember task ", true) -> {
                agent.addToWorkingMemory(input.removePrefix("remember task ").trim())
                println("Добавлено в рабочую память.")
            }
            input.startsWith("remember profile ", true) -> {
                agent.addToLongTermMemory(input.removePrefix("remember profile ").trim())
                println("Добавлено в долговременную память.")
            }
            input.equals("show short", true) -> { println("Краткосрочная память:"); agent.showShortTerm() }
            input.equals("show task", true) -> { println("Рабочая память:"); agent.showWorkingMemory() }
            input.equals("show profile", true) -> { println("Долговременная память:"); agent.showLongTermMemory() }
            input.equals("clear short", true) -> { agent.clearShortTerm(); println("Краткосрочная память очищена.") }
            input.equals("clear task", true) -> { agent.clearWorkingMemory(); println("Рабочая память очищена.") }
            input.equals("clear profile", true) -> { agent.clearLongTermMemory(); println("Долговременная память очищена.") }
            input.equals("reset", true) -> { agent.resetAll(); println("Все слои памяти сброшены.") }
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
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

data class UserProfile(
    val fields: MutableMap<String, String> = mutableMapOf()
) {
    fun toPrompt(): String {
        if (fields.isEmpty()) return ""
        return "User profile:\n" + fields.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }
}

class MemoryAgent(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val systemPrompt: String = "Ты — полезный ассистент с многослойной памятью.",
    private val maxTokens: Int = 1000,
    private val windowSize: Int = 10
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    // Три слоя памяти
    private val shortTermHistory = mutableListOf<Map<String, String>>()
    private val workingMemory = mutableListOf<String>()
    private val longTermMemory = mutableListOf<String>()

    // Профиль пользователя
    private val userProfile = UserProfile()

    // Файлы
    private val shortTermFile = File("short_term_memory.json")
    private val workingFile = File("working_memory.json")
    private val longTermFile = File("long_term_memory.json")
    private val profileFile = File("user_profile.json")

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
        loadProfile()
    }

    private fun saveAll() {
        saveShortTerm()
        saveWorking()
        saveLongTerm()
        saveProfile()
    }

    private fun loadShortTerm() { /* без изменений */ }
    private fun saveShortTerm() { /* без изменений */ }
    private fun loadWorking() { /* без изменений */ }
    private fun saveWorking() { /* без изменений */ }
    private fun loadLongTerm() { /* без изменений */ }
    private fun saveLongTerm() { /* без изменений */ }

    private fun loadProfile() {
        if (profileFile.exists()) {
            try {
                val json = JSONObject(profileFile.readText())
                userProfile.fields.clear()
                json.keys().forEach { key ->
                    userProfile.fields[key] = json.getString(key)
                }
            } catch (_: Exception) { }
        }
    }

    private fun saveProfile() {
        val json = JSONObject()
        userProfile.fields.forEach { (k, v) -> json.put(k, v) }
        profileFile.writeText(json.toString(2))
    }

    // ----- Управление профилем (новые методы) -----
    fun setProfileField(key: String, value: String) {
        userProfile.fields[key] = value
        saveProfile()
    }

    fun deleteProfileField(key: String) {
        userProfile.fields.remove(key)
        saveProfile()
    }

    fun clearProfile() {
        userProfile.fields.clear()
        saveProfile()
    }

    fun showProfile() {
        if (userProfile.fields.isEmpty()) {
            println("Профиль пуст.")
        } else {
            println("Профиль пользователя:")
            userProfile.fields.forEach { (k, v) -> println("  $k: $v") }
        }
    }

    // ----- Остальные методы памяти (без изменений, но с добавлением профиля) -----
    fun addToShortTerm(fact: String) { /* без изменений */ }
    fun addToWorkingMemory(fact: String) { /* без изменений */ }
    fun addToLongTermMemory(fact: String) { /* без изменений */ }

    fun showShortTerm() { /* без изменений */ }
    fun showWorkingMemory() { /* без изменений */ }
    fun showLongTermMemory() { /* без изменений */ }

    fun clearShortTerm() { /* без изменений */ }
    fun clearWorkingMemory() { /* без изменений */ }
    fun clearLongTermMemory() { /* без изменений */ }

    fun resetAll() {
        shortTermHistory.clear()
        workingMemory.clear()
        longTermMemory.clear()
        userProfile.fields.clear()
        shortTermHistory.add(mapOf("role" to "system", "content" to systemPrompt))
        saveAll()
    }

    // ----- Отправка сообщения (с учётом профиля) -----
    fun sendMessage(userMessage: String): String {
        shortTermHistory.add(mapOf("role" to "user", "content" to userMessage))
        val messages = buildMessages()
        val (answer, _) = callApi(messages)
        shortTermHistory.add(mapOf("role" to "assistant", "content" to answer))
        // Обрезка краткосрочной памяти
        if (shortTermHistory.size > windowSize + 2) {
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
        // Базовый системный промпт
        result.add(shortTermHistory.first { it["role"] == "system" })
        // Долговременная память
        if (longTermMemory.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to "Long-term knowledge:\n${longTermMemory.joinToString("\n") { "- $it" }}"))
        }
        // Рабочая память
        if (workingMemory.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to "Working memory (current task):\n${workingMemory.joinToString("\n") { "- $it" }}"))
        }
        // Профиль пользователя
        val profilePrompt = userProfile.toPrompt()
        if (profilePrompt.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to profilePrompt))
        }
        // Краткосрочная история (кроме первого system)
        result.addAll(shortTermHistory.drop(1))
        return result
    }

    private fun callApi(messages: List<Map<String, String>>, maxTokens: Int = this.maxTokens): Pair<String, JSONObject?> {
        // без изменений, как в предыдущем коде
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

// ----- CLI с поддержкой профиля -----
fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY не задан")
    val agent = MemoryAgent(apiKey, systemPrompt = "Ты — ассистент с памятью и профилем пользователя.")
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Агент с профилем пользователя")
    println("Команды для профиля:")
    println("  profile show                  – показать профиль")
    println("  profile set <key> <value>     – задать поле профиля")
    println("  profile delete <key>          – удалить поле")
    println("  profile clear                 – очистить профиль")
    println("Остальные команды: см. help")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) -> {
                println("profile show/set/delete/clear - управление профилем")
                println("remember short/task/profile ... - работа с памятью")
                println("show/clear short/task/profile   - просмотр/очистка памяти")
                println("reset                          - сбросить всё")
            }
            input.startsWith("profile ", true) -> {
                val parts = input.removePrefix("profile ").trim().split(" ", limit = 3)
                when (parts.getOrElse(0) { "" }) {
                    "show" -> agent.showProfile()
                    "set" -> {
                        if (parts.size >= 3) {
                            agent.setProfileField(parts[1], parts[2])
                            println("Поле '${parts[1]}' установлено.")
                        } else println("Используйте: profile set <ключ> <значение>")
                    }
                    "delete" -> {
                        if (parts.size >= 2) {
                            agent.deleteProfileField(parts[1])
                            println("Поле '${parts[1]}' удалено.")
                        } else println("Используйте: profile delete <ключ>")
                    }
                    "clear" -> { agent.clearProfile(); println("Профиль очищен.") }
                    else -> println("Неизвестная команда профиля.")
                }
            }
            // ... остальные команды (remember, show, clear, reset) идентичны предыдущей версии ...
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
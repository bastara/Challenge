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
import java.util.concurrent.TimeoutException

// ----- Стратегии -----
sealed class Strategy {
    object SlidingWindow : Strategy()
    object StickyFacts : Strategy()
    object Branching : Strategy()
}

// ----- Состояние ветки -----
data class BranchState(
    val name: String,
    val history: MutableList<Map<String, String>> = mutableListOf()
)

// ----- Главный агент -----
class Agent(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val systemPrompt: String = "Ты — полезный ассистент. Отвечай кратко и по делу.",
    private val maxTokens: Int = 400
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private var currentStrategy: Strategy = Strategy.SlidingWindow

    // Sliding Window
    private val slidingHistory = mutableListOf<Map<String, String>>()
    var slidingWindowSize = 8
        private set

    // Sticky Facts
    private val factsHistory = mutableListOf<Map<String, String>>()
    private var facts = mutableListOf<String>()
    private val maxFacts = 20
    var factsWindowSize = 8
        private set

    // Branching
    private val branches = mutableMapOf<String, BranchState>()
    private var activeBranch: String? = null
    private var checkpoint: List<Map<String, String>>? = null

    // Файлы стратегий
    private val slidingFile = File("sliding_history.json")
    private val factsFile = File("sticky_facts_history.json")
    private val branchesFile = File("branches_history.json")

    init {
        loadStrategyData(Strategy.SlidingWindow)
    }

    /** Человекочитаемое название текущей стратегии */
    fun strategyDisplayName(): String = when (currentStrategy) {
        Strategy.SlidingWindow -> "Sliding Window"
        Strategy.StickyFacts -> "Sticky Facts"
        Strategy.Branching -> "Branching"
    }

    // ----- Переключение стратегии (с автосохранением предыдущей) -----
    fun setStrategy(newStrategy: Strategy) {
        if (newStrategy == currentStrategy) return
        saveStrategyData(currentStrategy)
        loadStrategyData(newStrategy)
        currentStrategy = newStrategy
    }

    private fun saveStrategyData(strategy: Strategy) {
        when (strategy) {
            Strategy.SlidingWindow -> saveSlidingWindow()
            Strategy.StickyFacts -> saveStickyFacts()
            Strategy.Branching -> saveBranches()
        }
    }

    private fun loadStrategyData(strategy: Strategy) {
        when (strategy) {
            Strategy.SlidingWindow -> loadSlidingWindow()
            Strategy.StickyFacts -> loadStickyFacts()
            Strategy.Branching -> loadBranches()
        }
    }

    // ----- Сохранение / загрузка Sliding Window -----
    private fun saveSlidingWindow() {
        val array = JSONArray()
        slidingHistory.forEach { array.put(JSONObject(it)) }
        slidingFile.writeText(array.toString(2))
    }

    private fun loadSlidingWindow() {
        slidingHistory.clear()
        if (slidingFile.exists()) {
            try {
                JSONArray(slidingFile.readText()).forEach { obj ->
                    obj as JSONObject
                    slidingHistory.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content")))
                }
            } catch (_: Exception) {
                slidingHistory.add(mapOf("role" to "system", "content" to systemPrompt))
            }
        } else {
            slidingHistory.add(mapOf("role" to "system", "content" to systemPrompt))
            saveSlidingWindow()
        }
    }

    // ----- Сохранение / загрузка Sticky Facts -----
    private fun saveStickyFacts() {
        val root = JSONObject()
        root.put("facts", JSONArray(facts))
        root.put("history", JSONArray().apply {
            factsHistory.forEach { put(JSONObject(it)) }
        })
        factsFile.writeText(root.toString(2))
    }

    private fun loadStickyFacts() {
        factsHistory.clear()
        facts.clear()
        if (factsFile.exists()) {
            try {
                val root = JSONObject(factsFile.readText())
                root.getJSONArray("facts").forEach { facts.add(it as String) }
                root.getJSONArray("history").forEach { obj ->
                    obj as JSONObject
                    factsHistory.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content")))
                }
            } catch (_: Exception) {
                factsHistory.add(mapOf("role" to "system", "content" to systemPrompt))
            }
        } else {
            factsHistory.add(mapOf("role" to "system", "content" to systemPrompt))
            saveStickyFacts()
        }
    }

    // ----- Сохранение / загрузка Branching -----
    private fun saveBranches() {
        val root = JSONObject()
        root.put("activeBranch", activeBranch ?: "")
        val branchesObj = JSONObject()
        branches.forEach { (name, state) ->
            branchesObj.put(name, JSONArray().apply {
                state.history.forEach { put(JSONObject(it)) }
            })
        }
        root.put("branches", branchesObj)
        checkpoint?.let { cp ->
            root.put("checkpoint", JSONArray().apply { cp.forEach { put(JSONObject(it)) } })
        }
        branchesFile.writeText(root.toString(2))
    }

    private fun loadBranches() {
        branches.clear(); activeBranch = null; checkpoint = null
        if (branchesFile.exists()) {
            try {
                val root = JSONObject(branchesFile.readText())
                activeBranch = root.optString("activeBranch", "").ifBlank { null }
                root.getJSONObject("branches").keys().forEach { key ->
                    val arr = root.getJSONObject("branches").getJSONArray(key)
                    val hist = mutableListOf<Map<String, String>>()
                    arr.forEach { obj -> obj as JSONObject; hist.add(mapOf("role" to obj.getString("role"), "content" to obj.getString("content"))) }
                    branches[key] = BranchState(key, hist)
                }
                if (root.has("checkpoint")) {
                    checkpoint = root.getJSONArray("checkpoint").map { obj -> obj as JSONObject; mapOf("role" to obj.getString("role"), "content" to obj.getString("content")) }
                }
                if (activeBranch == null && branches.isNotEmpty()) activeBranch = branches.keys.first()
            } catch (_: Exception) {
                branches["main"] = BranchState("main", mutableListOf(mapOf("role" to "system", "content" to systemPrompt)))
                activeBranch = "main"
            }
        } else {
            branches["main"] = BranchState("main", mutableListOf(mapOf("role" to "system", "content" to systemPrompt)))
            activeBranch = "main"
            saveBranches()
        }
    }

    // ----- Отправка сообщения (автосохранение после ответа) -----
    fun sendMessage(userMessage: String): String {
        val result = when (currentStrategy) {
            Strategy.SlidingWindow -> sendSlidingWindow(userMessage)
            Strategy.StickyFacts -> sendStickyFacts(userMessage)
            Strategy.Branching -> sendBranching(userMessage)
        }
        saveStrategyData(currentStrategy)   // сохраняем сразу после ответа
        return result
    }

    // ----- Sliding Window -----
    private fun sendSlidingWindow(userMessage: String): String {
        slidingHistory.add(mapOf("role" to "user", "content" to userMessage))
        val (answer, _) = callApi(slidingHistory.takeLast(slidingWindowSize))
        slidingHistory.add(mapOf("role" to "assistant", "content" to answer))
        return answer
    }

    // ----- Sticky Facts -----
    private fun sendStickyFacts(userMessage: String): String {
        factsHistory.add(mapOf("role" to "user", "content" to userMessage))
        val messages = buildFactsMessages()
        val (answer, _) = callApi(messages)
        factsHistory.add(mapOf("role" to "assistant", "content" to answer))
        updateFacts()
        return answer
    }

    private fun buildFactsMessages(): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        factsHistory.firstOrNull { it["role"] == "system" }?.let { result.add(it) }
        if (facts.isNotEmpty()) {
            result.add(mapOf("role" to "system", "content" to "Current facts:\n${facts.joinToString("\n") { "- $it" }}"))
        }
        result.addAll(factsHistory.filter { it["role"] != "system" }.takeLast(factsWindowSize))
        return result
    }

    private fun updateFacts() {
        val lastMessages = factsHistory.filter { it["role"] != "system" }.takeLast(6)
        if (lastMessages.isEmpty()) return
        val prompt = """
            Extract important facts from the following conversation fragment.
            Return each fact on a new line in the format "- [key]: [value]". If nothing new, return "none".
            
            ${lastMessages.joinToString("\n") { "${it["role"]}: ${it["content"]}" }}
        """.trimIndent()
        val (extracted, _) = callApi(listOf(mapOf("role" to "user", "content" to prompt)), maxTokens = 150)
        if (!extracted.contains("none", true) && extracted.isNotBlank()) {
            val newFacts = extracted.split("\n").map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }
            facts.addAll(newFacts)
            if (facts.size > maxFacts) facts = facts.takeLast(maxFacts).toMutableList()
        }
    }

    // ----- Branching -----
    private fun sendBranching(userMessage: String): String {
        val branch = branches[activeBranch] ?: error("No active branch")
        branch.history.add(mapOf("role" to "user", "content" to userMessage))
        val (answer, _) = callApi(branch.history)
        branch.history.add(mapOf("role" to "assistant", "content" to answer))
        return answer
    }

    fun createCheckpoint() {
        if (currentStrategy != Strategy.Branching) { println("Только в Branching."); return }
        checkpoint = branches[activeBranch]?.history?.toList()
        println("Checkpoint сохранён.")
    }

    fun createBranch(name: String) {
        if (currentStrategy != Strategy.Branching) { println("Только в Branching."); return }
        if (checkpoint == null) { println("Сначала checkpoint."); return }
        branches[name] = BranchState(name, checkpoint!!.map { it.toMutableMap() }.toMutableList())
        activeBranch = name
        println("Ветка '$name' создана.")
    }

    fun switchBranch(name: String) {
        if (currentStrategy != Strategy.Branching) { println("Только в Branching."); return }
        if (branches.containsKey(name)) { activeBranch = name; println("Перешли в '$name'.") }
        else println("Ветка '$name' не найдена.")
    }

    fun listBranches() {
        if (currentStrategy != Strategy.Branching) { println("Только в Branching."); return }
        branches.forEach { (name, state) -> println("${if (name == activeBranch) "*" else " "} $name (${state.history.size} сообщ.)") }
    }

    // ----- Вызов API -----
    private fun callApi(messages: List<Map<String, String>>, maxTokens: Int = this.maxTokens, temperature: Double = 0.0): Pair<String, JSONObject?> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply { messages.forEach { put(JSONObject(it)) } })
            put("temperature", temperature)
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

    fun reset() {
        when (currentStrategy) {
            Strategy.SlidingWindow -> { slidingHistory.clear(); slidingHistory.add(mapOf("role" to "system", "content" to systemPrompt)); saveSlidingWindow() }
            Strategy.StickyFacts -> { factsHistory.clear(); facts.clear(); factsHistory.add(mapOf("role" to "system", "content" to systemPrompt)); saveStickyFacts() }
            Strategy.Branching -> {
                branches.clear(); activeBranch = null; checkpoint = null
                branches["main"] = BranchState("main", mutableListOf(mapOf("role" to "system", "content" to systemPrompt)))
                activeBranch = "main"; saveBranches()
            }
        }
        println("Стратегия ${strategyDisplayName()} сброшена.")
    }

    fun printHistory() {
        val h = when (currentStrategy) {
            Strategy.SlidingWindow -> slidingHistory
            Strategy.StickyFacts -> factsHistory
            Strategy.Branching -> branches[activeBranch]?.history ?: emptyList()
        }
        h.forEach { println("${it["role"]}: ${it["content"]}") }
    }

    fun printFacts() {
        if (currentStrategy != Strategy.StickyFacts) println("Только в Sticky Facts.")
        else if (facts.isEmpty()) println("Фактов нет.")
        else facts.forEach { println("- $it") }
    }

    fun getCurrentStrategy() = currentStrategy
}

// ----- CLI -----
fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY не задан")
    val agent = Agent(apiKey, systemPrompt = "Ты — ассистент для сбора ТЗ. Задавай уточняющие вопросы.")
    val scanner = Scanner(System.`in`, "UTF-8")

    fun printHelp() {
        println("=".repeat(50))
        println("Текущая стратегия: ${agent.strategyDisplayName()}")
        println("─".repeat(50))
        println("Общие команды:")
        println("  help / ?                – эта справка")
        println("  strategy <имя>          – переключить стратегию (sliding, facts, branching)")
        println("  history                 – показать историю текущей стратегии")
        println("  reset                   – сбросить историю текущей стратегии")
        println("  exit                    – выход")

        when (agent.getCurrentStrategy()) {
            Strategy.SlidingWindow -> {
                println("─".repeat(50))
                println("Команды Sliding Window:")
                println("  window <N>              – размер окна (по умолчанию ${agent.slidingWindowSize})")
            }
            Strategy.StickyFacts -> {
                println("─".repeat(50))
                println("Команды Sticky Facts:")
                println("  facts                   – показать извлечённые факты")
                println("  window <N>              – размер окна (по умолчанию ${agent.factsWindowSize})")
            }
            Strategy.Branching -> {
                println("─".repeat(50))
                println("Команды Branching:")
                println("  checkpoint              – сохранить точку ветвления")
                println("  branch <имя>            – создать новую ветку от checkpoint")
                println("  switch <имя>            – переключиться на другую ветку")
                println("  branches                – показать список веток")
            }
        }
        println("─".repeat(50))
        println("Приглашение: [${agent.strategyDisplayName()}] Вы: (введите сообщение)")
        println("=".repeat(50))
    }

    printHelp() // приветствие при запуске

    while (true) {
        val prompt = when (agent.getCurrentStrategy()) {
            Strategy.SlidingWindow -> "[Sliding]"
            Strategy.StickyFacts -> "[Sticky]"
            Strategy.Branching -> "[Branching]"
        }
        print("$prompt Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) || input.equals("?") -> printHelp()
            input.startsWith("strategy ", true) -> {
                val newStrategy = when (input.removePrefix("strategy ").trim().lowercase()) {
                    "sliding" -> Strategy.SlidingWindow
                    "facts" -> Strategy.StickyFacts
                    "branching" -> Strategy.Branching
                    else -> { println("Неизвестная стратегия."); continue }
                }
                agent.setStrategy(newStrategy)
                println("Стратегия изменена на ${agent.strategyDisplayName()}.")
            }
            input.equals("checkpoint", true) -> agent.createCheckpoint()
            input.startsWith("branch ", true) -> agent.createBranch(input.removePrefix("branch ").trim())
            input.startsWith("switch ", true) -> agent.switchBranch(input.removePrefix("switch ").trim())
            input.equals("branches", true) -> agent.listBranches()
            input.equals("facts", true) -> agent.printFacts()
            input.equals("history", true) -> agent.printHistory()
            input.equals("reset", true) -> agent.reset()
            input.isEmpty() -> continue
            else -> {
                print("Агент: ")
                val start = System.currentTimeMillis()
                println(agent.sendMessage(input))
                println("(за ${"%.1f".format((System.currentTimeMillis() - start) / 1000.0)} с)")
            }
        }
    }
}
import org.json.JSONObject
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Scanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val scanner = Scanner(System.`in`, "UTF-8")
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val monitorActive = AtomicBoolean(false)
    var monitorThread: Thread? = null

    println("Команды:")
    println("  start <город> [секунд]  – начать сбор и автоматический показ обновлений")
    println("  report                  – показать все сохранённые данные")
    println("  stop                    – остановить сбор и мониторинг")
    println("  clear                   – очистить все данные погоды")
    println("  exit                    – выход")

    while (true) {
        print("> ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> {
                monitorActive.set(false)
                monitorThread?.interrupt()
                break
            }
            input.startsWith("start ", true) -> {
                val parts = input.removePrefix("start ").trim().split(" ")
                val city = parts[0]
                val interval = if (parts.size > 1) parts[1].toIntOrNull() ?: 60 else 60

                val args = JSONObject().apply {
                    put("city", city)
                    put("interval_seconds", interval)
                }
                val startResponse = sendRequest(httpClient, "start_periodic_weather", args)
                println(startResponse)

                monitorActive.set(false)
                monitorThread?.interrupt()
                monitorThread?.join(1000)

                val initialCount = getRecordCount(httpClient)

                monitorActive.set(true)
                monitorThread = thread {
                    var lastCount = initialCount
                    while (monitorActive.get()) {
                        Thread.sleep(interval * 1000L)
                        if (!monitorActive.get()) break
                        val currentCount = getRecordCount(httpClient)
                        if (currentCount > lastCount) {
                            val newRecords = getNewRecords(httpClient, lastCount)
                            newRecords?.forEach { record ->
                                val temp = record.optJSONObject("main")?.optDouble("temp")
                                val desc = record.optJSONArray("weather")?.optJSONObject(0)?.optString("description")
                                val time = record.optString("collected_at", "?")
                                println("[${time}] Погода в $city: $temp°C, $desc")
                            }
                            lastCount = currentCount
                        }
                    }
                }
            }
            input.equals("report", true) -> {
                val response = sendRequest(httpClient, "get_weather_report", null)
                println(response)
            }
            input.equals("stop", true) -> {
                monitorActive.set(false)
                monitorThread?.interrupt()
                val response = sendRequest(httpClient, "stop_periodic_weather", null)
                println(response)
            }
            input.equals("clear", true) -> {
                val response = sendRequest(httpClient, "clear_weather_data", null)
                println(response)
            }
            else -> println("Неизвестная команда")
        }
    }
}

fun sendRequest(client: HttpClient, toolName: String, arguments: JSONObject?): String {
    val params = JSONObject().apply {
        put("name", toolName)
        if (arguments != null) put("arguments", arguments)
    }
    val request = JSONObject().apply {
        put("jsonrpc", "2.0")
        put("id", "1")
        put("method", "tools/call")
        put("params", params)
    }
    val httpRequest = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8081/mcp"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(10))
        .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
        .build()
    val response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    return if (response.statusCode() == 200) {
        val json = JSONObject(response.body())
        json.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
    } else {
        "Ошибка: ${response.statusCode()} ${response.body()}"
    }
}

fun getRecordCount(client: HttpClient): Int {
    val report = sendRequest(client, "get_weather_report", null)
    return try {
        val json = JSONObject(report)
        val reportArray = json.optJSONArray("report")
        reportArray?.length() ?: 0
    } catch (e: Exception) {
        0
    }
}

fun getNewRecords(client: HttpClient, fromIndex: Int): List<JSONObject>? {
    val report = sendRequest(client, "get_weather_report", null)
    return try {
        val json = JSONObject(report)
        val reportArray = json.optJSONArray("report")
        if (reportArray != null) {
            (fromIndex until reportArray.length()).map { reportArray.getJSONObject(it) }
        } else null
    } catch (e: Exception) {
        null
    }
}
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val storageFile = File("weather_data.json")
private val savedData: MutableList<JSONObject> = if (storageFile.exists()) {
    try {
        val array = JSONArray(storageFile.readText())
        (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
    } catch (_: Exception) {
        mutableListOf()
    }
} else mutableListOf()

private fun saveData() {
    storageFile.writeText(JSONArray(savedData).toString(2))
}

private fun collectWeather(city: String, apiKey: String) {
    val url = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$apiKey&units=metric&lang=ru"
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build()
    try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val weather = JSONObject(response.body())
            weather.put("collected_at", LocalDateTime.now().toString())
            savedData.add(weather)
            saveData()
        } else {
            val error = JSONObject().apply {
                put("error", "HTTP ${response.statusCode()}")
                put("collected_at", LocalDateTime.now().toString())
            }
            savedData.add(error)
            saveData()
        }
    } catch (e: Exception) {
        val error = JSONObject().apply {
            put("error", e.message ?: "unknown")
            put("collected_at", LocalDateTime.now().toString())
        }
        savedData.add(error)
        saveData()
    }
}

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("OPENWEATHER_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Ошибка: переменная окружения OPENWEATHER_API_KEY не задана.")
        return
    }

    val scheduler = Executors.newScheduledThreadPool(1)
    var future: ScheduledFuture<*>? = null

    val server = HttpServer.create(InetSocketAddress(8081), 0)
    server.createContext("/mcp") { exchange ->
        if (exchange.requestMethod == "POST") {
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = JSONObject(body)
            val response = handleRequest(request, apiKey, scheduler) { task ->
                future?.cancel(false)
                future = task
            }
            val responseBytes = response.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        } else {
            exchange.sendResponseHeaders(405, -1)
        }
    }
    server.start()
    println("MCP-сервер периодического сбора погоды запущен на http://localhost:8081/mcp")
}

fun handleRequest(
    request: JSONObject,
    apiKey: String,
    scheduler: java.util.concurrent.ScheduledExecutorService,
    setFuture: (ScheduledFuture<*>?) -> Unit
): JSONObject {
    val method = request.getString("method")
    val id = request.opt("id")

    return when (method) {
        "initialize" -> {
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("capabilities", JSONObject())
                    put("serverInfo", JSONObject().apply {
                        put("name", "periodic-weather-server")
                        put("version", "1.0.0")
                    })
                })
            }
        }
        "tools/list" -> {
            val tools = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "start_periodic_weather")
                    put("description", "Запускает периодический сбор погоды с настраиваемым интервалом в секундах")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("city", JSONObject().apply {
                                put("type", "string")
                                put("description", "Название города, например 'Moscow'")
                            })
                            put("interval_seconds", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Интервал сбора в секундах (по умолчанию 60)")
                            })
                        })
                        put("required", JSONArray().apply { put("city") })
                    })
                })
                put(JSONObject().apply {
                    put("name", "get_weather_report")
                    put("description", "Возвращает все сохранённые записи погоды")
                })
                put(JSONObject().apply {
                    put("name", "stop_periodic_weather")
                    put("description", "Останавливает периодический сбор погоды")
                })
                put(JSONObject().apply {
                    put("name", "clear_weather_data")
                    put("description", "Очищает все сохранённые данные о погоде")
                })
            }
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply { put("tools", tools) })
            }
        }
        "tools/call" -> {
            val params = request.getJSONObject("params")
            val toolName = params.getString("name")
            val arguments = params.optJSONObject("arguments") ?: JSONObject()

            val result = when (toolName) {
                "start_periodic_weather" -> {
                    val city = arguments.getString("city")
                    val intervalSec = arguments.optInt("interval_seconds", 60)
                    val future = scheduler.scheduleAtFixedRate(
                        { collectWeather(city, apiKey) },
                        0,
                        intervalSec.toLong(),
                        TimeUnit.SECONDS
                    )
                    setFuture(future)
                    JSONObject().apply {
                        put("status", "started for $city every $intervalSec sec")
                    }
                }
                "get_weather_report" -> {
                    if (!storageFile.exists() || storageFile.readText().isBlank()) {
                        JSONObject().apply { put("report", "No data yet") }
                    } else {
                        JSONObject().apply {
                            put("report", JSONArray(storageFile.readText()))
                        }
                    }
                }
                "stop_periodic_weather" -> {
                    setFuture(null)
                    JSONObject().apply { put("status", "stopped") }
                }
                "clear_weather_data" -> {
                    savedData.clear()
                    saveData()
                    JSONObject().apply { put("status", "data cleared") }
                }
                else -> JSONObject().apply { put("error", "Unknown tool: $toolName") }
            }

            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", result.toString())
                        })
                    })
                })
            }
        }
        else -> {
            JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("error", JSONObject().apply {
                    put("code", -32601)
                    put("message", "Method not found")
                })
            }
        }
    }
}
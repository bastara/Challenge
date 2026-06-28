import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.Scanner

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("OPENWEATHER_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Ошибка: переменная окружения OPENWEATHER_API_KEY не задана.")
        return
    }

    val server = HttpServer.create(InetSocketAddress(8081), 0)
    server.createContext("/mcp") { exchange ->
        if (exchange.requestMethod == "POST") {
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = JSONObject(body)
            val response = handleWeatherRequest(request, apiKey)
            val responseBytes = response.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        } else {
            exchange.sendResponseHeaders(405, -1)
        }
    }
    server.start()
    println("Погодный MCP-сервер запущен на http://localhost:8081/mcp")
}

fun handleWeatherRequest(request: JSONObject, apiKey: String): JSONObject {
    val method = request.getString("method")
    val id = request.opt("id")

    return when (method) {
        "initialize" -> JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("result", JSONObject().apply {
                put("capabilities", JSONObject())
                put("serverInfo", JSONObject().apply {
                    put("name", "weather-server"); put("version", "1.0.0")
                })
            })
        }
        "tools/list" -> {
            val tools = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "get_weather")
                    put("description", "Возвращает текущую погоду для указанного города")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("city", JSONObject().apply {
                                put("type", "string"); put("description", "Название города на английском")
                            })
                        })
                        put("required", JSONArray().apply { put("city") })
                    })
                })
            }
            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", JSONObject().apply { put("tools", tools) })
            }
        }
        "tools/call" -> {
            val params = request.getJSONObject("params")
            val toolName = params.getString("name")
            val arguments = params.optJSONObject("arguments") ?: JSONObject()

            val result = when (toolName) {
                "get_weather" -> {
                    val city = arguments.optString("city", "")
                    if (city.isBlank()) {
                        JSONObject().apply { put("error", "Не указан город") }
                    } else {
                        getWeather(city, apiKey)
                    }
                }
                else -> JSONObject().apply { put("error", "Unknown tool: $toolName") }
            }

            JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", JSONObject().apply {
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text"); put("text", result.toString())
                        })
                    })
                })
            }
        }
        else -> JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id)
            put("error", JSONObject().apply {
                put("code", -32601); put("message", "Method not found")
            })
        }
    }
}

fun getWeather(city: String, apiKey: String): JSONObject {
    val urlString = "https://api.openweathermap.org/data/2.5/weather?q=$city&appid=$apiKey&units=metric&lang=ru"
    return try {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val scanner = Scanner(connection.inputStream, "UTF-8").useDelimiter("\\A")
            val responseBody = if (scanner.hasNext()) scanner.next() else ""
            JSONObject(responseBody)
        } else {
            JSONObject().apply { put("error", "HTTP $responseCode: ${connection.responseMessage}") }
        }
    } catch (e: Exception) {
        JSONObject().apply { put("error", "Ошибка соединения: ${e.message}") }
    }
}
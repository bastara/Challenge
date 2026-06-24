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
    val scanner = Scanner(System.`in`, "UTF-8")
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    while (true) {
        print("Введите город (или 'exit'): ")
        val city = scanner.nextLine().trim()
        if (city.equals("exit", true)) break

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", "1")
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", "get_weather")
                put("arguments", JSONObject().apply {
                    put("city", city)
                })
            })
        }

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8081/mcp"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val json = JSONObject(response.body())
            val result = json.optJSONObject("result")
            if (result != null) {
                val content = result.getJSONArray("content")
                val text = content.getJSONObject(0).getString("text")
                val weatherData = JSONObject(text)
                if (weatherData.has("error")) {
                    println("Ошибка: ${weatherData.getString("error")}")
                } else {
                    val main = weatherData.getJSONObject("main")
                    val weather = weatherData.getJSONArray("weather").getJSONObject(0)
                    println("Город: ${weatherData.getString("name")}")
                    println("Температура: ${main.getDouble("temp")}°C")
                    println("Ощущается: ${main.getDouble("feels_like")}°C")
                    println("Описание: ${weather.getString("description")}")
                    println("Влажность: ${main.getInt("humidity")}%")
                    println("Ветер: ${weatherData.getJSONObject("wind").getDouble("speed")} м/с")
                }
            }
        } else {
            println("Ошибка сервера: ${response.statusCode()} ${response.body()}")
        }
    }
}
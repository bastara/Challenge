import org.json.JSONObject
import org.json.JSONArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class DeepSeekApi(private val apiKey: String) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val chatUrl = "https://api.deepseek.com/v1/chat/completions"
    private val modelsUrl = "https://api.deepseek.com/v1/models"

    /** Быстрая проверка доступности DeepSeek API (2 сек) */
    fun ping(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build()
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(2, TimeUnit.SECONDS)
                .get()
                .statusCode() == 200
        } catch (e: Exception) {
            false
        }
    }

    fun chat(prompt: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system")
            .put("content", "You are a helpful developer assistant that knows the project."))
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = JSONObject()
        body.put("model", "deepseek-chat")
        body.put("messages", messages)
        body.put("temperature", 0.3)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(chatUrl))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(60))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Chat API error ${response.statusCode()}: ${response.body()}")
        }
        val jsonResponse = JSONObject(response.body())
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.getJSONObject(0).optJSONObject("message")
            return message?.optString("content", "No response") ?: "No response"
        }
        return "No response"
    }
}
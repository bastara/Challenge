import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class OllamaEmbedder(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text"
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Быстрая проверка доступности Ollama (2 сек) */
    fun ping(): Boolean {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/api/tags"))
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

    fun getEmbedding(text: String): DoubleArray {
        val body = JSONObject()
        body.put("model", model)
        body.put("prompt", text)

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/embeddings"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(60))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw RuntimeException("Ollama embedding error ${response.statusCode()}: ${response.body()}")
        }
        val json = JSONObject(response.body())
        val embeddingArray = json.getJSONArray("embedding")
        val embedding = DoubleArray(embeddingArray.length())
        for (i in 0 until embeddingArray.length()) {
            embedding[i] = embeddingArray.getDouble(i)
        }
        return embedding
    }
}
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.math.sqrt

class RagStore(private val embedder: OllamaEmbedder) {
    private val chunks = mutableListOf<String>()
    private val embeddings = mutableListOf<DoubleArray>()

    companion object {
        val CACHE_DIR = File(System.getProperty("user.home"), ".project-assistant-cache").also { it.mkdirs() }
        private const val MAX_CHUNK_LENGTH = 800  // максимальная длина чанка в символах

        fun getCacheFile(url: String): File {
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return File(CACHE_DIR, "$hash.json")
        }

        fun loadFromCache(url: String, embedder: OllamaEmbedder): RagStore? {
            val file = getCacheFile(url)
            if (!file.exists()) return null
            return try {
                val root = JSONObject(file.readText())
                val store = RagStore(embedder)
                val chunksArray = root.getJSONArray("chunks")
                val embArray = root.getJSONArray("embeddings")
                for (i in 0 until chunksArray.length()) {
                    store.chunks.add(chunksArray.getString(i))
                }
                for (i in 0 until embArray.length()) {
                    val arr = embArray.getJSONArray(i)
                    val emb = DoubleArray(arr.length())
                    for (j in 0 until arr.length()) {
                        emb[j] = arr.getDouble(j)
                    }
                    store.embeddings.add(emb)
                }
                println("Loaded ${store.chunks.size} chunks from cache for $url")
                store
            } catch (e: Exception) {
                System.err.println("Cache load failed: ${e.message}")
                file.delete()
                null
            }
        }
    }

    fun saveToCache(url: String) {
        val file = getCacheFile(url)
        val root = JSONObject()
        val chunksArray = JSONArray(chunks)
        val embArray = JSONArray()
        embeddings.forEach { emb ->
            val arr = JSONArray()
            emb.forEach { arr.put(it) }
            embArray.put(arr)
        }
        root.put("chunks", chunksArray)
        root.put("embeddings", embArray)
        file.writeText(root.toString())
        println("Saved ${chunks.size} chunks to cache for $url")
    }

    fun indexDocuments(readmeText: String, docsFiles: Map<String, String>) {
        chunks.clear()
        embeddings.clear()
        // Обрабатываем README
        if (readmeText.isNotBlank()) {
            addTextWithSplit(readmeText)
        }
        // Обрабатываем файлы из docs/
        for ((fileName, content) in docsFiles) {
            val paragraphs = content.split("\n\n").filter { it.isNotBlank() }
            for (p in paragraphs) {
                val prefixed = "File: $fileName\n\n$p"
                addTextWithSplit(prefixed)
            }
        }
    }

    private fun addTextWithSplit(text: String) {
        if (text.length <= MAX_CHUNK_LENGTH) {
            addChunk(text)
        } else {
            // Разбиваем длинный текст на части с перекрытием в 100 символов
            var start = 0
            while (start < text.length) {
                val end = minOf(start + MAX_CHUNK_LENGTH, text.length)
                addChunk(text.substring(start, end))
                start += MAX_CHUNK_LENGTH - 100  // overlap
            }
        }
    }

    private fun addChunk(text: String) {
        try {
            val emb = embedder.getEmbedding(text)
            chunks.add(text)
            embeddings.add(emb)
        } catch (e: Exception) {
            System.err.println("Skipping chunk: ${e.message}")
        }
    }

    fun queryRelevant(query: String, topK: Int = 3): List<String> {
        if (chunks.isEmpty()) return emptyList()
        val queryEmb = embedder.getEmbedding(query)
        val scores = embeddings.map { emb -> cosineSimilarity(queryEmb, emb) }
        val indexedScores = scores.withIndex().sortedByDescending { it.value }.take(topK)
        return indexedScores.map { chunks[it.index] }
    }

    private fun cosineSimilarity(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        return dot / (sqrt(normA) * sqrt(normB))
    }
}
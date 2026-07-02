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
import kotlin.math.sqrt

private const val EMBEDDING_DIM = 768   // nomic-embed-text

fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("DEEPSEEK_API_KEY")
    if (apiKey.isNullOrBlank()) {
        println("Ошибка: переменная окружения DEEPSEEK_API_KEY не задана.")
        return
    }

    val server = HttpServer.create(InetSocketAddress(8084), 0)
    server.createContext("/mcp") { exchange ->
        if (exchange.requestMethod == "POST") {
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val request = JSONObject(body)
            val response = handleIndexerRequest(request, apiKey)
            val responseBytes = response.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        } else {
            exchange.sendResponseHeaders(405, -1)
        }
    }
    server.start()
    println("Сервер индексации (Ollama + RAG + фильтрация + rewrite) запущен на http://localhost:8084/mcp")
}

fun handleIndexerRequest(request: JSONObject, apiKey: String): JSONObject {
    val method = request.getString("method")
    val id = request.opt("id")

    return try {
        when (method) {
            "initialize" -> JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", id)
                put("result", JSONObject().apply {
                    put("capabilities", JSONObject())
                    put("serverInfo", JSONObject().apply {
                        put("name", "indexer-server"); put("version", "3.0.0")
                    })
                })
            }
            "tools/list" -> {
                val tools = JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "index_folder")
                        put("description", "Индексирует текстовые файлы из указанной папки, создаёт индексы для двух стратегий чанкинга")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("path", JSONObject().apply {
                                    put("type", "string"); put("description", "Путь к папке с .txt файлами")
                                })
                                put("max_tokens", JSONObject().apply {
                                    put("type", "integer"); put("description", "Макс. число токенов в чанке (по умолчанию 500)")
                                })
                                put("overlap", JSONObject().apply {
                                    put("type", "integer"); put("description", "Перекрытие в токенах для фиксированного разбиения (по умолчанию 50)")
                                })
                            })
                            put("required", JSONArray().apply { put("path") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "search")
                        put("description", "Ищет чанки, релевантные запросу, в указанном индексе")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("query", JSONObject().apply {
                                    put("type", "string"); put("description", "Поисковый запрос")
                                })
                                put("strategy", JSONObject().apply {
                                    put("type", "string"); put("description", "fixed или structural")
                                })
                                put("top_k", JSONObject().apply {
                                    put("type", "integer"); put("description", "Кол-во результатов (по умолчанию 5)")
                                })
                            })
                            put("required", JSONArray().apply { put("query"); put("strategy") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "compare_strategies")
                        put("description", "Сравнивает два индекса (fixed и structural) по числу чанков, размеру и т.д.")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("path", JSONObject().apply {
                                    put("type", "string"); put("description", "Путь к папке с .txt файлами (той же, что индексировалась)")
                                })
                            })
                            put("required", JSONArray().apply { put("path") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "ask_llm")
                        put("description", "Задаёт вопрос LLM без дополнительного контекста (чистый DeepSeek)")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("question", JSONObject().apply {
                                    put("type", "string"); put("description", "Вопрос пользователя")
                                })
                            })
                            put("required", JSONArray().apply { put("question") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "rag_query")
                        put("description", "Ищет релевантные чанки, фильтрует, опционально переписывает запрос и передаёт контекст LLM")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("question", JSONObject().apply {
                                    put("type", "string"); put("description", "Вопрос пользователя")
                                })
                                put("strategy", JSONObject().apply {
                                    put("type", "string"); put("description", "fixed или structural (по умолчанию structural)")
                                })
                                put("top_k", JSONObject().apply {
                                    put("type", "integer"); put("description", "Кол-во чанков для контекста (по умолчанию 5)")
                                })
                                put("min_score", JSONObject().apply {
                                    put("type", "number"); put("description", "Порог косинусного сходства (0.0 = без фильтра)")
                                })
                                put("use_rewrite", JSONObject().apply {
                                    put("type", "boolean"); put("description", "Переформулировать ли запрос перед поиском")
                                })
                            })
                            put("required", JSONArray().apply { put("question") })
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
                    "index_folder" -> {
                        val path = arguments.getString("path")
                        val maxTokens = arguments.optInt("max_tokens", 500)
                        val overlap = arguments.optInt("overlap", 50)
                        indexFolder(path, maxTokens, overlap)
                    }
                    "search" -> {
                        val query = arguments.getString("query")
                        val strategy = arguments.getString("strategy")
                        val topK = arguments.optInt("top_k", 5)
                        searchIndex(query, strategy, topK)
                    }
                    "compare_strategies" -> {
                        val path = arguments.getString("path")
                        compareStrategies(path)
                    }
                    "ask_llm" -> {
                        val question = arguments.getString("question")
                        callLLM(question, apiKey)
                    }
                    "rag_query" -> {
                        val originalQuestion = arguments.getString("question")
                        val strategy = arguments.optString("strategy", "structural")
                        val topK = arguments.optInt("top_k", 5)
                        val minScore = arguments.optDouble("min_score", 0.0)
                        val useRewrite = arguments.optBoolean("use_rewrite", false)

                        val questionToUse = if (useRewrite) {
                            rewriteQuery(originalQuestion, apiKey)
                        } else {
                            originalQuestion
                        }

                        val searchResult = searchIndex(questionToUse, strategy, topK)
                        val resultsArray = searchResult.optJSONArray("results")
                        val context = StringBuilder()
                        var filteredCount = 0
                        if (resultsArray != null) {
                            for (i in 0 until resultsArray.length()) {
                                val item = resultsArray.getJSONObject(i)
                                val score = item.optDouble("score", 0.0)
                                if (minScore > 0.0 && score < minScore) continue
                                filteredCount++
                                val text = item.optString("text", "")
                                context.append(text).append("\n\n")
                            }
                        }

                        val prompt = """
                            Ты — ассистент, который использует базу знаний об отелях Причерноморья.
                            Ответь на вопрос пользователя, опираясь **только** на приведённый ниже контекст.
                            Если ответа нет в контексте, скажи: "Недостаточно информации".
                            Контекст:
                            $context
                            
                            Вопрос: $originalQuestion
                            Ответ:
                        """.trimIndent()
                        val llmResponse = callLLM(prompt, apiKey)
                        llmResponse.put("original_top_k", resultsArray?.length() ?: 0)
                        llmResponse.put("filtered_count", filteredCount)
                        if (useRewrite) {
                            llmResponse.put("rewritten_query", questionToUse)
                        }
                        llmResponse
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
    } catch (e: Exception) {
        e.printStackTrace()
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", JSONObject().apply {
                put("code", -32603)
                put("message", e.message ?: "Internal error")
            })
        }
    }
}

// ---------- Chunking ----------

fun chunkFixed(text: String, maxTokens: Int = 500, overlap: Int = 50): List<String> {
    val maxChars = 1000
    val overlapChars = 200
    val chunks = mutableListOf<String>()
    var start = 0
    while (start < text.length) {
        val end = minOf(start + maxChars, text.length)
        chunks.add(text.substring(start, end))
        start += (maxChars - overlapChars)
    }
    return chunks
}

fun chunkStructural(text: String, fileName: String): List<Pair<String, String>> {
    val sections = mutableListOf<Pair<String, String>>()
    val lines = text.lines()
    var currentTitle = fileName
    val currentContent = StringBuilder()
    for (line in lines) {
        if (line.trimStart().startsWith("#")) {
            if (currentContent.isNotBlank()) {
                sections.add(currentTitle to currentContent.toString().trim())
                currentContent.clear()
            }
            currentTitle = line.trimStart().removePrefix("#").trim()
        } else {
            currentContent.appendLine(line)
        }
    }
    if (currentContent.isNotBlank()) {
        sections.add(currentTitle to currentContent.toString().trim())
    }
    if (sections.isEmpty()) {
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        var temp = ""
        for (p in paragraphs) {
            if (temp.length + p.length > 2000 && temp.isNotBlank()) {
                sections.add(fileName to temp.trim())
                temp = p
            } else {
                temp += if (temp.isEmpty()) p else "\n\n$p"
            }
        }
        if (temp.isNotBlank()) sections.add(fileName to temp.trim())
    }
    return sections
}

// ---------- Embeddings (Ollama) ----------

fun getEmbedding(text: String): List<Double> {
    val safeText = if (text.length > 800) text.take(800) else text
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build()
    val body = JSONObject().apply {
        put("model", "nomic-embed-text")
        put("prompt", safeText)
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:11434/api/embeddings"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(60))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        throw RuntimeException("Ollama API error ${response.statusCode()}: ${response.body()}")
    }
    val json = JSONObject(response.body())
    val arr = json.getJSONArray("embedding")
    return (0 until arr.length()).map { arr.getDouble(it) }
}

// ---------- Cosine similarity ----------

fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
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

// ---------- Index I/O ----------

data class ChunkEntry(
    val chunk_id: String,
    val text: String,
    val embedding: List<Double>,
    val metadata: Map<String, String>
)

fun saveIndex(strategy: String, chunks: List<ChunkEntry>) {
    val array = JSONArray()
    for (c in chunks) {
        array.put(JSONObject().apply {
            put("chunk_id", c.chunk_id)
            put("text", c.text)
            put("embedding", JSONArray(c.embedding))
            put("metadata", JSONObject(c.metadata))
        })
    }
    File("index_${strategy}.json").writeText(array.toString(2))
}

fun loadIndex(strategy: String): List<ChunkEntry> {
    val file = File("index_${strategy}.json")
    if (!file.exists()) return emptyList()
    val array = JSONArray(file.readText())
    val chunks = mutableListOf<ChunkEntry>()
    for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val embArr = obj.getJSONArray("embedding")
        val emb = (0 until embArr.length()).map { embArr.getDouble(it) }
        val meta = mutableMapOf<String, String>()
        val metaObj = obj.getJSONObject("metadata")
        for (key in metaObj.keySet()) {
            meta[key] = metaObj.getString(key)
        }
        chunks.add(ChunkEntry(
            chunk_id = obj.getString("chunk_id"),
            text = obj.getString("text"),
            embedding = emb,
            metadata = meta
        ))
    }
    return chunks
}

// ---------- Инструменты ----------

fun indexFolder(path: String, maxTokens: Int, overlap: Int): JSONObject {
    val dir = File(path)
    if (!dir.isDirectory) return JSONObject().apply { put("error", "Не является папкой: $path") }
    val txtFiles = dir.listFiles { f -> f.extension == "txt" } ?: emptyArray()
    if (txtFiles.isEmpty()) return JSONObject().apply { put("error", "Нет .txt файлов в папке") }

    val allText = txtFiles.joinToString("\n\n") { it.readText() }

    val fixedChunks = chunkFixed(allText, maxTokens, overlap)
    val fixedEntries = mutableListOf<ChunkEntry>()
    for ((i, chunk) in fixedChunks.withIndex()) {
        val emb = getEmbedding(chunk)
        fixedEntries.add(ChunkEntry(
            chunk_id = "fixed_$i",
            text = chunk,
            embedding = emb,
            metadata = mapOf("source" to path, "strategy" to "fixed", "index" to i.toString())
        ))
    }
    saveIndex("fixed", fixedEntries)

    val structuralSections = mutableListOf<Pair<String, String>>()
    for (file in txtFiles) {
        structuralSections.addAll(chunkStructural(file.readText(), file.name))
    }
    val structuralEntries = mutableListOf<ChunkEntry>()
    for (idx in structuralSections.indices) {
        val (title, content) = structuralSections[idx]
        val emb = getEmbedding(content)
        structuralEntries.add(ChunkEntry(
            chunk_id = "struct_$idx",
            text = content,
            embedding = emb,
            metadata = mapOf("source" to path, "strategy" to "structural", "section" to title, "index" to idx.toString())
        ))
    }
    saveIndex("structural", structuralEntries)

    return JSONObject().apply {
        put("status", "indexed")
        put("fixed_chunks", fixedEntries.size)
        put("structural_chunks", structuralEntries.size)
    }
}

fun searchIndex(query: String, strategy: String, topK: Int): JSONObject {
    val chunks = loadIndex(strategy)
    if (chunks.isEmpty()) return JSONObject().apply { put("error", "Индекс '$strategy' не найден") }
    val queryEmb = getEmbedding(query)
    val scored = chunks.map { it to cosineSimilarity(queryEmb, it.embedding) }
        .sortedByDescending { it.second }
        .take(topK)
    val results = JSONArray()
    for ((chunk, score) in scored) {
        results.put(JSONObject().apply {
            put("chunk_id", chunk.chunk_id)
            put("text", chunk.text.take(300) + "...")
            put("score", score)
            put("metadata", JSONObject(chunk.metadata))
        })
    }
    return JSONObject().apply { put("results", results) }
}

fun compareStrategies(path: String): JSONObject {
    val fixed = loadIndex("fixed")
    val structural = loadIndex("structural")
    if (fixed.isEmpty() && structural.isEmpty()) return JSONObject().apply { put("error", "Индексы не найдены. Сначала выполните index_folder") }

    val fixedSizes = fixed.map { it.text.split("\\s+".toRegex()).size }
    val structSizes = structural.map { it.text.split("\\s+".toRegex()).size }

    return JSONObject().apply {
        put("fixed_chunks", fixed.size)
        put("structural_chunks", structural.size)
        put("fixed_avg_words", if (fixedSizes.isNotEmpty()) fixedSizes.average() else 0)
        put("structural_avg_words", if (structSizes.isNotEmpty()) structSizes.average() else 0)
        put("fixed_min_words", fixedSizes.minOrNull() ?: 0)
        put("fixed_max_words", fixedSizes.maxOrNull() ?: 0)
        put("structural_min_words", structSizes.minOrNull() ?: 0)
        put("structural_max_words", structSizes.maxOrNull() ?: 0)
        put("fixed_sample_boundaries", fixed.take(3).map { it.text.take(80) + "..." })
        put("structural_sample_boundaries", structural.take(3).map { "${it.metadata["section"]}: ${it.text.take(60)}..." })
    }
}

// ---------- Query Rewrite ----------

fun rewriteQuery(original: String, apiKey: String): String {
    val prompt = """
        Перепиши следующий вопрос так, чтобы он стал более эффективным для поиска по документам, сохранив исходный смысл.
        Верни только переписанный вопрос, без кавычек и дополнительных комментариев.
        Вопрос: $original
    """.trimIndent()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val body = JSONObject().apply {
        put("model", "deepseek-chat")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
        put("temperature", 0.0)
        put("max_tokens", 200)
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() == 200) {
        val json = JSONObject(response.body())
        val rewritten = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        println("Переформулированный запрос: $rewritten")
        return rewritten
    } else {
        println("Ошибка rewrite API: ${response.statusCode()}, используется оригинальный запрос")
        return original
    }
}

// ---------- LLM (DeepSeek Chat) ----------

fun callLLM(prompt: String, apiKey: String): JSONObject {
    val body = JSONObject().apply {
        put("model", "deepseek-chat")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
        put("temperature", 0.0)
        put("max_tokens", 500)
    }.toString()
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        return JSONObject().apply { put("error", "LLM API error ${response.statusCode()}") }
    }
    val json = JSONObject(response.body())
    val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    return JSONObject().apply { put("answer", answer) }
}
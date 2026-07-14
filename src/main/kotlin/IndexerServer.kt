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

// ---------- Состояние задачи (память) ----------
data class TaskMemory(
    var goal: String = "",
    val constraints: MutableList<String> = mutableListOf(),
    val terms: MutableList<String> = mutableListOf(),
    val notes: MutableList<String> = mutableListOf()
) {
    fun toPrompt(): String {
        if (goal.isEmpty() && constraints.isEmpty() && terms.isEmpty() && notes.isEmpty()) return ""
        val sb = StringBuilder("Текущая память задачи:\n")
        if (goal.isNotEmpty()) sb.appendLine("- Цель: $goal")
        if (constraints.isNotEmpty()) sb.appendLine("- Ограничения: ${constraints.joinToString(", ")}")
        if (terms.isNotEmpty()) sb.appendLine("- Термины/факты: ${terms.joinToString(", ")}")
        if (notes.isNotEmpty()) sb.appendLine("- Заметки: ${notes.joinToString(", ")}")
        return sb.toString()
    }
}

// ---------- Основной сервер ----------
private const val EMBEDDING_DIM = 768

// Хранилище сессий чата (в памяти)
private val chatSessions = mutableMapOf<String, ChatSession>()

data class ChatSession(
    val history: MutableList<Map<String, String>> = mutableListOf(),
    val taskMemory: TaskMemory = TaskMemory()
)

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
    println("Сервер индексации (RAG + локальная LLM + оптимизация) запущен на http://localhost:8084/mcp")
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
                        put("name", "chat-rag-server"); put("version", "6.0.0")
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
                        put("description", "Ищет релевантные чанки, фильтрует, опционально переписывает запрос и передаёт контекст LLM с требованием цитирования и источников")
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
                    put(JSONObject().apply {
                        put("name", "start_chat")
                        put("description", "Начинает новую сессию чата с RAG")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("session_id", JSONObject().apply {
                                    put("type", "string"); put("description", "Идентификатор сессии (любая строка)")
                                })
                                put("goal", JSONObject().apply {
                                    put("type", "string"); put("description", "Цель диалога (опционально)")
                                })
                            })
                            put("required", JSONArray().apply { put("session_id") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "chat_with_rag")
                        put("description", "Отправляет сообщение в RAG-чат с историей и памятью задачи")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("session_id", JSONObject().apply {
                                    put("type", "string"); put("description", "Идентификатор сессии")
                                })
                                put("message", JSONObject().apply {
                                    put("type", "string"); put("description", "Сообщение пользователя")
                                })
                            })
                            put("required", JSONArray().apply { put("session_id"); put("message") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "get_task_state")
                        put("description", "Возвращает текущее состояние памяти задачи для сессии")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("session_id", JSONObject().apply {
                                    put("type", "string"); put("description", "Идентификатор сессии")
                                })
                            })
                            put("required", JSONArray().apply { put("session_id") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "ollama_chat")
                        put("description", "Отправляет запрос в локальную модель через Ollama API")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("type", "string"); put("description", "Сообщение пользователя")
                                })
                                put("model", JSONObject().apply {
                                    put("type", "string"); put("description", "Модель Ollama (по умолчанию deepseek-r1:7b)")
                                })
                            })
                            put("required", JSONArray().apply { put("message") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "local_rag_query")
                        put("description", "Ищет чанки локально и генерирует ответ через локальную LLM (Ollama)")
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
                                    put("type", "number"); put("description", "Порог косинусного сходства")
                                })
                            })
                            put("required", JSONArray().apply { put("question") })
                        })
                    })
                    put(JSONObject().apply {
                        put("name", "optimized_ollama_chat")
                        put("description", "Отправляет запрос в оптимизированную локальную модель через Ollama (temperature=0.1, max_tokens=300, спец. промпт)")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("message", JSONObject().apply {
                                    put("type", "string"); put("description", "Сообщение пользователя")
                                })
                            })
                            put("required", JSONArray().apply { put("message") })
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

                        val questionToUse = if (useRewrite) rewriteQuery(originalQuestion, apiKey) else originalQuestion

                        val searchResult = searchIndex(questionToUse, strategy, topK)
                        val resultsArray = searchResult.optJSONArray("results")
                        val contextBuilder = StringBuilder()
                        val sources = JSONArray()
                        var filteredCount = 0
                        var hasRelevant = false
                        if (resultsArray != null) {
                            for (i in 0 until resultsArray.length()) {
                                val item = resultsArray.getJSONObject(i)
                                val score = item.optDouble("score", 0.0)
                                if (minScore > 0.0 && score < minScore) continue
                                filteredCount++
                                val text = item.optString("text", "")
                                val metadata = item.optJSONObject("metadata")
                                val section = metadata?.optString("section", "?") ?: "?"
                                val chunkId = item.optString("chunk_id", "?")
                                contextBuilder.append("Source [${filteredCount}] (section: $section, chunk: $chunkId, score: ${"%.3f".format(score)}):\n$text\n\n")
                                sources.put(JSONObject().apply {
                                    put("section", section)
                                    put("chunk_id", chunkId)
                                    put("score", score)
                                    put("text_preview", text.take(200))
                                })
                                hasRelevant = true
                            }
                        }

                        val answerJson: JSONObject
                        if (!hasRelevant) {
                            answerJson = JSONObject().apply {
                                put("answer", "Не знаю. Недостаточно релевантного контекста для ответа.")
                                put("sources", JSONArray())
                                put("status", "no_context")
                            }
                        } else {
                            val prompt = """
                                Ты — ассистент, который использует базу знаний об отелях Причерноморья.
                                Ответь на вопрос пользователя, опираясь **только** на приведённый ниже контекст.
                                Твой ответ **обязательно** должен содержать:
                                1. Краткий ответ (2-3 предложения).
                                2. Цитаты из контекста (фрагменты текста с указанием номера источника в формате [1]).
                                3. Список использованных источников в конце (секция + чанк).
                                Если контекст не содержит ответа, скажи: "Не знаю. В документах недостаточно информации".
                                Контекст:
                                $contextBuilder
                                
                                Вопрос: $originalQuestion
                                Ответ:
                            """.trimIndent()
                            val llmResponse = callLLM(prompt, apiKey)
                            llmResponse.put("sources", sources)
                            llmResponse.put("status", "ok")
                            answerJson = llmResponse
                        }
                        answerJson.put("original_top_k", resultsArray?.length() ?: 0)
                        answerJson.put("filtered_count", filteredCount)
                        if (useRewrite) answerJson.put("rewritten_query", questionToUse)
                        answerJson
                    }
                    "start_chat" -> {
                        val sessionId = arguments.getString("session_id")
                        val goal = arguments.optString("goal", "")
                        val session = ChatSession()
                        if (goal.isNotEmpty()) session.taskMemory.goal = goal
                        chatSessions[sessionId] = session
                        JSONObject().apply {
                            put("status", "started")
                            put("session_id", sessionId)
                        }
                    }
                    "chat_with_rag" -> {
                        val sessionId = arguments.getString("session_id")
                        val message = arguments.getString("message")
                        val session = chatSessions.getOrPut(sessionId) { ChatSession() }

                        // Добавляем сообщение пользователя в историю
                        session.history.add(mapOf("role" to "user", "content" to message))

                        try {
                            // Формируем поисковый запрос: последние сообщения + память задачи
                            val searchQuery = buildString {
                                val recent = session.history.takeLast(4).joinToString(" ") { it["content"] ?: "" }
                                append(recent)
                                val taskPrompt = session.taskMemory.toPrompt()
                                if (taskPrompt.isNotEmpty()) append("\n$taskPrompt")
                            }

                            // Ищем чанки
                            val searchResult = searchIndex(searchQuery, "structural", 5)
                            val resultsArray = searchResult.optJSONArray("results")
                            val contextBuilder = StringBuilder()
                            val sources = JSONArray()
                            if (resultsArray != null) {
                                for (i in 0 until resultsArray.length()) {
                                    val item = resultsArray.getJSONObject(i)
                                    val text = item.optString("text", "")
                                    val metadata = item.optJSONObject("metadata")
                                    val section = metadata?.optString("section", "?") ?: "?"
                                    val chunkId = item.optString("chunk_id", "?")
                                    val score = item.optDouble("score", 0.0)
                                    contextBuilder.append("Source [${i + 1}] (section: $section, chunk: $chunkId, score: ${"%.3f".format(score)}):\n$text\n\n")
                                    sources.put(JSONObject().apply {
                                        put("section", section)
                                        put("chunk_id", chunkId)
                                        put("score", score)
                                        put("text_preview", text.take(200))
                                    })
                                }
                            }

                            // Формируем промпт
                            val historyText = session.history.takeLast(6).joinToString("\n") { "${it["role"]}: ${it["content"]}" }
                            val taskPrompt = session.taskMemory.toPrompt()

                            val prompt = """
                                Ты — ассистент, использующий базу знаний об отелях Причерноморья.
                                У тебя есть история диалога и память задачи. Отвечай на сообщение пользователя, опираясь на контекст и историю.
                                
                                История диалога (последние сообщения):
                                $historyText
                                
                                $taskPrompt
                                
                                Контекст из документов:
                                $contextBuilder
                                
                                Твой ответ **обязательно** должен содержать:
                                1. Краткий ответ (2-3 предложения).
                                2. Цитаты из контекста с указанием номера источника в формате [1].
                                3. Список использованных источников (секция + чанк).
                                Если контекст не содержит ответа, скажи: "Не знаю. В документах недостаточно информации".
                                
                                Сообщение пользователя: $message
                                Ответ:
                            """.trimIndent()

                            val llmResponse = callLLM(prompt, apiKey)
                            val answer = llmResponse.optString("answer", "Ошибка генерации ответа")

                            // Добавляем ответ в историю
                            session.history.add(mapOf("role" to "assistant", "content" to answer))

                            // Автоматически обновляем память задачи
                            updateTaskMemory(session, apiKey)

                            JSONObject().apply {
                                put("answer", answer)
                                put("sources", sources)
                                put("session_id", sessionId)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            JSONObject().apply {
                                put("answer", "Ошибка при обработке запроса: ${e.message}")
                                put("sources", JSONArray())
                                put("session_id", sessionId)
                            }
                        }
                    }
                    "get_task_state" -> {
                        val sessionId = arguments.getString("session_id")
                        val session = chatSessions[sessionId]
                        if (session == null) {
                            JSONObject().apply { put("error", "Сессия не найдена") }
                        } else {
                            val mem = session.taskMemory
                            JSONObject().apply {
                                put("goal", mem.goal)
                                put("constraints", JSONArray(mem.constraints))
                                put("terms", JSONArray(mem.terms))
                                put("notes", JSONArray(mem.notes))
                            }
                        }
                    }
                    "ollama_chat" -> {
                        val message = arguments.getString("message")
                        val model = arguments.optString("model", "deepseek-r1:7b")
                        val response = callOllamaChat(model, message)
                        JSONObject().apply { put("answer", response) }
                    }
                    "local_rag_query" -> {
                        val question = arguments.getString("question")
                        val strategy = arguments.optString("strategy", "structural")
                        val topK = arguments.optInt("top_k", 5)
                        val minScore = arguments.optDouble("min_score", 0.0)

                        // 1. Поиск (выполняется локально через Ollama embeddings)
                        val searchResult = searchIndex(question, strategy, topK)
                        val resultsArray = searchResult.optJSONArray("results")
                        val contextBuilder = StringBuilder()
                        val sources = JSONArray()
                        var filteredCount = 0
                        if (resultsArray != null) {
                            for (i in 0 until resultsArray.length()) {
                                val item = resultsArray.getJSONObject(i)
                                val score = item.optDouble("score", 0.0)
                                if (minScore > 0.0 && score < minScore) continue
                                filteredCount++
                                val text = item.optString("text", "")
                                val metadata = item.optJSONObject("metadata")
                                val section = metadata?.optString("section", "?") ?: "?"
                                val chunkId = item.optString("chunk_id", "?")
                                contextBuilder.append("Source [${filteredCount}] (section: $section, chunk: $chunkId, score: ${"%.3f".format(score)}):\n$text\n\n")
                                sources.put(JSONObject().apply {
                                    put("section", section)
                                    put("chunk_id", chunkId)
                                    put("score", score)
                                    put("text_preview", text.take(200))
                                })
                            }
                        }

                        if (filteredCount == 0) {
                            JSONObject().apply {
                                put("answer", "Не знаю. Недостаточно релевантного контекста.")
                                put("sources", JSONArray())
                            }
                        } else {
                            // 2. Генерация локальной моделью
                            val prompt = """
                                Ты — ассистент, который использует базу знаний об отелях Причерноморья.
                                Ответь на вопрос пользователя, опираясь **только** на приведённый ниже контекст.
                                Твой ответ **обязательно** должен содержать:
                                1. Краткий ответ (2-3 предложения).
                                2. Цитаты из контекста с указанием номера источника в формате [1].
                                3. Список использованных источников в конце (секция + чанк).
                                Контекст:
                                $contextBuilder
                                
                                Вопрос: $question
                                Ответ:
                            """.trimIndent()
                            val ollamaResponse = callOllamaChat("deepseek-r1:7b", prompt)
                            JSONObject().apply {
                                put("answer", ollamaResponse)
                                put("sources", sources)
                            }
                        }
                    }
                    "optimized_ollama_chat" -> {
                        val message = arguments.getString("message")
                        val response = callOllamaChatOptimized(message)
                        JSONObject().apply { put("answer", response) }
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
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(120)).build()
    val body = JSONObject().apply {
        put("model", "nomic-embed-text")
        put("prompt", safeText)
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://81.26.184.66:11434/api/embeddings"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(120))
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
        put("max_tokens", 600)
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

// ---------- Ollama Chat ----------

fun callOllamaChat(model: String, prompt: String): String {
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(600)).build()
    val body = JSONObject().apply {
        put("model", model)
        put("prompt", prompt)
        put("stream", false)
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://81.26.184.66:11434/api/generate"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(600))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        return "Ошибка Ollama: ${response.statusCode()}"
    }
    val json = JSONObject(response.body())
    return json.optString("response", "Пустой ответ")
}

// ---------- Optimized Ollama Chat ----------

fun callOllamaChatOptimized(prompt: String): String {
    val optimizedPrompt = """
        Ты — эксперт по отелям Причерноморья. Используй ТОЛЬКО информацию из контекста.
        Отвечай кратко (2-3 предложения), обязательно указывай источник.
        Вопрос: $prompt
        Ответ (с цитатами):
    """.trimIndent()

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(300)).build()
    val body = JSONObject().apply {
        put("model", "deepseek-r1:7b")
        put("prompt", optimizedPrompt)
        put("stream", false)
        put("options", JSONObject().apply {
            put("temperature", 0.1)
            put("max_tokens", 300)
        })
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://81.26.184.66:11434/api/generate"))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(300))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) {
        return "Ошибка оптимизированной модели: ${response.statusCode()}"
    }
    val json = JSONObject(response.body())
    return json.optString("response", "Пустой ответ")
}

// ---------- Обновление памяти задачи ----------

fun updateTaskMemory(session: ChatSession, apiKey: String) {
    val recentHistory = session.history.takeLast(8).joinToString("\n") { "${it["role"]}: ${it["content"]}" }
    val prompt = """
        На основе последних сообщений диалога обнови память задачи. Выдели новые ограничения, термины, факты, цель.
        Текущая память задачи: ${session.taskMemory.toPrompt()}
        Последние сообщения:
        $recentHistory
        
        Верни JSON с полями:
        - goal (строка, если изменилась или новая)
        - constraints (массив строк, новые ограничения)
        - terms (массив строк, новые термины/факты)
        - notes (массив строк, дополнительные заметки)
        Если изменений нет, верни пустой JSON: {}
        Ответь только JSON, без текста.
    """.trimIndent()

    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val body = JSONObject().apply {
        put("model", "deepseek-chat")
        put("messages", JSONArray().apply {
            put(JSONObject().apply { put("role", "user"); put("content", prompt) })
        })
        put("temperature", 0.0)
        put("max_tokens", 300)
    }.toString()
    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.deepseek.com/v1/chat/completions"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .timeout(Duration.ofSeconds(30))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            val json = JSONObject(response.body())
            val content = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
            val cleanJson = content.replace("```json", "").replace("```", "").trim()
            if (cleanJson.isNotEmpty() && cleanJson.startsWith("{")) {
                val update = JSONObject(cleanJson)
                if (update.has("goal") && update.getString("goal").isNotEmpty()) {
                    session.taskMemory.goal = update.getString("goal")
                }
                if (update.has("constraints")) {
                    val arr = update.getJSONArray("constraints")
                    for (i in 0 until arr.length()) {
                        val c = arr.getString(i)
                        if (c !in session.taskMemory.constraints) session.taskMemory.constraints.add(c)
                    }
                }
                if (update.has("terms")) {
                    val arr = update.getJSONArray("terms")
                    for (i in 0 until arr.length()) {
                        val t = arr.getString(i)
                        if (t !in session.taskMemory.terms) session.taskMemory.terms.add(t)
                    }
                }
                if (update.has("notes")) {
                    val arr = update.getJSONArray("notes")
                    for (i in 0 until arr.length()) {
                        val n = arr.getString(i)
                        if (n !in session.taskMemory.notes) session.taskMemory.notes.add(n)
                    }
                }
            }
        }
    } catch (e: Exception) {
        println("Ошибка обновления памяти задачи: ${e.message}")
    }
}
import kotlinx.coroutines.*
import java.io.File
import java.io.PrintStream
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

fun main(args: Array<String>) = runBlocking {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    System.setErr(PrintStream(System.err, true, "UTF-8"))

    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("Set DEEPSEEK_API_KEY env variable")
    val ollamaUrl = System.getenv("OLLAMA_URL") ?: "http://localhost:11434"
    val ollamaModel = System.getenv("OLLAMA_EMBED_MODEL") ?: "nomic-embed-text"

    val embedder = OllamaEmbedder(ollamaUrl, ollamaModel)
    val deepSeekApi = DeepSeekApi(apiKey)

    val currentRepoUrl = AtomicReference<String?>(null)
    val currentTempDir = AtomicReference<Path?>(null)
    val currentProjectDir = AtomicReference<String?>(null)
    val currentRagStore = AtomicReference<RagStore?>(null)

    println("=== Project Assistant Ready ===")
    println("Commands:")
    println("  repo <url>  - Load GitHub repository (owner/repo or full URL)")
    println("  help <query> - Ask a question about the loaded project")
    println("  clear       - Delete cached embeddings for current repo")
    println("  status      - Show current repository and branch")
    println("  exit        - Shut down and clean up")

    while (true) {
        print("> ")
        val line = readLine()?.trim() ?: break
        if (line.isEmpty()) continue

        val parts = line.split("\\s+".toRegex(), 2)
        when (parts[0]) {
            "repo" -> {
                if (parts.size < 2) {
                    println("Usage: repo <owner/repo or URL>")
                    continue
                }
                val url = parts[1]
                currentTempDir.getAndSet(null)?.let {
                    it.toFile().deleteRecursively()
                    println("Previous clone deleted.")
                }
                try {
                    val tempDir = Files.createTempDirectory("project-repo-")
                    cloneRepository(url, tempDir.toFile())
                    val projectDir = tempDir.toFile().absolutePath

                    val ragStore = RagStore.loadFromCache(url, embedder) ?: run {
                        val readme = File(projectDir, "README.md").takeIf { it.exists() }?.readText() ?: ""
                        val docsFiles = mutableMapOf<String, String>()
                        val docsDir = File(projectDir, "docs")
                        if (docsDir.exists()) {
                            docsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                                val relativePath = file.relativeTo(docsDir).path
                                docsFiles[relativePath] = file.readText()
                            }
                        }
                        val store = RagStore(embedder)
                        store.indexDocuments(readme, docsFiles)
                        store.saveToCache(url)
                        store
                    }
                    currentRepoUrl.set(url)
                    currentTempDir.set(tempDir)
                    currentProjectDir.set(projectDir)
                    currentRagStore.set(ragStore)

                    println("Repository loaded: $url")
                    println("  Project dir: $projectDir")
                } catch (e: Exception) {
                    System.err.println("Failed to load repository: ${e.message}")
                    currentTempDir.getAndSet(null)?.toFile()?.deleteRecursively()
                }
            }
            "help" -> {
                val question = parts.getOrNull(1)
                if (question.isNullOrBlank()) {
                    println("Usage: help <your question>")
                    continue
                }
                val projectDir = currentProjectDir.get()
                val ragStore = currentRagStore.get()
                if (projectDir == null || ragStore == null) {
                    println("No repository loaded. Use 'repo <url>' first.")
                    continue
                }

                val thinkingJob = launch {
                    print("Thinking")
                    while (isActive) {
                        delay(500)
                        print(".")
                    }
                }

                val result = withTimeoutOrNull(90_000L) {
                    val ollamaOk = withTimeoutOrNull(3000L) { embedder.ping() } ?: false
                    if (!ollamaOk) {
                        thinkingJob.cancelAndJoin()
                        println("\nOllama is not available or too slow. Make sure it's running.")
                        return@withTimeoutOrNull
                    }
                    val deepseekOk = withTimeoutOrNull(3000L) { deepSeekApi.ping() } ?: false
                    if (!deepseekOk) {
                        thinkingJob.cancelAndJoin()
                        println("\nDeepSeek API is not available or too slow. Check key/internet.")
                        return@withTimeoutOrNull
                    }

                    val branch = getGitBranch(projectDir)
                    val docs = ragStore.queryRelevant(question, topK = 3)
                    val docsText = docs.joinToString("\n\n") { "---\n$it" }

                    val prompt = """
                        You are an expert on the project.
                        Current git branch: $branch
                        
                        Relevant documentation:
                        $docsText
                        
                        Question: $question
                        
                        Answer concisely using the provided documentation.
                    """.trimIndent()

                    val answer = deepSeekApi.chat(prompt)
                    thinkingJob.cancelAndJoin()
                    println("\nAnswer:\n$answer\n")
                }

                if (result == null && thinkingJob.isActive) {
                    thinkingJob.cancelAndJoin()
                    println("\nRequest timed out or service is not responding.")
                }
            }
            "clear" -> {
                val url = currentRepoUrl.get()
                if (url != null) {
                    val cacheFile = RagStore.getCacheFile(url)
                    if (cacheFile.exists()) {
                        cacheFile.delete()
                        println("Cache deleted for $url")
                    } else {
                        println("No cache file found.")
                    }
                    currentRagStore.set(null)
                    println("RAG store cleared. Reload the repo to re-index.")
                } else {
                    println("No repository loaded.")
                }
            }
            "status" -> {
                val url = currentRepoUrl.get()
                val projectDir = currentProjectDir.get()
                if (url != null && projectDir != null) {
                    val branch = getGitBranch(projectDir)
                    println("Current repository: $url")
                    println("  Project dir: $projectDir")
                    println("  Git branch: $branch")
                    println("  RAG loaded: ${currentRagStore.get() != null}")
                } else {
                    println("No repository loaded.")
                }
            }
            "exit" -> {
                println("Shutting down...")
                break
            }
            else -> {
                println("Unknown command. Available: repo, help, clear, status, exit")
            }
        }
    }

    currentTempDir.get()?.toFile()?.deleteRecursively()
    println("Goodbye.")
}

fun cloneRepository(repoUrl: String, targetDir: File) {
    val fullUrl = if (repoUrl.startsWith("http")) repoUrl
    else "https://github.com/$repoUrl.git"

    println("Cloning $fullUrl into ${targetDir.absolutePath} ...")
    Git.cloneRepository()
        .setURI(fullUrl)
        .setDirectory(targetDir)
        .call()
        .close()
    println("Clone completed.")
}

fun getGitBranch(projectDir: String): String {
    return try {
        val repo = FileRepositoryBuilder()
            .setGitDir(File(projectDir, ".git"))
            .build()
        val branch = repo.branch ?: "HEAD detached or unknown"
        repo.close()
        branch
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}
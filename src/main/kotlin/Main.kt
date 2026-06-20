import java.io.PrintStream
import java.util.*

// ----- CLI -----
fun main() {
    System.setOut(PrintStream(System.out, true, "UTF-8"))
    val apiKey = System.getenv("DEEPSEEK_API_KEY") ?: error("DEEPSEEK_API_KEY не задан")
    val agent = TaskAgent(apiKey)
    val scanner = Scanner(System.`in`, "UTF-8")

    println("Агент с конечным автоматом и инвариантами")
    println("Команды:")
    println("  start <описание>          – начать новую задачу")
    println("  step <описание>           – зафиксировать выполненный шаг")
    println("  next                      – перейти к следующему шагу")
    println("  pause                     – приостановить задачу")
    println("  resume                    – продолжить задачу")
    println("  status                    – показать состояние задачи")
    println("  complete                  – завершить задачу")
    println("  reset                     – сбросить задачу и историю")
    println("  invariant add <правило>   – добавить инвариант")
    println("  invariant remove <номер>  – удалить инвариант по номеру")
    println("  invariant list            – показать все инварианты")
    println("  invariant clear           – очистить инварианты")
    println("  help / ?                  – справка")
    println("  exit                      – выход")
    println()

    while (true) {
        print("Вы: ")
        val input = scanner.nextLine().trim()
        when {
            input.equals("exit", true) -> break
            input.equals("help", true) || input.equals("?") -> {
                println("start/step/next/pause/resume/status/complete/reset")
                println("invariant add/remove/list/clear")
            }

            input.startsWith("start ", true) -> println(agent.startTask(input.removePrefix("start ").trim()))
            input.startsWith("step ", true) -> println(agent.recordStep(input.removePrefix("step ").trim()))
            input.equals("next", true) -> println(agent.nextStep())
            input.equals("pause", true) -> println(agent.pauseTask())
            input.equals("resume", true) -> println(agent.resumeTask())
            input.equals("status", true) -> println(agent.showStatus())
            input.equals("complete", true) -> println(agent.completeTask())
            input.equals("reset", true) -> {
                agent.reset()
                println("Задача и история сброшены.")
            }

            input.startsWith("invariant ", true) -> {
                val parts = input.removePrefix("invariant ").trim().split(" ", limit = 2)
                when (parts.getOrElse(0) { "" }) {
                    "add" -> {
                        if (parts.size >= 2) println(agent.addInvariant(parts[1]))
                        else println("Укажите правило: invariant add <текст>")
                    }

                    "remove" -> {
                        val index = parts.getOrElse(1) { "" }.toIntOrNull()
                        if (index != null) println(agent.removeInvariant(index))
                        else println("Укажите номер: invariant remove <число>")
                    }

                    "list" -> println(agent.listInvariants())
                    "clear" -> println(agent.clearInvariants())
                    else -> println("Неизвестная команда. Используйте add, remove, list, clear.")
                }
            }

            input.isEmpty() -> continue
            else -> {
                print("Агент: ")
                val start = System.currentTimeMillis()
                val answer = agent.sendMessage(input)
                println(answer)
                println("(за ${"%.1f".format((System.currentTimeMillis() - start) / 1000.0)} с)")
            }
        }
    }
}
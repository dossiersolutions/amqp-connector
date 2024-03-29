package no.dossier.libraries.amqpconnector.error

actual sealed class AmqpError {
    actual abstract val message: String
    actual abstract val causes: Map<String, AmqpError>
    private val stackTrace: Array<out StackTraceElement> = Thread.currentThread().stackTrace

    private fun getErrorTree(levels: List<Boolean> = emptyList()): String = buildString {
        val levelPrefix = levels.joinToString("") { if (it) "│   " else "    " }
        message.lines().forEach {
            if (it == message.lines().first())
                append("[${this@AmqpError::class.simpleName}] $it\n")
            else {
                val localPrefix = if (causes.isEmpty()) "        " else "│       "
                append("$levelPrefix$localPrefix$it\n")
            }
        }
        causes.entries.forEach { entry ->
            val lastEntry = entry != causes.entries.last()
            val entryPrefix = levelPrefix + if (lastEntry) "├── " else "└── "
            append("$entryPrefix\uD83D\uDD34 ${entry.key} : ${entry.value.getErrorTree(levels + lastEntry)}")
        }
    }

    private fun getFullStackTrace(): String = buildString {
        stackTrace.forEach {
            append("${it}\n")
        }
    }

    private fun getMiniStackTrace(): String = buildString {
        stackTrace.filter {
            it.className.startsWith("no.dossier.")
                    && (it.fileName as String) !in listOf("InternalError.kt")
        }.forEachIndexed { index, it ->
            val symbol = if (index == 0) "┌" else "│"
            append("$symbol ${it}\n")
        }
    }

    actual override fun toString(): String =
        "\n\uD83D\uDD34 " + getErrorTree() + "mini trace:\n" + getMiniStackTrace()
}
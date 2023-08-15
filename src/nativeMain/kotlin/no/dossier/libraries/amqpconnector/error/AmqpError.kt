package no.dossier.libraries.amqpconnector.error

actual sealed class AmqpError {
    actual abstract val message: String
    actual abstract val causes: Map<String, AmqpError>

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

    private fun getFullStackTrace(): String = "Stacktrace not implemented on native!"

    private fun getMiniStackTrace(): String = "Stacktrace not implemented on native!"

    actual override fun toString(): String = "\n\uD83D\uDD34 " + getErrorTree() + "mini trace:\n" + getMiniStackTrace()
}
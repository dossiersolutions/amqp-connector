package no.dossier.libraries.amqpconnector.rabbitmq

enum class AMQPExchangeType(val stringRepresentation: String) {
    DEFAULT(""),
    FANOUT("fanout"),
    DIRECT("direct"),
    TOPIC("topic"),
    HEADERS("headers")
}

data class AMQPExchangeSpec(
    val name: String,
    val type: AMQPExchangeType
)
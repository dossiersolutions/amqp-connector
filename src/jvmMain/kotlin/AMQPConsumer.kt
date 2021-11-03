package no.dossier.libraries.amqpconnector.rabbitmq

import com.rabbitmq.client.Connection
import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.serialization.json.Json

class AMQPConsumer<T: Any>(
    private val topicName: String,
    private val bindingKey: String,
    private val numberOfWorkers: Int,
    private val messageHandler: (AMQPMessage<T>) -> Unit,
    private val serializer: KSerializer<T>
) {
    @OptIn(DelicateCoroutinesApi::class)
    fun startConsuming(connection: Connection) {
        val amqpChannel = connection.createChannel()
        amqpChannel.exchangeDeclare(topicName, "topic")
        val queueName: String = amqpChannel.queueDeclare().queue
        amqpChannel.queueBind(queueName, topicName, bindingKey)

        val workersChannel = KChannel<AMQPMessage<T>>(16)

        GlobalScope.launch {
            repeat(numberOfWorkers) {
                launch(Dispatchers.IO) {
                    while(true) messageHandler(workersChannel.receive())
                }
            }
        }

        amqpChannel.basicConsume(queueName, true, { consumerTag, payload ->
            runBlocking {
                workersChannel.send(
                    AMQPMessage(
                        payload.properties.headers.mapValues { it.value.toString() },
                        Json.decodeFromString(serializer, String(payload.body))
                    )
                )
            }
        }, { consumerTag ->

        })
    }
}
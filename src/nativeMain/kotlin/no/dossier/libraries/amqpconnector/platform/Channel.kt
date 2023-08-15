package no.dossier.libraries.amqpconnector.platform

import no.dossier.libraries.amqpconnector.primitives.AmqpQueueSpec
import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperties

actual class Channel {
    actual val nextPublishSeqNo: Long
        get() = TODO("Not yet implemented")

    actual fun queueDeclare(
        name: String,
        durable: Boolean,
        exclusive: Boolean,
        autoDelete: Boolean,
        queueArgs: Map<String, String>?
    ): AmqpQueueSpec {
        TODO("Not yet implemented")
    }

    actual fun exchangeDeclare(name: String, type: String, durable: Boolean) {
    }

    actual fun queueBind(actualQueueName: String, name: String, actualBindingKey: String) {
    }

    actual fun basicQos(prefetchCount: Int) {
    }

    actual fun basicConsume(
        actualMainQueueName: String,
        autoAckEnabled: Boolean,
        deliverCallback: (consumerTag: String, message: Delivery) -> Unit,
        cancelCallback: (consumerTag: String) -> Unit
    ): String? {
        TODO("Not yet implemented")
    }

    actual fun basicCancel(tag: String) {
    }

    actual fun basicPublish(
        replyToExchange: String,
        routingKey: String,
        messageProperties: AmqpMessageProperties,
        rawPayload: ByteArray
    ) {
    }

    actual fun basicAck(deliveryTag: Long, multiple: Boolean) {
    }

    actual fun basicReject(deliveryTag: Long, requeue: Boolean) {
    }

    actual fun confirmSelect() {
    }

    actual fun addConfirmListener(
        ackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit,
        nackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit
    ) {
    }

}
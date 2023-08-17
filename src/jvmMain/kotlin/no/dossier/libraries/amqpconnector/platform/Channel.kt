package no.dossier.libraries.amqpconnector.platform

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import no.dossier.libraries.amqpconnector.primitives.AmqpQueueSpec
import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperties

actual class Channel(private val rmqChannel: Channel) {

    actual val nextPublishSeqNo: Long
        get() = rmqChannel.nextPublishSeqNo

    actual fun queueDeclare(
        name: String,
        durable: Boolean,
        exclusive: Boolean,
        autoDelete: Boolean,
        queueArgs: Map<String, String>?
    ): AmqpQueueSpec = AmqpQueueSpec(
        rmqChannel.queueDeclare(name, durable, exclusive, autoDelete, queueArgs).queue,
        durable,
        exclusive,
        autoDelete
    )

    actual fun exchangeDeclare(name: String, type: String, durable: Boolean) {
        rmqChannel.exchangeDeclare(name, type, durable)
    }

    actual fun queueBind(actualQueueName: String, name: String, actualBindingKey: String) {
        rmqChannel.queueBind(actualQueueName, name, actualBindingKey)
    }

    actual fun basicQos(prefetchCount: Int) {
        rmqChannel.basicQos(prefetchCount)
    }

    actual fun basicConsume(
        actualMainQueueName: String,
        autoAckEnabled: Boolean,
        deliverCallback: (consumerTag: String, message: Delivery) -> Unit,
        cancelCallback: (consumerTag: String) -> Unit
    ): String? {
        return rmqChannel.basicConsume(
            actualMainQueueName,
            autoAckEnabled,
            { consumerTag, rmqDelivery -> deliverCallback(consumerTag, Delivery(rmqDelivery)) },
            cancelCallback
        )
    }

    actual fun basicCancel(tag: String) {
        rmqChannel.basicCancel(tag)
    }

    actual fun basicPublish(
        replyToExchange: String,
        routingKey: String,
        messageProperties: AmqpMessageProperties,
        rawPayload: ByteArray
    ) {
        rmqChannel.basicPublish(replyToExchange, routingKey, buildProperties(messageProperties), rawPayload)
    }

    private fun buildProperties(replyProperties: AmqpMessageProperties): AMQP.BasicProperties? {
        val amqpPropertiesBuilder = AMQP.BasicProperties().builder()
            .deliveryMode(replyProperties.deliveryMode.code)
            .headers(replyProperties.headers as Map<String, Any>?)

        replyProperties.replyTo?.run { amqpPropertiesBuilder.replyTo(replyProperties.replyTo) }
        replyProperties.correlationId?.run { amqpPropertiesBuilder.correlationId(replyProperties.correlationId) }

        return amqpPropertiesBuilder.build()
    }

    actual fun basicAck(deliveryTag: Long, multiple: Boolean) {
        rmqChannel.basicAck(deliveryTag, multiple)
    }

    actual fun basicReject(deliveryTag: Long, requeue: Boolean) {
        basicReject(deliveryTag, requeue)
    }

    actual fun confirmSelect() {
        rmqChannel.confirmSelect()
    }

    actual fun addConfirmListener(
        ackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit,
        nackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit
    ) {
        rmqChannel.addConfirmListener(ackCallback, nackCallback)
    }

}
package no.dossier.libraries.amqpconnector.platform

import no.dossier.libraries.amqpconnector.primitives.AmqpQueueSpec
import no.dossier.libraries.amqpconnector.primitives.AmqpMessageProperties

expect class Channel {
    val nextPublishSeqNo: Long

    fun queueDeclare(name: String, durable: Boolean, exclusive: Boolean, autoDelete: Boolean, queueArgs: Map<String, String>?): AmqpQueueSpec
    fun exchangeDeclare(name: String, type: String, durable: Boolean)
    fun queueBind(actualQueueName: String, name: String, actualBindingKey: String)
    fun basicQos(prefetchCount: Int)
    fun basicConsume(actualMainQueueName: String, autoAckEnabled: Boolean, deliverCallback: (consumerTag: String, message: Delivery) -> Unit, cancelCallback: (consumerTag: String) -> Unit): String?
    fun basicCancel(tag: String)
    fun basicPublish(replyToExchange: String, routingKey: String, messageProperties: AmqpMessageProperties, rawPayload: ByteArray)
    fun basicAck(deliveryTag: Long, multiple: Boolean)
    fun basicReject(deliveryTag: Long, requeue: Boolean)
    fun confirmSelect()
    fun addConfirmListener(ackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit, nackCallback: (sequenceNumber: Long, multiple: Boolean) -> Unit)
}
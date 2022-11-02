amqp-connector is a RabbitMQ/AMQP client library relying on the official [RabbitMQ client for Java](https://www.rabbitmq.com/api-guide.html).

![GitHub](https://img.shields.io/github/license/dossiersolutions/amqp-connector)
![Maven Central](https://img.shields.io/maven-central/v/no.dossier.libraries/amqp-connector)

## Features
* Publish/Subscribe, Direct notifications, Broadcasts, Request / Reply schemes (RPC)
* Fast async publishing using Kotlin coroutines
* Publisher confirms
* Explicit consumer acknowledgements / rejections
* Fast parallel message processing using Kotlin coroutines
* Enforced or on-demand replying
* Configurable queues (durability, explicitness, auto-deletion)
* Configurable forwarding of dead-letter messages
* Automatic connection and channel recovery
* Pausing and resuming consumers
* Custom hooks on successful message publication / consumption
* Implicit message acknowledgements

### Currently unsupported
* Publishing and processing messages in batches
* Dynamic subscription and un-subscription
* Confirmations for replies from consumers

## Getting started
1. Add the amqp-connector dependency to your Gradle build script

       implementation("no.dossier.libraries:amqp-connector:0.1.0")

3. Create an instance of the connector using dedicated Kotlin DSL and start publishing and/or consuming messages

    ```
    class ExampleResource {
       val connector = connector(role = PublisherAndConsumer) {
           connectionString = "amqp://guest:guest@localhost:5672/"
           clientName = "sample-app"
    
           consumer(::sampleProcessingFunction) {
               workersCoroutineScope = CoroutineScope(Dispatchers.Default)
               exchange { name = "some-ref-data" }
               bindingKey = Custom("refdata.*.user.#")
           }
       }
    
       val publisher = connector.publisher {
           exchange { name = "somedata-exchange" }
           routingKey = "somedata.cool.special"
       }
    
       suspend fun sampleProcessingFunction(message: AmqpInboundMessage<String>): Outcome<AmqpConsumingError, Unit> {
           println(message.payload)
           return Success(Unit)
       }
    
       suspend fun sendSamplePublication(request: String): Outcome<AmqpPublishingError, Unit> =
           publisher(AmqpOutboudMessage(request)) 
    }
    ```
See the internal Javadoc for more details

## License
See [LICENSE](LICENSE)

## Contribute
Any contributions and improvements are very welcome. Feel free to submit any pull-requests.

package vito.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import vito.consumer.ChatMessageListener;

@Configuration
public class RabbitMQConfig {

    private ExecutorService broadcastExecutor;

    /**
     * Declares queues on startup so room.1..room.20 exist without manual creation.
     * @param connectionFactory RabbitMQ connection factory
     * @return RabbitAdmin instance
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        return admin;
    }

    /**
     * Declares per-room queues with TTL and max length.
     * @param rabbitAdmin         RabbitAdmin to declare queues
     * @param queueMessageTtlMs   message TTL in ms
     * @param queueMaxLength     max messages per queue
     * @return placeholder bean
     */
    @Bean
    public Object roomQueuesDeclarer(RabbitAdmin rabbitAdmin,
                                    @Value("${app.rabbitmq.queue-message-ttl:60000}") int queueMessageTtlMs,
                                    @Value("${app.rabbitmq.queue-max-length:1000}") int queueMaxLength) {
        Map<String, Object> args = Map.of(
                "x-message-ttl", queueMessageTtlMs,
                "x-max-length", queueMaxLength);
        for (String queueName : Constants.QUEUE_NAMES) {
            rabbitAdmin.declareQueue(new Queue(queueName, true, false, false, args));
        }
        return new Object();
    }

    /**
     * Fixed thread pool for broadcasting to WebSocket sessions.
     * @param threads number of threads in the pool
     * @return ExecutorService
     */
    @Bean
    public ExecutorService broadcastExecutor(
            @Value("${app.consumer.threads:10}") int threads) {
        this.broadcastExecutor = Executors.newFixedThreadPool(threads);
        return this.broadcastExecutor;
    }

    /**
     * One listener container per room queue.
     * @param connectionFactory             RabbitMQ connection factory
     * @param chatMessageListener          message listener
     * @param prefetch                      prefetch count per consumer
     * @param concurrentConsumersPerQueue   concurrent consumers per queue
     * @return list of started containers
     */
    @Bean
    @DependsOn("roomQueuesDeclarer")
    public List<SimpleMessageListenerContainer> perRoomContainers(
            ConnectionFactory connectionFactory,
            ChatMessageListener chatMessageListener,
            @Value("${app.consumer.prefetch:10}") int prefetch,
            @Value("${app.consumer.concurrent-consumers-per-queue:1}") int concurrentConsumersPerQueue) {
        List<SimpleMessageListenerContainer> containers = new ArrayList<>();
        for (String queueName : Constants.QUEUE_NAMES) {
            SimpleMessageListenerContainer container = new SimpleMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.setQueueNames(queueName);
            container.setConcurrentConsumers(concurrentConsumersPerQueue);
            container.setPrefetchCount(prefetch);
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setMessageListener(chatMessageListener);
            containers.add(container);
        }
        containers.forEach(SimpleMessageListenerContainer::start);
        return containers;
    }

    /** Shuts down the broadcast executor. */
    @PreDestroy
    public void shutdownExecutor() {
        if (broadcastExecutor != null) {
            broadcastExecutor.shutdown();
        }
    }
}

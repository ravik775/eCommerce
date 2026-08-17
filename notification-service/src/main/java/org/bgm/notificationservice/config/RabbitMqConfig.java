package org.bgm.notificationservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * ADR-0003 (doc/adr/ADR-0003-eventing-kafka-rabbitmq.md): the
 * notification.dispatch work queue, with a dead-letter exchange/queue.
 * A message that keeps failing dispatch (see NotificationDispatchWorker)
 * is retried a bounded number of times, then republished to the DLQ
 * instead of being silently dropped or requeued forever.
 */
@Configuration
public class RabbitMqConfig {

    // Defaults match config-repo/notification-service.yml exactly — the
    // defaults here are what keeps the hermetic contextLoads test working
    // with config-server disabled (see src/test/resources/application.properties),
    // not a substitute for that config file in real deployments.
    @Value("${notification.rabbitmq.dispatch-queue:notification.dispatch}")
    private String dispatchQueueName;

    @Value("${notification.rabbitmq.dispatch-exchange:notification.exchange}")
    private String dispatchExchangeName;

    @Value("${notification.rabbitmq.dispatch-dlq:notification.dispatch.dlq}")
    private String dispatchDlqName;

    @Value("${notification.rabbitmq.dispatch-dlx:notification.dispatch.dlx}")
    private String dispatchDlxName;

    @Bean
    public DirectExchange dispatchExchange() {
        return new DirectExchange(dispatchExchangeName);
    }

    @Bean
    public DirectExchange dispatchDeadLetterExchange() {
        return new DirectExchange(dispatchDlxName);
    }

    @Bean
    public Queue dispatchQueue() {
        return QueueBuilder.durable(dispatchQueueName)
                .withArgument("x-dead-letter-exchange", dispatchDlxName)
                .withArgument("x-dead-letter-routing-key", dispatchDlqName)
                .build();
    }

    @Bean
    public Queue dispatchDeadLetterQueue() {
        return QueueBuilder.durable(dispatchDlqName).build();
    }

    @Bean
    public Binding dispatchBinding() {
        return BindingBuilder.bind(dispatchQueue()).to(dispatchExchange()).with(dispatchQueueName);
    }

    @Bean
    public Binding dispatchDeadLetterBinding() {
        return BindingBuilder.bind(dispatchDeadLetterQueue()).to(dispatchDeadLetterExchange()).with(dispatchDlqName);
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                          Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        // ADR-0057: this bean is fully custom (not Boot's auto-configured
        // one), so the standard spring.rabbitmq.template.observation-enabled
        // property has no effect on it — same class of gap the
        // auto-startup comment below already documents for the listener
        // factory. Without this, NotificationEventConsumer.dispatch()'s
        // rabbitTemplate.convertAndSend(...) call creates no span, so
        // there is nothing for NotificationDispatchWorker's downstream
        // work (or OrderCorrelationScope's attribute stamping, ADR-0056)
        // to attach to in Tempo — set explicitly here instead.
        template.setObservationEnabled(true);
        return template;
    }

    // Retries a failed dispatch 3 times (1s apart), then republishes to the
    // DLX/DLQ instead of requeueing forever or dropping silently.
    @Bean
    public RabbitListenerContainerFactory<?> dispatchListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RabbitTemplate rabbitTemplate,
            @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        // This factory is fully custom (not Boot's auto-configured one), so
        // the standard spring.rabbitmq.listener.simple.auto-startup property
        // has to be applied explicitly here — found via the same hermetic
        // test that caught the EventSchemaValidator bean gap.
        factory.setAutoStartup(autoStartup);
        // ADR-0057: same gap as the RabbitTemplate above — this factory
        // being custom means spring.rabbitmq.listener.simple.observation-enabled
        // has no effect either. Without this, NotificationDispatchWorker's
        // @RabbitListener invocations create no span, matching the exact
        // Kafka-listener gap this same ADR fixes on the Kafka side.
        factory.setObservationEnabled(true);

        RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(
                rabbitTemplate, dispatchDlxName, dispatchDlqName);
        RetryOperationsInterceptor retryInterceptor = RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 1.0, 1000)
                .recoverer(recoverer)
                .build();
        factory.setAdviceChain(retryInterceptor);
        return factory;
    }
}

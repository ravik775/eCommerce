package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentProcessorResolver {

    private final Map<PaymentMethod, PaymentProcessor> processorsByMethod;

    public PaymentProcessorResolver(List<PaymentProcessor> processors) {
        this.processorsByMethod = processors.stream()
                .collect(Collectors.toUnmodifiableMap(PaymentProcessor::method, Function.identity()));
    }

    public PaymentProcessor resolve(PaymentMethod method) {
        PaymentProcessor processor = processorsByMethod.get(method);
        if (processor == null) {
            throw new IllegalStateException("No PaymentProcessor registered for method: " + method);
        }
        return processor;
    }
}

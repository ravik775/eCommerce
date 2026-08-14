package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class CreditCardProcessor extends AbstractSimulatedProcessor {
    @Override
    public PaymentMethod method() {
        return PaymentMethod.CREDIT_CARD;
    }

    @Override
    protected String gatewayName() {
        return "SIMULATED-CREDIT-CARD";
    }
}

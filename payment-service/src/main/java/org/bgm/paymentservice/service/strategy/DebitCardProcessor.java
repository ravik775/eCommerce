package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class DebitCardProcessor extends AbstractSimulatedProcessor {
    @Override
    public PaymentMethod method() {
        return PaymentMethod.DEBIT_CARD;
    }

    @Override
    protected String gatewayName() {
        return "SIMULATED-DEBIT-CARD";
    }
}

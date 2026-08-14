package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class UPIProcessor extends AbstractSimulatedProcessor {
    @Override
    public PaymentMethod method() {
        return PaymentMethod.UPI;
    }

    @Override
    protected String gatewayName() {
        return "SIMULATED-UPI";
    }
}

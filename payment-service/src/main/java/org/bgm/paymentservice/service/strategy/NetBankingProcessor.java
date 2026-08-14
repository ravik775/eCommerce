package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class NetBankingProcessor extends AbstractSimulatedProcessor {
    @Override
    public PaymentMethod method() {
        return PaymentMethod.NET_BANKING;
    }

    @Override
    protected String gatewayName() {
        return "SIMULATED-NET-BANKING";
    }
}

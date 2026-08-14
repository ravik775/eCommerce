package org.bgm.paymentservice.service.strategy;

import org.bgm.paymentservice.model.PaymentMethod;
import org.springframework.stereotype.Component;

@Component
public class QRCodeProcessor extends AbstractSimulatedProcessor {
    @Override
    public PaymentMethod method() {
        return PaymentMethod.QR_CODE;
    }

    @Override
    protected String gatewayName() {
        return "SIMULATED-QR-CODE";
    }
}

package org.bgm.common.idempotency;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ADR-0024: proves the hash is genuinely deterministic (field order must
 * not change it — the whole point of using it as a dedupe key) and that
 * a real difference in payload produces a different hash.
 */
class PayloadHasherTest {

    @Test
    void sameFieldsDifferentOrder_produceIdenticalHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("customerId", 42);
        a.put("quantity", 2);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("quantity", 2);
        b.put("customerId", 42);

        assertEquals(PayloadHasher.sha256Hex(a), PayloadHasher.sha256Hex(b));
    }

    @Test
    void differentPayload_producesDifferentHash() {
        Map<String, Object> a = Map.of("customerId", 42, "quantity", 2);
        Map<String, Object> b = Map.of("customerId", 42, "quantity", 3);

        assertNotEquals(PayloadHasher.sha256Hex(a), PayloadHasher.sha256Hex(b));
    }

    @Test
    void sameLogicalRequest_hashesIdenticallyAcrossCalls() {
        record OrderRequest(@JsonProperty long customerId, @JsonProperty int quantity) {
        }
        OrderRequest r1 = new OrderRequest(42, 2);
        OrderRequest r2 = new OrderRequest(42, 2);

        assertEquals(PayloadHasher.sha256Hex(r1), PayloadHasher.sha256Hex(r2));
    }
}

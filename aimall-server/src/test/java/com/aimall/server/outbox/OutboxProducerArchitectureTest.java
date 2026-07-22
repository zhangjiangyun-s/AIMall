package com.aimall.server.outbox;

import com.aimall.server.service.impl.OrderServiceImpl;
import com.aimall.server.service.impl.PaymentQueryStateService;
import com.aimall.server.service.impl.RefundTaskStateService;
import com.aimall.server.service.impl.ReturnApplyServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.retry.annotation.Retryable;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OutboxProducerArchitectureTest {
    @Test
    void criticalBusinessFactProducersKeepTransactionalBoundary() {
        assertTransactional(OrderServiceImpl.class, "create");
        assertTransactional(PaymentQueryStateService.class, "apply");
        assertTransactional(ReturnApplyServiceImpl.class, "refund");
        assertTransactional(RefundTaskStateService.class, "finalizeBusiness");
    }

    @Test
    void inventoryOrchestratorsKeepBoundedConcurrencyRetry() {
        assertRetryable(OrderServiceImpl.class, "create");
        assertRetryable(PaymentQueryStateService.class, "apply");
        assertRetryable(RefundTaskStateService.class, "finalizeBusiness");
    }

    private void assertTransactional(Class<?> type, String methodName) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        assertNotNull(method.getAnnotation(Transactional.class),
                () -> type.getSimpleName() + "." + methodName + " must remain transactional");
    }

    private void assertRetryable(Class<?> type, String methodName) {
        Method method = Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        Retryable retryable = method.getAnnotation(Retryable.class);
        assertNotNull(retryable,
                () -> type.getSimpleName() + "." + methodName + " must retain bounded concurrency retry");
    }
}

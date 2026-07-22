package com.aimall.server.validation;

import com.aimall.server.order.dto.OrderCreateRequest;
import com.aimall.server.payment.dto.PaymentOrderRequest;
import com.aimall.server.user.dto.ReturnApplyRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class Stage8RequestValidationTest {
    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void orderCreationRejectsMalformedRequestIdAndEmptyCartSelection() {
        OrderCreateRequest request = new OrderCreateRequest("bad request id", 0L, null, List.of());

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void paymentRejectsInvalidOrderId() {
        PaymentOrderRequest request = new PaymentOrderRequest(0L);

        assertFalse(validator.validate(request).isEmpty());
    }

    @Test
    void returnApplicationRejectsInvalidQuantity() {
        ReturnApplyRequest request = new ReturnApplyRequest(
                1L,
                "reason",
                "description",
                List.of(new ReturnApplyRequest.ReturnItem(1L, 0)),
                "REFUND_ONLY"
        );

        assertFalse(validator.validate(request).isEmpty());
    }
}

package com.aimall.server.mapper;

import com.aimall.server.entity.PaymentCallbackEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;

@Mapper
public interface PaymentCallbackEventMapper extends BaseMapper<PaymentCallbackEvent> {
    @Insert("""
        INSERT IGNORE INTO payment_callback_event
        (event_id, provider, provider_reference, request_id, trace_id, tenant_id, event_source, order_id,
         provider_status, amount, signature_valid, payload_hash, raw_payload, processing_state, create_time)
        VALUES (#{eventId}, #{provider}, #{providerReference}, #{requestId}, #{traceId}, #{tenantId}, #{eventSource}, #{orderId},
                #{providerStatus}, #{amount}, #{signatureValid}, #{payloadHash},
                #{rawPayload}, #{processingState}, NOW())
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertIgnore(PaymentCallbackEvent event);
}

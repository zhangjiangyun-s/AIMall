package com.aimall.server.service.impl;

import com.aimall.server.entity.*;
import com.aimall.server.mapper.MoneySnapshotMapper;
import com.aimall.server.money.MoneyPolicy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class MoneySnapshotService {
    private final MoneySnapshotMapper mapper;
    public MoneySnapshotService(MoneySnapshotMapper mapper) { this.mapper=mapper; }

    public void record(OmsOrder v){mapper.upsertOrder(v.getId(),currency(v.getCurrencyCode()),scale(v.getCurrencyScale()),s(v.getTotalAmount()),s(v.getPayAmount()),s(v.getFreightAmount()),s(v.getPromotionAmount()),s(v.getCouponAmount()),s(v.getDiscountAmount()),s(v.getRefundedAmount()));}
    public void record(OmsOrderItem v){mapper.upsertOrderItem(v.getId(),v.getOrderId(),s(v.getProductPrice()),s(v.getPromotionAmount()),s(v.getCouponAmount()),s(v.getRealAmount()));}
    public void record(OmsPaymentRecord v){mapper.upsertPayment(v.getId(),currency(v.getCurrencyCode()),scale(v.getCurrencyScale()),s(v.getAmount()),s(v.getPaidAmount()),s(v.getRefundedAmount()));}
    public void record(OmsRefundRecord v){mapper.upsertRefund(v.getId(),currency(v.getCurrencyCode()),scale(v.getCurrencyScale()),s(v.getAmount()));}
    public void record(OmsOrderReturnItem v){mapper.upsertReturnItem(v.getId(),v.getOrderItemId(),s(v.getRefundAmount()));}
    public BigDecimal orderItemRealAmount(Long id,BigDecimal fallback){BigDecimal v=mapper.selectOrderItemRealAmount(id);return v==null?s(fallback):v;}
    public BigDecimal completedRefundAmount(Long id,BigDecimal fallback){BigDecimal v=mapper.selectCompletedRefundAmount(id);return v==null?s(fallback):v;}
    private BigDecimal s(BigDecimal v){return MoneyPolicy.storage(v);}
    private String currency(String v){return v==null||v.isBlank()?MoneyPolicy.DEFAULT_CURRENCY:v;}
    private int scale(Integer v){return v==null?MoneyPolicy.DEFAULT_CURRENCY_SCALE:v;}
}

UPDATE oms_payment_record
SET pay_status = 'REFUNDED',
    update_time = NOW()
WHERE pay_status = 'PARTIALLY_REFUNDED'
  AND refunded_amount = amount;

UPDATE oms_order
SET refund_status = 'FULL_REFUNDED',
    modify_time = NOW()
WHERE refund_status = 'PARTIALLY_REFUNDED'
  AND refunded_amount = pay_amount;

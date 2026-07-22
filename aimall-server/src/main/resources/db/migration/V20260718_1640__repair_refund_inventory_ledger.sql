UPDATE inventory_ledger ledger
JOIN oms_refund_record refund
  ON ledger.event_id = CONCAT('REFUND-', refund.id, ':null')
JOIN oms_order_return_item return_item
  ON return_item.return_apply_id = refund.return_apply_id
SET ledger.event_id = CONCAT('REFUND-', refund.id, ':', return_item.order_item_id),
    ledger.order_id = refund.order_id,
    ledger.order_item_id = return_item.order_item_id,
    ledger.on_hand_delta = ledger.quantity,
    ledger.reserved_delta = 0,
    ledger.sold_delta = -ledger.quantity
WHERE ledger.operation = 'RESTORE'
  AND (
        SELECT COUNT(*)
        FROM oms_order_return_item counted_item
        WHERE counted_item.return_apply_id = refund.return_apply_id
      ) = 1;

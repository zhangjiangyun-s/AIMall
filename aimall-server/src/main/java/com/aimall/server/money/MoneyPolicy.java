package com.aimall.server.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

public final class MoneyPolicy {
    public static final int STORAGE_SCALE = 4;
    public static final String DEFAULT_CURRENCY = "CNY";
    public static final int DEFAULT_CURRENCY_SCALE = 2;
    public static final RoundingMode SETTLEMENT_ROUNDING = RoundingMode.HALF_UP;

    private MoneyPolicy() {}

    public static BigDecimal storage(BigDecimal amount) {
        return value(amount).setScale(STORAGE_SCALE, SETTLEMENT_ROUNDING);
    }

    public static BigDecimal channel(BigDecimal amount, int currencyScale) {
        if (currencyScale < 0 || currencyScale > STORAGE_SCALE) {
            throw new IllegalArgumentException("currencyScale must be between 0 and 4");
        }
        return value(amount).setScale(currencyScale, SETTLEMENT_ROUNDING);
    }

    public static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights) {
        BigDecimal normalizedTotal = storage(total);
        if (weights == null || weights.isEmpty()) return List.of();
        BigDecimal weightSum = weights.stream().map(MoneyPolicy::storage).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (normalizedTotal.signum() < 0 || weightSum.signum() <= 0) {
            throw new IllegalArgumentException("allocation total and weights must be positive");
        }
        List<BigDecimal> result = new ArrayList<>(weights.size());
        BigDecimal remaining = normalizedTotal;
        for (int index = 0; index < weights.size(); index++) {
            BigDecimal allocated = index == weights.size() - 1
                    ? remaining
                    : normalizedTotal.multiply(storage(weights.get(index)))
                        .divide(weightSum, STORAGE_SCALE, SETTLEMENT_ROUNDING)
                        .min(remaining).max(BigDecimal.ZERO.setScale(STORAGE_SCALE));
            allocated = storage(allocated);
            result.add(allocated);
            remaining = storage(remaining.subtract(allocated));
        }
        return List.copyOf(result);
    }

    private static BigDecimal value(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}

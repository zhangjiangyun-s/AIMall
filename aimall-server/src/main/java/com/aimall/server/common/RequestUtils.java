package com.aimall.server.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class RequestUtils {

    private RequestUtils() {
    }

    public static String requiredString(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.toString().trim();
    }

    public static String optionalString(Map<String, ?> params, String field) {
        Object value = value(params, field);
        return value == null ? null : value.toString();
    }

    public static Long requiredLong(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "必须是数字");
        }
    }

    public static Long optionalLong(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "必须是数字");
        }
    }

    public static Integer requiredInt(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
    }

    public static Integer optionalInt(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "必须是整数");
        }
    }

    public static BigDecimal optionalDecimal(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + "必须是金额");
        }
    }

    public static List<Long> requiredLongList(Map<String, ?> params, String field) {
        Object value = value(params, field);
        if (!(value instanceof List<?> source) || source.isEmpty()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return source.stream().map(item -> {
            if (item == null || item.toString().isBlank()) {
                throw new IllegalArgumentException(field + "包含空值");
            }
            try {
                return Long.valueOf(item.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(field + "必须是数字数组");
            }
        }).toList();
    }

    private static Object value(Map<String, ?> params, String field) {
        if (params == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return params.get(field);
    }
}

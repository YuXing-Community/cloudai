package com.cloudai.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.UUID;

public final class Json {
    /** 与 Spring MVC 输出一致：LocalDateTime -> "yyyy-MM-dd HH:mm:ss"（SSE 推送与接口返回格式统一） */
    public static final ObjectMapper M = new ObjectMapper()
            .registerModule(new JavaTimeModule().addSerializer(java.time.LocalDateTime.class,
                    new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode read(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new BizException("JSON 格式错误: " + e.getMessage());
        }
    }

    public static <T> T convert(Object o, Class<T> type) {
        return M.convertValue(o, type);
    }

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    public static ArrayNode arr() {
        return M.createArrayNode();
    }

    public static String text(JsonNode n, String field, String def) {
        if (n == null) return def;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }

    public static String uuid() {
        return UUID.randomUUID().toString();
    }
}

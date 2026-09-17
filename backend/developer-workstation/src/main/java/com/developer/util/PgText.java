package com.developer.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 的 text / jsonb 不接受 NUL 字符（码点 0；jsonb 里的转义形式同样被拒），
 * 写库前把用户消息、模型输出这类不受控文本里的 NUL 去掉。
 */
public final class PgText {

    private static final String NUL = String.valueOf((char) 0);

    private PgText() {
    }

    public static String clean(String s) {
        return s == null || !s.contains(NUL) ? s : s.replace(NUL, "");
    }

    /** 递归清理 JSON 结构（Map / List / String）；非字符串的叶子原样保留。 */
    @SuppressWarnings("unchecked")
    public static <T> T cleanJson(T value) {
        if (value instanceof String s) {
            return (T) clean(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(k instanceof String ks ? clean(ks) : k, cleanJson(v)));
            return (T) out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(cleanJson(v)));
            return (T) out;
        }
        return value;
    }
}

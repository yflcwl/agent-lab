package com.example.solution.agent;

import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonResponseExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonResponseExtractor() {
    }

    static String extractObject(String response) {
        if (response == null) {
            throw new IllegalArgumentException("Agent 未返回文本结果");
        }
        int firstBrace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace < firstBrace) {
            throw new IllegalArgumentException("Agent 返回中没有 JSON 对象，响应摘要：" + summarize(response));
        }
        // 优先精确匹配：从每个 '{' 出发找与之配对的 '}'，只有能通过 Jackson 校验才采用，
        // 这样可跳过叙述里出现的花括号，也不会被正文里的花括号提前截断。
        for (int candidate = firstBrace; candidate >= 0 && candidate < lastBrace;
             candidate = response.indexOf('{', candidate + 1)) {
            int end = matchingEnd(response, candidate);
            if (end > candidate) {
                String json = response.substring(candidate, end + 1);
                if (isValid(json)) {
                    return json;
                }
            }
        }
        // 兜底：取第一个 '{' 到最后一个 '}'，能通过校验就采用。
        String fallback = response.substring(firstBrace, lastBrace + 1);
        if (isValid(fallback)) {
            return fallback;
        }
        throw new IllegalArgumentException("Agent 返回的 JSON 对象不完整，响应摘要：" + summarize(response));
    }

    private static int matchingEnd(String response, int start) {
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < response.length(); i++) {
            char current = response.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isValid(String json) {
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String summarize(String response) {
        String normalized = response.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300) + "…";
    }
}

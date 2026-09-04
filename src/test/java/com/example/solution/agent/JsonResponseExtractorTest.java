package com.example.solution.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonResponseExtractorTest {

    @Test
    void extractsJsonObjectFromModelExplanationAndCodeFence() {
        String response = "已完成本章。\n```json\n{\"result\":\"SUCCESS\",\"content\":\"正文含有 { 花括号 }\"}\n```";

        assertThat(JsonResponseExtractor.extractObject(response))
                .isEqualTo("{\"result\":\"SUCCESS\",\"content\":\"正文含有 { 花括号 }\"}");
    }

    @Test
    void includesResponseSummaryWhenModelDoesNotReturnJson() {
        assertThatThrownBy(() -> JsonResponseExtractor.extractObject("我还需要更多信息"))
                .hasMessageContaining("响应摘要：我还需要更多信息");
    }

    @Test
    void skipsBracesInsideLeadingText() {
        String response = "先对比 {方案A} 与 {方案B}，再输出：{\"result\":\"SUCCESS\",\"content\":\"正文\",\"summary\":\"摘要\"}";

        assertThat(JsonResponseExtractor.extractObject(response))
                .isEqualTo("{\"result\":\"SUCCESS\",\"content\":\"正文\",\"summary\":\"摘要\"}");
    }

    @Test
    void ignoresTrailingTextAfterJsonObject() {
        String response = "{\"result\":\"SUCCESS\",\"content\":\"正文\"} 以上是本章内容。";

        assertThat(JsonResponseExtractor.extractObject(response))
                .isEqualTo("{\"result\":\"SUCCESS\",\"content\":\"正文\"}");
    }
}

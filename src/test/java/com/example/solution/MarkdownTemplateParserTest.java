package com.example.solution;

import com.example.solution.application.MarkdownTemplateParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarkdownTemplateParserTest {

    private final MarkdownTemplateParser parser = new MarkdownTemplateParser();

    @Test
    void preservesHeadingTreeAndIncludesChildHeadingsInParentRequirement() {
        var sections = parser.parse("""
                # 测试方案
                ## 1. 方案概述
                描述目标。
                ## 2. 总体架构
                描述架构。
                ### 2.1 子章节
                子章节要求。
                """);

        assertThat(sections).hasSize(4);
        assertThat(sections.get(1).nodeId()).isEqualTo("heading-2");
        assertThat(sections.get(1).title()).isEqualTo("1. 方案概述");
        assertThat(sections.get(3).title()).isEqualTo("2.1 子章节");
        assertThat(sections.get(2).requirement()).contains("### 2.1 子章节");
    }

    @Test
    void rejectsTemplateWithoutHeading() {
        assertThatThrownBy(() -> parser.parse("只有普通正文"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsRawTitleAndStableNodeId() {
        var sections = parser.parse("## 十、机场不停航施工专项管理（仅在施工影响飞行区或机场运行时编制）");

        assertThat(sections.getFirst().nodeId()).isEqualTo("heading-1");
        assertThat(sections.getFirst().title()).isEqualTo("十、机场不停航施工专项管理（仅在施工影响飞行区或机场运行时编制）");
    }
}

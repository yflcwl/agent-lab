package com.example.solution;

import com.example.solution.application.PlanningDecisionValidator;
import com.example.solution.domain.PlannedChapter;
import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.PlanningDecisionType;
import com.example.solution.domain.TemplateSection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningDecisionValidatorTest {

    private final PlanningDecisionValidator validator = new PlanningDecisionValidator();

    @Test
    void rejectsCyclicDependencies() {
        List<TemplateSection> sections = List.of(
                new TemplateSection("heading-1", 2, "第一章", ""),
                new TemplateSection("heading-2", 2, "第二章", ""));
        PlanningDecision decision = new PlanningDecision(PlanningDecisionType.CREATE_PLAN, "分析",
                List.of(
                        new PlannedChapter("heading-1", List.of("heading-2"), 1),
                        new PlannedChapter("heading-2", List.of("heading-1"), 2)),
                "1", List.of(), "测试");

        assertThatThrownBy(() -> validator.validate(decision, sections, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("循环依赖");
    }
}

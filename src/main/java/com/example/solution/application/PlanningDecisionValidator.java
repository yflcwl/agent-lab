package com.example.solution.application;

import com.example.solution.domain.ChapterTask;
import com.example.solution.domain.ChapterTaskStatus;
import com.example.solution.domain.PlannedChapter;
import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.TemplateSection;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class PlanningDecisionValidator {

    public void validate(PlanningDecision decision, List<TemplateSection> templateSections,
                         List<ChapterTask> currentTasks) {
        if (decision == null || decision.decisionType() == null) {
            throw new IllegalArgumentException("PlanningAgent 必须返回 decisionType");
        }
        if (!decision.tasks().isEmpty()) {
            validatePlanTasks(decision.tasks(), templateSections);
        }
        if (decision.decisionType().name().equals("CREATE_PLAN") && !currentTasks.isEmpty()) {
            throw new IllegalArgumentException("已有任务列表时不能重新创建计划");
        }
        if (currentTasks.isEmpty() && decision.decisionType().name().equals("CREATE_PLAN")
                && decision.tasks().isEmpty()) {
            throw new IllegalArgumentException("CREATE_PLAN 必须返回章节任务列表");
        }
        if (StringUtils.hasText(decision.selectedTaskKey())) {
            Set<String> availableKeys = currentTasks.isEmpty()
                    ? decision.tasks().stream().map(PlannedChapter::templateNodeId).collect(java.util.stream.Collectors.toSet())
                    : currentTasks.stream()
                    .filter(task -> task.status() == ChapterTaskStatus.NOT_STARTED || task.status() == ChapterTaskStatus.REVISING)
                    .map(ChapterTask::chapterKey).collect(java.util.stream.Collectors.toSet());
            if (!availableKeys.contains(decision.selectedTaskKey())) {
                throw new IllegalArgumentException("selectedTaskKey 不是可执行任务: " + decision.selectedTaskKey());
            }
            boolean hasRevision = currentTasks.stream().anyMatch(task -> task.status() == ChapterTaskStatus.REVISING);
            if (hasRevision && currentTasks.stream().noneMatch(task -> task.chapterKey().equals(decision.selectedTaskKey())
                    && task.status() == ChapterTaskStatus.REVISING)) {
                throw new IllegalArgumentException("存在待修改章节时必须优先选择修改任务");
            }
        } else if (!decision.decisionType().name().equals("WAIT")) {
            throw new IllegalArgumentException("非 WAIT 决策必须返回 selectedTaskKey");
        }
    }

    private void validatePlanTasks(List<PlannedChapter> tasks, List<TemplateSection> templateSections) {
        Set<String> expectedKeys = templateSections.stream().map(TemplateSection::nodeId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> actualKeys = new HashSet<>();
        for (PlannedChapter task : tasks) {
            if (!StringUtils.hasText(task.templateNodeId()) || !actualKeys.add(task.templateNodeId())
                    || !expectedKeys.contains(task.templateNodeId())) {
                throw new IllegalArgumentException("章节任务 templateNodeId 必须引用且只能引用一个模板节点");
            }
        }
        Map<String, List<String>> graph = new HashMap<>();
        for (PlannedChapter task : tasks) {
            for (String dependency : task.dependencies()) {
                if (!actualKeys.contains(dependency) || dependency.equals(task.templateNodeId())) {
                    throw new IllegalArgumentException("章节依赖无效: " + task.templateNodeId() + " -> " + dependency);
                }
            }
            graph.put(task.templateNodeId(), task.dependencies());
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String key : graph.keySet()) {
            detectCycle(key, graph, visiting, visited);
        }
    }

    private void detectCycle(String key, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(key)) {
            return;
        }
        if (!visiting.add(key)) {
            throw new IllegalArgumentException("任务计划存在循环依赖");
        }
        for (String dependency : graph.getOrDefault(key, List.of())) {
            detectCycle(dependency, graph, visiting, visited);
        }
        visiting.remove(key);
        visited.add(key);
    }
}

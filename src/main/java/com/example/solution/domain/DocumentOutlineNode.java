package com.example.solution.domain;

import java.time.LocalDateTime;

/**
 * 一次撰写任务专属的大纲节点。首版由 Markdown 模板复制而来，后续可在此基础上调整。
 */
public record DocumentOutlineNode(
        Long id,
        Long writingTaskId,
        Long parentNodeId,
        String nodeKey,
        String templateNodeId,
        int level,
        String title,
        String requirement,
        int displayOrder,
        String origin,
        boolean locked,
        int outlineVersion,
        LocalDateTime createdAt) {
}

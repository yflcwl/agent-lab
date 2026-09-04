CREATE TABLE IF NOT EXISTS writing_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    goal TEXT NOT NULL COMMENT '撰写目标',
    template_markdown LONGTEXT NOT NULL COMMENT '模板 Markdown',
    template_analysis LONGTEXT NULL COMMENT '模板分析',
    status VARCHAR(32) NOT NULL COMMENT '任务状态',
    plan_version INT NOT NULL DEFAULT 0 COMMENT '计划版本',
    state_version BIGINT NOT NULL DEFAULT 0 COMMENT '状态版本',
    last_decision LONGTEXT NULL COMMENT '最后一次决策',
    last_error TEXT NULL COMMENT '最后一次错误信息',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间'
);

CREATE TABLE IF NOT EXISTS chapter_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    chapter_key VARCHAR(100) NOT NULL COMMENT '章节键',
    title VARCHAR(500) NOT NULL COMMENT '章节标题',
    requirement LONGTEXT NULL COMMENT '章节要求',
    dependencies TEXT NULL COMMENT '依赖的章节键列表',
    priority_value INT NOT NULL DEFAULT 0 COMMENT '优先级数值',
    status VARCHAR(32) NOT NULL COMMENT '章节状态',
    previous_status VARCHAR(32) NULL COMMENT '上一状态',
    content LONGTEXT NULL COMMENT '章节内容',
    summary TEXT NULL COMMENT '章节摘要',
    content_version INT NOT NULL DEFAULT 0 COMMENT '内容版本',
    lease_until TIMESTAMP NULL COMMENT '执行租约到期时间',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_chapter_task_key UNIQUE (writing_task_id, chapter_key),
    CONSTRAINT fk_chapter_task_writing_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS document_outline_node (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    parent_node_id BIGINT NULL COMMENT '父大纲节点 ID',
    node_key VARCHAR(100) NOT NULL COMMENT '任务内稳定节点键',
    template_node_id VARCHAR(100) NULL COMMENT '原始模板节点键',
    level_value INT NOT NULL COMMENT 'Markdown 标题层级',
    title VARCHAR(500) NOT NULL COMMENT '节点标题',
    requirement LONGTEXT NULL COMMENT '节点写作要求',
    display_order INT NOT NULL COMMENT '模板中的展示顺序',
    origin VARCHAR(32) NOT NULL COMMENT '节点来源',
    locked BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否仍受模板约束',
    outline_version INT NOT NULL DEFAULT 1 COMMENT '大纲版本',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_document_outline_node_key UNIQUE (writing_task_id, node_key),
    CONSTRAINT fk_outline_writing_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chapter_task_outline (
    chapter_task_id BIGINT PRIMARY KEY COMMENT '章节任务 ID',
    outline_node_id BIGINT NOT NULL COMMENT '动态大纲节点 ID',
    CONSTRAINT fk_task_outline_chapter FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_outline_node FOREIGN KEY (outline_node_id) REFERENCES document_outline_node (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chapter_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    chapter_task_id BIGINT NOT NULL COMMENT '关联章节任务 ID',
    decision VARCHAR(32) NOT NULL COMMENT '评审决策',
    feedback_text LONGTEXT NULL COMMENT '反馈内容',
    processed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已处理',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_feedback_writing_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_feedback_chapter_task FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS chapter_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    chapter_task_id BIGINT NOT NULL COMMENT '关联章节任务 ID',
    version_no INT NOT NULL COMMENT '版本号',
    content LONGTEXT NOT NULL COMMENT '版本内容',
    summary TEXT NULL COMMENT '版本摘要',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT uk_chapter_version UNIQUE (chapter_task_id, version_no),
    CONSTRAINT fk_version_chapter_task FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS planning_decision (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    plan_version INT NOT NULL COMMENT '计划版本',
    decision_type VARCHAR(32) NOT NULL COMMENT '决策类型',
    raw_response LONGTEXT NULL COMMENT '模型原始响应',
    structured_decision LONGTEXT NOT NULL COMMENT '结构化决策内容',
    valid BOOLEAN NOT NULL COMMENT '是否有效',
    validation_error TEXT NULL COMMENT '校验错误信息',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_decision_writing_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS source_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    original_filename VARCHAR(500) NOT NULL COMMENT '原始文件名',
    stored_path VARCHAR(1000) NOT NULL COMMENT 'Agent workspace 中的文件路径',
    file_type VARCHAR(32) NOT NULL COMMENT '文件类型',
    file_size BIGINT NOT NULL COMMENT '文件大小',
    file_hash VARCHAR(64) NOT NULL COMMENT '文件哈希',
    extraction_status VARCHAR(32) NOT NULL COMMENT '可提取状态',
    extraction_error TEXT NULL COMMENT '提取失败原因',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_source_document_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_trace (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    actor VARCHAR(64) NOT NULL COMMENT 'TaskManager 或 Agent 名称',
    event_type VARCHAR(64) NOT NULL COMMENT '执行事件类型',
    detail TEXT NOT NULL COMMENT '对用户可见的执行摘要',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_agent_trace_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    chapter_task_id BIGINT NULL COMMENT '关联章节任务 ID，规划时为空',
    agent_name VARCHAR(64) NOT NULL COMMENT '执行 Agent 名称',
    run_type VARCHAR(32) NOT NULL COMMENT 'PLAN 或 EXECUTE',
    status VARCHAR(32) NOT NULL COMMENT '观测运行状态',
    error_message TEXT NULL COMMENT '技术执行错误',
    started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '开始时间',
    ended_at TIMESTAMP NULL COMMENT '结束时间',
    CONSTRAINT fk_agent_run_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_chapter FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS agent_run_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    agent_run_id BIGINT NOT NULL COMMENT '关联 Agent 运行 ID',
    event_type VARCHAR(64) NOT NULL COMMENT 'AgentScope 观测事件类型',
    tool_call_id VARCHAR(128) NULL COMMENT '工具调用关联 ID',
    tool_name VARCHAR(128) NULL COMMENT '工具名称',
    detail TEXT NOT NULL COMMENT '安全的页面展示摘要',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (agent_run_id) REFERENCES agent_run (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    chapter_task_id BIGINT NULL COMMENT '目标章节任务 ID',
    role VARCHAR(32) NOT NULL COMMENT 'USER、AGENT 或 SYSTEM',
    message_type VARCHAR(32) NOT NULL COMMENT 'QUESTION、CHAPTER_INSTRUCTION 或 PLAN_INSTRUCTION',
    content LONGTEXT NOT NULL COMMENT '对话或指令内容',
    status VARCHAR(32) NOT NULL COMMENT '处理状态',
    related_agent_run_id BIGINT NULL COMMENT '处理该消息的 AgentRun',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CONSTRAINT fk_task_message_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_task_message_chapter FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE SET NULL,
    CONSTRAINT fk_task_message_run FOREIGN KEY (related_agent_run_id) REFERENCES agent_run (id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS chapter_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键',
    writing_task_id BIGINT NOT NULL COMMENT '关联撰写任务 ID',
    chapter_task_id BIGINT NOT NULL COMMENT '关联章节任务 ID',
    content_version INT NOT NULL COMMENT '生成该 checkpoint 时章节的内容版本',
    partial_content LONGTEXT NOT NULL COMMENT '已生成的部分正文',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_chapter_checkpoint_task UNIQUE (chapter_task_id),
    CONSTRAINT fk_checkpoint_writing_task FOREIGN KEY (writing_task_id) REFERENCES writing_task (id) ON DELETE CASCADE,
    CONSTRAINT fk_checkpoint_chapter_task FOREIGN KEY (chapter_task_id) REFERENCES chapter_task (id) ON DELETE CASCADE
);

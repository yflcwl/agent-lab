package com.example.incremental.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String model = "dashscope:qwen3.6-flash";
    private Path dataRoot = Path.of(".incremental-agent/data");
    private Path agentWorkspace = Path.of(".incremental-agent/agent-workspace");
    private String writingSystemPrompt;
    private Path stateRoot = Path.of(".incremental-agent/state");
    private Duration timeout = Duration.ofMinutes(3);
    private String ragEmbeddingModel = "text-embedding-v3";
    private String ragEmbeddingBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private int ragEmbeddingDimensions = 1024;
    private int ragChunkSize = 800;
    private int ragChunkOverlap = 120;
    private int ragTopK = 5;
    private String ragStore = "pgvector";
    private String ragJdbcUrl = "jdbc:postgresql://localhost:5432/incremental_agent";
    private String ragDatabaseUsername = "incremental_agent";
    private String ragDatabasePassword;
    private String researchMode = "hybrid";

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Path getDataRoot() {
        return dataRoot;
    }

    public void setDataRoot(Path dataRoot) {
        this.dataRoot = dataRoot;
    }

    public Path getAgentWorkspace() {
        return agentWorkspace;
    }

    public void setAgentWorkspace(Path agentWorkspace) {
        this.agentWorkspace = agentWorkspace;
    }

    public String getWritingSystemPrompt() {
        return writingSystemPrompt;
    }

    public void setWritingSystemPrompt(String writingSystemPrompt) {
        this.writingSystemPrompt = writingSystemPrompt;
    }

    public Path getStateRoot() {
        return stateRoot;
    }

    public void setStateRoot(Path stateRoot) {
        this.stateRoot = stateRoot;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public String getRagEmbeddingModel() {
        return ragEmbeddingModel;
    }

    public void setRagEmbeddingModel(String ragEmbeddingModel) {
        this.ragEmbeddingModel = ragEmbeddingModel;
    }

    public String getRagEmbeddingBaseUrl() {
        return ragEmbeddingBaseUrl;
    }

    public void setRagEmbeddingBaseUrl(String ragEmbeddingBaseUrl) {
        this.ragEmbeddingBaseUrl = ragEmbeddingBaseUrl;
    }

    public int getRagEmbeddingDimensions() {
        return ragEmbeddingDimensions;
    }

    public void setRagEmbeddingDimensions(int ragEmbeddingDimensions) {
        this.ragEmbeddingDimensions = ragEmbeddingDimensions;
    }

    public int getRagChunkSize() {
        return ragChunkSize;
    }

    public void setRagChunkSize(int ragChunkSize) {
        this.ragChunkSize = ragChunkSize;
    }

    public int getRagChunkOverlap() {
        return ragChunkOverlap;
    }

    public void setRagChunkOverlap(int ragChunkOverlap) {
        this.ragChunkOverlap = ragChunkOverlap;
    }

    public int getRagTopK() {
        return ragTopK;
    }

    public void setRagTopK(int ragTopK) {
        this.ragTopK = ragTopK;
    }

    public String getRagStore() {
        return ragStore;
    }

    public void setRagStore(String ragStore) {
        this.ragStore = ragStore;
    }

    public String getRagJdbcUrl() {
        return ragJdbcUrl;
    }

    public void setRagJdbcUrl(String ragJdbcUrl) {
        this.ragJdbcUrl = ragJdbcUrl;
    }

    public String getRagDatabaseUsername() {
        return ragDatabaseUsername;
    }

    public void setRagDatabaseUsername(String ragDatabaseUsername) {
        this.ragDatabaseUsername = ragDatabaseUsername;
    }

    public String getRagDatabasePassword() {
        return ragDatabasePassword;
    }

    public void setRagDatabasePassword(String ragDatabasePassword) {
        this.ragDatabasePassword = ragDatabasePassword;
    }

    public String getResearchMode() {
        return researchMode;
    }

    public void setResearchMode(String researchMode) {
        this.researchMode = researchMode;
    }
}


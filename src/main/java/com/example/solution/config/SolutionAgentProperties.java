package com.example.solution.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "solution.agent")
public class SolutionAgentProperties {

    private String model = "dashscope:qwen3.6-flash";
    private String workspace = ".agentscope/workspace";
    private Duration timeout = Duration.ofMinutes(3);
    private int executionThreads = 2;
    private Duration leaseDuration = Duration.ofMinutes(5);
    private int maxRetries = 3;
    private Duration retryDelay = Duration.ofSeconds(5);

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getWorkspace() {
        return workspace;
    }

    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getExecutionThreads() {
        return executionThreads;
    }

    public void setExecutionThreads(int executionThreads) {
        this.executionThreads = executionThreads;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryDelay() {
        return retryDelay;
    }

    public void setRetryDelay(Duration retryDelay) {
        this.retryDelay = retryDelay;
    }
}

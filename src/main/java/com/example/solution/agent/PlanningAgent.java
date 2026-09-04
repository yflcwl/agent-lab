package com.example.solution.agent;

import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.PlanningRequest;

public interface PlanningAgent {

    Response plan(PlanningRequest request);

    default Response plan(PlanningRequest request, AgentExecutionObserver observer) {
        return plan(request);
    }

    record Response(PlanningDecision decision, String rawResponse) {
    }
}

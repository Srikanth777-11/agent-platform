package com.agentplatform.common.exception;

public class AgentException extends RuntimeException {
    private final String agentName;

    public AgentException(String agentName, String message) {
        super("[" + agentName + "] " + message);
        this.agentName = agentName;
    }

    public AgentException(String agentName, String message, Throwable cause) {
        super("[" + agentName + "] " + message, cause);
        this.agentName = agentName;
    }

    public String getAgentName() {
        return agentName;
    }
}

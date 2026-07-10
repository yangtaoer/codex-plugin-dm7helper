package io.dm7codex.plugin.sql;

public final class SecretBearingSqlException extends SecurityException {
    private final String policyName;

    SecretBearingSqlException(String policyName) {
        super("SQL security policy " + policyName + " rejected the statement");
        this.policyName = policyName;
    }

    public String policyName() {
        return policyName;
    }
}

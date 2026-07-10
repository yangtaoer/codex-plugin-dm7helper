package io.dm7codex.plugin.sql;

public enum SqlPurpose {
    PRODUCTION_CHANGE,
    MIGRATION,
    TEST,
    MOCK,
    SEED,
    SAMPLE;

    public boolean isReleaseEligible() {
        return this == PRODUCTION_CHANGE || this == MIGRATION;
    }
}

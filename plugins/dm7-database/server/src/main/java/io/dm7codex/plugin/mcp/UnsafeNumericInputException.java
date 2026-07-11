package io.dm7codex.plugin.mcp;

final class UnsafeNumericInputException extends IllegalArgumentException {
    UnsafeNumericInputException() { super("Numeric input cannot be represented safely"); }
}

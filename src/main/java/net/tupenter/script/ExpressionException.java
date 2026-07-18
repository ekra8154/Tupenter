package net.tupenter.script;

/**
 * Expression parse/evaluation failure. Extends IllegalArgumentException so
 * legacy soft-fail catch sites (auto-detect math, /calc) keep working.
 */
public class ExpressionException extends IllegalArgumentException {
    public ExpressionException(String message) {
        super(message);
    }
}

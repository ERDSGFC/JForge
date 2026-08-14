package io.github.erdsgfc.jforge;

/**
 * Runtime exception thrown by the ORM for connection, SQL, transaction, mapping and
 * configuration errors.
 *
 * <p>Carries a coarse error {@link Code} category plus an optional SQL statement so
 * callers can programmatically classify the failure ({@link #code()}), read the
 * offending SQL ({@link #sql()}) and get a self-contained message — the generated
 * repository code embeds the operation, table name and SQL in the message, so the
 * root cause does not have to be dug out of the {@link #getCause()} chain to
 * understand what failed.</p>
 */
public class OrmException extends RuntimeException {

    /** Coarse error category for programmatic handling. */
    public enum Code {
        /** Connection acquisition/release failed. */
        CONNECTION,
        /** SQL statement execution failed. */
        SQL,
        /** Transaction begin/commit/rollback failed. */
        TRANSACTION,
        /** Entity/row mapping failed. */
        MAPPING,
        /** Configuration/validation error. */
        CONFIGURATION
    }

    private final Code code;
    private final String sql;

    /**
     * Creates an exception with the default {@link Code#SQL} category.
     *
     * @param message the error message
     */
    public OrmException(String message) {
        this(Code.SQL, message, null, null);
    }

    /**
     * Creates an exception with the default {@link Code#SQL} category and a cause.
     *
     * @param message the error message
     * @param cause   the underlying cause
     */
    public OrmException(String message, Throwable cause) {
        this(Code.SQL, message, null, cause);
    }

    /**
     * Creates an exception with an explicit category and no cause.
     *
     * @param code    the error category
     * @param message the error message
     */
    public OrmException(Code code, String message) {
        this(code, message, null, null);
    }

    /**
     * Creates an exception with an explicit category and cause.
     *
     * @param code    the error category
     * @param message the error message
     * @param cause   the underlying cause
     */
    public OrmException(Code code, String message, Throwable cause) {
        this(code, message, null, cause);
    }

    /**
     * Creates an exception with an explicit category, SQL context and cause.
     *
     * @param code    the error category
     * @param message the error message
     * @param sql     the SQL statement that failed, or {@code null}
     * @param cause   the underlying cause
     */
    public OrmException(Code code, String message, String sql, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.sql = sql;
    }

    /** Returns the coarse error category. */
    public Code code() {
        return code;
    }

    /** Returns the SQL statement that failed, or {@code null} when not applicable. */
    public String sql() {
        return sql;
    }
}

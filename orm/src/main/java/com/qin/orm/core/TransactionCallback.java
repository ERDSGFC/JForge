package com.qin.orm.core;

/**
 * Callback executed inside a programmatic transaction via
 * {@link TransactionOperations#execute(TransactionCallback)}.
 *
 * <p>The callback body may invoke any repository method; all operations on the
 * current thread join the active transaction automatically. It declares no
 * checked exceptions because every ORM operation fails fast with the unchecked
 * {@link com.qin.orm.OrmException} — a callback that needs to run code throwing
 * checked exceptions must wrap it in a try/catch and rethrow as a runtime
 * exception (which triggers a rollback).</p>
 *
 * @param <T> the return type of the transaction body
 */
@FunctionalInterface
public interface TransactionCallback<T> {

    /**
     * Runs the transactional work.
     *
     * @return the value to return from {@link TransactionOperations#execute}, or
     *         {@code null} when the body is run for side effects only
     */
    T doInTransaction();
}

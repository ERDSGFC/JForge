package com.qin.orm;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Built-in {@link TransactionManager} backed by a {@code ThreadLocal}.
 * Used when no third-party transaction manager (e.g. Spring) is installed.
 */
public final class SimpleTransactionManager implements TransactionManager {

    private final ThreadLocal<Connection> txConnection = new ThreadLocal<>();

    @Override
    public Connection connection(DataSource dataSource) {
        Connection txConn = txConnection.get();
        if (txConn != null) {
            return txConn;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new OrmException("Cannot obtain connection", e);
        }
    }

    @Override
    public void release(Connection conn) {
        if (conn != txConnection.get()) {
            try {
                conn.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    @Override
    public void begin(DataSource dataSource) {
        if (txConnection.get() != null) {
            throw new OrmException("A transaction is already active on this thread");
        }
        try {
            Connection conn = dataSource.getConnection();
            conn.setAutoCommit(false);
            txConnection.set(conn);
        } catch (SQLException e) {
            throw new OrmException("Cannot begin transaction", e);
        }
    }

    @Override
    public void commit() {
        Connection conn = txConnection.get();
        if (conn == null) {
            throw new OrmException("No active transaction to commit");
        }
        try {
            conn.commit();
        } catch (SQLException e) {
            throw new OrmException("Commit failed", e);
        } finally {
            closeAndClear(conn);
        }
    }

    @Override
    public void rollback() {
        Connection conn = txConnection.get();
        if (conn == null) {
            throw new OrmException("No active transaction to rollback");
        }
        try {
            conn.rollback();
        } catch (SQLException e) {
            throw new OrmException("Rollback failed", e);
        } finally {
            closeAndClear(conn);
        }
    }

    @Override
    public boolean isActive() {
        return txConnection.get() != null;
    }

    private void closeAndClear(Connection conn) {
        txConnection.remove();
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort
        }
    }
}
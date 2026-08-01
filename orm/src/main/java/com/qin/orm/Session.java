package com.qin.orm;

import com.qin.orm.core.DefaultRowMapper;
import com.qin.orm.core.EntityMetadata;
import com.qin.orm.core.RowMapper;
import com.qin.orm.core.SqlGenerator;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Top-level ORM entry point. Stateless between calls except for an optional
 * transaction connection started via {@link #beginTransaction()}.
 */
public class Session implements AutoCloseable {

    private final DataSource dataSource;
    private final boolean ownsDataSource;
    private Connection txConnection;
    private boolean inTransaction;

    Session(DataSource dataSource, boolean ownsDataSource) {
        this.dataSource = dataSource;
        this.ownsDataSource = ownsDataSource;
    }

    // ==================== CRUD ====================

    /**
     * Inserts the entity. If the id is {@code @GeneratedValue}, the database-generated
     * key is written back into the entity field.
     */
    public void insert(Object entity) {
        EntityMetadata meta = EntityMetadata.of(entity.getClass());
        withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    SqlGenerator.insertSql(meta), Statement.RETURN_GENERATED_KEYS)) {
                int index = 1;
                for (Field field : meta.fields()) {
                    if (field == meta.idField() && meta.idGenerated()) {
                        continue;
                    }
                    ps.setObject(index++, meta.getFieldValue(entity, field));
                }
                ps.executeUpdate();
                if (meta.idGenerated()) {
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (keys.next()) {
                            meta.setFieldValue(entity, meta.idField(), keys.getObject(1));
                        }
                    }
                }
                return null;
            }
        });
    }

    /** Updates all mapped columns of the entity, matched by its id. Returns affected rows. */
    public int update(Object entity) {
        EntityMetadata meta = EntityMetadata.of(entity.getClass());
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SqlGenerator.updateSql(meta))) {
                int index = 1;
                for (Field field : meta.fields()) {
                    if (field != meta.idField()) {
                        ps.setObject(index++, meta.getFieldValue(entity, field));
                    }
                }
                ps.setObject(index, meta.getFieldValue(entity, meta.idField()));
                return ps.executeUpdate();
            }
        });
    }

    /** Deletes the entity by its id. Returns affected rows. */
    public int delete(Object entity) {
        EntityMetadata meta = EntityMetadata.of(entity.getClass());
        return deleteById(meta.entityClass(), meta.getFieldValue(entity, meta.idField()));
    }

    /** Deletes the row with the given id. Returns affected rows. */
    public int deleteById(Class<?> entityClass, Object id) {
        EntityMetadata meta = EntityMetadata.of(entityClass);
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SqlGenerator.deleteSql(meta))) {
                ps.setObject(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** Loads an entity by its id, or {@code null} if absent. */
    public <T> T findById(Class<T> entityClass, Object id) {
        EntityMetadata meta = EntityMetadata.of(entityClass);
        RowMapper<T> mapper = new DefaultRowMapper<>(meta);
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SqlGenerator.selectByIdSql(meta))) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapper.map(rs) : null;
                }
            }
        });
    }

    /** Loads all rows of the entity's table. */
    public <T> List<T> findAll(Class<T> entityClass) {
        EntityMetadata meta = EntityMetadata.of(entityClass);
        RowMapper<T> mapper = new DefaultRowMapper<>(meta);
        return withConnection(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SqlGenerator.selectAllSql(meta))) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(mapper.map(rs));
                    }
                    return result;
                }
            }
        });
    }

    // ==================== Transactions ====================

    /** Starts a transaction; subsequent operations use a single connection. */
    public void beginTransaction() {
        if (inTransaction) {
            throw new OrmException("Transaction already active");
        }
        try {
            txConnection = dataSource.getConnection();
            txConnection.setAutoCommit(false);
            inTransaction = true;
        } catch (SQLException e) {
            throw new OrmException("Cannot begin transaction", e);
        }
    }

    public void commit() {
        requireTransaction();
        try {
            txConnection.commit();
        } catch (SQLException e) {
            throw new OrmException("Commit failed", e);
        } finally {
            endTransaction();
        }
    }

    public void rollback() {
        requireTransaction();
        try {
            txConnection.rollback();
        } catch (SQLException e) {
            throw new OrmException("Rollback failed", e);
        } finally {
            endTransaction();
        }
    }

    @Override
    public void close() {
        if (inTransaction) {
            rollback();
        }
        if (ownsDataSource && dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new OrmException("Failed to close data source", e);
            }
        }
    }

    // ==================== Internals ====================

    /** Runs an operation on a connection; closes it only when not inside a transaction. */
    private <R> R withConnection(SqlOperation<R> operation) {
        Connection conn = connection();
        boolean closeConn = !inTransaction;
        try {
            return operation.run(conn);
        } catch (SQLException e) {
            throw new OrmException("SQL failed", e);
        } finally {
            if (closeConn) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }

    private void requireTransaction() {
        if (!inTransaction) {
            throw new OrmException("No active transaction");
        }
    }

    private void endTransaction() {
        try {
            txConnection.close();
        } catch (SQLException e) {
            // best effort
        }
        txConnection = null;
        inTransaction = false;
    }

    private Connection connection() {
        if (inTransaction) {
            return txConnection;
        }
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            throw new OrmException("Cannot obtain connection", e);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<R> {
        R run(Connection conn) throws SQLException;
    }
}

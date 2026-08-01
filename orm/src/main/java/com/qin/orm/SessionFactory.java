package com.qin.orm;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;

/** Entry point for creating {@link Session}s. */
public final class SessionFactory {

    private static final int DEFAULT_POOL_SIZE = 10;

    private SessionFactory() {
    }

    /** Opens a session on a HikariCP-pooled JDBC URL (e.g. {@code jdbc:h2:mem:test}). */
    public static Session open(String jdbcUrl) {
        return open(jdbcUrl, null, null);
    }

    /** Opens a session on a HikariCP-pooled JDBC URL with credentials. */
    public static Session open(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(DEFAULT_POOL_SIZE);
        // PreparedStatement cache: reuse compiled statements for identical SQL (pre-generated
        // metadata SQL strings make every operation hit this cache).
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        return new Session(new HikariDataSource(config), true);
    }

    /** Opens a session on a caller-provided data source (not closed by the session). */
    public static Session open(DataSource dataSource) {
        return new Session(dataSource, false);
    }
}

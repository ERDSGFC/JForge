package io.github.erdsgfc.jforge.processor.dialect;

import io.github.erdsgfc.jforge.annotation.DialectSupport;

/**
 * SQLite 的内建方言实现（编译期使用，不进框架 jar）。
 *
 * <p>生成键：SQLite 3.35+（xerial JDBC 驱动内置引擎均为新版本）支持
 * {@code INSERT ... RETURNING}——官方推荐的生成键方式，优于 {@code getGeneratedKeys}
 * （后者在 SQLite 驱动上等价于 {@code last_insert_rowid()}）。</p>
 */
public final class SqliteDialect implements DialectSupport {

    @Override
    public boolean supportsReturningKeys() {
        return true; // SQLite 3.35+ 支持 INSERT ... RETURNING
    }

    @Override
    public String serialType() {
        // SQLite 无独立自增类型:INTEGER PRIMARY KEY 即 rowid 别名自动自增;
        // AUTOINCREMENT 仅防止 rowid 重用。
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    public String quote() {
        return "\""; // SQLite 双引号/方括号/反引号均支持,取标准写法
    }

    @Override
    public String limitClause() {
        return "LIMIT ? OFFSET ?";
    }

    @Override
    public String upsertClause() {
        return "ON CONFLICT"; // INSERT ... ON CONFLICT(col) DO UPDATE SET ...
    }

    @Override
    public String booleanLiteral() {
        return "1"; // SQLite 无布尔类型,以 0/1 存储
    }

    @Override
    public boolean supportsBacktickQuotedIdentifiers() {
        return true;
    }
}

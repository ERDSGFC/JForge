module jforge.annotation {
    exports io.github.erdsgfc.jforge.annotation;

    // JForgeConverter.sqlType() 返回 java.sql.SQLType(JDBC 4.2)。
    requires java.sql;
    // 生成源码公开使用 JSpecify 类型注解，模块消费者无需重复声明可读性。
    requires transitive org.jspecify;
}

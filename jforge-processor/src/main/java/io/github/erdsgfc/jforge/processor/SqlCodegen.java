package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.MethodSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the JDBC code fragments (parameter binding, row mapping) shared by the
 * generated repository implementations. All type decisions are made here at
 * compile time — the emitted code calls type-exact setters/getters directly.
 */
public final class SqlCodegen {

    private static final ClassName ORM_EXCEPTION = ClassName.get("io.github.erdsgfc.jforge", "JForgeException");

    private SqlCodegen() {
    }

    /**
     * Builds a type-exact parameter bind statement with a constant index:
     * {@code ps.setXxx(index, expr);}.
     *
     * @param typeName the declared type of the parameter (e.g. {@code "int"}, {@code "java.lang.String"})
     * @param expr     the value expression (e.g. {@code "id"}, {@code "entity.age()"})
     * @param index    the 1-based placeholder index (compile-time constant)
     * @return the bind code block
     */
    public static CodeBlock bindParam(String typeName, String expr, int index) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), index, expr);
    }

    /**
     * Builds a type-exact parameter bind statement with a runtime index expression,
     * used inside loops over a variable-length parameter list.
     *
     * @param typeName  the declared type of the parameter
     * @param expr      the value expression
     * @param indexExpr the runtime index expression (e.g. the loop variable {@code "i"})
     * @return the bind code block
     */
    public static CodeBlock bindParam(String typeName, String expr, String indexExpr) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), indexExpr, expr);
    }

    /**
     * Builds a column-read statement by index and maps it through the entity's setter.
     * Used by generated CRUD where the SELECT column order always matches the field order.
     *
     * @param typeName   the field type string
     * @param entityVar  the entity variable name
     * @param setterName the builder-setter method name
     * @param index      the 1-based column index
     * @return the read code block
     */
    public static CodeBlock readColumn(String typeName, String entityVar, String setterName, int index) {
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            // LocalDate/LocalDateTime/enums: getObject(index, Class) — cast to the field type.
            return CodeBlock.of("$L.$L(($T) $L.getObject($L, $T.class));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName),
                    "rs", index, TypeNameUtils.toTypeName(typeName));
        }
        return CodeBlock.of("$L.$L($L.$L($L));",
                entityVar, setterName, "rs", getter, index);
    }

    /**
     * Builds a column-read statement by name and maps it through the entity's setter.
     * Used by {@code @Query} methods where the SELECT column order is user-controlled.
     *
     * @param typeName   the field type string
     * @param entityVar  the entity variable name
     * @param setterName the builder-setter method name
     * @param column     the column name
     * @return the read code block
     */
    public static CodeBlock readColumnByName(String typeName, String entityVar, String setterName, String column) {
        String getter = TypeNameUtils.jdbcGetter(typeName);
        if (getter.equals("getObject")) {
            return CodeBlock.of("$L.$L(($T) $L.getObject($S, $T.class));",
                    entityVar, setterName, TypeNameUtils.toTypeName(typeName),
                    "rs", column, TypeNameUtils.toTypeName(typeName));
        }
        return CodeBlock.of("$L.$L($L.$L($S));",
                entityVar, setterName, "rs", getter, column);
    }

    /**
     * Joins column names for SELECT / INSERT column lists.
     *
     * @param names the column names
     * @return comma-joined string
     */
    public static String joinColumns(List<String> names) {
        return String.join(",", names);
    }

    /**
     * Builds a placeholder list, e.g. 3 → {@code "?,?,?"}.
     *
     * @param count the number of placeholders
     * @return the placeholder string
     */
    public static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /**
     * Converts {@code :name} placeholders in a SQL string to {@code ?} placeholders.
     *
     * @param sql              the SQL with named placeholders (e.g. {@code "WHERE age > :age"})
     * @param placeholderOrder receives the placeholder names in order of appearance
     * @return the SQL with {@code ?} placeholders
     */
    public static String convertPlaceholders(String sql, List<String> placeholderOrder) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == ':' && i + 1 < sql.length() && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                int start = ++i;
                while (i < sql.length() && Character.isJavaIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                placeholderOrder.add(sql.substring(start, i));
                out.append('?');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /**
     * Starts the tx-aware block in the generated method: acquires the connection and opens the
     * {@code PreparedStatement} as a try-with-resources resource so it is always closed —
     * {@code Connection conn = getConnection(); try (PreparedStatement ps = conn.prepareStatement(...)) \{}.
     *
     * @param method           the method builder
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlExpr          the SQL expression passed to prepareStatement (a SQL field name, or
     *                         {@code sql.toString()} for dynamically built IN queries)
     * @param generatedKeys    whether to use {@code RETURN_GENERATED_KEYS}
     */
    public static MethodSpec.Builder beginTxBlock(MethodSpec.Builder method, ClassName connection,
            ClassName preparedStatement, String sqlExpr, boolean generatedKeys) {
        method.addStatement("$T conn = getConnection()", connection);
        if (generatedKeys) {
            return method.beginControlFlow("try ($T ps = conn.prepareStatement($L, $T.RETURN_GENERATED_KEYS))",
                    preparedStatement, sqlExpr, ClassName.get("java.sql", "Statement"));
        }
        return method.beginControlFlow("try ($T ps = conn.prepareStatement($L))", preparedStatement, sqlExpr);
    }

    /**
     * Closes the tx-aware try block with catch + finally (releaseConnection). The catch
     * throws an {@code JForgeException} whose message embeds the operation, table name and SQL
     * plus the underlying {@code SQLException} message, so a failure is self-describing
     * without digging into the cause chain.
     *
     * @param method     the method builder
     * @param sqlException the SQLException class
     * @param operation  the operation name (e.g. {@code "save"}, {@code "findById"})
     * @param tableName  the table the operation targets
     * @param sql        the SQL statement that failed (or its fixed prefix for dynamic IN queries)
     */
    public static void endTxBlock(MethodSpec.Builder method, ClassName sqlException,
            String operation, String tableName, String sql) {
        String message = operation + " on table '" + tableName + "' [" + sql + "]: ";
        method.nextControlFlow("catch ($T e)", sqlException)
                .addStatement("throw new $T($T.Code.SQL, $S + e.getMessage(), $S, e)",
                        ORM_EXCEPTION, ORM_EXCEPTION, message, sql)
                .nextControlFlow("finally")
                .addStatement("releaseConnection(conn)")
                .endControlFlow();
    }

    /**
     * Computes the INSERT columns for an entity: all mapped columns except a database-generated
     * primary key (which the database assigns on insert).
     *
     * @param model the parsed entity model
     * @return the columns to bind in an INSERT
     */
    public static List<EntityModel.ColumnModel> insertColumns(EntityModel model) {
        List<EntityModel.ColumnModel> columns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!(column.isId && model.idGenerated())) {
                columns.add(column);
            }
        }
        return columns;
    }

    /**
     * Extracts the column names of a list of column models.
     *
     * @param columns the column models
     * @return the column-name strings, in order
     */
    public static List<String> namesOf(List<EntityModel.ColumnModel> columns) {
        List<String> names = new ArrayList<>();
        for (EntityModel.ColumnModel column : columns) {
            names.add(column.columnName);
        }
        return names;
    }
}

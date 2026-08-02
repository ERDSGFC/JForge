package com.qin.orm.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import java.util.List;

/**
 * Builds the JDBC code fragments (parameter binding, row mapping) shared by the
 * generated repository implementations. All type decisions are made here at
 * compile time — the emitted code calls type-exact setters/getters directly.
 */
public final class SqlCodegen {

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

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

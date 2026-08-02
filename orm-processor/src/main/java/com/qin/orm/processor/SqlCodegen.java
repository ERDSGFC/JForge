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
     * Binds one parameter: {@code ps.setXxx(index, expr);}.
     * @param typeName  the declared type of the parameter (e.g. "int", "java.lang.String")
     * @param expr      the value expression (e.g. "id", "entity.age()")
     * @param index     the 1-based placeholder index (constant)
     */
    public static CodeBlock bindParam(String typeName, String expr, int index) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), index, expr);
    }

    /**
     * Binds one parameter with a runtime index expression (e.g. the loop variable "i").
     */
    public static CodeBlock bindParam(String typeName, String expr, String indexExpr) {
        return CodeBlock.of("$L.$L($L, $L);",
                "ps", TypeNameUtils.jdbcSetter(typeName), indexExpr, expr);
    }

    /** Reads a column by index and maps it through the given setter call. */
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

    /** Reads a column by name (used by @Query methods where SELECT order is user-controlled). */
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

    /** Column names joined for SELECT / INSERT column lists. */
    public static String joinColumns(List<String> names) {
        return String.join(",", names);
    }

    /** "?,?,?" placeholders. */
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

    /** Converts a {@code :name} SQL to {@code ?} placeholders, collecting placeholder order. */
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

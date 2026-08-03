package com.qin.orm.processor;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

/** TypeMirror-string → JavaPoet TypeName and JDBC binder/reader mapping helpers. */
public final class TypeNameUtils {

    private TypeNameUtils() {
    }

    /**
     * Converts a TypeMirror string to a JavaPoet type name.
     *
     * @param typeName the type string, e.g. {@code "int"}, {@code "byte[]"}, {@code "java.lang.Long"}
     * @return the corresponding JavaPoet type (primitive, array, or class)
     */
    public static TypeName toTypeName(String typeName) {
        if (typeName.equals("byte[]")) {
            return ArrayTypeName.of(TypeName.BYTE);
        }
        return switch (typeName) {
            case "byte" -> TypeName.BYTE;
            case "short" -> TypeName.SHORT;
            case "int" -> TypeName.INT;
            case "long" -> TypeName.LONG;
            case "float" -> TypeName.FLOAT;
            case "double" -> TypeName.DOUBLE;
            case "boolean" -> TypeName.BOOLEAN;
            case "char" -> TypeName.CHAR;
            default -> ClassName.bestGuess(typeName);
        };
    }

    /**
     * Returns the JDBC result-set getter method name for a field type.
     *
     * @param typeName the field type string, e.g. {@code "long"}, {@code "java.lang.String"}
     * @return the getter method name, e.g. {@code "getLong"}, {@code "getString"}, or
     *         {@code "getObject"} for types without a dedicated getter (LocalDate etc.)
     */
    public static String jdbcGetter(String typeName) {
        switch (typeName) {
            case "long":
            case "java.lang.Long":
                return "getLong";
            case "int":
            case "java.lang.Integer":
                return "getInt";
            case "java.lang.String":
                return "getString";
            case "boolean":
            case "java.lang.Boolean":
                return "getBoolean";
            case "double":
            case "java.lang.Double":
                return "getDouble";
            case "float":
            case "java.lang.Float":
                return "getFloat";
            case "short":
            case "java.lang.Short":
                return "getShort";
            case "byte":
            case "java.lang.Byte":
                return "getByte";
            case "java.math.BigDecimal":
                return "getBigDecimal";
            case "java.sql.Timestamp":
                return "getTimestamp";
            case "java.sql.Date":
                return "getDate";
            case "byte[]":
                return "getBytes";
            default:
                return "getObject"; // LocalDate/LocalDateTime/enums etc.
        }
    }

    /**
     * Returns the JDBC prepared-statement setter method name for a field type.
     *
     * @param typeName the field type string, e.g. {@code "long"}, {@code "java.lang.String"}
     * @return the setter method name, e.g. {@code "setLong"}, {@code "setString"}, or
     *         {@code "setObject"} for types without a dedicated setter
     */
    public static String jdbcSetter(String typeName) {
        switch (typeName) {
            case "long":
            case "java.lang.Long":
                return "setLong";
            case "int":
            case "java.lang.Integer":
                return "setInt";
            case "java.lang.String":
                return "setString";
            case "boolean":
            case "java.lang.Boolean":
                return "setBoolean";
            case "double":
            case "java.lang.Double":
                return "setDouble";
            case "float":
            case "java.lang.Float":
                return "setFloat";
            case "short":
            case "java.lang.Short":
                return "setShort";
            case "byte":
            case "java.lang.Byte":
                return "setByte";
            case "java.math.BigDecimal":
                return "setBigDecimal";
            case "java.sql.Timestamp":
                return "setTimestamp";
            case "java.sql.Date":
                return "setDate";
            case "byte[]":
                return "setBytes";
            default:
                return "setObject";
        }
    }

    /**
     * Returns the boxed type name for a primitive type (used for setter casts).
     *
     * @param typeName the type string, e.g. {@code "int"}
     * @return the boxed type name, e.g. {@code "Integer"}; non-primitives pass through unchanged
     */
    public static String boxedType(String typeName) {
        switch (typeName) {
            case "byte":
                return "Byte";
            case "short":
                return "Short";
            case "int":
                return "Integer";
            case "long":
                return "Long";
            case "float":
                return "Float";
            case "double":
                return "Double";
            case "boolean":
                return "Boolean";
            case "char":
                return "Character";
            default:
                return typeName;
        }
    }
}

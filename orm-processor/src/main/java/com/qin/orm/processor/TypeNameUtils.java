package com.qin.orm.processor;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

/** TypeMirror-string → JavaPoet TypeName and JDBC binder/reader mapping helpers. */
public final class TypeNameUtils {

    private TypeNameUtils() {
    }

    /** e.g. "int" → TypeName.INT, "byte[]" → byte[][], "java.lang.Long" → ClassName. */
    public static TypeName toTypeName(String typeName) {
        if (typeName.equals("byte[]")) {
            return ArrayTypeName.of(TypeName.BYTE);
        }
        switch (typeName) {
            case "byte":
                return TypeName.BYTE;
            case "short":
                return TypeName.SHORT;
            case "int":
                return TypeName.INT;
            case "long":
                return TypeName.LONG;
            case "float":
                return TypeName.FLOAT;
            case "double":
                return TypeName.DOUBLE;
            case "boolean":
                return TypeName.BOOLEAN;
            case "char":
                return TypeName.CHAR;
            default:
                return ClassName.bestGuess(typeName);
        }
    }

    /**
     * The JDBC "get" method name for a field type, e.g. "long"/"Long" → "getLong",
     * "byte[]" → "getBytes", "java.lang.String" → "getString", "java.time.LocalDate" → "getObject".
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

    /** The JDBC "set" method name for a field type, e.g. "long"/"Long" → "setLong". */
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

    /** The boxed class literal for a type (used for casts), e.g. "int" → "Integer". */
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

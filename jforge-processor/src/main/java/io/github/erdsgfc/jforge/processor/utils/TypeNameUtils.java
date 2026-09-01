package io.github.erdsgfc.jforge.processor.utils;

import com.palantir.javapoet.TypeName;

import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

/** JDBC 绑定/读取映射以及 TypeMirror 到 JavaPoet TypeName 的辅助工具。 */
public final class TypeNameUtils {

    private TypeNameUtils() {
    }

    /**
     * 返回字段类型对应的 JDBC 结果集 getter 方法名。
     *
     * @param typeName 字段类型字符串，如 {@code "long"}、{@code "java.lang.String"}
     * @return getter 方法名，如 {@code "getLong"}、{@code "getString"}；没有专用 getter 的类型
     *         （LocalDate 等）返回 {@code "getObject"}
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
                return "getObject"; // LocalDate/LocalDateTime/enums 等
        }
    }

    /**
     * 返回字段类型对应的 JDBC 预编译语句 setter 方法名。
     *
     * @param typeName 字段类型字符串，如 {@code "long"}、{@code "java.lang.String"}
     * @return setter 方法名，如 {@code "setLong"}、{@code "setString"}；没有专用 setter 的类型
     *         返回 {@code "setObject"}
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
     * 返回字段类型对应的 JDBC 生成键读取器后缀：{@code getXxx} 方法名去掉开头的
     * {@code "get"}（如 {@code "getLong"} → {@code "Long"}、{@code "getString"} → {@code "String"}）。
     * 用于生成 {@code keys.getXxx(1)} 回写表达式。
     *
     * @param typeName 字段类型字符串
     * @return 生成键读取器的后缀
     */
    public static String jdbcReturnSuffix(String typeName) {
        return jdbcGetter(typeName).substring(3);
    }

    /**
     * 把 TypeMirror 转换为不带 TYPE_USE 注解的 JavaPoet TypeName。
     * JavaPoet 原生处理基本类型、数组、泛型、通配符和类型变量等全部类型种类。
     *
     * @param type 类型镜像
     * @param types 类型工具，用于深度剥离 TYPE_USE 注解
     * @return 对应的 JavaPoet 类型（不含类型注解）
     */
    public static TypeName toTypeNameWithGenerics(TypeMirror type, Types types) {
        return TypeName.get(types.stripAnnotations(type));
    }

}

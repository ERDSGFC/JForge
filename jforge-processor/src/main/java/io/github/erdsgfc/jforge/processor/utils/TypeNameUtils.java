package io.github.erdsgfc.jforge.processor.utils;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import static io.github.erdsgfc.jforge.processor.ClassEnum.NULLABLE;
import static io.github.erdsgfc.jforge.processor.ClassEnum.NON_NULL;

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
        return switch (typeName) {
            case "long", "java.lang.Long" -> "getLong";
            case "int", "java.lang.Integer" -> "getInt";
            case "java.lang.String" -> "getString";
            case "boolean", "java.lang.Boolean" -> "getBoolean";
            case "double", "java.lang.Double" -> "getDouble";
            case "float", "java.lang.Float" -> "getFloat";
            case "short", "java.lang.Short" -> "getShort";
            case "byte", "java.lang.Byte" -> "getByte";
            case "java.math.BigDecimal" -> "getBigDecimal";
            case "java.sql.Timestamp" -> "getTimestamp";
            case "java.sql.Date" -> "getDate";
            case "java.sql.Time" -> "getTime";
            case "java.sql.Array" -> "getArray";
            case "java.sql.Blob" -> "getBlob";
            case "java.sql.Clob" -> "getClob";
            case "java.sql.NClob" -> "getNClob";
            case "java.sql.Ref" -> "getRef";
            case "java.sql.RowId" -> "getRowId";
            case "java.sql.SQLXML" -> "getSQLXML";
            case "java.net.URL" -> "getURL";
            case "byte[]" -> "getBytes";
            default -> "getObject"; // LocalDate/LocalDateTime/enums 等
        };
    }

    /**
     * 返回字段类型对应的 JDBC 预编译语句 setter 方法名。
     *
     * @param typeName 字段类型字符串，如 {@code "long"}、{@code "java.lang.String"}
     * @return setter 方法名，如 {@code "setLong"}、{@code "setString"}；没有专用 setter 的类型
     *         返回 {@code "setObject"}
     */
    public static String jdbcSetter(String typeName) {
        return switch (typeName) {
            case "long", "java.lang.Long" -> "setLong";
            case "int", "java.lang.Integer" -> "setInt";
            case "java.lang.String" -> "setString";
            case "boolean", "java.lang.Boolean" -> "setBoolean";
            case "double", "java.lang.Double" -> "setDouble";
            case "float", "java.lang.Float" -> "setFloat";
            case "short", "java.lang.Short" -> "setShort";
            case "byte", "java.lang.Byte" -> "setByte";
            case "java.math.BigDecimal" -> "setBigDecimal";
            case "java.sql.Timestamp" -> "setTimestamp";
            case "java.sql.Date" -> "setDate";
            case "java.sql.Time" -> "setTime";
            case "java.sql.Array" -> "setArray";
            case "java.sql.Blob" -> "setBlob";
            case "java.sql.Clob" -> "setClob";
            case "java.sql.NClob" -> "setNClob";
            case "java.sql.Ref" -> "setRef";
            case "java.sql.RowId" -> "setRowId";
            case "java.sql.SQLXML" -> "setSQLXML";
            case "java.net.URL" -> "setURL";
            case "byte[]" -> "setBytes";
            default -> "setObject";
        };
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

    /**
     * 深度剥离源 TYPE_USE 注解后，按声明处的 JSpecify 空性给最外层引用类型补标注。
     */
    public static TypeName toTypeNameWithNullability(TypeMirror type, Element declaration, Types types) {
        return withNullability(toTypeNameWithGenerics(type, types),
                Nullability.isNullable(declaration, type));
    }

    /** 给引用类型添加明确的 JSpecify 空性标注；基本类型和 {@code void} 保持不变。 */
    public static TypeName withNullability(TypeName type, boolean nullable) {
        if (type.isPrimitive() || type.equals(TypeName.VOID)) {
            return type;
        }
        ClassName annotation = nullable ? NULLABLE.getJavaPoetClassName() : NON_NULL.getJavaPoetClassName();
        return type.annotated(AnnotationSpec.builder(annotation).build());
    }

}

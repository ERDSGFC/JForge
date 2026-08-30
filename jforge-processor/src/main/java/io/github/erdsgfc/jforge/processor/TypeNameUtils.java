package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.util.ArrayList;
import java.util.List;

/** TypeMirror 字符串 → JavaPoet TypeName 以及 JDBC 绑定/读取映射的辅助工具。 */
public final class TypeNameUtils {

    private TypeNameUtils() {
    }

    /**
     * 把 TypeMirror 字符串转换为 JavaPoet 类型名。
     *
     * @param typeName 类型字符串，如 {@code "int"}、{@code "byte[]"}、{@code "java.lang.Long"}
     * @return 对应的 JavaPoet 类型（基本类型、数组或类）
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
     * 返回 jdbcGetter 对应的局部变量类型:基本类型 getter(getInt/getLong/…)返回基本类型
     * (可空列的行映射用基本类型局部变量承接,避免包装类装箱);getString/getObject 等
     * 返回原类型。
     *
     * @param typeName 字段类型字符串，如 {@code "java.lang.Integer"}
     * @return 局部变量类型字符串，如 {@code "int"}、{@code "java.lang.String"}
     */
    public static String jdbcVarType(String typeName) {
        String getter = jdbcGetter(typeName);
        if (getter.startsWith("get") && getter.length() > 3) {
            String simple = getter.substring(3).toLowerCase();
            if (simple.equals("int") || simple.equals("long") || simple.equals("boolean")
                    || simple.equals("double") || simple.equals("float")
                    || simple.equals("short") || simple.equals("byte")) {
                return simple;
            }
        }
        return typeName;
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
     * 返回基本类型的包装类型名（用于 setter 强制转换）。
     *
     * @param typeName 类型字符串，如 {@code "int"}
     * @return 包装类型名，如 {@code "Integer"}；非基本类型原样返回
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
     * 把 TypeMirror 转换为 JavaPoet TypeName，保留泛型参数（如 {@code List<UserEntity>}）。
     *
     * @param type 类型镜像
     * @return 对应的 JavaPoet 类型
     */
    public static TypeName toTypeNameWithGenerics(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) type;
            if (!declared.getTypeArguments().isEmpty()) {
                List<TypeName> args = new ArrayList<>();
                for (TypeMirror arg : declared.getTypeArguments()) {
                    args.add(toTypeNameWithGenerics(arg));
                }
                return ParameterizedTypeName.get(
                        ClassName.get((TypeElement) declared.asElement()), args.toArray(new TypeName[0]));
            }
            // 非泛型直接由元素构造——不经 toString（TypeMirror.toString 会输出
            // TYPE_USE 注解前缀如 "java.lang.@org.jspecify.annotations.Nullable Integer"，
            // bestGuess 无法解析）。
            return ClassName.get((TypeElement) declared.asElement());
        }
        return TypeNameUtils.toTypeName(type.toString());
    }

    /**
     * 返回类型镜像的"干净"类型名字符串（全限定名），供 JDBC 绑定/读取 API 映射——
     * 剥除 TYPE_USE 注解：javac 的 {@code TypeMirror.toString()} 会把类型注解
     * 拼进字符串（如 {@code java.lang.@org.jspecify.annotations.Nullable Integer}），
     * 与 {@link #jdbcSetter}/{@link #jdbcGetter} 的精确匹配不兼容。
     *
     * @param type 类型镜像（可能带 TYPE_USE 注解）
     * @return 无注解的全限定类型名，如 {@code "java.lang.Integer"}、{@code "java.lang.List<java.lang.String>"}
     */
    public static String plainTypeName(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) type;
            String qualified = ((TypeElement) declared.asElement()).getQualifiedName().toString();
            if (declared.getTypeArguments().isEmpty()) {
                return qualified;
            }
            StringBuilder sb = new StringBuilder(qualified).append('<');
            for (int i = 0; i < declared.getTypeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(plainTypeName(declared.getTypeArguments().get(i)));
            }
            return sb.append('>').toString();
        }
        return type.toString(); // 基本类型/数组无 TYPE_USE 注解，toString 干净
    }
}

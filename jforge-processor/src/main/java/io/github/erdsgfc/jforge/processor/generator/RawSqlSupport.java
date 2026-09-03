package io.github.erdsgfc.jforge.processor.generator;

import io.github.erdsgfc.jforge.annotation.DialectSupport;
import io.github.erdsgfc.jforge.annotation.JForgeSql;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.annotation.processing.Messager;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @Condition(rawSql)} 和 {@code @UpdateSet(rawSql)} 声明共用的解析与校验工具。
 *
 * <p>标量参数保留原有的单个 {@code ?} 占位符语法。class 和 record 参数使用命名的
 * {@code :fieldName} 占位符；每个占位符都会被替换为 {@code ?}，并按其在 SQL 中出现的
 * 顺序生成绑定。对象字段只解析声明类型的直接字段或 record component，不进行递归展开。</p>
 */
final class RawSqlSupport {
    /** 一个 rawSql 占位符对应的 JDBC 绑定信息。 */
    record Binding(String expression, String typeName, boolean nullable) {}

    /** 已转换占位符的 SQL 文本及按出现顺序排列的绑定信息。 */
    record Plan(String sql, List<Binding> bindings) {
        /** 返回 SQL 中是否至少存在一个值占位符。 */
        boolean hasBindings() { return !bindings.isEmpty(); }
    }

    private RawSqlSupport() {}

    /**
     * 解析 rawSql，校验参数形态，并解析对象字段的访问表达式。
     *
     * @param rawSql 注解中的原始 SQL 片段
     * @param parameterType 被标注参数或字段的类型
     * @param declaration 用于查找 JSpecify 空性标记的声明元素
     * @param rootExpression 指向标量值或对象值的 Java 表达式
     * @param requireJForgeSql 是否要求对象参数标注 {@link JForgeSql}
     * @param messager 编译器诊断信息输出器
     * @param types 编译器类型工具
     * @param method 诊断信息所属的方法元素
     * @param dialect 当前实体使用的数据库方言
     * @return 解析后的计划；报告编译错误后返回 {@code null}
     */
    static Plan resolve(String rawSql, TypeMirror parameterType, Element declaration,
            String rootExpression, boolean requireJForgeSql, Messager messager, Types types,
            ExecutableElement method, DialectSupport dialect) {
        SqlCodegen.PlaceholderResult placeholders = SqlCodegen.parsePlaceholders(rawSql, dialect);
        String sql = placeholders.sql();
        List<String> names = placeholders.names();
        int questionMarks = placeholders.explicitQuestionMarks();
        if (names.isEmpty()) {
            if (questionMarks > 1) {
                error(messager, method, "rawSql scalar parameters support only one '?' placeholder; "
                        + "use a class/record with named :field placeholders");
                return null;
            }
            if (questionMarks == 0) {
                return new Plan(rawSql, List.of());
            }
            TypeMirror type = types.stripAnnotations(parameterType);
            return new Plan(rawSql, List.of(binding(rootExpression, type, declaration, types)));
        }
        if (questionMarks > 0) {
            error(messager, method, "rawSql object parameters cannot mix named :field and '?' placeholders");
            return null;
        }
        TypeMirror type = types.stripAnnotations(parameterType);
        if (!isObjectType(type)) {
            error(messager, method, "named rawSql placeholders require a class or record parameter");
            return null;
        }
        TypeElement typeElement = (TypeElement) ((DeclaredType) type).asElement();
        if (requireJForgeSql && typeElement.getAnnotation(JForgeSql.class) == null) {
            error(messager, method, "rawSql object type must be annotated with @JForgeSql: " + type);
            return null;
        }
        Map<String, VariableElement> fields = directFields(typeElement);
        List<Binding> bindings = new ArrayList<>();
        for (String name : names) {
            VariableElement field = fields.get(name);
            if (field == null) {
                error(messager, method, "rawSql placeholder :" + name
                        + " does not match a direct field/component of " + type);
                return null;
            }
            if (field.getAnnotation(Where.class) != null) {
                error(messager, method, "rawSql object fields cannot use @Where or recurse: " + name);
                return null;
            }
            TypeMirror fieldType = types.stripAnnotations(field.asType());
            if (isObjectType(fieldType)
                    && ((TypeElement) ((DeclaredType) fieldType).asElement()).getAnnotation(JForgeSql.class) != null) {
                error(messager, method, "rawSql object fields cannot recurse into @JForgeSql type: " + name);
                return null;
            }
            String accessor = readAccessor(typeElement, field, method, messager);
            if (accessor == null) return null;
            String expression = rootExpression + "." + accessor;
            bindings.add(binding(expression, field.asType(), field, types));
        }
        return new Plan(sql, List.copyOf(bindings));
    }

    /** 移除 TYPE_USE 注解后，根据源类型构建一个绑定项。 */
    private static Binding binding(String expression, TypeMirror type, Element declaration,
            Types types) {
        TypeMirror plain = types.stripAnnotations(type);
        boolean primitive = plain.getKind().isPrimitive();
        return new Binding(expression, plain.toString(), !primitive && Nullability.isNullable(declaration, type));
    }

    /** 返回 {@code type} 直接声明的非静态字段，并保留源代码声明顺序。 */
    private static Map<String, VariableElement> directFields(TypeElement type) {
        Map<String, VariableElement> fields = new LinkedHashMap<>();
        for (Element element : type.getEnclosedElements()) {
            if (element.getKind() == ElementKind.FIELD) {
                VariableElement field = (VariableElement) element;
                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    fields.put(field.getSimpleName().toString(), field);
                }
            }
        }
        return fields;
    }

    /**
     * 解析生成代码使用的访问方式：JavaBean getter、record 风格 accessor、布尔类型的
     * {@code isXxx} 方法，或者 public 字段。
     */
    private static String readAccessor(TypeElement type, VariableElement field, ExecutableElement method,
            Messager messager) {
        String name = field.getSimpleName().toString();
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) continue;
            String candidate = enclosed.getSimpleName().toString();
            if (candidate.equals(getter) || candidate.equals(name)
                    || (candidate.startsWith("is") && candidate.substring(2).equalsIgnoreCase(name))) {
                return candidate + "()";
            }
        }
        if (field.getModifiers().contains(Modifier.PUBLIC)) {
            return name;
        }
        messager.printMessage(javax.tools.Diagnostic.Kind.ERROR, "rawSql object type " + type.getQualifiedName()
                + " is missing a getter/accessor for field '" + name + "'");
        return null;
    }

    /** 返回类型是否为 rawSql 支持的对象参数类型。 */
    private static boolean isObjectType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        ElementKind kind = ((TypeElement) ((DeclaredType) type).asElement()).getKind();
        return kind == ElementKind.CLASS || kind == ElementKind.RECORD;
    }

    /** 将 rawSql 校验错误报告到对应的注解方法。 */
    private static void error(Messager messager, ExecutableElement method, String message) {
        messager.printMessage(javax.tools.Diagnostic.Kind.ERROR, message, method);
    }

}

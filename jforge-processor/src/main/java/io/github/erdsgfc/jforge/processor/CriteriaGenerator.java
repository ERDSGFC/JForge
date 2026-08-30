package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.And;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Or;
import io.github.erdsgfc.jforge.annotation.Where;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * 展开 {@link Where} 条件对象参数为 WHERE 片段——编译期递归解析条件对象字段：
 *
 * <ul>
 *   <li>值类型字段 → 单条件 {@code 列 op ?}（字段名经命名策略映射列；{@link Condition}
 *       可指定字段与操作符；字段值为 {@code null} 时跳过）；</li>
 *   <li>{@code Optional}/{@code OptionalInt}/{@code OptionalLong} 字段 →
 *       {@code isEmpty()} 时生成 {@code 列 IS NULL}（显式空值查询）、有值时生成条件；</li>
 *   <li>自定义类字段（非 JDK 值类型）→ 括号分组 {@code ( ... )}，递归展开，
 *       连接方式由字段上的 {@link And}/{@link Or} 指定（缺省 AND）。</li>
 * </ul>
 *
 * <p>生成代码与动态 WHERE 同一形态：where 前缀变量维护连接符（首个条件得
 * {@code WHERE}、其后按连接符），null 跳过与 Optional 分支在拼接/绑定两阶段
 * 各自展开一次。</p>
 */
final class CriteriaGenerator {

    /** 条件对象类型字段 → 展开单元。 */
    static final class Unit {
        final String conn;          // 与上一条件的连接符（" AND "/" OR "；首个 "")
        final String column;        // 列名（值条件）；null = 嵌套组
        final String op;            // 操作符 SQL（"="/">"/"IS NULL"…）
        final String readExpr;      // 值读取表达式（criteria.getName()；Optional 为 getter 调用）
        final String valueExpr;     // 绑定值表达式（Optional 的 .get()/.getAsInt()；null = 用 readExpr）
        final String bindType;      // 绑定类型（Optional 剥离类型实参后）
        final String guard;         // 前置判断表达式（null 检查/Optional != null）；null = 恒真
        final boolean optional;     // Optional 语义（空 → IS NULL）
        final List<Unit> nested;    // 嵌套组单元（非 null = 括号分组）
        final String rawSql;        // 原生 SQL 片段（非 null 替代 column/op；含 ? 绑定字段值）

        Unit(String conn, String column, String op, String readExpr, String valueExpr,
                String bindType, String guard, boolean optional, List<Unit> nested, String rawSql) {
            this.conn = conn;
            this.column = column;
            this.op = op;
            this.readExpr = readExpr;
            this.valueExpr = valueExpr;
            this.bindType = bindType;
            this.guard = guard;
            this.optional = optional;
            this.nested = nested;
            this.rawSql = rawSql;
        }
    }

    private final javax.annotation.processing.Messager messager;
    private final Diagnostic.Kind errorKind;
    /** 嵌套括号组的组内前缀变量名序号（单线程处理器，自增即可唯一）。 */
    private int varSeq;

    CriteriaGenerator(javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind) {
        this.messager = messager;
        this.errorKind = errorKind;
    }

    /**
     * 解析 {@code @Where} 参数的条件对象类型为展开单元列表。
     *
     * @param info      仓库信息（宿主实体列映射）
     * @param method    仓库方法（报错定位）
     * @param parameter @Where 参数
     * @return 展开单元；解析失败已报错返回 {@code null}
     */
    List<Unit> parse(JForgeProcessor.DaoInfo info, ExecutableElement method, VariableElement parameter) {
        TypeMirror type = parameter.asType();
        if (type.getKind() != TypeKind.DECLARED) {
            error(method, "@Where parameter must be a criteria object type: " + type);
            return null;
        }
        return parseType(info, method, (TypeElement) ((DeclaredType) type).asElement(), "criteria");
    }

    /**
     * 追加条件对象各字段的拼接代码（与动态 WHERE 的 where 前缀变量机制一致）。
     *
     * @param spec     方法构建器
     * @param units    展开单元
     * @param whereVar where 前缀变量名（" WHERE " 初值，调用方声明）
     * @param nextConn 该片段之后的连接符（下一片段的 conn 或 " AND "）
     */
    void emitAppend(MethodSpec.Builder spec, List<Unit> units, String whereVar, String nextConn) {
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            String after = i + 1 < units.size() ? units.get(i + 1).conn : nextConn;
            emitUnitAppend(spec, unit, whereVar, after);
        }
    }

    /** 追加绑定代码（与拼接同条件展开，索引变量递增）。 */
    void emitBind(MethodSpec.Builder spec, List<Unit> units, String indexVar) {
        for (Unit unit : units) {
            emitUnitBind(spec, unit, indexVar);
        }
    }

    // ---- 解析 ----------------------------------------------------------------

    private List<Unit> parseType(JForgeProcessor.DaoInfo info, ExecutableElement method,
            TypeElement criteriaType, String accessor) {
        List<Unit> units = new ArrayList<>();
        boolean first = true;
        for (Element enclosed : criteriaType.getEnclosedElements()) {
            if (enclosed.getKind() != javax.lang.model.element.ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            String conn = first ? "" : (field.getAnnotation(Or.class) != null ? " OR " : " AND ");
            first = false;
            String readExpr = accessor + "." + readMethodName(criteriaType, field, method);
            if (readExpr == null) {
                return null;
            }
            Unit unit = parseField(info, method, field, conn, readExpr);
            if (unit == null) {
                return null;
            }
            units.add(unit);
        }
        return units;
    }

    private Unit parseField(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement field, String conn, String readExpr) {
        TypeMirror fieldType = field.asType();
        Condition condition = field.getAnnotation(Condition.class);

        // 原生 SQL 条件：rawSql 非空直接使用（跳过列映射），含 ? 绑定字段值。
        String rawSql = condition != null ? condition.rawSql() : "";
        if (!rawSql.isEmpty()) {
            if (isOptional(fieldType)) {
                error(method, "@Condition rawSql on Optional field is not supported: "
                        + field.getSimpleName());
                return null;
            }
            return new Unit(conn, null, null, readExpr,
                    rawSql.contains("?") ? readExpr : null,
                    rawSql.contains("?") ? TypeNameUtils.plainTypeName(fieldType) : null,
                    readExpr + " != null", false, null, rawSql);
        }

        // Optional 族：空 → IS NULL，有值 → 条件（列名取 @Condition.value 或字段名）。
        if (isOptional(fieldType)) {
            String column = findColumn(info, method, condition, field.getSimpleName().toString());
            if (column == null) {
                return null;
            }
            String op = condition != null ? condition.op().sql() : "=";
            return new Unit(conn, column, op, readExpr,
                    readExpr + optionalValueMethod(fieldType), optionalValueType(fieldType),
                    readExpr + " != null", true, null, null);
        }
        // 嵌套组：自定义类字段 → 括号递归。
        if (isNestedType(fieldType)) {
            List<Unit> nested = parseType(info, method,
                    (TypeElement) ((DeclaredType) fieldType).asElement(), readExpr);
            if (nested == null) {
                return null;
            }
            return new Unit(conn, null, null, readExpr, null, null,
                    readExpr + " != null", false, nested, null);
        }
        // 值条件：列名 = @Condition.value 或字段名；null 跳过。
        String column = findColumn(info, method, condition, field.getSimpleName().toString());
        if (column == null) {
            return null;
        }
        String op = condition != null ? condition.op().sql() : "=";
        String guard = readExpr + " != null";
        return new Unit(conn, column, op, readExpr, null, TypeNameUtils.plainTypeName(fieldType),
                guard, false, null, null);
    }

    private String findColumn(JForgeProcessor.DaoInfo info, ExecutableElement method,
            Condition condition, String fieldName) {
        String entityField = condition != null && !condition.value().isEmpty()
                ? condition.value() : fieldName;
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(entityField)) {
                return column.columnName;
            }
        }
        error(method, "@Where field '" + fieldName + "' does not match any field of entity "
                + info.model.entityQualifiedName());
        return null;
    }

    /** 条件对象字段的读取方法名：getXxx()（getter 惯例）或 xxx()（record accessor）。 */
    private String readMethodName(TypeElement criteriaType, VariableElement field,
            ExecutableElement method) {
        String name = field.getSimpleName().toString();
        String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Element enclosed : criteriaType.getEnclosedElements()) {
            if (enclosed.getKind() != javax.lang.model.element.ElementKind.METHOD) {
                continue;
            }
            String methodName = enclosed.getSimpleName().toString();
            if (methodName.equals(getter) || methodName.equals(name)
                    || (methodName.startsWith("is") && methodName.substring(2).equalsIgnoreCase(name))) {
                return methodName + "()";
            }
        }
        error(method, "@Where criteria class " + criteriaType.getQualifiedName()
                + " is missing a getter for field '" + name + "' (expected " + getter
                + "(), " + name + "() or is" + Character.toUpperCase(name.charAt(0))
                + name.substring(1) + "())");
        return null;
    }

    /** Optional/OptionalInt/OptionalLong/OptionalDouble。 */
    static boolean isOptional(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        String name = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        return name.equals("java.util.Optional") || name.equals("java.util.OptionalInt")
                || name.equals("java.util.OptionalLong") || name.equals("java.util.OptionalDouble");
    }

    /** Optional 的值读取方法（get()/getAsInt()/…）与绑定类型。 */
    static String optionalValueType(TypeMirror type) {
        DeclaredType declared = (DeclaredType) type;
        String name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
        return switch (name) {
            case "java.util.Optional" -> {
                List<? extends TypeMirror> args = declared.getTypeArguments();
                yield args.isEmpty() ? "java.lang.Object" : TypeNameUtils.plainTypeName(args.get(0));
            }
            case "java.util.OptionalInt" -> "int";
            case "java.util.OptionalLong" -> "long";
            case "java.util.OptionalDouble" -> "double";
            default -> throw new IllegalStateException("unreachable: " + name);
        };
    }

    /**
     * 嵌套组判定：枚举与 JDK 值类型（java./javax. 开头，非 Optional）→ 值条件；
     * 其余（用户自定义类）→ 括号分组。
     */
    private static boolean isNestedType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        if (element.getKind() == javax.lang.model.element.ElementKind.ENUM) {
            return false;
        }
        if (isOptional(type)) {
            return false;
        }
        String name = element.getQualifiedName().toString();
        return !(name.startsWith("java.") || name.startsWith("javax."));
    }

    // ---- 生成 ----------------------------------------------------------------

    private void emitUnitAppend(MethodSpec.Builder spec, Unit unit, String whereVar, String after) {
        if (unit.rawSql != null) {
            // 原生 SQL 条件（guard = 字段值非 null 时拼）。
            beginGuard(spec, unit.guard);
            spec.addStatement("$L.append($L).append($S)", "sql", whereVar, " " + unit.rawSql);
            spec.addStatement("$L = $S", whereVar, after);
            endGuard(spec, unit.guard);
            return;
        }
        if (unit.nested != null) {
            // 括号分组：外部连接符 + "(" + 组内独立前缀变量 + 组内单元 + ")"。
            beginGuard(spec, unit.guard);
            spec.addStatement("$L.append($L).append($S)", "sql", whereVar, "(");
            String w = "w" + (++varSeq);
            spec.addStatement("$T $L = $S", ClassName.get("java.lang", "String"), w, "");
            emitAppend(spec, unit.nested, w, " AND ");
            spec.addStatement("$L.append($S)", "sql", ")");
            spec.addStatement("$L = $S", whereVar, after);
            endGuard(spec, unit.guard);
            return;
        }
        beginGuard(spec, unit.guard);
        if (unit.optional) {
            // Optional：有值 → 条件；空 → IS NULL（两者都拼接，连接符仅其后置一次）。
            spec.beginControlFlow("if ($L.isPresent())", unit.readExpr);
            spec.addStatement("$L.append($L).append($S)", "sql", whereVar, " " + unit.column + " " + unit.op + " ?");
            spec.nextControlFlow("else");
            spec.addStatement("$L.append($L).append($S)", "sql", whereVar, " " + unit.column + " IS NULL");
            spec.endControlFlow();
        } else {
            spec.addStatement("$L.append($L).append($S)", "sql", whereVar, " " + unit.column + " " + unit.op + " ?");
        }
        spec.addStatement("$L = $S", whereVar, after);
        endGuard(spec, unit.guard);
    }

    private void emitUnitBind(MethodSpec.Builder spec, Unit unit, String indexVar) {
        if (unit.rawSql != null) {
            // 含 ? 才绑定字段值；纯常量条件不绑定。
            if (unit.rawSql.contains("?")) {
                beginGuard(spec, unit.guard);
                spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.readExpr, indexVar));
                spec.addCode("\n");
                endGuard(spec, unit.guard);
            }
            return;
        }
        if (unit.nested != null) {
            beginGuard(spec, unit.guard);
            emitBind(spec, unit.nested, indexVar);
            endGuard(spec, unit.guard);
            return;
        }
        if (unit.optional) {
            // 有值分支绑定（IS NULL 无占位符）。
            beginGuard(spec, unit.guard);
            spec.beginControlFlow("if ($L.isPresent())", unit.readExpr);
            spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.valueExpr, indexVar));
            spec.addCode("\n");
            spec.endControlFlow();
            endGuard(spec, unit.guard);
        } else {
            beginGuard(spec, unit.guard);
            spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.valueExpr != null ? unit.valueExpr : unit.readExpr, indexVar));
            spec.addCode("\n");
            endGuard(spec, unit.guard);
        }
    }

    /** Optional 族的值读取方法：get()/getAsInt()/getAsLong()/getAsDouble()。 */
    static String optionalValueMethod(TypeMirror type) {
        String name = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        return switch (name) {
            case "java.util.Optional" -> ".get()";
            case "java.util.OptionalInt" -> ".getAsInt()";
            case "java.util.OptionalLong" -> ".getAsLong()";
            case "java.util.OptionalDouble" -> ".getAsDouble()";
            default -> throw new IllegalStateException("unreachable: " + name);
        };
    }

    private static void beginGuard(MethodSpec.Builder spec, String guard) {
        if (guard != null) {
            spec.beginControlFlow("if ($L)", guard);
        }
    }

    private static void endGuard(MethodSpec.Builder spec, String guard) {
        if (guard != null) {
            spec.endControlFlow();
        }
    }

    private void error(ExecutableElement method, String message) {
        messager.printMessage(errorKind, message, method);
    }
}

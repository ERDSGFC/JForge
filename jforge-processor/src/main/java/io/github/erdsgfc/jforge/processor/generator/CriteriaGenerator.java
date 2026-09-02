package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.And;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.JForgeSql;
import io.github.erdsgfc.jforge.annotation.Or;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.Nullability;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
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
public final class CriteriaGenerator {

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
        boolean collection;
        boolean array;
        String elementType;

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
            this.collection = false;
            this.array = false;
            this.elementType = null;
        }

        Unit(String conn, String column, String op, String readExpr, String valueExpr,
                String bindType, String guard, boolean optional, List<Unit> nested, String rawSql,
                boolean collection, boolean array, String elementType) {
            this(conn, column, op, readExpr, valueExpr, bindType, guard, optional, nested, rawSql);
            this.collection = collection;
            this.array = array;
            this.elementType = elementType;
        }
    }

    private final javax.annotation.processing.Messager messager;
    private final Diagnostic.Kind errorKind;
    private final Types types;
    /** 嵌套括号组的组内前缀变量名序号（单线程处理器，自增即可唯一）。 */
    private int varSeq;

    CriteriaGenerator(javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind, Types types) {
        this.messager = messager;
        this.errorKind = errorKind;
        this.types = types;
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
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        if (element.getKind() != javax.lang.model.element.ElementKind.CLASS
                && element.getKind() != javax.lang.model.element.ElementKind.RECORD) {
            error(method, "@Where parameter must be a class or record: " + type);
            return null;
        }
        if (element.getAnnotation(JForgeSql.class) == null) {
            error(method, "@Where type must be annotated with @JForgeSql: " + type);
            return null;
        }
        return parseType(info, method, element, parameter.getSimpleName().toString());
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

    /**
     * 以括号分组追加一组条件(条件对象顶层展开与嵌套组共用):{@code ( ... )}——
     * 组内 OR/AND 结构与外层隔离(顶层条件对象与其他条件组合时,组内 @Or 不会
     * 泄漏成 "A OR B AND C")。组内各单元用独立前缀变量 wN("" 初值——首个条件
     * 无前缀,其后按连接符)。空组(组内全部 guard 未命中,只拼了前缀 + "()")
     * 回退到起始长度——不消费外层 where 前缀,不产生非法空括号。
     */
    void emitGroupAppend(MethodSpec.Builder spec, List<Unit> units, String whereVar, String after) {
        int seq = ++varSeq;
        spec.addStatement("int gs$L = sql.length()", seq);
        spec.addStatement("sql.append($L).append($S)", whereVar, "(");
        spec.addStatement("$T w$L = $S", ClassName.get("java.lang", "String"), seq, "");
        emitAppend(spec, units, "w" + seq, " AND ");
        spec.addStatement("sql.append($S)", ")");
        // 空组回退:内容仅前缀 + "()" 时撤销,where 前缀未被消费。
        spec.beginControlFlow("if (sql.length() == gs$L + $L.length() + 2)", seq, whereVar);
        spec.addStatement("sql.setLength(gs$L)", seq);
        spec.nextControlFlow("else");
        spec.addStatement("$L = $S", whereVar, after);
        spec.endControlFlow();
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
        if (condition != null && field.getAnnotation(Where.class) != null) {
            error(method, "A @Where field cannot also be annotated with @Condition: " + field.getSimpleName());
            return null;
        }

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
                    rawSql.contains("?") ? types.stripAnnotations(fieldType).toString() : null,
                    Nullability.isNullable(field, fieldType) ? readExpr + " != null" : null,
                    false, null, rawSql);
        }

        // Optional 族：空 → IS NULL，有值 → 条件（列名取 @Condition.value 或字段名）。
        if (isOptional(fieldType)) {
            String column = findColumn(info, method, condition, field.getSimpleName().toString());
            if (column == null) {
                return null;
            }
            String op = condition != null ? condition.op().sql() : "=";
            return new Unit(conn, column, op, readExpr,
                    readExpr + optionalValueMethod(fieldType), optionalValueType(fieldType, types),
                    Nullability.isNullable(field, fieldType) ? readExpr + " != null" : null,
                    true, null, null);
        }
        boolean array = fieldType.getKind() == TypeKind.ARRAY;
        boolean collection = !array && isIterable(fieldType);
        if (array || collection) {
            if (array && ((javax.lang.model.type.ArrayType) fieldType).getComponentType().getKind() == TypeKind.ARRAY) {
                error(method, "Multi-dimensional array @Where fields are not supported");
                return null;
            }
            if (condition != null && condition.op() != io.github.erdsgfc.jforge.annotation.Op.EQ) {
                error(method, "Iterable/array @Where fields only support @Condition(op = EQ)");
                return null;
            }
            String column = findColumn(info, method, condition, field.getSimpleName().toString());
            if (column == null) return null;
            String elementType = array ? ((javax.lang.model.type.ArrayType) fieldType).getComponentType().toString()
                    : iterableElementType(fieldType);
            return new Unit(conn, column, "=", readExpr, null, elementType,
                    Nullability.isNullable(field, fieldType) ? readExpr + " != null" : null,
                    false, null, null, collection, array, elementType);
        }
        // 嵌套组仅由显式 @Where 触发，避免把普通自定义值类型误判为条件组。
        if (field.getAnnotation(Where.class) != null) {
            if (fieldType.getKind() != TypeKind.DECLARED) {
                error(method, "@Where field must be a class or record: " + field.getSimpleName());
                return null;
            }
            TypeElement nestedType = (TypeElement) ((DeclaredType) fieldType).asElement();
            if (nestedType.getAnnotation(JForgeSql.class) == null) {
                error(method, "Nested @Where type must be annotated with @JForgeSql: " + fieldType);
                return null;
            }
            List<Unit> nested = parseType(info, method,
                    nestedType, readExpr);
            if (nested == null) {
                return null;
            }
            return new Unit(conn, null, null, readExpr, null, null,
                    Nullability.isNullable(field, fieldType) ? readExpr + " != null" : null,
                    false, nested, null);
        }
        // 值条件：列名 = @Condition.value 或字段名；null 跳过。
        String column = findColumn(info, method, condition, field.getSimpleName().toString());
        if (column == null) {
            return null;
        }
        String op = condition != null ? condition.op().sql() : "=";
        String guard = Nullability.isNullable(field, fieldType) ? readExpr + " != null" : null;
        return new Unit(conn, column, op, readExpr, null, types.stripAnnotations(fieldType).toString(),
                guard, false, null, null);
    }

    private String findColumn(JForgeProcessor.DaoInfo info, ExecutableElement method,
            Condition condition, String fieldName) {
        String entityField = condition != null && !condition.value().isEmpty()
                ? condition.value() : fieldName;
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(entityField)) {
                return SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName);
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
    static String optionalValueType(TypeMirror type, Types types) {
        DeclaredType declared = (DeclaredType) type;
        String name = ((TypeElement) declared.asElement()).getQualifiedName().toString();
        return switch (name) {
            case "java.util.Optional" -> {
                List<? extends TypeMirror> args = declared.getTypeArguments();
                yield args.isEmpty() ? "java.lang.Object" : types.stripAnnotations(args.getFirst()).toString();
            }
            case "java.util.OptionalInt" -> "int";
            case "java.util.OptionalLong" -> "long";
            case "java.util.OptionalDouble" -> "double";
            default -> throw new IllegalStateException("unreachable: " + name);
        };
    }

    private boolean isIterable(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
        if (element.getQualifiedName().contentEquals("java.lang.Iterable")) return true;
        return types.directSupertypes(type).stream().anyMatch(this::isIterable);
    }

    private String iterableElementType(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return "java.lang.Object";
        List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
        return args.isEmpty() ? "java.lang.Object" : types.stripAnnotations(args.getFirst()).toString();
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
            // 括号分组(含空组回退)由 emitGroupAppend 统一处理。
            beginGuard(spec, unit.guard);
            emitGroupAppend(spec, unit.nested, whereVar, after);
            endGuard(spec, unit.guard);
            return;
        }
        beginGuard(spec, unit.guard);
        if (unit.collection || unit.array) {
            if (unit.collection) {
                // 集合:直接遍历 getter 返回值拼占位符,零临时内存。占位符先入局部缓冲
                // (空集判定在循环后才知道:空 → 1 = 0,不能先拼 "IN (")。
                int seq = ++varSeq;
                spec.addStatement("$T inSql_$L = new $T()", ClassName.get(StringBuilder.class),
                        seq, ClassName.get(StringBuilder.class));
                spec.addStatement("int idx_$L = 0", seq);
                spec.beginControlFlow("for ($L value : $L)", unit.elementType, unit.readExpr);
                spec.beginControlFlow("if (idx_$L > 0)", seq);
                spec.addStatement("inSql_$L.append($S)", seq, ",?");
                spec.nextControlFlow("else");
                spec.addStatement("inSql_$L.append($S)", seq, "?");
                spec.endControlFlow();
                spec.addStatement("idx_$L++", seq);
                spec.endControlFlow();
                spec.beginControlFlow("if (idx_$L == 0)", seq);
                spec.addStatement("sql.append($L).append($S)", whereVar, " 1 = 0");
                spec.nextControlFlow("else");
                spec.addStatement("sql.append($L).append($S).append(inSql_$L).append($S)",
                        whereVar, " " + unit.column + " IN (", seq, ")");
                spec.endControlFlow();
            } else {
                // 数组:长度循环前可知——判空后首项外提拼占位符。
                spec.beginControlFlow("if ($L.length == 0)", unit.readExpr);
                spec.addStatement("sql.append($L).append($S)", whereVar, " 1 = 0");
                spec.nextControlFlow("else");
                spec.addStatement("sql.append($L).append($S)", whereVar, " " + unit.column + " IN (?");
                spec.beginControlFlow("for (int j = 1; j < $L.length; j++)", unit.readExpr);
                spec.addStatement("sql.append($S)", ",?");
                spec.endControlFlow();
                spec.addStatement("sql.append($S)", ")");
                spec.endControlFlow();
            }
        } else if (unit.optional) {
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
                spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.readExpr, indexVar,
                        false, false, null));
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
        if (unit.collection || unit.array) {
            // 直接遍历 getter 返回值绑值(零临时内存;空集时循环 0 次,与拼接阶段的
            // 1 = 0 分支一致——无占位符可绑)。
            beginGuard(spec, unit.guard);
            spec.beginControlFlow("for ($L value : $L)", unit.elementType, unit.readExpr);
            spec.addCode(SqlCodegen.bindParam(unit.bindType, "value", "i++", true, false, null));
            spec.addCode("\n");
            spec.endControlFlow();
            endGuard(spec, unit.guard);
            return;
        }
        if (unit.optional) {
            // 有值分支绑定（IS NULL 无占位符）。
            beginGuard(spec, unit.guard);
            spec.beginControlFlow("if ($L.isPresent())", unit.readExpr);
            spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.valueExpr, indexVar,
                    false, false, null));
            spec.addCode("\n");
            spec.endControlFlow();
            endGuard(spec, unit.guard);
        } else {
            beginGuard(spec, unit.guard);
            spec.addCode(SqlCodegen.bindParam(unit.bindType,
                    unit.valueExpr != null ? unit.valueExpr : unit.readExpr, indexVar,
                    false, false, null));
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

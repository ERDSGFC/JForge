package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.*;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Map;

/**
 * 已解析的 WHERE 条件节点，供 {@code @Select}/{@code @Update}/{@code @Delete}/{@code @Query}
 * 生成器复用——覆盖普通列条件、{@code Optional}（IS NULL）、{@code rawSql} 原生片段、
 * 数组/集合（{@code IN}/{@code NOT IN}）与转换器绑定五类形态。
 *
 * @param columnName      WHERE 中使用的列名（已按方言引用符包裹；{@code null} = rawSql 条件）
 * @param op              操作符 SQL 片段（{@code "="}/{@code ">"}/{@code "IN"}/{@code "NOT IN"}/{@code "LIKE"}…）
 * @param paramName       参数名（绑定表达式）
 * @param typeName        绑定类型——数组/集合为元素类型、Optional 剥离类型实参后、普通参数为声明类型
 * @param dynamic         参数可空（JSpecify {@code @Nullable} 或非 {@code @NullMarked} 作用域默认）
 *                        → 运行时为 {@code null} 时整段跳过
 * @param optional        {@code Optional} 参数：{@code isPresent} → 条件；{@code isEmpty} → IS NULL
 * @param valueExpr       Optional 的绑定值表达式（{@code param.get()}/{@code getAsInt()}…）
 * @param rawSql          原生 SQL 条件片段（非 null 替代 column/op 拼装；含 {@code ?} 绑定参数）
 * @param converterField  宿主列 {@code @Convert} 转换器静态字段名（{@code null} = 无转换器）
 * @param collection      {@code Iterable} 参数（{@code List}/{@code Set}/…）→ {@code 列 IN/NOT IN (?,...)}
 * @param array           数组参数（如 {@code Long[]}）→ {@code 列 IN/NOT IN (?,...)}
 * @param elementTypeName 数组/集合的元素类型（生成 IN/NOT IN 绑定局部变量声明的 JavaPoet 类型；
 *                        非集合条件时为 {@code null}）
 */
record WhereCondition(String columnName, String op, String paramName, String typeName, boolean dynamic,
                      boolean optional, String valueExpr, String rawSql, String converterField,
                      boolean collection, boolean array, TypeName elementTypeName,
                      List<RawSqlSupport.Binding> rawBindings) {

    /**
     * 解析绑定到宿主实体列的 {@code @Condition} 参数：字段名取 {@link Condition#value()}
     * （缺省按参数名）、操作符取 {@link Condition#op()}、rawSql 直接透传。参数类型分派：
     * 数组/集合 → IN/NOT IN 条件（支持 EQ/NE；多维数组与 rawSql 组合报错）；Optional → IS NULL
     * 语义；其余 → 普通列条件。动态性按参数 JSpecify 空性判定（基本类型恒静态）。
     *
     * @return 解析结果；校验失败已报错并返回 {@code null}（调用方跳过方法生成）
     */
    static WhereCondition resolveHost(JForgeProcessor.DaoInfo info, ExecutableElement method,
                                      VariableElement parameter, ProcessingEnvironment env,
                                      String diagnosticPrefix) {
        return resolveHost(info, method, parameter, env, diagnosticPrefix,
                Map.of(info.model.entityQualifiedName(), info.model));
    }

    static WhereCondition resolveHost(JForgeProcessor.DaoInfo info, ExecutableElement method,
                                      VariableElement parameter, ProcessingEnvironment env,
                                      String diagnosticPrefix, Map<String, EntityModel> entities) {
        String paramName = parameter.getSimpleName().toString();
        Condition condition = parameter.getAnnotation(Condition.class);
        String fieldName = condition != null && !condition.value().isEmpty()
                ? condition.value() : paramName;
        EntityModel fieldEntity = info.model;
        if (condition != null) {
            TypeMirror entityType = conditionEntity(condition);
            if (entityType != null) {
                fieldEntity = entities.get(entityType.toString());
                if (fieldEntity == null) {
                    env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            diagnosticPrefix + " @Condition(entity) must reference the host entity or a @Join entity",
                            parameter);
                    return null;
                }
            }
        }
        String op = condition != null ? condition.op().sql() : "=";
        String rawSql = condition != null ? condition.rawSql() : "";
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        TypeMirror type = env.getTypeUtils().stripAnnotations(parameter.asType());
        boolean array = type.getKind() == TypeKind.ARRAY;
        boolean collection = !optional && isIterable(type, env);
        if ((array || collection) && condition != null
                && condition.op() != Op.EQ && condition.op() != Op.NE) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Iterable/array WHERE parameters only support @Condition(op = EQ) or @Condition(op = NE)", parameter);
            return null;
        }
        if (array && ((ArrayType) type).getComponentType().getKind() == TypeKind.ARRAY) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Multi-dimensional array WHERE parameters are not supported", parameter);
            return null;
        }
        boolean primitive = type.getKind().isPrimitive();
        boolean dynamic = !primitive && Nullability.isNullableParameter(parameter);
        // 数组/集合的元素类型（生成 IN 绑定局部变量声明用）。
        TypeName elementType = null;
        if (array) {
            elementType = TypeName.get(env.getTypeUtils().stripAnnotations(
                    ((ArrayType) type).getComponentType()));
        } else if (collection) {
            elementType = iterableElementType(type, env);
        }
        if (array || collection) {
            op = condition != null && condition.op() == Op.NE ? "NOT IN" : "IN";
        }
        String bindType = optional ? CriteriaGenerator.optionalValueType(type, env.getTypeUtils())
                : elementType != null ? elementType.toString() : type.toString();
        String valueExpr = optional ? paramName + CriteriaGenerator.optionalValueMethod(type) : null;
        if (!rawSql.isEmpty()) {
            if (collection || array) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Iterable/array parameters cannot be used with @Condition(rawSql)", parameter);
                return null;
            }
            RawSqlSupport.Plan plan = RawSqlSupport.resolve(rawSql, parameter.asType(), parameter,
                    paramName, condition.requireJForgeSql(), env.getMessager(), env.getTypeUtils(), method,
                    info.model.dialectSupport());
            if (plan == null) return null;
            return new WhereCondition(null, op, paramName, bindType, dynamic, optional, valueExpr,
                    plan.sql(), null, false, false, null, plan.bindings());
        }
        for (EntityModel.ColumnModel column : fieldEntity.columns()) {
            if (column.fieldName.equals(fieldName)) {
                String converter = fieldEntity == info.model && column.converter != null
                        ? SqlCodegen.converterFieldName(info.model, column) : null;
                String qualified = fieldEntity == info.model
                        ? SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName)
                        : SqlCodegen.quoteIdentifier(info.model.dialectSupport(), fieldEntity.tableName()) + "."
                                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName);
                return new WhereCondition(qualified,
                        op, paramName, bindType, dynamic, optional, valueExpr, null, converter,
                        collection, array, elementType, List.of());
            }
        }
        env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                diagnosticPrefix + " parameter field '" + fieldName + "' does not match any field of entity "
                        + fieldEntity.entityQualifiedName(), method);
        return null;
    }

    private static TypeMirror conditionEntity(Condition condition) {
        try {
            condition.entity();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror().getKind() == TypeKind.VOID ? null : e.getTypeMirror();
        }
    }

    private static boolean isIterable(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeMirror iterable = env.getElementUtils().getTypeElement("java.lang.Iterable").asType();
        return env.getTypeUtils().isAssignable(env.getTypeUtils().erasure(type),
                env.getTypeUtils().erasure(iterable));
    }

    private static TypeName iterableElementType(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return ClassName.get(Object.class);
        List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
        return args.isEmpty() ? ClassName.get(Object.class)
                : TypeName.get(env.getTypeUtils().stripAnnotations(args.getFirst()));
    }

    /**
     * 条件是否兼容静态 SQL 常量形态（编译期确定 WHERE 子句与绑定索引）：无 null 守卫、
     * 非 Optional、非数组/集合（IN 的占位符数量运行时才知）。注意 Optional 条件不一定
     * "动态"（可能无 null 守卫），但 IS NULL 分支需要运行时判断——同样不兼容静态常量。
     */
    boolean staticCompatible() {
        return !dynamic && !collection && !array && !optional;
    }

    /**
     * 生成一个条件的动态 SQL 拼接代码（动态形态）。
     *
     * <p>IN/NOT IN 条件（数组/集合）的空值语义：元素数为 0 时，IN 拼 {@code 1 = 0}、
     * NOT IN 拼 {@code 1 = 1}；非空时拼 {@code 列 IN/NOT IN (?,?,...)}。集合直接遍历入参拼占位符
     * （零临时内存）——占位符先入局部缓冲，空集判定在循环后才知道（不能先拼
     * {@code "IN ("} 再回退，{@code IN ()} 是非法 SQL）。</p>
     */
    static void appendSql(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            if (c.collection) {
                // 集合:直接遍历入参拼占位符,零临时内存。占位符先拼入局部缓冲
                // inSql_xxx(空集判定在循环后才知道，不能先拼 IN/NOT IN 的左括号。
                spec.addStatement("$T inSql_$L = new $T()", ClassName.get(StringBuilder.class),
                        c.paramName, ClassName.get(StringBuilder.class));
                spec.addStatement("int idx_$L = 0", c.paramName);
                spec.beginControlFlow("for ($T value : $N)", c.elementTypeName, c.paramName);
                spec.beginControlFlow("if (idx_$L > 0)", c.paramName);
                spec.addStatement("inSql_$L.append($S)", c.paramName, ",?");
                spec.nextControlFlow("else");
                spec.addStatement("inSql_$L.append($S)", c.paramName, "?");
                spec.endControlFlow();
                spec.addStatement("idx_$L++", c.paramName);
                spec.endControlFlow();
                spec.beginControlFlow("if (idx_$L == 0)", c.paramName);
                spec.addStatement("sql.append(where).append($S)", emptyCollectionSql(c.op));
                spec.nextControlFlow("else");
                spec.addStatement("sql.append(where).append($S).append(inSql_$L).append($S)",
                        " " + c.columnName + " " + c.op + " (", c.paramName, ")");
                spec.endControlFlow();
            } else {
                // 数组:长度循环前可知(无快照)——判空后首项外提拼占位符。
                spec.beginControlFlow("if ($L == 0)", c.paramName + ".length");
                spec.addStatement("sql.append(where).append($S)", emptyCollectionSql(c.op));
                spec.nextControlFlow("else");
                spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " " + c.op + " (?");
                spec.beginControlFlow("for (int j = 1; j < $L; j++)", c.paramName + ".length");
                spec.addStatement("sql.append($S)", ",?");
                spec.endControlFlow();
                spec.addStatement("sql.append($S)", ")");
                spec.endControlFlow();
            }
        } else if (c.rawSql != null) {
            spec.addStatement("sql.append(where).append($S)", " " + c.rawSql);
        } else if (c.optional) {
            spec.beginControlFlow("if ($N.isPresent())", c.paramName);
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " " + c.op + " ?");
            spec.nextControlFlow("else");
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " IS NULL");
            spec.endControlFlow();
        } else {
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " " + c.op + " ?");
        }
        spec.addStatement("where = $S", " AND ");
        if (c.dynamic) spec.endControlFlow();
    }

    private static String emptyCollectionSql(String op) {
        return "NOT IN".equals(op) ? " 1 = 1" : " 1 = 0";
    }

    /**
     * 生成一个条件的动态参数绑定代码（与 {@link #appendSql} 同条件展开，索引递增）。
     * IN/NOT IN 条件按元素逐个绑定（元素可空走 setObject）；rawSql 无 {@code ?} 的纯常量
     * 条件不绑定；Optional 只在 {@code isPresent} 分支绑定（IS NULL 无占位符）。
     */
    static void appendBind(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            // 集合/数组都直接遍历入参绑值(零临时内存;空集时循环 0 次,与拼接阶段
            // 的 1 = 0 分支一致——无占位符可绑)。
            spec.beginControlFlow("for ($T value : $N)", c.elementTypeName, c.paramName);
            spec.addCode(SqlCodegen.bindParam(c.typeName, "value", "i++", true, false,
                    c.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else if (c.rawSql != null) {
            for (RawSqlSupport.Binding binding : c.rawBindings) {
                spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), "i++",
                        binding.nullable(), false, null));
                spec.addCode("\n");
            }
        } else if (c.optional) {
            spec.beginControlFlow("if ($N.isPresent())", c.paramName);
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.valueExpr, "i++", false, false, c.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else {
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.paramName, "i++", false, false, c.converterField));
            spec.addCode("\n");
        }
        if (c.dynamic) spec.endControlFlow();
    }

    static void appendStaticWhereSql(StringBuilder sql, List<WhereCondition> conditions) {
        if (conditions.isEmpty()) return;
        sql.append(" WHERE ");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) sql.append(" AND ");
            WhereCondition c = conditions.get(i);
            sql.append(c.rawSql != null ? c.rawSql : c.columnName + " " + c.op + " ?");
        }
    }

    static void appendStaticBinds(MethodSpec.Builder spec, List<WhereCondition> conditions, int index) {
        for (WhereCondition c : conditions) {
            if (c.rawSql != null) {
                for (RawSqlSupport.Binding binding : c.rawBindings) {
                    spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), index++,
                            binding.nullable(), false, null));
                    spec.addCode("\n");
                }
                continue;
            }
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.valueExpr != null ? c.valueExpr : c.paramName,
                    index++, false, false, c.converterField));
            spec.addCode("\n");
        }
    }
}

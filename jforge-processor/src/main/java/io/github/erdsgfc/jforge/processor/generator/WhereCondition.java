package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Op;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * 已解析的 WHERE 条件节点，供 {@code @Select}/{@code @Update}/{@code @Delete}/{@code @Query}
 * 生成器复用——覆盖普通列条件、{@code Optional}（IS NULL）、{@code rawSql} 原生片段、
 * 数组/集合（{@code IN}）与转换器绑定五类形态。
 *
 * @param columnName      WHERE 中使用的列名（已按方言引用符包裹；{@code null} = rawSql 条件）
 * @param op              操作符 SQL 片段（{@code "="}/{@code ">"}/{@code "IN"}/{@code "LIKE"}…）
 * @param paramName       参数名（绑定表达式）
 * @param typeName        绑定类型——数组/集合为元素类型、Optional 剥离类型实参后、普通参数为声明类型
 * @param dynamic         参数可空（JSpecify {@code @Nullable} 或非 {@code @NullMarked} 作用域默认）
 *                        → 运行时为 {@code null} 时整段跳过
 * @param optional        {@code Optional} 参数：{@code isPresent} → 条件；{@code isEmpty} → IS NULL
 * @param valueExpr       Optional 的绑定值表达式（{@code param.get()}/{@code getAsInt()}…）
 * @param rawSql          原生 SQL 条件片段（非 null 替代 column/op 拼装；含 {@code ?} 绑定参数）
 * @param converterField  宿主列 {@code @Convert} 转换器静态字段名（{@code null} = 无转换器）
 * @param collection      {@code Iterable} 参数（{@code List}/{@code Set}/…）→ {@code 列 IN (?,...)}
 * @param array           数组参数（如 {@code Long[]}）→ {@code 列 IN (?,...)}
 * @param elementTypeName 数组/集合的元素类型（元素绑定类型；非 IN 条件时为 {@code null}）
 */
record WhereCondition(String columnName, String op, String paramName, String typeName, boolean dynamic,
                      boolean optional, String valueExpr, String rawSql, String converterField,
                      boolean collection, boolean array, String elementTypeName) {

    /**
     * 解析绑定到宿主实体列的 {@code @Condition} 参数：字段名取 {@link Condition#value()}
     * （缺省按参数名）、操作符取 {@link Condition#op()}、rawSql 直接透传。参数类型分派：
     * 数组/集合 → IN 条件（仅支持 EQ；多维数组与 rawSql 组合报错）；Optional → IS NULL
     * 语义；其余 → 普通列条件。动态性按参数 JSpecify 空性判定（基本类型恒静态）。
     *
     * @return 解析结果；校验失败已报错并返回 {@code null}（调用方跳过方法生成）
     */
    static WhereCondition resolveHost(JForgeProcessor.DaoInfo info, ExecutableElement method,
                                      VariableElement parameter, ProcessingEnvironment env,
                                      String diagnosticPrefix) {
        String paramName = parameter.getSimpleName().toString();
        Condition condition = parameter.getAnnotation(Condition.class);
        String fieldName = condition != null && !condition.value().isEmpty()
                ? condition.value() : paramName;
        String op = condition != null ? condition.op().sql() : "=";
        String rawSql = condition != null ? condition.rawSql() : "";
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        TypeMirror type = env.getTypeUtils().stripAnnotations(parameter.asType());
        boolean array = type.getKind() == TypeKind.ARRAY;
        boolean collection = !optional && isIterable(type, env);
        if ((array || collection) && condition != null && condition.op() != Op.EQ) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Iterable/array WHERE parameters only support @Condition(op = EQ)", parameter);
            return null;
        }
        if (array && ((ArrayType) type).getComponentType().getKind() == TypeKind.ARRAY) {
            env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Multi-dimensional array WHERE parameters are not supported", parameter);
            return null;
        }
        boolean primitive = type.getKind().isPrimitive();
        boolean dynamic = !primitive && Nullability.isNullableParameter(parameter);
        // 数组的对象
        String elementType = null;
        if (array) {
            elementType = ((ArrayType) type).getComponentType().toString();
        } else if (collection) {
            elementType = iterableElementType(type, env);
        }
        String bindType = optional ? CriteriaGenerator.optionalValueType(type, env.getTypeUtils())
                : elementType != null ? elementType : type.toString();
        String valueExpr = optional ? paramName + CriteriaGenerator.optionalValueMethod(type) : null;
        if (!rawSql.isEmpty()) {
            if (collection || array) {
                env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Iterable/array parameters cannot be used with @Condition(rawSql)", parameter);
                return null;
            }
            return new WhereCondition(null, op, paramName, bindType, dynamic, optional, valueExpr,
                    rawSql, null, false, false, null);
        }
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(fieldName)) {
                String converter = column.converter != null
                        ? SqlCodegen.converterFieldName(info.model, column) : null;
                return new WhereCondition(SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName),
                        op, paramName, bindType, dynamic, optional, valueExpr, null, converter,
                        collection, array, elementType);
            }
        }
        env.getMessager().printMessage(Diagnostic.Kind.ERROR,
                diagnosticPrefix + " parameter field '" + fieldName + "' does not match any field of entity "
                        + info.model.entityQualifiedName(), method);
        return null;
    }

    private static boolean isIterable(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeMirror iterable = env.getElementUtils().getTypeElement("java.lang.Iterable").asType();
        return env.getTypeUtils().isAssignable(env.getTypeUtils().erasure(type),
                env.getTypeUtils().erasure(iterable));
    }

    private static String iterableElementType(TypeMirror type, ProcessingEnvironment env) {
        if (type.getKind() != TypeKind.DECLARED) return "java.lang.Object";
        List<? extends TypeMirror> args = ((DeclaredType) type).getTypeArguments();
        return args.isEmpty() ? "java.lang.Object" : env.getTypeUtils().stripAnnotations(args.getFirst()).toString();
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
     * <p>IN 条件（数组/集合）的空值语义：元素数为 0 时拼 {@code 1 = 0}（恒假——空集合
     * 匹配任何行都不成立），非空时拼 {@code 列 IN (?,?,...)}——集合先复制到局部
     * {@code sqlValues_xxx} 列表，避免对运行时修改的入参二次迭代时长度不一致。</p>
     */
    static void appendSql(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            String size = c.array ? c.paramName + ".length" : "sqlValues_" + c.paramName + ".size()";
            if (c.collection) {
                spec.addStatement("$T<$L> sqlValues_$L = new $T<>()", List.class,
                        c.elementTypeName, c.paramName, ArrayList.class);
                spec.beginControlFlow("for ($L value : $N)", c.elementTypeName, c.paramName);
                spec.addStatement("sqlValues_$L.add(value)", c.paramName);
                spec.endControlFlow();
            }
            spec.beginControlFlow("if ($L == 0)", size);
            spec.addStatement("sql.append(where).append($S)", " 1 = 0");
            spec.nextControlFlow("else");
            spec.addStatement("sql.append(where).append($S)", " " + c.columnName + " IN (");
            spec.beginControlFlow("for (int j = 0; j < $L; j++)", size);
            spec.addStatement("if (j > 0) sql.append($S)", ", ");
            spec.addStatement("sql.append($S)", "?");
            spec.endControlFlow();
            spec.addStatement("sql.append($S)", ")");
            spec.endControlFlow();
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

    /**
     * 生成一个条件的动态参数绑定代码（与 {@link #appendSql} 同条件展开，索引递增）。
     * IN 条件按元素逐个绑定（元素可空走 setObject）；rawSql 无 {@code ?} 的纯常量
     * 条件不绑定；Optional 只在 {@code isPresent} 分支绑定（IS NULL 无占位符）。
     */
    static void appendBind(MethodSpec.Builder spec, WhereCondition c) {
        if (c.dynamic) spec.beginControlFlow("if ($N != null)", c.paramName);
        if (c.collection || c.array) {
            String size = c.array ? c.paramName + ".length" : "bindValues_" + c.paramName + ".size()";
            if (c.collection) {
                spec.addStatement("$T<$L> bindValues_$L = new $T<>()", List.class,
                        c.elementTypeName, c.paramName, ArrayList.class);
                spec.beginControlFlow("for ($L value : $N)", c.elementTypeName, c.paramName);
                spec.addStatement("bindValues_$L.add(value)", c.paramName);
                spec.endControlFlow();
            }
            spec.beginControlFlow("for (int j = 0; j < $L; j++)", size);
            String expr = c.array ? c.paramName + "[j]" : "bindValues_" + c.paramName + ".get(j)";
            spec.addCode(SqlCodegen.bindParam(c.typeName, expr, "i++", true, false, c.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else if (c.rawSql != null && !c.rawSql.contains("?")) {
            // no binding
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
            if (c.rawSql != null && !c.rawSql.contains("?")) continue;
            spec.addCode(SqlCodegen.bindParam(c.typeName, c.valueExpr != null ? c.valueExpr : c.paramName,
                    index++, false, false, c.converterField));
            spec.addCode("\n");
        }
    }
}

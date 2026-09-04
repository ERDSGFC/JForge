package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 {@code Update} 声明式更新方法：不写 SQL，按参数自动构造
 * {@code UPDATE t SET ... WHERE ...}。
 *
 * <p>{@link UpdateSet} 参数 → SET 列（缺省按参数名映射列；{@code @Nullable} 为 {@code null}
 * 时跳过该 SET；{@code Optional} 空时 {@code SET 列 = NULL}）；{@link Condition} 参数 /
 * {@link Where} 条件对象 → WHERE 条件（动态语义与 {@code @Select} 一致）。生成形态
 * 与动态 WHERE 同一套：全静态 → SQL 常量 + 静态绑定；含动态 → StringBuilder 拼接。</p>
 */
public final class UpdateGenerator {

    /** 一个 SET 单元。 */
    private static final class SetUnit {
        final String column;       // 列名
        final String paramName;    // 绑定表达式:方法参数名,或条件对象 SET 字段的读取表达式(criteria.getX())
        final String bindType;     // 绑定类型（Optional 剥离后）
        final boolean dynamic;     // @Nullable → null 跳过
        final boolean optional;    // Optional：空 → SET NULL
        final String valueExpr;    // Optional 绑定值表达式
        String rawSql;              // 原生 SET 表达式（已转换命名占位符）
        final String converterField; // 宿主列 @Convert 转换器字段（非 null = 值经转换器绑定）
        List<RawSqlSupport.Binding> rawBindings = List.of();

        SetUnit(String column, String paramName, String bindType, boolean dynamic,
                boolean optional, String valueExpr, String rawSql, String converterField) {
            this.column = column;
            this.paramName = paramName;
            this.bindType = bindType;
            this.dynamic = dynamic;
            this.optional = optional;
            this.valueExpr = valueExpr;
            this.rawSql = rawSql;
            this.converterField = converterField;
        }
    }

    private final javax.annotation.processing.ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final CriteriaGenerator criteriaGenerator;

    public UpdateGenerator(javax.annotation.processing.ProcessingEnvironment processingEnv,
                           JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR, processingEnv.getTypeUtils());
    }

    public void updateMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
                              ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        Map<String, Integer> seen = new HashMap<>();
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            int overloadIndex = seen.merge(method.getSimpleName().toString(), 1, Integer::sum) - 1;
            if (method.getAnnotation(Update.class) == null) {
                continue;
            }
            if (method.getAnnotation(Select.class) != null
                    || method.getAnnotation(Query.class) != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Update is mutually exclusive with @Select/@Query on the same method", method);
                continue;
            }
            MethodSpec impl = updateMethod(info, builder, method, overloadIndex, connection, preparedStatement, sqlException);
            if (impl != null) {
                builder.addMethod(impl);
            }
        }
    }

    private MethodSpec updateMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder, ExecutableElement method,
            int overloadIndex, ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        String methodName = method.getSimpleName().toString();

        // 解析 SET 列（@UpdateSet 参数）与 WHERE 条件（@Condition 参数 + @Where 条件对象）。
        List<SetUnit> sets = new ArrayList<>();
        List<WhereCondition> conditions = new ArrayList<>();
        List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(UpdateSet.class) != null) {
                SetUnit unit = resolveSet(info, method, parameter);
                if (unit == null) {
                    return null;
                }
                sets.add(unit);
            } else if (parameter.getAnnotation(Where.class) != null) {
                // 条件对象:顶层 @UpdateSet 字段是 SET 修改列(值表达式 = criteria.getX()),
                // 其余字段是 WHERE 条件。
                List<SetUnit> criteriaSets = resolveCriteriaSets(info, method, parameter);
                if (criteriaSets == null) {
                    return null;
                }
                sets.addAll(criteriaSets);
                List<CriteriaGenerator.Unit> units = criteriaGenerator.parse(info, method, parameter, true);
                if (units == null) {
                    return null;
                }
                criteriaUnits.addAll(units);
            } else {
            WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                    processingEnv, "@Condition");
                if (condition == null) {
                    return null;
                }
                conditions.add(condition);
            }
        }
        if (sets.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Update method must have at least one @UpdateSet parameter", method);
            return null;
        }

        String baseSql = "UPDATE "
                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithNullability(
                        method.getReturnType(), method, processingEnv.getTypeUtils()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithNullability(
                            parameter.asType(), parameter, processingEnv.getTypeUtils()),
                    parameter.getSimpleName().toString());
        }
        boolean logSql = configHelper.logSql(info.element);

        // 全静态：SET 无动态 + WHERE 无动态 + 无 @Where 条件对象 → SQL 常量 + 静态绑定。
        // 静态形态 = 全部 SET/条件无 null 守卫(optional 可进静态——非空契约 opt.get())。
        boolean allStatic = criteriaUnits.isEmpty()
                && sets.stream().noneMatch(s -> s.dynamic)
                && conditions.stream().allMatch(WhereCondition::staticCompatible);
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql + " SET ");
            for (int i = 0; i < sets.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                SetUnit unit = sets.get(i);
                sql.append(unit.rawSql != null ? unit.rawSql : unit.column + " = ?");
            }
            WhereCondition.appendStaticWhereSql(sql, conditions);
            String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
            builder.addField(FieldSpec.builder(String.class, sqlField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", sql.toString()).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            int index = 1;
            for (SetUnit unit : sets) {
                if (unit.rawSql != null) {
                    for (RawSqlSupport.Binding binding : unit.rawBindings) {
                        spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), index++,
                                binding.nullable(), false, null));
                        spec.addCode("\n");
                    }
                    continue;
                }
                // Optional SET(非空契约,静态形态):绑定 valueExpr(opt.get());普通 SET 绑参数。
                spec.addCode(SqlCodegen.bindParam(unit.bindType,
                        unit.valueExpr != null ? unit.valueExpr : unit.paramName,
                        index++, false, false, unit.converterField));
                spec.addCode("\n");
            }
            WhereCondition.appendStaticBinds(spec, conditions, index);
            spec.addStatement("return ps.executeUpdate()");
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                    sql.toString(), logSql);
            return spec.build();
        }

        // 动态形态：sql 变量 + where 前缀变量 + 双阶段 if 展开。
        spec.addStatement("$T conn = getConnection()", connection);
        spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                ClassName.get(StringBuilder.class), baseSql + " SET");
        spec.addStatement("$T setConn = $S", ClassName.get(String.class), "");
        for (SetUnit unit : sets) {
            emitSetAppend(spec, unit, "setConn");
        }
        boolean hasWhere = !conditions.isEmpty() || !criteriaUnits.isEmpty();
        if (hasWhere) {
            spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        }
        for (WhereCondition condition : conditions) {
            WhereCondition.appendSql(spec, condition);
        }
        criteriaGenerator.emitGroupAppend(spec, criteriaUnits, "where", " AND ");
        // 守卫仅在"可能发生"时生成,避免静态条件的死分支:
        // - where 守卫:存在 @Nullable 动态条件(可能整段跳过)或条件对象(组可能全空回退)
        //   时才可能无条件——全静态条件恒拼,守卫恒 false;
        // - setConn 守卫:存在 dynamic SET(null 跳过)时才可能 SET 为空——静态 SET 恒拼。
        if (conditions.stream().anyMatch(c -> c.dynamic()) || !criteriaUnits.isEmpty()) {
            spec.beginControlFlow("if (where.equals($S))", " WHERE ");
            spec.addStatement("return 0");
            spec.endControlFlow();
        }
        if (sets.stream().anyMatch(s -> s.dynamic)) {
            spec.beginControlFlow("if (setConn.isEmpty())");
            spec.addStatement("return 0");
            spec.endControlFlow();
        }
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        spec.addStatement("int i = 1");
        for (SetUnit unit : sets) {
            emitSetBind(spec, unit);
        }
        for (WhereCondition condition : conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");
        spec.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlockExpr(spec, sqlException, methodName, info.model.tableName(),
                "sql.toString()", logSql);
        return spec.build();
    }

    // ---- SET 单元解析与生成 ---------------------------------------------------

    /**
     * 解析 {@code @Where} 条件对象顶层的 {@code @UpdateSet} 字段为 SET 单元——
     * 字段即修改列的值(绑定表达式 = {@code criteria.getX()}),与 {@code @UpdateSet}
     * 方法参数的动态语义一致(可空字段 null 跳过 SET、Optional 空 → SET NULL)。
     * 校验失败的报错已在 {@code CriteriaGenerator.parse} 按场景给出;这里只做字段
     * 到 SET 单元的映射,无 {@code @UpdateSet} 字段时返回空列表。
     */
    private List<SetUnit> resolveCriteriaSets(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        TypeMirror type = parameter.asType();
        if (type.getKind() != TypeKind.DECLARED) {
            return List.of(); // 类型校验失败已在 CriteriaGenerator.parse 报错
        }
        TypeElement criteriaType = (TypeElement) ((DeclaredType) type).asElement();
        String accessor = parameter.getSimpleName().toString();
        List<SetUnit> sets = new ArrayList<>();
        for (Element enclosed : criteriaType.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            UpdateSet set = field.getAnnotation(UpdateSet.class);
            if (set == null) {
                continue;
            }
            String readExpr = accessor + "." + criteriaGenerator.readMethodName(criteriaType, field, method);
            if (readExpr == null) {
                return null; // getter 缺失已由 readMethodName 报错
            }
            TypeMirror fieldType = field.asType();
            boolean optional = CriteriaGenerator.isOptional(fieldType);
            boolean primitive = fieldType.getKind().isPrimitive();
            boolean dynamic = !primitive && Nullability.isNullable(field, fieldType);
            String bindType = optional
                    ? CriteriaGenerator.optionalValueType(fieldType, processingEnv.getTypeUtils())
                    : processingEnv.getTypeUtils().stripAnnotations(fieldType).toString();
            String valueExpr = optional ? readExpr + CriteriaGenerator.optionalValueMethod(fieldType) : null;
            String rawSql = set.rawSql();
            if (!rawSql.isEmpty()) {
                SetUnit unit = new SetUnit(null, readExpr, bindType, dynamic, optional, valueExpr, rawSql, null);
                RawSqlSupport.Plan plan = RawSqlSupport.resolve(rawSql, fieldType, field, readExpr,
                        set.requireJForgeSql(), processingEnv.getMessager(), processingEnv.getTypeUtils(), method,
                        info.model.dialectSupport());
                if (plan == null) return null;
                unit.rawSql = plan.sql();
                unit.rawBindings = plan.bindings();
                sets.add(unit);
                continue;
            }
            String fieldName = !set.value().isEmpty() ? set.value() : field.getSimpleName().toString();
            String column = null;
            for (EntityModel.ColumnModel model : info.model.columns()) {
                if (model.fieldName.equals(fieldName)) {
                    column = model.columnName;
                    break;
                }
            }
            if (column == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@UpdateSet field '" + fieldName + "' does not match any field of entity "
                                + info.model.entityQualifiedName(), method);
                return null;
            }
            String converterField = SqlCodegen.converterFieldForField(info.model, fieldName);
            sets.add(new SetUnit(SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column),
                    readExpr, bindType, dynamic, optional, valueExpr, null, converterField));
        }
        return sets;
    }

    private SetUnit resolveSet(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        UpdateSet set = parameter.getAnnotation(UpdateSet.class);
        String paramName = parameter.getSimpleName().toString();
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        // 动态判定与 WhereCondition.resolveHost 同规则:基本类型恒固定;
        // 非基本类型(含 Optional)显式 @Nullable 时动态。
        boolean primitive = parameter.asType().getKind().isPrimitive();
        boolean dynamic = !primitive && Nullability.isNullableParameter(parameter);
        String bindType = optional
                ? CriteriaGenerator.optionalValueType(parameter.asType(), processingEnv.getTypeUtils())
                : processingEnv.getTypeUtils().stripAnnotations(parameter.asType()).toString();
        String valueExpr = optional
                ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                : null;

        // 原生 SQL SET 表达式：rawSql 非空直接使用（跳过列映射），含 ? 绑定参数。
        String rawSql = set != null ? set.rawSql() : "";
        if (!rawSql.isEmpty()) {
            SetUnit unit = new SetUnit(null, paramName, bindType, dynamic, optional, valueExpr, rawSql, null);
            RawSqlSupport.Plan plan = RawSqlSupport.resolve(rawSql, parameter.asType(), parameter, paramName,
                    set.requireJForgeSql(), processingEnv.getMessager(), processingEnv.getTypeUtils(), method,
                    info.model.dialectSupport());
            if (plan == null) return null;
            unit.rawSql = plan.sql();
            unit.rawBindings = plan.bindings();
            return unit;
        }

        String fieldName = set != null && !set.value().isEmpty()
                ? set.value() : paramName;
        String column = null;
        for (EntityModel.ColumnModel model : info.model.columns()) {
            if (model.fieldName.equals(fieldName)) {
                column = model.columnName;
                break;
            }
        }
        if (column == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@UpdateSet parameter field '" + fieldName + "' does not match any field of entity "
                            + info.model.entityQualifiedName(), method);
            return null;
        }
        // SET 值须与列存同表示 → 复用该列 @Convert 转换器（无需注解）。
        String converterField = SqlCodegen.converterFieldForField(info.model, fieldName);
        return new SetUnit(SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column),
                paramName, bindType, dynamic, optional, valueExpr, null, converterField);
    }

    private void emitSetAppend(MethodSpec.Builder spec, SetUnit unit, String setConnVar) {
        if (unit.dynamic) {
            spec.beginControlFlow("if ($L != null)", unit.paramName);
        }
        if (unit.rawSql != null) {
            // 原生 SET 表达式：Optional 有值才拼（空跳过，rawSql 不生成 = NULL）。
            if (unit.optional) {
                spec.beginControlFlow("if ($L.isPresent())", unit.paramName);
            }
            spec.addStatement("$L.append($L).append($S)", "sql", setConnVar, " " + unit.rawSql);
            if (unit.optional) {
                spec.endControlFlow();
            }
        } else if (unit.optional) {
            spec.beginControlFlow("if ($L.isPresent())", unit.paramName);
            spec.addStatement("$L.append($L).append($S)", "sql", setConnVar, " " + unit.column + " = ?");
            spec.nextControlFlow("else");
            spec.addStatement("$L.append($L).append($S)", "sql", setConnVar, " " + unit.column + " = NULL");
            spec.endControlFlow();
        } else {
            spec.addStatement("$L.append($L).append($S)", "sql", setConnVar, " " + unit.column + " = ?");
        }
        spec.addStatement("$L = $S", setConnVar, ",");
        if (unit.dynamic) {
            spec.endControlFlow();
        }
    }

    private void emitSetBind(MethodSpec.Builder spec, SetUnit unit) {
        if (unit.dynamic) {
            spec.beginControlFlow("if ($L != null)", unit.paramName);
        }
        if (unit.rawSql != null) {
            for (RawSqlSupport.Binding binding : unit.rawBindings) {
                spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), "i++",
                        binding.nullable(), false, null));
                spec.addCode("\n");
            }
        } else if (unit.optional) {
            spec.beginControlFlow("if ($L.isPresent())", unit.paramName);
            spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.valueExpr, "i++", false, false,
                    unit.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else {
            spec.addCode(SqlCodegen.bindParam(unit.bindType, unit.paramName, "i++", false, false,
                    unit.converterField));
            spec.addCode("\n");
        }
        if (unit.dynamic) {
            spec.endControlFlow();
        }
    }

    // ---- WHERE 条件（与 @Select 相同的解析与生成） ------------------------------

}

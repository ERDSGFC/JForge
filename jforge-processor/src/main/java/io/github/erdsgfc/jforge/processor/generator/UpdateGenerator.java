package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.core.CriteriaGenerator;
import io.github.erdsgfc.jforge.processor.generator.core.DaoMethod;
import io.github.erdsgfc.jforge.processor.generator.core.RawSqlSupport;
import io.github.erdsgfc.jforge.processor.generator.core.WhereCondition;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    public void updateMethod(JForgeProcessor.DaoInfo info, DaoMethod call, TypeSpec.Builder builder,
                             ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        ExecutableElement method = call.method();
        MethodSpec impl = buildUpdateMethod(info, builder, method, call.overloadIndex(), connection,
                preparedStatement, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

    private MethodSpec buildUpdateMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder, ExecutableElement method,
            int overloadIndex, ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        String methodName = method.getSimpleName().toString();

        // 解析 SET 列（@UpdateSet 参数）与 WHERE 条件（@Condition 参数 + @Where 条件对象）。
        List<SetUnit> sets = new ArrayList<>();
        List<WhereCondition> conditions = new ArrayList<>();
        List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        boolean criteriaNullable = false; // 任一 @Where 参数可空 → 整体运行时可能跳过,静态折叠不可用
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(UpdateSet.class) != null) {
                SetUnit unit = resolveSet(info, method, parameter);
                if (unit == null) {
                    return null;
                }
                sets.add(unit);
            } else if (parameter.getAnnotation(Where.class) != null) {
                // 条件对象:顶层 @UpdateSet 字段是 SET 修改列(值表达式 = criteria.getX()),
                // 其余字段是 WHERE 条件。参数可空 → 整体运行时可能跳过,静态折叠不可用。
                if (Nullability.isNullableParameter(parameter)) {
                    criteriaNullable = true;
                }
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
                    processingEnv, "@Condition", Map.of(), false);
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

        // 非空契约参数（@UpdateSet/@Condition/@Where，非基本/非数组/非集合）在方法顶部
        // 快速失败——null 直送会静默绑 NULL 或深处抛模糊 NPE（数组/集合的动态路径在
        // 占位符拼接处已有检查，顶层不重复）。
        for (VariableElement parameter : method.getParameters()) {
            if (WhereCondition.needsRequireNonNull(parameter, processingEnv)) {
                spec.addStatement("$T.requireNonNull($N, $S)", Objects.class,
                        parameter.getSimpleName(), parameter.getSimpleName() + " must not be null");
            }
        }

        // 全静态：SET 无动态 + WHERE 无动态，且条件对象（若存在）参数非空、字段全静态 →
        // SQL 常量 + 静态绑定（条件对象组同样折叠，不做运行时 StringBuilder 拼接）。
        boolean allStatic = !criteriaNullable
                && sets.stream().noneMatch(s -> s.dynamic)
                && conditions.stream().allMatch(WhereCondition::staticCompatible)
                && CriteriaGenerator.staticCompatible(criteriaUnits);
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql + " SET ");
            for (int i = 0; i < sets.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                SetUnit unit = sets.get(i);
                sql.append(unit.rawSql != null ? unit.rawSql : unit.column + " = ?");
            }
            boolean hasDirect = !conditions.isEmpty();
            if (hasDirect) {
                WhereCondition.appendStaticWhereSql(sql, conditions);
            }
            if (!criteriaUnits.isEmpty()) {
                // 条件对象组：前导连接符与动态形态一致（无直接条件时首个得 WHERE）,
                // 组整体括号包裹,嵌套 @Where 递归(staticCompatible 保证组恒非空)。
                sql.append(hasDirect ? " AND " : " WHERE ");
                sql.append("(");
                CriteriaGenerator.appendStaticSql(sql, criteriaUnits);
                sql.append(")");
            }
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
            if (!criteriaUnits.isEmpty()) {
                CriteriaGenerator.appendStaticBinds(spec, criteriaUnits,
                        index + WhereCondition.staticBindCount(conditions));
            }
            spec.addStatement("return ps.executeUpdate()");
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                    sql.toString(), logSql);
            return spec.build();
        }

        // 动态形态：sql 变量 + where 前缀变量 + 双阶段 if 展开。
        spec.addStatement("$T conn = getConnection()", connection);
        // 静态前缀折叠：SET 全静态时整段 SET 文本编译期确定——并入 SQL 常量字段，
        // 运行时不再逐 SET 拼接；条件序列头部的连续全静态条件也并入（前缀已含
        // WHERE，where 守卫无需再生成）。SET 含 dynamic（null 跳过）时前缀无法
        // 确定，SET 部分保持全量动态。
        boolean setsStatic = sets.stream().noneMatch(s -> s.dynamic);
        int staticPrefix = 0;
        while (setsStatic && staticPrefix < conditions.size()
                && conditions.get(staticPrefix).staticCompatible()) {
            staticPrefix++;
        }
        if (setsStatic) {
            StringBuilder prefix = new StringBuilder(baseSql + " SET ");
            for (int i = 0; i < sets.size(); i++) {
                if (i > 0) {
                    prefix.append(", ");
                }
                SetUnit unit = sets.get(i);
                prefix.append(unit.rawSql != null ? unit.rawSql : unit.column + " = ?");
            }
            if (staticPrefix > 0) {
                WhereCondition.appendStaticWhereSql(prefix, conditions.subList(0, staticPrefix));
            }
            String prefixField = methodName + "PrefixSql"
                    + (overloadIndex > 0 ? "_" + overloadIndex : "");
            builder.addField(FieldSpec.builder(String.class, prefixField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", prefix.toString()).build());
            spec.addStatement("$T sql = new $T($L)", ClassName.get(StringBuilder.class),
                    ClassName.get(StringBuilder.class), prefixField);
        } else {
            spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                    ClassName.get(StringBuilder.class), baseSql + " SET");
            spec.addStatement("$T setConn = $S", ClassName.get(String.class), "");
            for (SetUnit unit : sets) {
                emitSetAppend(spec, unit, "setConn");
            }
        }
        boolean hasWhere = !conditions.isEmpty() || !criteriaUnits.isEmpty();
        if (hasWhere) {
            spec.addStatement("$T where = $S", ClassName.get(String.class),
                    staticPrefix > 0 ? " AND " : " WHERE ");
        }
        for (int i = staticPrefix; i < conditions.size(); i++) {
            WhereCondition.appendSql(spec, conditions.get(i));
        }
        criteriaGenerator.emitGroupAppend(spec, criteriaUnits, "where", " AND ");
        // 守卫仅在"可能发生"时生成,避免静态条件的死分支:
        // - where 守卫:前缀未含 WHERE(staticPrefix == 0)且存在可能整体缺失的动态
        //   条件/条件对象组时生成;前缀已含 WHERE 时 SQL 恒有 WHERE,守卫恒不触发;
        // - setConn 守卫:存在 dynamic SET(null 跳过)时才可能 SET 为空——静态 SET 恒拼。
        if (staticPrefix == 0
                && (conditions.stream().anyMatch(c -> c.dynamic()) || !criteriaUnits.isEmpty())) {
            spec.beginControlFlow("if (where.equals($S))", " WHERE ");
            spec.addStatement("return 0");
            spec.endControlFlow();
        }
        if (sets.stream().anyMatch(s -> s.dynamic)) {
            spec.beginControlFlow("if (setConn.isEmpty())");
            spec.addStatement("return 0");
            spec.endControlFlow();
        }
        // 固化 SQL 字符串:DEBUG 日志、prepareStatement 与 catch 复用同一份,只 toString 一次。
        SqlCodegen.beginDynamicSqlBlock(spec, preparedStatement, logSql);
        spec.addStatement("int i = 1");
        for (SetUnit unit : sets) {
            emitSetBind(spec, unit);
        }
        for (WhereCondition condition : conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");
        spec.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlockSqlVar(spec, sqlException, methodName, info.model.tableName(), logSql);
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

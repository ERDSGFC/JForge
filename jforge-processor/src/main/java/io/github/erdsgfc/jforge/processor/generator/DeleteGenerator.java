package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 生成 {@code Delete} 声明式删除方法：不写 SQL，按参数自动构造
 * {@code DELETE FROM t WHERE ...}。WHERE 条件与 {@code @Update}/{@code @Select}
 * 同一套（{@link Condition} 参数 / {@link Where} 条件对象，动态/静态形态一致）。
 */
public final class DeleteGenerator {

    private final javax.annotation.processing.ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final CriteriaGenerator criteriaGenerator;

    public DeleteGenerator(javax.annotation.processing.ProcessingEnvironment processingEnv,
                           JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR, processingEnv.getTypeUtils());
    }

    public void deleteMethod(JForgeProcessor.DaoInfo info, DaoMethod call, TypeSpec.Builder builder,
                             ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        ExecutableElement method = call.method();
        MethodSpec impl = buildDeleteMethod(info, builder, method, call.overloadIndex(), connection,
                preparedStatement, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

    private MethodSpec buildDeleteMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            ExecutableElement method, int overloadIndex, ClassName connection, ClassName preparedStatement,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();

        // WHERE 条件：@Condition 参数 + @Where 条件对象。
        List<WhereCondition> conditions = new ArrayList<>();
        List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        boolean criteriaNullable = false; // 任一 @Where 参数可空 → 整体运行时可能跳过,静态折叠不可用
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(Where.class) != null) {
                if (Nullability.isNullableParameter(parameter)) {
                    criteriaNullable = true;
                }
                List<CriteriaGenerator.Unit> units = criteriaGenerator.parse(info, method, parameter, false);
                if (units == null) {
                    return null;
                }
                criteriaUnits.addAll(units);
            } else {
                WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                    processingEnv, "@Condition", Map.of(info.model.entityQualifiedName(), info.model), false);
                if (condition == null) {
                    return null;
                }
                conditions.add(condition);
            }
        }

        String baseSql = "DELETE FROM "
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

        // 非空契约参数（@Condition/@Where，非基本/非数组/非集合）在方法顶部快速失败——
        // null 直送会静默绑 NULL 或深处抛模糊 NPE（数组/集合的动态路径在占位符
        // 拼接处已有检查，顶层不重复）。
        for (VariableElement parameter : method.getParameters()) {
            if (WhereCondition.needsRequireNonNull(parameter, processingEnv)) {
                spec.addStatement("$T.requireNonNull($N, $S)", Objects.class,
                        parameter.getSimpleName(), parameter.getSimpleName() + " must not be null");
            }
        }

        // 全静态：WHERE 无动态参数，且条件对象（若存在）参数非空、字段全静态 →
        // SQL 常量 + 静态绑定（条件对象组同样折叠，不做运行时 StringBuilder 拼接）。
        boolean allStatic = !criteriaNullable
                && conditions.stream().allMatch(WhereCondition::staticCompatible)
                && CriteriaGenerator.staticCompatible(criteriaUnits);
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql);
            boolean hasDirect = !conditions.isEmpty();
            if (hasDirect) {
                WhereCondition.appendStaticWhereSql(sql, conditions);
            }
            if (!criteriaUnits.isEmpty()) {
                // 条件对象组：前导连接符与动态形态一致（无直接条件时首个得 WHERE）,
                // 组整体括号包裹,嵌套 @Where 递归(staticCompatible 保证组恒非空,
                // 无需空组回退)。
                sql.append(hasDirect ? " AND " : " WHERE ");
                sql.append("(");
                CriteriaGenerator.appendStaticSql(sql, criteriaUnits);
                sql.append(")");
            }
            String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
            builder.addField(FieldSpec.builder(String.class, sqlField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", sql.toString()).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            WhereCondition.appendStaticBinds(spec, conditions, 1);
            if (!criteriaUnits.isEmpty()) {
                CriteriaGenerator.appendStaticBinds(spec, criteriaUnits,
                        1 + WhereCondition.staticBindCount(conditions));
            }
            spec.addStatement("return ps.executeUpdate()");
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                    sql.toString(), logSql);
            return spec.build();
        }

        // 动态形态。
        spec.addStatement("$T conn = getConnection()", connection);
        spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                ClassName.get(StringBuilder.class), baseSql);
        // 动态形态必有 WHERE（全静态已在上方 return）——where 前缀变量恒声明。
        spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        for (WhereCondition condition : conditions) {
            WhereCondition.appendSql(spec, condition);
        }
        criteriaGenerator.emitGroupAppend(spec, criteriaUnits, "where", " AND ");
        // 守卫仅在可能发生空 WHERE 时生成(dynamic 条件可能跳过/条件对象组可能回退)——
        // 全静态条件恒拼,守卫恒 false。
        if (conditions.stream().anyMatch(WhereCondition::dynamic) || !criteriaUnits.isEmpty()) {
            spec.beginControlFlow("if (where.equals($S))", " WHERE ");
            if (method.getReturnType().getKind() == TypeKind.BOOLEAN) {
                spec.addStatement("return false");
            } else {
                spec.addStatement("return 0");
            }
            spec.endControlFlow();
        }
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        spec.addStatement("int i = 1");
        for (WhereCondition condition : conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");
        spec.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlockExpr(spec, sqlException, methodName, info.model.tableName(),
                "sql.toString()", logSql);
        return spec.build();
    }

}

package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public void deleteMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
                              ClassName connection, ClassName preparedStatement, ClassName sqlException) {
        Map<String, Integer> seen = new HashMap<>();
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            int overloadIndex = seen.merge(method.getSimpleName().toString(), 1, Integer::sum) - 1;
            if (method.getAnnotation(Delete.class) == null) {
                continue;
            }
            if (method.getAnnotation(Select.class) != null
                    || method.getAnnotation(Query.class) != null
                    || method.getAnnotation(Update.class) != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Delete is mutually exclusive with @Select/@Query/@Update on the same method",
                        method);
                continue;
            }
            MethodSpec impl = deleteMethod(info, builder, method, overloadIndex, connection, preparedStatement, sqlException);
            if (impl != null) {
                builder.addMethod(impl);
            }
        }
    }

    private MethodSpec deleteMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            ExecutableElement method, int overloadIndex, ClassName connection, ClassName preparedStatement,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();

        // WHERE 条件：@Condition 参数 + @Where 条件对象。
        List<WhereCondition> conditions = new ArrayList<>();
        List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(Where.class) != null) {
                List<CriteriaGenerator.Unit> units = criteriaGenerator.parse(info, method, parameter);
                if (units == null) {
                    return null;
                }
                criteriaUnits.addAll(units);
            } else {
                WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                    processingEnv, "@Condition", configHelper);
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
                .returns(TypeNameUtils.toTypeNameWithGenerics(method.getReturnType(), processingEnv.getTypeUtils()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithGenerics(parameter.asType(), processingEnv.getTypeUtils()),
                    parameter.getSimpleName().toString());
        }
        boolean logSql = configHelper.logSql(info.element);

        // 全静态：WHERE 无动态 + 无 @Where 条件对象 → SQL 常量 + 静态绑定。
        // 静态形态 = 全部条件无 null 守卫(optional 可进静态——非空契约 opt.get()
        // 绑定;IS NULL 只在可空 Optional 下生成)。
        boolean allStatic = criteriaUnits.isEmpty()
                && conditions.stream().allMatch(WhereCondition::staticCompatible);
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql);
            WhereCondition.appendStaticWhereSql(sql, conditions);
            String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
            builder.addField(FieldSpec.builder(String.class, sqlField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", sql.toString()).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            WhereCondition.appendStaticBinds(spec, conditions, 1);
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
        criteriaGenerator.emitAppend(spec, criteriaUnits, "where", " AND ");
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

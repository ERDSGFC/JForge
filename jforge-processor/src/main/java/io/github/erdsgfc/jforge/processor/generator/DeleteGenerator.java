package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Delete;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Update;
import io.github.erdsgfc.jforge.annotation.Where;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

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
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
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
            MethodSpec impl = deleteMethod(info, builder, method, connection, preparedStatement, sqlException);
            if (impl != null) {
                builder.addMethod(impl);
            }
        }
    }

    private MethodSpec deleteMethod(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            ExecutableElement method, ClassName connection, ClassName preparedStatement,
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
                    processingEnv, "@Condition");
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
        boolean allStatic = criteriaUnits.isEmpty()
                && conditions.stream().noneMatch(c -> c.dynamic());
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql);
            if (!conditions.isEmpty()) {
                sql.append(" WHERE ");
                for (int i = 0; i < conditions.size(); i++) {
                    if (i > 0) {
                        sql.append(" AND ");
                    }
                    WhereCondition condition = conditions.get(i);
                    sql.append(condition.rawSql() != null ? condition.rawSql()
                            : condition.columnName() + " " + condition.op() + " ?");
                }
            }
            builder.addField(FieldSpec.builder(String.class, methodName + "Sql",
                    Modifier.PRIVATE, Modifier.FINAL).initializer("$S", sql.toString()).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, methodName + "Sql", false, logSql);
            int index = 1;
            for (WhereCondition condition : conditions) {
                // rawSql 无 ? 的纯常量条件不绑定参数。
                if (condition.rawSql() != null && !condition.rawSql().contains("?")) {
                    continue;
                }
                spec.addCode(SqlCodegen.bindParam(condition.typeName(), condition.paramName(),
                        index++, false, false, condition.converterField()));
                spec.addCode("\n");
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
        boolean hasWhere = !conditions.isEmpty() || !criteriaUnits.isEmpty();
        if (hasWhere) {
            spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        }
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
        SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                baseSql, logSql);
        return spec.build();
    }

}

package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.MethodSpec;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import org.jspecify.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.util.List;

/**
 * 已解析的 WHERE 条件，供 {@code @Select}、{@code @Update} 和 {@code @Delete} 生成器复用。
 */
record WhereCondition(String columnName, String op, String paramName, String typeName, boolean dynamic,
                      boolean optional, String valueExpr, String rawSql, String converterField) {

    /**
     * 解析绑定到宿主实体列的 {@code @Condition} 参数。
     */
    static WhereCondition resolveHost(JForgeProcessor.DaoInfo info, ExecutableElement method,
                                      VariableElement parameter, ProcessingEnvironment processingEnv, String diagnosticPrefix) {
        String paramName = parameter.getSimpleName().toString();
        Condition condition = parameter.getAnnotation(Condition.class);
        String fieldName = condition != null && !condition.value().isEmpty()
                ? condition.value() : paramName;
        String op = condition != null ? condition.op().sql() : "=";
        String rawSql = condition != null ? condition.rawSql() : "";
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        boolean dynamic = optional
                || parameter.asType().getAnnotation(Nullable.class) != null;
        String bindType = optional
                ? CriteriaGenerator.optionalValueType(parameter.asType(), processingEnv.getTypeUtils())
                : processingEnv.getTypeUtils().stripAnnotations(parameter.asType()).toString();
        String valueExpr = optional
                ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                : null;
        if (!rawSql.isEmpty()) {
            return new WhereCondition(null, op, paramName, bindType, dynamic, optional, valueExpr,
                    rawSql, null);
        }
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(fieldName)) {
                String converterField = SqlCodegen.converterFieldForField(info.model, fieldName);
                return new WhereCondition(
                        SqlCodegen.quoteIdentifier(info.model.dialectSupport(), column.columnName),
                        op, paramName, bindType, dynamic, optional, valueExpr, null, converterField);
            }
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                diagnosticPrefix + " parameter field '" + fieldName + "' does not match any field of entity "
                        + info.model.entityQualifiedName(), method);
        return null;
    }

    /**
     * 生成一个条件的动态 SQL 拼接代码。
     */
    static void appendSql(MethodSpec.Builder spec, WhereCondition condition) {
        if (condition.dynamic) {
            spec.beginControlFlow("if ($N != null)", condition.paramName);
        }
        if (condition.rawSql != null) {
            if (condition.optional) {
                spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
            }
            spec.addStatement("sql.append(where).append($S)", " " + condition.rawSql);
            if (condition.optional) {
                spec.endControlFlow();
            }
        } else if (condition.optional) {
            spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
            spec.addStatement("sql.append(where).append($S)",
                    " " + condition.columnName + " " + condition.op + " ?");
            spec.nextControlFlow("else");
            spec.addStatement("sql.append(where).append($S)",
                    " " + condition.columnName + " IS NULL");
            spec.endControlFlow();
        } else {
            spec.addStatement("sql.append(where).append($S)",
                    " " + condition.columnName + " " + condition.op + " ?");
        }
        spec.addStatement("where = $S", " AND ");
        if (condition.dynamic) {
            spec.endControlFlow();
        }
    }

    /**
     * 生成一个条件的动态参数绑定代码。
     */
    static void appendBind(MethodSpec.Builder spec, WhereCondition condition) {
        if (condition.dynamic) {
            spec.beginControlFlow("if ($N != null)", condition.paramName);
        }
        if (condition.rawSql != null && !condition.rawSql.contains("?")) {
            // 纯常量条件不绑定参数。
        } else if (condition.optional) {
            spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
            spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.valueExpr, "i++",
                    false, false, condition.converterField));
            spec.addCode("\n");
            spec.endControlFlow();
        } else {
            spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.paramName, "i++",
                    false, false, condition.converterField));
            spec.addCode("\n");
        }
        if (condition.dynamic) {
            spec.endControlFlow();
        }
    }

    /** 将静态 WHERE 条件追加到 SQL。条件为空时不追加任何内容。 */
    static void appendStaticWhereSql(StringBuilder sql, List<WhereCondition> conditions) {
        if (conditions.isEmpty()) {
            return;
        }
        sql.append(" WHERE ");
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }
            WhereCondition condition = conditions.get(i);
            sql.append(condition.rawSql != null ? condition.rawSql
                    : condition.columnName + " " + condition.op + " ?");
        }
    }

    /** 生成静态 WHERE 参数绑定代码，并返回下一个可用的 JDBC 参数索引。 */
    static int appendStaticBinds(MethodSpec.Builder spec, List<WhereCondition> conditions, int index) {
        for (WhereCondition condition : conditions) {
            if (condition.rawSql != null && !condition.rawSql.contains("?")) {
                continue;
            }
            spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.paramName,
                    index++, false, false, condition.converterField));
            spec.addCode("\n");
        }
        return index;
    }
}

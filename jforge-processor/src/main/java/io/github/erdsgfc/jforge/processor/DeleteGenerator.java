package io.github.erdsgfc.jforge.processor;

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
final class DeleteGenerator {

    /** WHERE 条件单元（与 UpdateGenerator.WhereCondition 同构）。 */
    private static final class WhereCondition {
        final String columnName;
        final String op;
        final String paramName;
        final String typeName;
        final boolean dynamic;
        final boolean optional;
        final String valueExpr;
        final String rawSql;       // 原生 SQL 条件（非 null 替代 column/op；含 ? 绑定参数）

        WhereCondition(String columnName, String op, String paramName, String typeName,
                boolean dynamic, boolean optional, String valueExpr, String rawSql) {
            this.columnName = columnName;
            this.op = op;
            this.paramName = paramName;
            this.typeName = typeName;
            this.dynamic = dynamic;
            this.optional = optional;
            this.valueExpr = valueExpr;
            this.rawSql = rawSql;
        }
    }

    private final javax.annotation.processing.ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final CriteriaGenerator criteriaGenerator;

    DeleteGenerator(javax.annotation.processing.ProcessingEnvironment processingEnv,
            JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR);
    }

    void deleteMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
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
                WhereCondition condition = resolveCondition(info, method, parameter);
                if (condition == null) {
                    return null;
                }
                conditions.add(condition);
            }
        }

        String baseSql = "DELETE FROM " + info.model.tableName();
        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithGenerics(method.getReturnType()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithGenerics(parameter.asType()),
                    parameter.getSimpleName().toString());
        }
        boolean logSql = configHelper.logSql(info.element);

        // 全静态：WHERE 无动态 + 无 @Where 条件对象 → SQL 常量 + 静态绑定。
        boolean allStatic = criteriaUnits.isEmpty()
                && conditions.stream().noneMatch(c -> c.dynamic);
        if (allStatic) {
            StringBuilder sql = new StringBuilder(baseSql);
            if (!conditions.isEmpty()) {
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
            builder.addField(FieldSpec.builder(String.class, methodName + "Sql",
                    Modifier.PRIVATE, Modifier.FINAL).initializer("$S", sql.toString()).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, methodName + "Sql", false, logSql);
            int index = 1;
            for (WhereCondition condition : conditions) {
                // rawSql 无 ? 的纯常量条件不绑定参数。
                if (condition.rawSql != null && !condition.rawSql.contains("?")) {
                    continue;
                }
                spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.paramName, index++));
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
            emitConditionAppend(spec, condition);
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
            emitConditionBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");
        spec.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                baseSql, logSql);
        return spec.build();
    }

    private WhereCondition resolveCondition(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        String paramName = parameter.getSimpleName().toString();
        Condition condition = parameter.getAnnotation(Condition.class);
        String fieldName = condition != null && !condition.value().isEmpty()
                ? condition.value() : paramName;
        String op = condition != null ? condition.op().sql() : "=";
        String column = null;
        for (EntityModel.ColumnModel model : info.model.columns()) {
            if (model.fieldName.equals(fieldName)) {
                column = model.columnName;
                break;
            }
        }
        // 原生 SQL 条件：rawSql 非空直接使用（跳过列映射），含 ? 绑定参数。
        String rawSql = condition != null ? condition.rawSql() : "";
        if (!rawSql.isEmpty()) {
            boolean optional = CriteriaGenerator.isOptional(parameter.asType());
            boolean dynamic = optional
                    || parameter.asType().getAnnotation(org.jspecify.annotations.Nullable.class) != null;
            String bindType = optional
                    ? CriteriaGenerator.optionalValueType(parameter.asType())
                    : TypeNameUtils.plainTypeName(parameter.asType());
            String valueExpr = optional
                    ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                    : null;
            return new WhereCondition(null, op, paramName, bindType, dynamic, optional, valueExpr, rawSql);
        }
        if (column == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Condition parameter field '" + fieldName + "' does not match any field of entity "
                            + info.model.entityQualifiedName(), method);
            return null;
        }
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        boolean dynamic = optional
                || parameter.asType().getAnnotation(org.jspecify.annotations.Nullable.class) != null;
        String bindType = optional
                ? CriteriaGenerator.optionalValueType(parameter.asType())
                : TypeNameUtils.plainTypeName(parameter.asType());
        String valueExpr = optional
                ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                : null;
        return new WhereCondition(column, op, paramName, bindType, dynamic, optional, valueExpr, null);
    }

    private void emitConditionAppend(MethodSpec.Builder spec, WhereCondition condition) {
        if (condition.dynamic) {
            spec.beginControlFlow("if ($N != null)", condition.paramName);
        }
        if (condition.rawSql != null) {
            // 原生 SQL 条件：Optional 有值才拼（空跳过，rawSql 不生成 IS NULL）。
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

    private void emitConditionBind(MethodSpec.Builder spec, WhereCondition condition) {
        if (condition.dynamic) {
            spec.beginControlFlow("if ($N != null)", condition.paramName);
        }
        if (condition.rawSql != null && !condition.rawSql.contains("?")) {
            // 纯常量条件（无 ?）不绑定参数。
        } else if (condition.optional) {
            spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
            spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.valueExpr, "i++"));
            spec.addCode("\n");
            spec.endControlFlow();
        } else {
            spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.paramName, "i++"));
            spec.addCode("\n");
        }
        if (condition.dynamic) {
            spec.endControlFlow();
        }
    }
}

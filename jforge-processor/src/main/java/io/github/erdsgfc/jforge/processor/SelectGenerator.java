package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Condition;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.Select;
import io.github.erdsgfc.jforge.annotation.Table;
import io.github.erdsgfc.jforge.annotation.Where;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 生成 {@code @Select} 声明式查询方法：不写 SQL，按返回类型与方法参数自动构造
 * {@code SELECT} 语句。
 *
 * <ul>
 *   <li>返回宿主实体/{@code List<宿主实体>} → {@code SELECT <全列> FROM t}；</li>
 *   <li>返回 record → {@code SELECT <组件列> FROM t}（组件名经命名策略得列名）；</li>
 *   <li>返回标量（primitive）→ {@code SELECT COUNT(*)}。</li>
 * </ul>
 *
 * <p>每个方法参数即一个 WHERE 条件：字段名取自 {@link Condition#value()}（缺省按参数名推断）、
 * 操作符取自 {@link Condition#op()}（默认等于）。参数标注 JSpecify {@code @Nullable}
 * 时条件动态拼接——运行时为 {@code null} 则跳过；未标注的参数静态拼接。
 * 生成代码为编译期展开的 StringBuilder 拼接 + 类型精确绑定（拼接与绑定两阶段
 * 各自展开一次相同的条件判断，绑定用运行时索引）。结果映射委托
 * {@link QueryGenerator#appendResultMapping}（与 {@code @Query} 完全一致）。</p>
 */
final class SelectGenerator {

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final QueryGenerator queryGenerator;
    private final CriteriaGenerator criteriaGenerator;

    /**
     * @param processingEnv  处理环境（messager 报错）
     * @param configHelper   共享的 ORM 配置 helper（命名策略）
     * @param queryGenerator 结果映射的委托目标（{@code @Query} 同一套映射逻辑）
     */
    SelectGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper,
            QueryGenerator queryGenerator) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.queryGenerator = queryGenerator;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR);
    }

    /**
     * 为仓库上每个标注了 {@code @Select} 的方法生成实现方法。
     *
     * @param info              仓库信息
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     */
    void selectMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder,
            Map<String, QueryGenerator.EmbeddedEntity> embedded, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getAnnotation(Select.class) == null) {
                continue;
            }
            if (method.getAnnotation(Query.class) != null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Select and @Query are mutually exclusive on the same method", method);
                continue;
            }
            // selectMethod 校验失败时返回 null（已报错）——跳过而非 addMethod(null)，
            // 否则 javapoet 抛 NPE 掩盖真实编译错误。
            MethodSpec impl = selectMethod(info, method, builder, embedded, connection,
                    preparedStatement, resultSet, sqlException);
            if (impl != null) {
                builder.addMethod(impl);
            }
        }
    }

    /** 一个已解析的 WHERE 条件。 */
    private static final class WhereCondition {
        final String columnName;   // WHERE 中使用的列名
        final String op;           // 操作符 SQL 片段（"="/">"/"LIKE"…）
        final String paramName;    // 参数名（绑定表达式）
        final String typeName;     // 参数类型（绑定 API 选择）
        final boolean dynamic;     // @Nullable → 运行时为 null 时跳过
        final boolean optional;    // Optional 参数：isPresent → 条件；isEmpty → IS NULL
        final String valueExpr;    // Optional 的绑定值表达式（param.get()/getAsInt()…）
        final String rawSql;       // 原生 SQL 片段（非 null = 替代 column/op 拼装；含 ? 绑定参数）

        WhereCondition(String columnName, String op, String paramName, String typeName, boolean dynamic,
                boolean optional, String valueExpr, String rawSql) {
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

    /**
     * 构建一个 {@code @Select} 方法：SELECT 列部分按返回类型分派（实体全列 /
     * record 组件列 / COUNT(*)，FROM 恒为宿主实体表），WHERE 条件按参数解析，
     * 生成 StringBuilder 拼接 + 类型精确绑定，结果映射委托 {@link QueryGenerator}。
     */
    private MethodSpec selectMethod(JForgeProcessor.DaoInfo info, ExecutableElement method,
            TypeSpec.Builder builder, Map<String, QueryGenerator.EmbeddedEntity> embedded,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();
        boolean isList = returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().getSimpleName().contentEquals("List");
        TypeMirror elementType = isList
                ? ((DeclaredType) returnType).getTypeArguments().get(0)
                : returnType;

        // SELECT 列部分与结果映射分支：实体（限宿主）/ record / 标量 COUNT(*)。
        String columns;
        if (elementType.getKind() == TypeKind.DECLARED) {
            TypeElement element = (TypeElement) ((DeclaredType) elementType).asElement();
            if (element.getAnnotation(Table.class) != null) {
                // 实体：必须是宿主实体——@Select 的 FROM 表固定为宿主表。
                if (!element.getQualifiedName().contentEquals(info.model.entityQualifiedName())) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@Select can only return the host entity " + info.model.entityQualifiedName()
                                    + " (FROM is the host table): " + element.getQualifiedName(), method);
                    return null;
                }
                columns = SqlCodegen.joinColumns(SqlCodegen.namesOf(info.model.columns()));
            } else if (element.getKind() == ElementKind.RECORD) {
                // record：组件名经命名策略得列名，SELECT 顺序 = 组件顺序。
                List<String> names = new ArrayList<>();
                for (Element component : element.getRecordComponents()) {
                    names.add(configHelper.columnName(element, component.getSimpleName().toString()));
                }
                columns = SqlCodegen.joinColumns(names);
            } else {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Unsupported @Select return type " + returnType
                                + ": entity, record or primitive (COUNT) expected", method);
                return null;
            }
        } else if (elementType.getKind().isPrimitive()) {
            columns = "COUNT(*)";
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Unsupported @Select return type " + returnType
                            + ": entity, record or primitive (COUNT) expected", method);
            return null;
        }

        // 解析每个参数为一个 WHERE 条件（字段名 / 操作符 / 动态判定 + 字段存在性校验）。
        // @Where 参数是条件对象——递归展开为片段（值条件/括号分组/Optional IS NULL）。
        List<WhereCondition> conditions = new ArrayList<>();
        List<CriteriaGenerator.Unit> criteriaUnits = new ArrayList<>();
        for (VariableElement parameter : method.getParameters()) {
            if (parameter.getAnnotation(Where.class) != null) {
                List<CriteriaGenerator.Unit> units = criteriaGenerator.parse(info, method, parameter);
                if (units == null) {
                    return null;
                }
                criteriaUnits.addAll(units);
                continue;
            }
            WhereCondition condition = resolveCondition(info, method, parameter);
            if (condition == null) {
                return null;
            }
            conditions.add(condition);
        }

        String baseSql = "SELECT " + columns + " FROM " + info.model.tableName();
        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithGenerics(returnType));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithGenerics(parameter.asType()),
                    parameter.getSimpleName().toString());
        }

        boolean logSql = configHelper.logSql(info.element);
        // @Where 条件对象恒动态（字段运行时判断）——存在即走动态形态。
        boolean allStatic = criteriaUnits.isEmpty()
                && conditions.stream().allMatch(condition -> !condition.dynamic);
        if (allStatic) {
            // 全静态条件（无 @Nullable 参数）：WHERE 子句与绑定索引均编译期确定——
            // 生成完整 SQL 常量字段 + 静态索引绑定（与 @Query 同一形态，运行时零拼接）。
            String fullSql = baseSql;
            if (!conditions.isEmpty()) {
                StringBuilder where = new StringBuilder(" WHERE ");
                for (int i = 0; i < conditions.size(); i++) {
                    if (i > 0) {
                        where.append(" AND ");
                    }
                    WhereCondition condition = conditions.get(i);
                    if (condition.rawSql != null) {
                        where.append(condition.rawSql);
                    } else {
                        where.append(condition.columnName).append(' ').append(condition.op).append(" ?");
                    }
                }
                fullSql = baseSql + where;
            }
            builder.addField(FieldSpec.builder(String.class, methodName + "Sql",
                    Modifier.PRIVATE, Modifier.FINAL).initializer("$S", fullSql).build());
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
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
            spec.endControlFlow();
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(), baseSql, logSql);
            return spec.build();
        }

        // 动态 SQL：sql 变量必须在 prepareStatement 之前声明，
        // 不能复用 beginTxBlock（其 try 头立即引用 sqlExpr），头部手动生成。
        spec.addStatement("$T conn = getConnection()", connection);
        // 拼接阶段：无 WHERE 1=1——where 变量维护 " WHERE "/" AND " 前缀，
        // 运行时第一个执行的条件拼 WHERE、其后拼 AND（动态条件全为 null 时无 WHERE）。
        spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                ClassName.get(StringBuilder.class), baseSql);
        if (!conditions.isEmpty() || !criteriaUnits.isEmpty()) {
            spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        }
        for (WhereCondition condition : conditions) {
            appendCondition(spec, condition, true);
        }
        criteriaGenerator.emitAppend(spec, criteriaUnits, "where", " AND ");
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        // 绑定阶段：与拼接同条件展开，运行时索引 i 递增，类型精确 setXxx。
        spec.addStatement("int i = 1");
        for (WhereCondition condition : conditions) {
            appendCondition(spec, condition, false);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");

        // 结果映射：与 @Query 同一套（实体按下标+缺列校验 / record 按下标 / 标量 rs.getX(1)）。
        spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
        spec.endControlFlow();

        SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(), baseSql,
                configHelper.logSql(info.element));
        return spec.build();
    }

    /**
     * 展开一个条件的拼接（{@code bind=false}）或绑定（{@code bind=true}）代码。
     * 动态条件包在 {@code if (param != null)} 内——拼接与绑定两阶段各自展开一次，
     * 保证占位符与绑定参数严格一一对应。
     */
    private void appendCondition(MethodSpec.Builder spec, WhereCondition condition, boolean bind) {
        if (condition.dynamic) {
            spec.beginControlFlow("if ($N != null)", condition.paramName);
        }
        if (bind) {
            if (condition.rawSql != null) {
                // 原生 SQL 片段：Optional 有值才拼（空跳过，rawSql 不生成 IS NULL）。
                if (condition.optional) {
                    spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
                }
                spec.addStatement("sql.append(where).append($S)", " " + condition.rawSql);
                if (condition.optional) {
                    spec.endControlFlow();
                }
            } else if (condition.optional) {
                // Optional 参数：有值 → 条件；空 → IS NULL（两者都拼接，连接符仅其后置一次）。
                spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
                spec.addStatement("sql.append(where).append($S)",
                        " " + condition.columnName + " " + condition.op + " ?");
                spec.nextControlFlow("else");
                spec.addStatement("sql.append(where).append($S)",
                        " " + condition.columnName + " IS NULL");
                spec.endControlFlow();
            } else {
                // 前缀由 where 变量运行时维护（首个条件 " WHERE "，其后 " AND "）。
                spec.addStatement("sql.append(where).append($S)",
                        " " + condition.columnName + " " + condition.op + " ?");
            }
            spec.addStatement("where = $S", " AND ");
        } else if (condition.rawSql != null) {
            // rawSql 含 ? 才绑定（无 ? 为纯常量条件）。
            if (condition.rawSql.contains("?")) {
                if (condition.optional) {
                    spec.beginControlFlow("if ($N.isPresent())", condition.paramName);
                }
                spec.addCode(SqlCodegen.bindParam(condition.typeName, condition.valueExpr != null
                        ? condition.valueExpr : condition.paramName, "i++"));
                spec.addCode("\n");
                if (condition.optional) {
                    spec.endControlFlow();
                }
            }
        } else if (condition.optional) {
            // 有值分支绑定（IS NULL 无占位符）。
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

    /**
     * 解析一个方法参数为 WHERE 条件：字段名取 {@link Condition#value()}（缺省按参数名）、
     * 操作符取 {@link Condition#op()}、动态判定看 JSpecify {@code @Nullable}。
     * 校验字段存在并映射为列名：实体/标量场景匹配宿主实体字段；record 场景匹配
     * record 组件名（此时列名经命名策略推断）。
     *
     * @return 解析结果；校验失败已报错并返回 {@code null}
     */
    private WhereCondition resolveCondition(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        String paramName = parameter.getSimpleName().toString();
        Condition where = parameter.getAnnotation(Condition.class);
        String fieldName = where != null && !where.value().isEmpty() ? where.value() : paramName;
        String op = where != null ? where.op().sql() : io.github.erdsgfc.jforge.annotation.Op.EQ.sql();
        // Optional 参数：恒动态——isPresent → 条件；isEmpty → IS NULL（显式空值查询）。
        boolean optional = CriteriaGenerator.isOptional(parameter.asType());
        // JSpecify @Nullable 是 TYPE_USE 注解——标注在类型位置（@Nullable Integer age），
        // 必须从 TypeMirror 读取（参数声明的 getAnnotation 读不到）。
        boolean dynamic = optional
                || parameter.asType().getAnnotation(org.jspecify.annotations.Nullable.class) != null;

        // 原生 SQL 条件：rawSql 非空时直接作为条件片段（跳过列映射与操作符拼装），
        // 含 ? 时绑定参数、无 ? 时纯常量（参数仅用于动态跳过控制）。
        String rawSql = where != null ? where.rawSql() : "";
        if (!rawSql.isEmpty()) {
            String bindType = optional
                    ? CriteriaGenerator.optionalValueType(parameter.asType())
                    : TypeNameUtils.plainTypeName(parameter.asType());
            String valueExpr = optional
                    ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                    : null;
            return new WhereCondition(null, null, paramName, bindType, dynamic, optional, valueExpr,
                    rawSql);
        }

        String columnName;
        TypeMirror returnType = method.getReturnType();
        boolean isList = returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().getSimpleName().contentEquals("List");
        TypeMirror elementType = isList ? ((DeclaredType) returnType).getTypeArguments().get(0) : returnType;
        if (elementType.getKind() == TypeKind.DECLARED) {
            TypeElement element = (TypeElement) ((DeclaredType) elementType).asElement();
            if (element.getAnnotation(Table.class) != null || element.getKind() == ElementKind.RECORD) {
                // 实体/record：条件字段匹配返回类型的字段/组件。
                columnName = element.getKind() == ElementKind.RECORD
                        ? findRecordColumn(element, fieldName, method)
                        : findHostColumn(info, fieldName, method);
            } else {
                return null; // 返回类型错误已在 selectMethod 报过
            }
        } else {
            columnName = findHostColumn(info, fieldName, method);
        }
        if (columnName == null) {
            return null;
        }
        String bindType = optional
                ? CriteriaGenerator.optionalValueType(parameter.asType())
                : TypeNameUtils.plainTypeName(parameter.asType());
        String valueExpr = optional
                ? paramName + CriteriaGenerator.optionalValueMethod(parameter.asType())
                : null;
        return new WhereCondition(columnName, op, paramName, bindType, dynamic, optional, valueExpr, null);
    }

    /** 在宿主实体字段集合中查找字段并返回其列名；找不到则报错。 */
    private String findHostColumn(JForgeProcessor.DaoInfo info, String fieldName, ExecutableElement method) {
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.fieldName.equals(fieldName)) {
                return column.columnName;
            }
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@Select parameter field '" + fieldName + "' does not match any field of entity "
                        + info.model.entityQualifiedName(), method);
        return null;
    }

    /** 在 record 组件中查找字段并返回其列名（组件名经命名策略）；找不到则报错。 */
    private String findRecordColumn(TypeElement record, String fieldName, ExecutableElement method) {
        for (Element component : record.getRecordComponents()) {
            if (component.getSimpleName().contentEquals(fieldName)) {
                return configHelper.columnName(record, fieldName);
            }
        }
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "@Select parameter field '" + fieldName + "' does not match any component of record "
                        + record.getQualifiedName(), method);
        return null;
    }
}

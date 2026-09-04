package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.*;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.core.EntityGenerator;
import io.github.erdsgfc.jforge.processor.generator.core.RepositoryGenerator;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * 生成仓库 impl 类的 {@code @Query} 方法：按原文顺序扫描 {@code :name} 普通绑定和
 * {@code {:name}} SQL 片段，展开 {@code @RawSql}/{@code @Condition}/{@code @Where}，
 * 并按返回类型映射结果（实体 / DTO record / 标量 / 更新计数）。
 *
 * <p>依赖 {@link ProcessingEnvironment}（{@code @Query} 实体结果映射时重新解析实体模型）与
 * {@link JForgeConfigHelper}；其余经 {@link JForgeProcessor.DaoInfo} 参数传入。
 * 实体结果映射的 impl 引用 {@link RepositoryGenerator} 预置的 {@link EmbeddedEntity} 表：
 * 宿主实体直接复用；{@code @Query} 返回其他 {@code @Table} 实体时现场解析并作为
 * {@code private static final} 嵌套类嵌入当前仓库（同实体多个 {@code @Query} 只嵌一份）。</p>
 */
public final class QueryGenerator {

    /**
     * 一个将被嵌入当前仓库 impl 的实体 impl 的解析结果：实体模型 + 其在当前仓库内的
     * 嵌套类名（{@code daoPackage.ImplName.EntityImplName}）。
     */
    public static final class EmbeddedEntity {
        final ClassName impl;
        final EntityModel model;

        public EmbeddedEntity(ClassName impl, EntityModel model) {
            this.impl = impl;
            this.model = model;
        }
    }

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final CriteriaGenerator criteriaGenerator;

    /**
     * @param processingEnv the processing environment (for entity-model re-parse and error reporting)
     * @param configHelper  the shared ORM config helper
     */
    public QueryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR, processingEnv.getTypeUtils());
    }

    /**
     * 为单个 {@code @Query} 方法生成实现（SQL 取 {@code <方法名>Sql} 字段）。
     * 由 {@code RepositoryGenerator} 在 DAO 方法单次遍历中按注解分发调用——接口方法
     * 不再被多个生成器各自扫描，转换器字段经 {@code addedConverters} 跨方法去重
     * （DAO 级集合由调用方持有）。
     *
     * @param info              仓库信息
     * @param call              方法（含同名序号，SQL 字段名唯一性依赖它）
     * @param addedConverters   本 DAO 已添加的转换器字段名集合（去重，调用方持有）
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     */
    public void queryMethod(JForgeProcessor.DaoInfo info, DaoMethod call, Set<String> addedConverters,
                            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, ClassName connection,
                            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        ExecutableElement method = call.method();
        Query query = method.getAnnotation(Query.class);
        if (query == null) {
            return;
        }
        if (query.value().indexOf('[') >= 0 || query.value().indexOf(']') >= 0) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "@Query no longer supports bracketed dynamic fragments; use {:name} with @RawSql/@Condition/@Where",
                    method);
            return;
        }
        for (VariableElement parameter : method.getParameters()) {
            ClassName converter = bindConverter(parameter);
            if (converter != null) {
                String field = queryConverterFieldName(method.getSimpleName().toString(),
                        parameter.getSimpleName().toString());
                if (addedConverters.add(field)) {
                    builder.addField(SqlCodegen.converterField(converter, field));
                }
            }
        }
        // buildQueryMethod 校验失败时返回 null（已报错）——跳过而非 addMethod(null)，
        // 否则 javapoet 抛 NPE 掩盖真实编译错误（与 @Select/@Update/@Delete 同规则）。
        MethodSpec impl = buildQueryMethod(info, method, query, call.overloadIndex(), builder, embedded, connection,
                preparedStatement, resultSet, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

    /**
     * 构建一个 {@code @Query} 方法的实现:命名占位符转 {@code ?},按类型绑定每个
     * {@code @Bind} 参数,并按返回类型映射结果(实体、DTO record、标量或影响行数)。
     *
     * @param info              仓库信息
     * @param method            标注了注解的仓库方法
     * @param query             @Query 注解
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return 查询方法规格
     */
    private MethodSpec buildQueryMethod(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            int overloadIndex, TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded,
            ClassName connection, ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String methodName = method.getSimpleName().toString();
        String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
        QueryScan scan = scanQuery(query.value(), info.model.dialectSupport(), method);
        if (scan == null) return null;
        if (scan.explicitQuestionMarks() > 0) {
            error(method, "@Query uses raw '?' placeholders; use named :name placeholders with @Bind");
            return null;
        }

        Map<String, VariableElement> parameters = new LinkedHashMap<>();
        boolean valid = true;
        for (VariableElement parameter : method.getParameters()) {
            parameters.put(parameter.getSimpleName().toString(), parameter);
            int semanticCount = (parameter.getAnnotation(Bind.class) != null ? 1 : 0)
                    + (parameter.getAnnotation(RawSql.class) != null ? 1 : 0)
                    + (parameter.getAnnotation(Condition.class) != null ? 1 : 0)
                    + (parameter.getAnnotation(Where.class) != null ? 1 : 0);
            if (semanticCount == 0) {
                error(method, "Every @Query parameter must declare exactly one of @Bind, @RawSql, @Condition, or @Where: "
                        + parameter.getSimpleName());
                valid = false;
            } else if (semanticCount > 1) {
                error(method, "@Query parameter has multiple semantic annotations: " + parameter.getSimpleName());
                valid = false;
            }
        }
        Map<String, FragmentPlan> fragmentPlans = new HashMap<>();
        Set<String> ordinaryNames = new HashSet<>();
        Set<String> fragmentNames = new HashSet<>();
        boolean dynamic = false;
        for (QueryToken token : scan.tokens()) {
            if (!token.fragment()) {
                if (token.name() == null) continue;
                VariableElement parameter = parameters.get(token.name());
                if (parameter == null || parameter.getAnnotation(Bind.class) == null) {
                    error(method, "Query placeholder :" + token.name() + " must match a same-named @Bind parameter");
                    valid = false;
                    continue;
                }
                if (fragmentNames.contains(token.name())) {
                    error(method, "Query parameter '" + token.name() + "' cannot be used as both bind and fragment");
                    valid = false;
                }
                ordinaryNames.add(token.name());
                continue;
            }
            VariableElement parameter = parameters.get(token.name());
            if (parameter == null) {
                error(method, "Query fragment {:" + token.name() + "} has no matching method parameter");
                valid = false;
                continue;
            }
            boolean fragmentAnnotation = parameter.getAnnotation(RawSql.class) != null
                    || parameter.getAnnotation(Condition.class) != null
                    || parameter.getAnnotation(Where.class) != null;
            if (!fragmentAnnotation) {
                error(method, "Query fragment {:" + token.name()
                        + "} must match a same-named @RawSql, @Condition, or @Where parameter");
                valid = false;
            }
            if (ordinaryNames.contains(token.name())) {
                error(method, "Query parameter '" + token.name() + "' cannot be used as both bind and fragment");
                valid = false;
            }
            if (!fragmentNames.add(token.name())) {
                error(method, "Query fragment {:" + token.name() + "} may be used only once");
                valid = false;
            }
            FragmentPlan plan = fragmentPlan(info, method, parameter);
            if (plan == null) {
                valid = false;
                continue;
            }
            fragmentPlans.put(token.name(), plan);
            dynamic |= plan.dynamic();
        }
        for (VariableElement parameter : method.getParameters()) {
            String name = parameter.getSimpleName().toString();
            if (!ordinaryNames.contains(name) && !fragmentNames.contains(name)) {
                error(method, "Query parameter '" + name + "' is not referenced by :" + name + " or {:" + name + "}");
                valid = false;
            }
        }
        if (!valid) return null;
        TypeMirror returnType = method.getReturnType();
        boolean isUpdate = !query.value().trim().toUpperCase().startsWith("SELECT");
        String mappingSql = scan.mappingSql();
        if (!dynamic) {
            String staticSql = renderStaticSql(scan, fragmentPlans);
            builder.addField(SqlFieldGenerator.sqlField(sqlField, staticSql));
            MethodSpec.Builder spec = baseQueryMethod(methodName, method, returnType);
            boolean generatedKeys = method.getAnnotation(ReturnGeneratedKeys.class) != null;
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, generatedKeys, configHelper.logSql(info.element));
            int index = 1;
            for (QueryToken token : scan.tokens()) {
                if (token.name() == null) continue;
                if (!token.fragment()) {
                    VariableElement parameter = parameters.get(token.name());
                    spec.addCode(bindParameter(info, method, parameter, String.valueOf(index++)));
                } else {
                    FragmentPlan plan = fragmentPlans.get(token.name());
                    index = emitStaticFragmentBind(spec, plan, index);
                }
                spec.addCode("\n");
            }
            appendQueryExecution(spec, info, method, builder, embedded, returnType, mappingSql,
                    resultSet, isUpdate, generatedKeys);
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(),
                    staticSql, configHelper.logSql(info.element));
            return spec.build();
        }
        return dynamicQueryMethod2(info, method, query, builder, embedded, connection,
                preparedStatement, resultSet, sqlException, scan, fragmentPlans, parameters, mappingSql);
    }

    private MethodSpec dynamicQueryMethod2(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException,
            QueryScan scan, Map<String, FragmentPlan> plans, Map<String, VariableElement> parameters,
            String mappingSql) {
        String methodName = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();
        boolean isUpdate = !query.value().trim().toUpperCase().startsWith("SELECT");
        MethodSpec.Builder spec = baseQueryMethod(methodName, method, returnType);
        boolean logSql = configHelper.logSql(info.element);
        spec.addStatement("$T conn = getConnection()", connection);
        spec.addStatement("$T sql = new $T()", ClassName.get(StringBuilder.class), ClassName.get(StringBuilder.class));
        spec.addStatement("$T where = $S", ClassName.get(String.class), "");
        int fragmentSeq = 0;
        for (QueryToken token : scan.tokens()) {
            if (token.name() == null) {
                spec.addStatement("sql.append($S)", token.literal());
            } else if (!token.fragment()) {
                spec.addStatement("sql.append($S)", "?");
            } else {
                emitDynamicFragmentSql(spec, plans.get(token.name()), token, ++fragmentSeq);
            }
        }
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        spec.addStatement("int i = 1");
        for (QueryToken token : scan.tokens()) {
            if (token.name() == null) continue;
            if (!token.fragment()) {
                spec.addCode(bindParameter(info, method, parameters.get(token.name()), "i++"));
                spec.addCode("\n");
            } else {
                emitDynamicFragmentBind(spec, plans.get(token.name()));
            }
        }
        if (isUpdate) {
            spec.addStatement("return ps.executeUpdate()");
        } else {
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            appendResultMapping(spec, info, method, builder, embedded, returnType, mappingSql);
            spec.endControlFlow();
        }
        SqlCodegen.endTxBlockExpr(spec, sqlException, methodName, info.model.tableName(),
                "sql.toString()", logSql);
        return spec.build();
    }

    private MethodSpec.Builder baseQueryMethod(String methodName, ExecutableElement method, TypeMirror returnType) {
        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class).addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithNullability(returnType, method, processingEnv.getTypeUtils()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithNullability(parameter.asType(), parameter,
                    processingEnv.getTypeUtils()), parameter.getSimpleName().toString());
        }
        return spec;
    }

    private CodeBlock bindParameter(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter, String index) {
        String converter = effectiveConverterField(info, method, parameter);
        return SqlCodegen.bindParam(processingEnv.getTypeUtils().stripAnnotations(parameter.asType()).toString(),
                parameter.getSimpleName().toString(), index, isNullableParameter(parameter), false, converter);
    }

    private void emitDynamicFragmentSql(MethodSpec.Builder spec, FragmentPlan plan, QueryToken token, int sequence) {
        if (plan.condition() != null) {
            String start = "fragmentStart" + sequence;
            spec.addStatement("int $L = sql.length()", start);
            spec.addStatement("where = $S", "");
            WhereCondition.appendSql(spec, plan.condition());
            spec.addStatement("where = $S", "");
            spec.beginControlFlow("if (sql.length() > $L)", start);
            if (!token.prefix().isEmpty()) spec.addStatement("sql.insert($L, $S)", start, token.prefix());
            if (!token.suffix().isEmpty()) spec.addStatement("sql.append($S)", token.suffix());
            spec.nextControlFlow("else if ($L)", token.prefix().toUpperCase(Locale.ROOT).contains("WHERE")
                    && !token.suffix().isEmpty());
            // A leading fragment owns the WHERE keyword. If it is skipped but a
            // following predicate remains, retain WHERE and its connector.
            spec.addStatement("sql.append($S)", token.prefix());
            spec.endControlFlow();
        } else if (plan.criteria() != null) {
            String start = "fragmentStart" + sequence;
            spec.addStatement("int $L = sql.length()", start);
            spec.addStatement("where = $S", "");
            spec.beginControlFlow("if ($N != null)", plan.parameter().getSimpleName());
            criteriaGenerator.emitAppend(spec, plan.criteria(), "where", "");
            spec.endControlFlow();
            spec.addStatement("where = $S", "");
            spec.beginControlFlow("if (sql.length() > $L)", start);
            if (!token.prefix().isEmpty()) spec.addStatement("sql.insert($L, $S)", start, token.prefix());
            if (!token.suffix().isEmpty()) spec.addStatement("sql.append($S)", token.suffix());
            spec.endControlFlow();
        } else {
            if (plan.dynamic()) spec.beginControlFlow("if ($N != null)", plan.parameter().getSimpleName());
            if (!token.prefix().isEmpty()) spec.addStatement("sql.append($S)", token.prefix());
            spec.addStatement("sql.append($S)", " " + plan.sql());
            if (!token.suffix().isEmpty()) spec.addStatement("sql.append($S)", token.suffix());
            if (plan.dynamic()) spec.endControlFlow();
        }
    }

    private void emitDynamicFragmentBind(MethodSpec.Builder spec, FragmentPlan plan) {
        if (plan.condition() != null) {
            WhereCondition.appendBind(spec, plan.condition());
        } else if (plan.criteria() != null) {
            spec.beginControlFlow("if ($N != null)", plan.parameter().getSimpleName());
            criteriaGenerator.emitBind(spec, plan.criteria(), "i++");
            spec.endControlFlow();
        } else {
            if (plan.dynamic()) spec.beginControlFlow("if ($N != null)", plan.parameter().getSimpleName());
            for (RawSqlSupport.Binding binding : plan.bindings()) {
                spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), "i++",
                        binding.nullable(), false, null));
                spec.addCode("\n");
            }
            if (plan.dynamic()) spec.endControlFlow();
        }
    }

    private int emitStaticFragmentBind(MethodSpec.Builder spec, FragmentPlan plan, int index) {
        if (plan.condition() != null) {
            WhereCondition c = plan.condition();
            if (c.rawSql() != null) {
                for (RawSqlSupport.Binding binding : c.rawBindings()) {
                    spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), index++,
                            binding.nullable(), false, null));
                }
                return index;
            }
            spec.addCode(SqlCodegen.bindParam(c.typeName(), c.valueExpr() != null ? c.valueExpr() : c.paramName(),
                    index, false, false, c.converterField()));
            return index + 1;
        }
        for (RawSqlSupport.Binding binding : plan.bindings()) {
            spec.addCode(SqlCodegen.bindParam(binding.typeName(), binding.expression(), index++,
                    binding.nullable(), false, null));
        }
        return index;
    }

    private void appendQueryExecution(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info,
            ExecutableElement method, TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded,
            TypeMirror returnType, String mappingSql, ClassName resultSet, boolean isUpdate,
            boolean generatedKeys) {
        if (generatedKeys) {
            spec.addStatement("ps.executeUpdate()");
            spec.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
            spec.beginControlFlow("if (keys.next())");
            spec.addCode(generatedKeysWriteback(info, method)).addCode("\n");
            spec.endControlFlow().endControlFlow();
            spec.addStatement(returnType.getKind() == TypeKind.VOID ? "return" : "return null");
        } else if (isUpdate) {
            spec.addStatement("return ps.executeUpdate()");
        } else {
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            appendResultMapping(spec, info, method, builder, embedded, returnType, mappingSql);
            spec.endControlFlow();
        }
    }

    /**
     * 构建 {@code @ReturnGeneratedKeys} 方法的生成主键回写表达式:找到实体参数,
     * 返回对其 id setter 的赋值。
     *
     * @param info   仓库信息
     * @param method 标注了注解的方法
     * @return 回写代码块;无实体参数时返回 no-op 注释
     */
    private CodeBlock generatedKeysWriteback(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            TypeMirror type = parameter.asType();
            if (type.getKind() == TypeKind.DECLARED) {
                Element element = ((DeclaredType) type).asElement();
                if (element.getKind() == ElementKind.INTERFACE
                        && element.getAnnotation(Table.class) != null) {
                    EntityModel.ColumnModel idColumn = info.model.idColumn();
                    // 回写接收者:接口有 setter 直接调用;只读 id(无 setter)强转到宿主仓库的
                    // 嵌套实体 impl 调用 private 填充 setter(nestmates 允许宿主类访问)。
                    // getSimpleName() 返回 Name,条件表达式的另一分支是 String——
                    // 必须 toString() 才能让两分支类型一致。
                    String paramName = parameter.getSimpleName().toString();
                    String receiver = idColumn.hasSetter
                            ? paramName
                            : "((" + EntityModel.implNameOf(info.model.entitySimpleName(),
                                    info.model.implSuffix()) + ") " + paramName + ")";
                    return CodeBlock.of("$L.$L(keys.$L(1));", receiver, idColumn.setterName,
                            TypeNameUtils.jdbcGetter(idColumn.typeName));
                }
            }
        }
        return CodeBlock.of("/* no entity parameter to write back the generated key */");
    }

    /**
     * 构建含动态 WHERE 的 {@code @Query} 方法:WHERE 片段按方括号/@{@code @Nullable}
     * 判定动态性,生成 StringBuilder 拼接 + where 前缀变量 + 双阶段 if 展开绑定
     * (与 {@code @Select} 动态查询同一形态;静态片段原样保留,动态片段 null 时跳过)。
     */
    private MethodSpec dynamicQueryMethod(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException,
            ParsedWhere parsed, List<WhereFragment> fragments, Map<String, VariableElement> binds) {
        String methodName = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();
        boolean isUpdate = !query.value().trim().toUpperCase().startsWith("SELECT");

        // 每片段解析为绑定单元:占位符转 ? 的文本、绑定参数、动态判定。
        List<String> texts = new ArrayList<>();
        List<List<VariableElement>> bindUnits = new ArrayList<>();
        List<Boolean> dynamics = new ArrayList<>();
        for (WhereFragment fragment : fragments) {
            if (fragment.condition != null) {
                texts.add(null);
                bindUnits.add(List.of());
                dynamics.add(true);
                continue;
            }
            SqlCodegen.PlaceholderResult parsedPlaceholders = SqlCodegen.parsePlaceholders(
                    fragment.text, info.model.dialectSupport());
            List<String> placeholders = parsedPlaceholders.names();
            String text = parsedPlaceholders.sql();
            List<VariableElement> params = new ArrayList<>();
            for (String placeholder : placeholders) {
                VariableElement parameter = binds.get(placeholder);
                if (parameter == null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "No same-named @Bind parameter for query placeholder :" + placeholder, method);
                    continue;
                }
                params.add(parameter);
            }
            texts.add(text);
            bindUnits.add(params);
            dynamics.add(isDynamicFragment(fragment, binds, info.model.dialectSupport()));
        }

        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeNameUtils.toTypeNameWithNullability(
                        returnType, method, processingEnv.getTypeUtils()));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithNullability(
                            parameter.asType(), parameter, processingEnv.getTypeUtils()),
                    parameter.getSimpleName().toString());
        }
        // parsed 为 null = SQL 无 WHERE（@Condition 追加片段提供首个条件）——selectPart 取整条 SQL。
        String selectPart = parsed != null ? parsed.selectPart : query.value().trim();
        boolean logSql = configHelper.logSql(info.element);
        spec.addStatement("$T conn = getConnection()", connection);
        spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                ClassName.get(StringBuilder.class), selectPart);
        spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        // 拼接阶段:每片段 append(where) + 文本,where 置为下一片段连接符
        // (首个执行片段得 " WHERE ",其后得用户写的 AND/OR;动态片段跳过时连接符随之消失)。
        for (int i = 0; i < fragments.size(); i++) {
            String nextConn = (i + 1 < fragments.size() && !fragments.get(i + 1).conn.isEmpty())
                    ? fragments.get(i + 1).conn
                    : " AND ";
            if (fragments.get(i).condition != null) {
                WhereCondition.appendSql(spec, fragments.get(i).condition);
                continue;
            }
            if (dynamics.get(i)) {
                spec.beginControlFlow("if ($N != null)", bindUnits.get(i).get(0).getSimpleName());
            }
            spec.addStatement("sql.append(where).append($S)", texts.get(i));
            spec.addStatement("where = $S", nextConn);
            if (dynamics.get(i)) {
                spec.endControlFlow();
            }
        }
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        // 绑定阶段:与拼接同条件展开,运行时索引 i 递增,类型精确 setXxx。
        spec.addStatement("int i = 1");
        for (int f = 0; f < fragments.size(); f++) {
            if (fragments.get(f).condition != null) {
                WhereCondition.appendBind(spec, fragments.get(f).condition);
                continue;
            }
            if (dynamics.get(f)) {
                spec.beginControlFlow("if ($N != null)", bindUnits.get(f).get(0).getSimpleName());
            }
            for (VariableElement parameter : bindUnits.get(f)) {
                // 参数挂转换器（@Bind.converter / @Condition 复用宿主列）→ 经转换器绑定
                // （动态路径用运行时索引 "i++"）。
                String converterField = effectiveConverterField(info, method, parameter);
                spec.addCode(converterField != null
                        ? SqlCodegen.bindParam(processingEnv.getTypeUtils().stripAnnotations(parameter.asType()).toString(),
                                parameter.getSimpleName().toString(), "i++", false, false, converterField)
                        : SqlCodegen.bindParam(processingEnv.getTypeUtils().stripAnnotations(parameter.asType()).toString(),
                                parameter.getSimpleName().toString(), "i++", false, false, null));
                spec.addCode("\n");
            }
            if (dynamics.get(f)) {
                spec.endControlFlow();
            }
        }
        if (isUpdate) {
            spec.addStatement("return ps.executeUpdate()");
        } else {
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            appendResultMapping(spec, info, method, builder, embedded, returnType, selectPart);
            spec.endControlFlow();
        }
        SqlCodegen.endTxBlockExpr(spec, sqlException, methodName, info.model.tableName(),
                "sql.toString()", logSql);
        return spec.build();
    }

    /**
     * 解析 {@code @Condition} 参数为追加条件片段:字段名取 {@link Condition#value()}(缺省按参数名),
     * 列名从宿主实体字段映射。数组/集合保留 {@link WhereCondition} 结构，供动态路径按
     * 元素数量展开 {@code IN}/{@code NOT IN} 占位符；标量继续使用参数名伪占位符。
     * 连接符:SQL 已有 WHERE 片段时用 {@code " AND "},否则空(where 前缀变量给 WHERE)。
     */
    private WhereFragment appendFragment(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter, Condition where, boolean first) {
        if (!where.rawSql().isEmpty()) {
            TypeMirror strippedType = processingEnv.getTypeUtils().stripAnnotations(parameter.asType());
            boolean array = strippedType.getKind() == TypeKind.ARRAY;
            boolean collection = !CriteriaGenerator.isOptional(strippedType)
                    && isIterable(strippedType);
            if (array || collection) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Iterable/array parameters cannot be used with @Condition(rawSql)", parameter);
                return null;
            }
            if (where.rawSql().contains("?")) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Condition(rawSql) with '?' is not supported on @Query; use @Bind in the SQL text",
                        parameter);
                return null;
            }
            return new WhereFragment(first ? "" : " AND ", where.rawSql());
        }
        WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                processingEnv, "@Query");
        if (condition == null) {
            return null;
        }
        String conn = first ? "" : " AND ";
        if (condition.collection() || condition.array()) {
            return new WhereFragment(conn, condition);
        }
        return new WhereFragment(conn,
                condition.columnName() + " " + condition.op() + " :" + parameter.getSimpleName());
    }

    /**
     * 按返回类型追加 SELECT {@code @Query} 的结果映射代码:
     * 实体接口(按列名)、DTO record(按组件顺序)或标量。
     *
     * @param spec       接收映射代码的方法构建器
     * @param info       仓库信息
     * @param method     标注了注解的方法
     * @param builder    接收方法的 impl 类构建器
     * @param embedded   已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param returnType 方法的返回类型
     * @param sql        {@code @Query} 的 SQL（实体映射解析 SELECT 列顺序用）
     */
    void appendResultMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info,
            ExecutableElement method, TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded,
            TypeMirror returnType, String sql) {
        boolean isList = returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().getSimpleName().contentEquals("List");
        TypeMirror elementType = isList
                ? ((DeclaredType) returnType).getTypeArguments().get(0)
                : returnType;

        TypeElement element = elementType.getKind() == TypeKind.DECLARED
                ? (TypeElement) ((DeclaredType) elementType).asElement()
                : null;

        if (element != null && element.getAnnotation(Table.class) != null) {
            // 实体接口:解析 SELECT 列顺序按下标映射(失败回退按列名)。
            appendEntityMapping(spec, info, builder, embedded, elementType, isList, sql, method);
        } else if (element != null && element.getKind() == ElementKind.RECORD) {
            // DTO record:组件顺序按索引映射到 SELECT 列顺序。
            appendRecordMapping(spec, element, isList);
        } else if (elementType.getKind() != TypeKind.VOID) {
            // 单值(String/Long/...)。
            if (isList) {
                spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                        TypeName.get(elementType), ClassName.get(ArrayList.class));
                spec.beginControlFlow("while (rs.next())");
                spec.addStatement("result.add(rs.$L(1))", TypeNameUtils.jdbcGetter(elementType.toString()));
                spec.endControlFlow();
                spec.addStatement("return result");
            } else {
                spec.beginControlFlow("if (!rs.next())");
                spec.addStatement(elementType.getKind().isPrimitive() ? "return 0" : "return null");
                spec.endControlFlow();
                spec.addStatement("return rs.$L(1)", TypeNameUtils.jdbcGetter(elementType.toString()));
            }
        } else {
            spec.addStatement("return null");
        }
    }

    /**
     * 追加实体接口结果类型的行映射:优先按下标读取——编译期解析 {@code @Query} 的
     * SELECT 列顺序,为每个实体列定位其在 SELECT 中的位置(与 CRUD mapRow 一致,
     * 且 SELECT 缺实体列时编译报错而非静默错位);SQL 无法解析(通配符/函数/复杂
     * 表达式)时回退按列名读取。
     *
     * @param spec       接收映射代码的方法构建器
     * @param info       仓库信息
     * @param builder    接收方法的 impl 类构建器
     * @param embedded   已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param entityType 实体接口类型
     * @param isList     方法是否返回列表
     * @param sql        {@code @Query} 的 SQL（解析 SELECT 列顺序用）
     * @param method     标注了 {@code @Query} 的仓库方法（缺列报错绑定位置）
     */
    void appendEntityMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info,
            TypeSpec.Builder builder, Map<String, EmbeddedEntity> embedded, TypeMirror entityType,
            boolean isList, String sql, ExecutableElement method) {
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
        // 命中 embedded 表（宿主实体由 RepositoryGenerator 预置）直接复用，避免重复解析；
        // 非宿主实体（@Query 返回其他 @Table 实体）现场解析并作为嵌套类嵌入当前仓库，
        // 同实体多个 @Query 靠表去重只嵌一份。
        EmbeddedEntity ctx = embedded.get(entityElement.getQualifiedName().toString());
        if (ctx == null) {
            EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                    Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
            if (model == null) {
                return;
            }
            // 嵌套类名必须用该实体自身的 implSuffix() 计算（跨包可配置不同后缀），
            // 不能复用宿主仓库的值。
            ClassName impl = ClassName.get(info.daoPackage, info.implName,
                    EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()));
            ctx = new EmbeddedEntity(impl, model);
            embedded.put(entityElement.getQualifiedName().toString(), ctx);
            builder.addType(EntityGenerator.buildImpl(model));
            // @Convert 列的转换器实例字段(嵌入实体;字段名含实体名,与宿主字段不冲突)。
            for (EntityModel.ColumnModel column : model.columns()) {
                if (column.converter != null) {
                    builder.addField(SqlCodegen.converterField(model, column));
                }
            }
        }
        ClassName impl = ctx.impl;
        EntityModel model = ctx.model;
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    TypeName.get(entityType), ClassName.get(ArrayList.class));
            spec.beginControlFlow("while (rs.next())");
            spec.addStatement("$T e = new $T()", impl, impl);
            appendEntityColumnReads(spec, model, sql, method);
            spec.addStatement("result.add(e)");
            spec.endControlFlow();
            spec.addStatement("return result");
        } else {
            spec.beginControlFlow("if (!rs.next())");
            spec.addStatement("return null");
            spec.endControlFlow();
            spec.addStatement("$T e = new $T()", impl, impl);
            appendEntityColumnReads(spec, model, sql, method);
            spec.addStatement("return e");
        }
    }

    /**
     * 追加实体所有列的读取代码:优先按解析出的 SELECT 列顺序按下标读取
     * (与 CRUD mapRow 一致;SELECT 缺实体列时编译报错),解析失败回退按列名。
     */
    void appendEntityColumnReads(MethodSpec.Builder spec, EntityModel model, String sql,
            ExecutableElement method) {
        List<String> selectColumns = parseSelectColumns(sql);
        for (EntityModel.ColumnModel column : model.columns()) {
            if (selectColumns != null) {
                int pos = indexOfIgnoreCase(selectColumns, column.columnName);
                if (pos < 0) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "@Query SELECT is missing column '" + column.columnName + "' required by entity "
                                    + model.entityQualifiedName() + ": " + sql, method);
                    return;
                }
                spec.addCode(SqlCodegen.readColumn(column.typeName, column.javaType, column.javaClassType,
                        "e", column.setterName, pos + 1,
                        column.nullable, column.isEnum,
                        column.converter != null ? SqlCodegen.converterFieldName(model, column) : null));
            } else {
                spec.addCode(SqlCodegen.readColumnByName(column.typeName, column.javaType, column.javaClassType,
                        "e", column.setterName,
                        column.columnName, column.nullable, column.isEnum,
                        column.converter != null ? SqlCodegen.converterFieldName(model, column) : null));
            }
            spec.addCode("\n");
        }
    }

    /**
     * 追加 DTO record 结果类型的行映射:record 组件顺序按索引映射到 SELECT 列顺序。
     *
     * @param spec   接收映射代码的方法构建器
     * @param record DTO record 元素
     * @param isList 方法是否返回列表
     */
    void appendRecordMapping(MethodSpec.Builder spec, TypeElement record, boolean isList) {
        ClassName recordClass = ClassName.get(record);
        List<? extends Element> components = record.getRecordComponents();
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    recordClass, ClassName.get(ArrayList.class));
            spec.beginControlFlow("while (rs.next())");
            spec.addStatement("$T dto = new $T($L)", recordClass, recordClass,
                    recordArgs(components));
            spec.addStatement("result.add(dto)");
            spec.endControlFlow();
            spec.addStatement("return result");
        } else {
            spec.beginControlFlow("if (!rs.next())");
            spec.addStatement("return null");
            spec.endControlFlow();
            spec.addStatement("return new $T($L)", recordClass, recordArgs(components));
        }
    }

    /**
     * 从当前行构建 record 的构造参数列表。
     *
     * @param components record 组件
     * @return 逗号连接的 {@code rs.getXxx(i)} 表达式
     */
    private String recordArgs(List<? extends Element> components) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String typeName = components.get(i).asType().toString();
            sb.append("rs.").append(TypeNameUtils.jdbcGetter(typeName)).append("(").append(i + 1).append(")");
        }
        return sb.toString();
    }

    // ---- @Query 动态 WHERE（根据 JSpecify 空性自动推断） ------------------

    /** {@code @Query} SQL 的解析结果：SELECT 部分 + WHERE 片段序列。 */
    static final class ParsedWhere {
        final String selectPart;
        final List<WhereFragment> fragments;

        ParsedWhere(String selectPart, List<WhereFragment> fragments) {
            this.selectPart = selectPart;
            this.fragments = fragments;
        }
    }

    /** 一个 WHERE 条件片段：前导连接符 + 文本，或运行时展开的结构化条件。 */
    static final class WhereFragment {
        final String conn;       // 前导连接符（" AND "/" OR "；首片段 "")
        final String text;       // 条件文本
        final WhereCondition condition; // 集合/数组条件；非 null 时 text 为 null

        WhereFragment(String conn, String text) {
            this.conn = conn;
            this.text = text;
            this.condition = null;
        }

        WhereFragment(String conn, WhereCondition condition) {
            this.conn = conn;
            this.text = null;
            this.condition = condition;
        }
    }

    private record QueryToken(String literal, String name, boolean fragment, String prefix, String suffix) {}

    private record FragmentPlan(String sql, List<RawSqlSupport.Binding> bindings,
            WhereCondition condition, List<CriteriaGenerator.Unit> criteria,
            boolean dynamic, VariableElement parameter) {}

    private record QueryScan(List<QueryToken> tokens, String mappingSql, int explicitQuestionMarks) {}

    private FragmentPlan fragmentPlan(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        String name = parameter.getSimpleName().toString();
        RawSql raw = parameter.getAnnotation(RawSql.class);
        if (raw != null) {
            RawSqlSupport.Plan plan = RawSqlSupport.resolve(raw.value(), parameter.asType(), parameter, name,
                    raw.requireJForgeSql(), processingEnv.getMessager(), processingEnv.getTypeUtils(), method,
                    info.model.dialectSupport());
            if (plan == null) return null;
            boolean dynamic = isNullableParameter(parameter)
                    || plan.bindings().stream().anyMatch(RawSqlSupport.Binding::nullable);
            return new FragmentPlan(plan.sql(), plan.bindings(), null, null, dynamic, parameter);
        }
        Condition condition = parameter.getAnnotation(Condition.class);
        if (condition != null) {
            WhereCondition c = WhereCondition.resolveHost(info, method, parameter, processingEnv, "@Query", true);
            if (c == null) return null;
            return new FragmentPlan(null, List.of(), c, null,
                    c.dynamic() || c.collection() || c.array() || c.optional(), parameter);
        }
        Where where = parameter.getAnnotation(Where.class);
        if (where != null) {
            List<CriteriaGenerator.Unit> units = criteriaGenerator.parse(info, method, parameter, false, true);
            if (units == null) return null;
            return new FragmentPlan(null, List.of(), null, units, true, parameter);
        }
        return null;
    }

    private String renderStaticSql(QueryScan scan, Map<String, FragmentPlan> plans) {
        StringBuilder sql = new StringBuilder();
        for (QueryToken token : scan.tokens()) {
            if (token.name() == null) sql.append(token.literal());
            else if (!token.fragment()) sql.append("?");
            else {
                FragmentPlan plan = plans.get(token.name());
                sql.append(token.prefix());
                if (plan.condition() != null) {
                    WhereCondition c = plan.condition();
                    if (c.rawSql() != null) sql.append(" ").append(c.rawSql());
                    else sql.append(" ").append(c.columnName()).append(" ").append(c.op()).append(" ?");
                } else if (plan.criteria() == null) {
                    sql.append(" ").append(plan.sql());
                }
                sql.append(token.suffix());
            }
        }
        return sql.toString();
    }

    /**
     * 解析 Query 专用命名占位符。扫描规则与 {@link SqlCodegen#parsePlaceholders} 保持一致，
     * 额外识别完整的 {@code {:name}} 片段节点。
     */
    private QueryScan scanQuery(String sql, DialectSupport dialect, ExecutableElement method) {
        List<QueryToken> tokens = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        StringBuilder mapping = new StringBuilder();
        String quote = null;
        boolean line = false, block = false;
        int explicitQuestionMarks = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (line) { text.append(c); mapping.append(c); i++; if (c == '\n' || c == '\r') line = false; continue; }
            if (block) { text.append(c); mapping.append(c); i++; if (c == '*' && i < sql.length() && sql.charAt(i) == '/') { text.append('/'); mapping.append('/'); i++; block = false; } continue; }
            if (quote != null) {
                if (quote.length() > 1 && sql.startsWith(quote, i)) { text.append(quote); mapping.append(quote); i += quote.length(); quote = null; continue; }
                text.append(c); mapping.append(c); i++;
                if (quote.length() > 1) continue;
                if (c == '\\' && i < sql.length()) { text.append(sql.charAt(i)); mapping.append(sql.charAt(i++)); }
                else if (c == quote.charAt(0)) { if (i < sql.length() && sql.charAt(i) == quote.charAt(0)) { text.append(sql.charAt(i)); mapping.append(sql.charAt(i++)); } else quote = null; }
                continue;
            }
            if (c == '\'' || (c == '"' && dialect.quote().equals("\"")) || (c == '`' && dialect.supportsBacktickQuotedIdentifiers())) { quote = String.valueOf(c); text.append(c); mapping.append(c); i++; continue; }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') { text.append("--"); mapping.append("--"); i += 2; line = true; continue; }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') { text.append("/*"); mapping.append("/*"); i += 2; block = true; continue; }
            if (c == '$' && dialect.supportsDollarQuotedStrings()) { int end = dollarQuoteDelimiterEnd(sql, i); if (end >= 0) { quote = sql.substring(i, end); text.append(quote); mapping.append(quote); i = end; continue; } }
            if (dialect.supportsDoubleColonCast() && c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') { text.append("::"); mapping.append("::"); i += 2; continue; }
            if (c == '{' && i + 2 < sql.length() && sql.charAt(i + 1) == ':' && Character.isJavaIdentifierStart(sql.charAt(i + 2))) {
                int end = i + 2; while (end < sql.length() && Character.isJavaIdentifierPart(sql.charAt(end))) end++;
                if (end < sql.length() && sql.charAt(end) == '}') {
                    if ((i > 0 && isIdentifierChar(sql.charAt(i - 1)))
                            || (end + 1 < sql.length() && isIdentifierChar(sql.charAt(end + 1)))) {
                        error(method, "Query fragment {:name} must be a standalone SQL node");
                        return null;
                    }
                    String prefix = "";
                    String current = text.toString();
                    java.util.regex.Matcher before = java.util.regex.Pattern.compile("(?s)(.*?)(\\s+(?:AND|OR)\\s*)$").matcher(current);
                    if (before.matches()) { current = before.group(1); prefix = before.group(2); }
                    java.util.regex.Matcher whereBefore = java.util.regex.Pattern.compile("(?is)(.*?)(\\s+WHERE\\s*)$").matcher(current);
                    if (prefix.isEmpty() && whereBefore.matches()) { current = whereBefore.group(1); prefix = whereBefore.group(2); }
                    if (!current.isEmpty()) tokens.add(new QueryToken(current, null, false, "", ""));
                    String name = sql.substring(i + 2, end);
                    int next = end + 1;
                    String suffix = "";
                    java.util.regex.Matcher after = java.util.regex.Pattern.compile("^(\\s+(?:AND|OR)\\s+)(?=\\S)").matcher(sql.substring(next));
                    if (after.find()) { suffix = after.group(1); next += suffix.length(); }
                    tokens.add(new QueryToken(null, name, true, prefix, suffix));
                    text.setLength(0); i = next; continue;
                }
            }
            if (c == ':' && i + 1 < sql.length() && Character.isJavaIdentifierStart(sql.charAt(i + 1))) {
                if (text.length() > 0) { tokens.add(new QueryToken(text.toString(), null, false, "", "")); text.setLength(0); }
                int start = ++i; while (i < sql.length() && Character.isJavaIdentifierPart(sql.charAt(i))) i++;
                tokens.add(new QueryToken("?", sql.substring(start, i), false, "", "")); mapping.append('?'); continue;
            }
            if (c == '?') {
                if (dialect.supportsDoubleQuestionMarkEscape() && i + 1 < sql.length() && sql.charAt(i + 1) == '?') {
                    text.append("??"); mapping.append("??"); i += 2; continue;
                }
                explicitQuestionMarks++;
            }
            text.append(c); mapping.append(c); i++;
        }
        if (text.length() > 0) tokens.add(new QueryToken(text.toString(), null, false, "", ""));
        return new QueryScan(List.copyOf(tokens), mapping.toString(), explicitQuestionMarks);
    }

    private static int dollarQuoteDelimiterEnd(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && sql.charAt(i) == '$') return i + 1;
        if (i >= sql.length() || !(Character.isLetter(sql.charAt(i)) || sql.charAt(i) == '_')) return -1;
        i++; while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        return i < sql.length() && sql.charAt(i) == '$' ? i + 1 : -1;
    }

    /**
     * 解析 {@code @Query} 的 WHERE 部分为片段序列：顶层（括号外）AND/OR 切分。
     * 无 WHERE 返回 {@code null}
     * 返回 {@code null}——调用方走静态路径。
     */
    static ParsedWhere parseWhere(String sql) {
        int where = indexOfTopLevelKeyword(sql, "WHERE", 0);
        if (where < 0) {
            return null;
        }
        String selectPart = sql.substring(0, where);
        String body = sql.substring(where + 5);
        List<WhereFragment> fragments = new ArrayList<>();
        String conn = "";
        StringBuilder text = new StringBuilder();
        int paren = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                paren++;
                text.append(c);
            } else if (c == ')') {
                paren--;
                text.append(c);
            } else if (paren == 0 && isAndOr(body, i)) {
                flushFragment(fragments, conn, text);
                text.setLength(0); // 清空缓冲——否则下一片段会累积上一片段的内容
                // 连接符长度 = 关键字实际长度（AND=3 / OR=2），不能按固定 4 截取——
                // 否则会把后续列名的首字符吃进去（如 "OR user_name" → "OR u"）。
                // 前后各带空格（" AND "），拼接时与前后条件自然隔开。
                int len = body.regionMatches(true, i, "AND", 0, 3) ? 3 : 2;
                conn = " " + body.substring(i, i + len).toUpperCase() + " ";
                i += len - 1;
            } else {
                text.append(c);
            }
        }
        flushFragment(fragments, conn, text);
        return fragments.isEmpty() ? null : new ParsedWhere(selectPart, fragments);
    }

    private static void flushFragment(List<WhereFragment> out, String conn, StringBuilder text) {
        String s = text.toString().trim();
        if (!s.isEmpty()) {
            out.add(new WhereFragment(conn, s));
        }
    }

    /** 位置 {@code i} 是否为顶层 AND/OR 关键字（前后是词边界）。 */
    private static boolean isAndOr(String s, int i) {
        boolean and = s.regionMatches(true, i, "AND", 0, 3);
        boolean or = s.regionMatches(true, i, "OR", 0, 2);
        if (!and && !or) {
            return false;
        }
        int len = and ? 3 : 2;
        return (i == 0 || !isIdentifierChar(s.charAt(i - 1)))
                && (i + len >= s.length() || !isIdentifierChar(s.charAt(i + len)));
    }

    /** 收集方法的 {@code @Bind} 参数映射（占位符名 → 参数元素）。 */
    private static Map<String, VariableElement> bindsOf(ExecutableElement method) {
        Map<String, VariableElement> binds = new HashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            Bind bind = parameter.getAnnotation(Bind.class);
            if (bind != null) {
                binds.put(parameter.getSimpleName().toString(), parameter);
            }
        }
        return binds;
    }

    /** {@code @Bind.converter()} 哨兵的全限定名——处理器按名识别，不加载类。 */
    private static final String NO_CONVERTER = "io.github.erdsgfc.jforge.annotation.NoConverter";

    /** 生成 {@code @Bind} 转换器静态字段名：{@code CONVERTER_QUERY_<方法名>_<参数名>}（大写）。 */
    private static String queryConverterFieldName(String methodName, String paramName) {
        return "CONVERTER_QUERY_" + methodName.toUpperCase() + "_" + paramName.toUpperCase();
    }

    /**
     * 读取 {@code @Bind} 参数的绑定转换器：Class 类型属性访问时预编译类直接返回、同批
     * 源码抛 {@link MirroredTypeException}（与实体列 {@code @Convert} 同一机制）——两种
     * 路径都处理；哨兵 {@code NoConverter} 视为无转换器。转换器可为同批源码，不要求已编译。
     *
     * @param parameter 仓库方法参数
     * @return 转换器类名；参数无转换器（或默认哨兵）时 {@code null}
     */
    private ClassName bindConverter(VariableElement parameter) {
        Bind bind = parameter.getAnnotation(Bind.class);
        if (bind == null) {
            return null;
        }
        try {
            Class<?> cls = bind.converter();
            if (cls.getName().equals(NO_CONVERTER)) {
                return null;
            }
            TypeElement el = processingEnv.getElementUtils().getTypeElement(cls.getName());
            return el == null ? null : ClassName.get(el);
        } catch (MirroredTypeException e) {
            TypeMirror mirror = e.getTypeMirror();
            if (mirror.getKind() == TypeKind.DECLARED) {
                TypeElement el = (TypeElement) ((DeclaredType) mirror).asElement();
                if (el.getQualifiedName().contentEquals(NO_CONVERTER)) {
                    return null;
                }
                return ClassName.get(el);
            }
        }
        return null;
    }

    /** {@code @Bind} 参数的转换器静态字段名；无转换器时 {@code null}（供绑定站点复用预扫字段名）。 */
    private String queryConverterField(ExecutableElement method, VariableElement parameter) {
        ClassName converter = bindConverter(parameter);
        return converter == null ? null
                : queryConverterFieldName(method.getSimpleName().toString(),
                        parameter.getSimpleName().toString());
    }

    /**
     * 参数的有效转换器字段：① {@code @Bind.converter} 显式指定优先；② 否则 {@code @Condition}
     * 参数匹配宿主实体列时复用该列 {@code @Convert} 转换器字段（无需注解）——条件值须与列存
     * 相同表示才能命中；两者皆无返回 {@code null}（按声明类型绑定）。
     */
    private String effectiveConverterField(JForgeProcessor.DaoInfo info, ExecutableElement method,
            VariableElement parameter) {
        String field = queryConverterField(method, parameter);
        if (field != null) {
            return field;
        }
        Condition condition = parameter.getAnnotation(Condition.class);
        if (condition != null) {
            String fieldName = condition.value().isEmpty()
                    ? parameter.getSimpleName().toString()
                    : condition.value();
            return SqlCodegen.converterFieldForField(info.model, fieldName);
        }
        return null;
    }

    /**
     * 判定片段是否动态：仅当恰好一个占位符且对应参数标注 JSpecify
     * {@code @Nullable} 时自动推断为动态（多占位符片段保守处理为静态）。
     */
    private static boolean isDynamicFragment(WhereFragment fragment, Map<String, VariableElement> binds,
            DialectSupport dialect) {
        if (fragment.condition != null) {
            return fragment.condition.collection() || fragment.condition.array() || fragment.condition.dynamic();
        }
        List<String> placeholders = new ArrayList<>();
        placeholders.addAll(SqlCodegen.parsePlaceholders(fragment.text, dialect).names());
        if (placeholders.size() != 1) {
            return false;
        }
        VariableElement parameter = binds.get(placeholders.get(0));
        return parameter != null && isNullableParameter(parameter);
    }

    private boolean isIterable(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) return false;
        TypeMirror iterable = processingEnv.getElementUtils().getTypeElement("java.lang.Iterable").asType();
        return processingEnv.getTypeUtils().isAssignable(processingEnv.getTypeUtils().erasure(type),
                processingEnv.getTypeUtils().erasure(iterable));
    }

    /** 参数是否标注 JSpecify {@code @Nullable}（公共工具,见 {@link Nullability}）。 */
    static boolean isNullableParameter(VariableElement parameter) {
        return Nullability.isNullableParameter(parameter);
    }

    /**
     * {@code @Query} 方法的 SQL：命名占位符转 {@code ?}，并拼接静态 @Condition
     * 追加参数（非 {@code @Nullable}）的条件；动态追加条件由运行时 SQL 拼接处理。
     */
    static String querySql(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            ParsedWhere parsed, List<WhereFragment> appended) {
        String sql = SqlCodegen.parsePlaceholders(query.value(), info.model.dialectSupport()).sql();
        boolean first = parsed == null || parsed.fragments.isEmpty();
        for (WhereFragment fragment : appended) {
            if (fragment.condition != null) {
                continue;
            }
            SqlCodegen.PlaceholderResult placeholders = SqlCodegen.parsePlaceholders(
                    fragment.text, info.model.dialectSupport());
            List<String> names = placeholders.names();
            String text = placeholders.sql();
            if (names.size() == 1 && !isNullableParameter(findParameter(method, names.getFirst()))) {
                sql += (first ? " WHERE " : fragment.conn) + text;
                first = false;
            }
        }
        return sql;
    }

    private static VariableElement findParameter(ExecutableElement method, String name) {
        for (VariableElement parameter : method.getParameters()) {
            Bind bind = parameter.getAnnotation(Bind.class);
            if ((bind != null && parameter.getSimpleName().contentEquals(name))
                    || parameter.getSimpleName().contentEquals(name)) {
                return parameter;
            }
        }
        return null;
    }

    // ---- SELECT 列顺序解析(实体映射按下标读取) -------------------------------

    /**
     * 解析 SELECT 查询的顶层列名列表,用于把 {@code @Query} 实体映射从按名称改为
     * 按下标读取(与 CRUD mapRow 一致,且能在编译期校验 SELECT 缺列)。
     *
     * <p>支持:逗号分隔的列名、{@code AS} 别名、表前缀(如 {@code u.name})、
     * 引号列名;遇到通配符({@code *})、函数、括号等无法可靠解析的表达式返回
     * {@code null}——调用方回退到按列名映射(安全降级,不误报)。</p>
     *
     * @param sql {@code @Query} 的 SQL
     * @return SELECT 列名列表(按书写顺序),解析失败返回 {@code null}
     */
    private static List<String> parseSelectColumns(String sql) {
        int select = indexOfTopLevelKeyword(sql, "SELECT", 0);
        if (select < 0) {
            return null;
        }
        int from = indexOfTopLevelKeyword(sql, "FROM", select + 6);
        if (from < 0) {
            return null;
        }
        String list = sql.substring(select + 6, from).replaceFirst("(?i)\\s+DISTINCT\\s+", " ");
        List<String> columns = new ArrayList<>();
        for (String item : splitTopLevel(list)) {
            String name = selectColumnName(item);
            if (name == null) {
                return null; // 通配符或无法解析 → 回退按名称
            }
            columns.add(name);
        }
        return columns.isEmpty() ? null : columns;
    }

    /** 顶层(括号深度 0)逗号分割;函数参数内的逗号不分割。 */
    private static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    /**
     * 提取单个 SELECT 项的列名:去 AS 别名与表前缀({@code u.name AS n} → {@code name};
     * 按下标读取时别名不影响位置)、去引号;含通配符或函数/表达式返回 {@code null}。
     */
    private static String selectColumnName(String item) {
        String s = item.trim();
        if (s.isEmpty() || s.indexOf('*') >= 0 || s.indexOf('(') >= 0) {
            return null;
        }
        int as = indexOfTopLevelKeyword(s, "AS", 0);
        if (as >= 0) {
            s = s.substring(0, as);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        s = s.trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '`')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            s = s.substring(1, s.length() - 1);
        }
        return s.isEmpty() ? null : s;
    }

    /** 从 {@code from} 起查找下一个顶层(括号深度 0、非引号内)关键字,大小写不敏感。 */
    private static int indexOfTopLevelKeyword(String s, String keyword, int from) {
        String lower = s.toLowerCase();
        String kw = keyword.toLowerCase();
        int depth = 0;
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                }
                continue;
            }
            if (c == '"' || c == '`' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth == 0 && lower.startsWith(kw, i)
                    && (i == 0 || !isIdentifierChar(s.charAt(i - 1)))
                    && (i + kw.length() >= s.length() || !isIdentifierChar(s.charAt(i + kw.length())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int indexOfIgnoreCase(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private void error(ExecutableElement method, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, method);
    }
}

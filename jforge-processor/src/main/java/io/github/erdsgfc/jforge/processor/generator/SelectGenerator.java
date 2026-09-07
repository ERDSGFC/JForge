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

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.*;

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
 * 时条件动态拼接——运行时为 {@code null} 则跳过；未标注参数按 JSpecify 作用域判定。
 * 生成代码为编译期展开的 StringBuilder 拼接 + 类型精确绑定（拼接与绑定两阶段
 * 各自展开一次相同的条件判断，绑定用运行时索引）。结果映射委托
 * {@link QueryGenerator#appendResultMapping}（与 {@code @Query} 完全一致）。</p>
 */
public final class SelectGenerator {

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    private final QueryGenerator queryGenerator;
    private final CriteriaGenerator criteriaGenerator;

    /**
     * @param processingEnv  处理环境（messager 报错）
     * @param configHelper   共享的 ORM 配置 helper（命名策略）
     * @param queryGenerator 结果映射的委托目标（{@code @Query} 同一套映射逻辑）
     */
    public SelectGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper,
            QueryGenerator queryGenerator) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.queryGenerator = queryGenerator;
        this.criteriaGenerator = new CriteriaGenerator(processingEnv.getMessager(),
                Diagnostic.Kind.ERROR, processingEnv.getTypeUtils());
    }

    /**
     * 为仓库上每个标注了 {@code @Select} 的方法生成实现方法。
     *
     * @param info              仓库信息
     * @param call              方法（含同名序号，SQL 字段名唯一性依赖它）
     * @param builder           接收方法的 impl 类构建器
     * @param embedded          已嵌入/待嵌入当前仓库的实体 impl 表（键 = 实体接口全限定名）
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     */
    public void selectMethod(JForgeProcessor.DaoInfo info, DaoMethod call, TypeSpec.Builder builder,
                             Map<String, QueryGenerator.EmbeddedEntity> embedded, ClassName connection,
                             ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        ExecutableElement method = call.method();
        MethodSpec impl = buildSelectMethod(info, method, call.overloadIndex(), builder, embedded, connection,
                preparedStatement, resultSet, sqlException);
        if (impl != null) {
            builder.addMethod(impl);
        }
    }

    /** 只把 java.util.List 视为集合返回类型，避免按简单类名误判自定义 List 类型。 */
    private boolean isJavaUtilList(TypeMirror type) {
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement listElement = processingEnv.getElementUtils().getTypeElement("java.util.List");
        return listElement != null
                && processingEnv.getTypeUtils().isSameType(
                        processingEnv.getTypeUtils().erasure(type),
                        processingEnv.getTypeUtils().erasure(listElement.asType()));
    }

    /**
     * 构建一个 {@code @Select} 方法：SELECT 列部分按返回类型分派（实体全列 /
     * record 组件列 / COUNT(*)，FROM 恒为宿主实体表），WHERE 条件按参数解析，
     * 生成 StringBuilder 拼接 + 类型精确绑定，结果映射委托 {@link QueryGenerator}。
     */
    private MethodSpec buildSelectMethod(JForgeProcessor.DaoInfo info, ExecutableElement method,
            int overloadIndex, TypeSpec.Builder builder, Map<String, QueryGenerator.EmbeddedEntity> embedded,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();
        TypeMirror returnType = method.getReturnType();
        boolean isList = isJavaUtilList(returnType);
        TypeMirror elementType = returnType;
        if (isList) {
            List<? extends TypeMirror> typeArguments = ((DeclaredType) returnType).getTypeArguments();
            if (typeArguments.size() != 1) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@Select List return type must declare exactly one element type: " + returnType,
                        method);
                return null;
            }
            elementType = typeArguments.getFirst();
        }

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
                columns = SqlCodegen.joinColumns(SqlCodegen.quotedNames(info.model.columns(),
                        info.model.dialectSupport()));
            } else if (element.getKind() == ElementKind.RECORD) {
                // record：组件名经命名策略得列名，SELECT 顺序 = 组件顺序（按宿主方言包裹）。
                List<String> names = new ArrayList<>();
                DialectSupport dialect = info.model.dialectSupport();
                for (Element component : element.getRecordComponents()) {
                    names.add(SqlCodegen.quoteIdentifier(dialect,
                            configHelper.columnName(element, component.getSimpleName().toString())));
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

        List<JoinSpec> joins = parseJoins(info, method);
        if (joins == null) return null;
        Map<String, EntityModel> entities = new LinkedHashMap<>();
        entities.put(info.model.entityQualifiedName(), info.model);
        for (JoinSpec join : joins) entities.put(join.target().entityQualifiedName(), join.target());

        // 解析每个参数为一个 WHERE 条件（字段名 / 操作符 / 动态判定 + 字段存在性校验）。
        // @Where 参数是条件对象——递归展开为片段（值条件/括号分组/Optional IS NULL）。
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
                continue;
            }
            WhereCondition condition = WhereCondition.resolveHost(info, method, parameter,
                    processingEnv, "@Select", entities, false);
            if (condition == null) {
                return null;
            }
            conditions.add(condition);
        }

        String baseSql = "SELECT " + columns + " FROM "
                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
        for (JoinSpec join : joins) {
            baseSql += " " + join.type().sql() + " "
                    + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), join.target().tableName());
            if (join.type() != JoinType.CROSS) {
                baseSql += " ON ";
                for (int i = 0; i < join.on().size(); i++) {
                    if (i > 0) baseSql += " AND ";
                    Join.On on = join.on().get(i);
                    baseSql += qualifiedColumn(join.from(), on.local(), info.model.dialectSupport())
                            + " = " + qualifiedColumn(join.target(), on.target(), info.model.dialectSupport());
                }
            }
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
        // 生成完整 SQL 常量字段 + 静态索引绑定（条件对象组同样折叠，运行时零拼接）。
        boolean allStatic = !criteriaNullable
                && conditions.stream().allMatch(WhereCondition::staticCompatible)
                && CriteriaGenerator.staticCompatible(criteriaUnits);
        if (allStatic) {
            // WHERE 子句与绑定索引均编译期确定（与 @Query 同一形态，运行时零拼接）。
            StringBuilder fullSqlBuilder = new StringBuilder(baseSql);
            boolean hasDirect = !conditions.isEmpty();
            if (hasDirect) {
                WhereCondition.appendStaticWhereSql(fullSqlBuilder, conditions);
            }
            if (!criteriaUnits.isEmpty()) {
                // 条件对象组：前导连接符与动态形态一致（无直接条件时首个得 WHERE）,
                // 组整体括号包裹,嵌套 @Where 递归(staticCompatible 保证组恒非空)。
                fullSqlBuilder.append(hasDirect ? " AND " : " WHERE ");
                fullSqlBuilder.append("(");
                CriteriaGenerator.appendStaticSql(fullSqlBuilder, criteriaUnits);
                fullSqlBuilder.append(")");
            }
            String fullSql = fullSqlBuilder.toString();
            String sqlField = SqlFieldGenerator.methodSqlFieldName(methodName, overloadIndex);
            builder.addField(FieldSpec.builder(String.class, sqlField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL).initializer("$S", fullSql).build());
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            WhereCondition.appendStaticBinds(spec, conditions, 1);
            if (!criteriaUnits.isEmpty()) {
                CriteriaGenerator.appendStaticBinds(spec, criteriaUnits,
                        1 + WhereCondition.staticBindCount(conditions));
            }
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
            spec.endControlFlow();
            SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(), fullSql, logSql);
            return spec.build();
        }

        // 动态 SQL：sql 变量必须在 prepareStatement 之前声明，
        // 不能复用 beginTxBlock（其 try 头立即引用 sqlExpr），头部手动生成。
        spec.addStatement("$T conn = getConnection()", connection);
        // 拼接阶段：无 WHERE 1=1——where 变量维护 " WHERE "/" AND " 前缀，
        // 运行时第一个执行的条件拼 WHERE、其后拼 AND（动态条件全为 null 时无 WHERE）。
        // 静态前缀折叠：条件序列头部连续的全静态条件在编译期并入 SQL 常量字段，
        // 运行时 StringBuilder 从常量起拼——不再逐调用重复拼接恒定前缀。
        int staticPrefix = 0;
        while (staticPrefix < conditions.size()
                && conditions.get(staticPrefix).staticCompatible()) {
            staticPrefix++;
        }
        if (staticPrefix > 0) {
            StringBuilder prefix = new StringBuilder(baseSql);
            WhereCondition.appendStaticWhereSql(prefix, conditions.subList(0, staticPrefix));
            String prefixField = methodName + "PrefixSql"
                    + (overloadIndex > 0 ? "_" + overloadIndex : "");
            builder.addField(FieldSpec.builder(String.class, prefixField,
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$S", prefix.toString()).build());
            spec.addStatement("$T sql = new $T($L)", ClassName.get(StringBuilder.class),
                    ClassName.get(StringBuilder.class), prefixField);
            // 前缀已消费首个 WHERE——剩余动态段从 AND 续拼。
            spec.addStatement("$T where = $S", ClassName.get(String.class), " AND ");
        } else {
            spec.addStatement("$T sql = new $T($S)", ClassName.get(StringBuilder.class),
                    ClassName.get(StringBuilder.class), baseSql);
            spec.addStatement("$T where = $S", ClassName.get(String.class), " WHERE ");
        }
        for (int i = staticPrefix; i < conditions.size(); i++) {
            WhereCondition.appendSql(spec, conditions.get(i));
        }
        criteriaGenerator.emitGroupAppend(spec, criteriaUnits, "where", " AND ");
        if (logSql) {
            spec.beginControlFlow("if (log.isDebugEnabled())");
            spec.addStatement("log.debug($S, sql.toString())", "Executing SQL: {}");
            spec.endControlFlow();
        }
        spec.beginControlFlow("try ($T ps = conn.prepareStatement(sql.toString()))", preparedStatement);
        // 绑定阶段：与拼接同条件展开，运行时索引 i 递增，类型精确 setXxx。
        spec.addStatement("int i = 1");
        for (WhereCondition condition : conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, criteriaUnits, "i++");

        // 结果映射：与 @Query 同一套（实体按下标+缺列校验 / record 按下标 / 标量 rs.getX(1)）。
        spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
        spec.endControlFlow();

        SqlCodegen.endTxBlockExpr(spec, sqlException, methodName, info.model.tableName(),
                "sql.toString()", configHelper.logSql(info.element));
        return spec.build();
    }

    /** 编译期解析一个 @Select 方法上的全部连接，并校验连接图的顺序和字段。 */
    private List<JoinSpec> parseJoins(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        Join[] annotations = method.getAnnotationsByType(Join.class);
        List<JoinSpec> result = new ArrayList<>();
        Map<String, EntityModel> available = new LinkedHashMap<>();
        available.put(info.model.entityQualifiedName(), info.model);
        for (Join annotation : annotations) {
            TypeElement targetElement = mirroredClass(annotation, true);
            if (targetElement == null) {
                error(method, "@Join.entity must be an entity type");
                return null;
            }
            EntityModel target = EntityModel.parse(targetElement, processingEnv.getTypeUtils(),
                    Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
            if (target == null) return null;
            if (available.containsKey(target.entityQualifiedName())) {
                error(method, "@Join does not support joining the same entity more than once: "
                        + target.entityQualifiedName());
                return null;
            }
            TypeElement fromElement = mirroredClass(annotation, false);
            EntityModel from = fromElement == null ? info.model
                    : available.get(fromElement.getQualifiedName().toString());
            if (from == null) {
                error(method, "@Join.from must be the host entity or an earlier joined entity");
                return null;
            }
            JoinType type = annotation.type();
            List<Join.On> on = List.of(annotation.on());
            if (type == JoinType.CROSS && !on.isEmpty()) {
                error(method, "CROSS JOIN cannot declare @Join.On conditions");
                return null;
            }
            if (type != JoinType.CROSS && on.isEmpty()) {
                error(method, "A non-CROSS @Join requires at least one @Join.On condition");
                return null;
            }
            for (Join.On pair : on) {
                if (findColumn(from, pair.local()) == null || findColumn(target, pair.target()) == null) {
                    error(method, "@Join.On refers to an unknown entity field: "
                            + pair.local() + " -> " + pair.target());
                    return null;
                }
            }
            result.add(new JoinSpec(from, target, type, on));
            available.put(target.entityQualifiedName(), target);
        }
        return result;
    }

    /** 读取 Class 注解属性而不加载用户类型（javac 会抛 MirroredTypeException）。 */
    private TypeElement mirroredClass(Join annotation, boolean target) {
        try {
            if (target) annotation.entity(); else annotation.from();
            return null;
        } catch (MirroredTypeException e) {
            if (e.getTypeMirror().getKind() != TypeKind.DECLARED) return null;
            return (TypeElement) ((DeclaredType) e.getTypeMirror()).asElement();
        }
    }

    private static EntityModel.ColumnModel findColumn(EntityModel model, String field) {
        for (EntityModel.ColumnModel column : model.columns()) {
            if (column.fieldName.equals(field)) return column;
        }
        return null;
    }

    private static String qualifiedColumn(EntityModel model, String field, DialectSupport dialect) {
        EntityModel.ColumnModel column = findColumn(model, field);
        return SqlCodegen.quoteIdentifier(dialect, model.tableName()) + "."
                + SqlCodegen.quoteIdentifier(dialect, column.columnName);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private record JoinSpec(EntityModel from, EntityModel target, JoinType type, List<Join.On> on) { }

}

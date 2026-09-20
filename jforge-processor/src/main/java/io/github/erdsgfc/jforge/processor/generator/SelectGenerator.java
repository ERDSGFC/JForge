package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.core.AbstractGenerator;
import io.github.erdsgfc.jforge.processor.generator.core.DaoMethod;
import io.github.erdsgfc.jforge.processor.generator.core.WhereCondition;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

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
 * WHERE 解析与折叠机制继承 {@link AbstractGenerator}；生成代码为编译期展开的
 * StringBuilder 拼接 + 类型精确绑定（拼接与绑定两阶段各自展开一次相同的条件判断，
 * 绑定用运行时索引）。结果映射委托 {@link QueryGenerator#appendResultMapping}
 * （与 {@code @Query} 完全一致）。</p>
 */
public final class SelectGenerator extends AbstractGenerator {

    private final QueryGenerator queryGenerator;

    /**
     * @param processingEnv  处理环境（messager 报错）
     * @param configHelper   共享的 ORM 配置 helper（命名策略）
     * @param queryGenerator 结果映射的委托目标（{@code @Query} 同一套映射逻辑）
     */
    public SelectGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper,
            QueryGenerator queryGenerator) {
        super(processingEnv, configHelper);
        this.queryGenerator = queryGenerator;
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
        // 条件可引用的连接表：键为实体全限定名<b>与</b>别名——按类型引用（常规）与
        // 按别名引用（同表多次连接）都能命中。同表多次连接时实体名键会被后一次覆盖，
        // 此时必须用 alias 引用（两者都缺省则报歧义）。
        Map<String, AbstractGenerator.JoinRef> entities = new LinkedHashMap<>();
        String hostQualifier = SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
        entities.put(info.model.entityQualifiedName(),
                new AbstractGenerator.JoinRef(info.model, hostQualifier));
        for (JoinSpec join : joins) {
            AbstractGenerator.JoinRef ref = new AbstractGenerator.JoinRef(join.target(), qualify(join));
            entities.put(join.target().entityQualifiedName(), ref);
            if (!join.alias().isEmpty()) {
                entities.put(join.alias(), ref);
            }
        }

        // 解析每个参数为一个 WHERE 条件（字段名 / 操作符 / 动态判定 + 字段存在性校验）。
        // @Where 参数是条件对象——递归展开为片段（值条件/括号分组/Optional IS NULL）。
        WhereParts parts = resolveWhereParts(info, method, entities, false);
        if (parts == null) {
            return null;
        }

        String baseSql = "SELECT " + columns + " FROM "
                + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName());
        for (JoinSpec join : joins) {
            baseSql += " " + join.type().sql() + " "
                    + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), join.target().tableName());
            if (!join.alias().isEmpty()) {
                baseSql += " AS " + SqlCodegen.quoteIdentifier(info.model.dialectSupport(), join.alias());
            }
            if (join.type() != JoinType.CROSS) {
                baseSql += " ON ";
                for (int i = 0; i < join.on().size(); i++) {
                    if (i > 0) baseSql += " AND ";
                    Join.On on = join.on().get(i);
                    baseSql += qualifiedColumn(join.from().qualifier(), join.from().model(), on.local(),
                                    info.model.dialectSupport())
                            + " = " + qualifiedColumn(join.qualifier(), join.target(), on.target(),
                                    info.model.dialectSupport());
                }
            }
        }
        MethodSpec.Builder spec = methodShell(methodName, method);
        boolean logSql = configHelper.logSql(info.element);
        emitTopLevelRequireNonNull(spec, method);

        // 全静态：WHERE 无动态参数，且条件对象（若存在）参数非空、字段全静态 →
        // 生成完整 SQL 常量字段 + 静态索引绑定（条件对象组同样折叠，运行时零拼接）。
        if (allStatic(parts)) {
            // WHERE 子句与绑定索引均编译期确定（与 @Query 同一形态，运行时零拼接）。
            StringBuilder fullSqlBuilder = new StringBuilder(baseSql);
            appendStaticWhere(fullSqlBuilder, parts);
            String sqlField = addStaticSqlField(builder, fullSqlBuilder.toString(), methodName, overloadIndex);
            SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, false, logSql);
            appendStaticWhereBinds(spec, parts, 1);
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
            spec.endControlFlow();
            SqlCodegen.endTxBlockField(spec, sqlException, methodName, info.model.tableName(), sqlField, logSql);
            return spec.build();
        }

        // 动态 SQL：sql 变量必须在 prepareStatement 之前声明，
        // 不能复用 beginTxBlock（其 try 头立即引用 sqlExpr），头部手动生成。
        spec.addStatement("$T conn = getConnection()", connection);
        // 拼接阶段：无 WHERE 1=1——where 变量维护 " WHERE "/" AND " 前缀，
        // 运行时第一个执行的条件拼 WHERE、其后拼 AND（动态条件全为 null 时无 WHERE）。
        // 静态前缀折叠：条件序列头部连续的全静态条件在编译期并入 SQL 常量字段，
        // 运行时 StringBuilder 从常量起拼——不再逐调用重复拼接恒定前缀。
        int staticPrefix = emitPrefixedStringBuilder(spec, builder, parts, baseSql, methodName, overloadIndex);
        spec.addStatement("$T where = $S", ClassName.get(String.class),
                staticPrefix > 0 ? " AND " : " WHERE ");
        for (int i = staticPrefix; i < parts.conditions.size(); i++) {
            WhereCondition.appendSql(spec, parts.conditions.get(i));
        }
        criteriaGenerator.emitGroupAppend(spec, parts.criteriaUnits, "where", " AND ");
        // 固化 SQL 字符串:DEBUG 日志、prepareStatement 与 catch 复用同一份,只 toString 一次。
        SqlCodegen.beginTxBlockVar(spec, preparedStatement, logSql);
        // 绑定阶段：与拼接同条件展开，运行时索引 i 递增，类型精确 setXxx。
        spec.addStatement("int i = 1");
        for (WhereCondition condition : parts.conditions) {
            WhereCondition.appendBind(spec, condition);
        }
        criteriaGenerator.emitBind(spec, parts.criteriaUnits, "i++");

        // 结果映射：与 @Query 同一套（实体按下标+缺列校验 / record 按下标 / 标量 rs.getX(1)）。
        spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        queryGenerator.appendResultMapping(spec, info, method, builder, embedded, returnType, baseSql);
        spec.endControlFlow();

        SqlCodegen.endTxBlockVar(spec, sqlException, methodName, info.model.tableName(), logSql);
        return spec.build();
    }

    /**
     * 编译期解析一个 {@code @Select} 方法上的全部连接：校验顺序与字段，
     * 解析每个连接的 {@code from}（显式 / 别名 / 推断），并为每个连接确定 SQL 限定符。
     *
     * @param info   仓库信息
     * @param method 标注 {@code @Select} 的方法
     * @return 连接规格序列；任一校验失败已报错返回 {@code null}
     */
    private List<JoinSpec> parseJoins(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        Join[] annotations = method.getAnnotationsByType(Join.class);
        List<JoinSpec> result = new ArrayList<>();
        // 可用连接表：宿主起步（键 = 实体全限定名），此后每个连接按「实体名 + 别名」登记。
        Map<String, AbstractGenerator.JoinRef> available = new LinkedHashMap<>();
        Set<String> aliases = new HashSet<>();
        available.put(info.model.entityQualifiedName(), new AbstractGenerator.JoinRef(info.model,
                SqlCodegen.quoteIdentifier(info.model.dialectSupport(), info.model.tableName())));
        for (Join annotation : annotations) {
            TypeElement targetElement = mirroredClass(annotation, true);
            if (targetElement == null) {
                error(method, "@Join.entity must be an entity type");
                return null;
            }
            EntityModel target = EntityModel.parse(targetElement, processingEnv.getTypeUtils(),
                    Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
            if (target == null) return null;
            String alias = annotation.alias();
            if (!alias.isEmpty()) {
                if (!aliases.add(alias)) {
                    error(method, "@Join.alias must be unique within a query: " + alias);
                    return null;
                }
                if (alias.equals(info.model.entityQualifiedName()) || alias.contains(".")) {
                    error(method, "@Join.alias must not collide with an entity qualified name: " + alias);
                    return null;
                }
            } else if (available.containsKey(target.entityQualifiedName())) {
                // 无别名时按实体类型引用——同表再次连接会无法区分，要求改用别名。
                error(method, "@Join does not support joining the same entity more than once"
                        + " (give each @Join a distinct alias): " + target.entityQualifiedName());
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
            AbstractGenerator.JoinRef from = resolveFrom(info, method, annotation, on, available);
            if (from == null) {
                return null;
            }
            for (Join.On pair : on) {
                if (findColumn(from.model(), pair.local()) == null || findColumn(target, pair.target()) == null) {
                    error(method, "@Join.On refers to an unknown entity field: "
                            + pair.local() + " -> " + pair.target());
                    return null;
                }
            }
            AbstractGenerator.JoinRef ref = new AbstractGenerator.JoinRef(target, qualify(alias, target, info));
            result.add(new JoinSpec(from, target, type, on, alias, ref.qualifier()));
            available.put(target.entityQualifiedName(), ref);
            if (!alias.isEmpty()) {
                available.put(alias, ref);
            }
        }
        return result;
    }

    /**
     * 连接的 SQL 限定符：有别名用别名，无别名用表名——ON 与条件表达式都以此前缀列。
     *
     * @param alias  声明的别名（空串 = 无别名）
     * @param target 连接的目标实体（无别名时取其表名）
     * @param info   仓库信息（方言）
     * @return 已按方言引用符包裹的限定符
     */
    private static String qualify(String alias, EntityModel target, JForgeProcessor.DaoInfo info) {
        DialectSupport dialect = info.model.dialectSupport();
        return SqlCodegen.quoteIdentifier(dialect, alias.isEmpty() ? target.tableName() : alias);
    }

    /** 连接的 SQL 限定符（ON 与条件表达式的前缀）。 */
    private static String qualify(JoinSpec join) {
        return join.qualifier();
    }

    /**
     * 解析连接的 ON 左侧（{@code from}），优先级依次为：
     * <ol>
     *   <li>{@link Join#fromAlias()}——按别名引用某一次连接（自连接/同表多角色的唯一途径）；</li>
     *   <li>{@link Join#from()}——按实体类型引用此前已连接的实体；</li>
     *   <li>都缺省时<b>从 ON 左侧字段推断</b>：{@code local} 字段所属的可用连接即 {@code from}
     *       （链式连接因此无需重复书写）。推断命中多个（字段重名）时报歧义，要求显式指定。</li>
     * </ol>
     *
     * <p>{@link JoinType#CROSS}（无 ON 字段）无可推断依据，且 {@code from} 不参与
     * CROSS 的 SQL 生成，故沿用宿主实体。</p>
     *
     * @param info      仓库信息（宿主实体）
     * @param method    标注 {@code @Select} 的方法（报错定位）
     * @param annotation 连接注解
     * @param on        ON 字段对
     * @param available 可用连接表（键 = 实体全限定名或别名）
     * @return ON 左侧连接；无法确定或歧义时已报错返回 {@code null}
     */
    private AbstractGenerator.JoinRef resolveFrom(JForgeProcessor.DaoInfo info, ExecutableElement method,
            Join annotation, List<Join.On> on, Map<String, AbstractGenerator.JoinRef> available) {
        String fromAlias = annotation.fromAlias();
        TypeElement fromElement = mirroredClass(annotation, false);
        if (!fromAlias.isEmpty() && fromElement != null) {
            error(method, "@Join cannot specify both from and fromAlias");
            return null;
        }
        if (!fromAlias.isEmpty()) {
            AbstractGenerator.JoinRef explicit = available.get(fromAlias);
            if (explicit == null) {
                error(method, "@Join.fromAlias must reference an earlier @Join alias: " + fromAlias);
            }
            return explicit;
        }
        if (fromElement != null) {
            // 显式指定：必须是宿主或此前已连接的实体。
            AbstractGenerator.JoinRef explicit = available.get(fromElement.getQualifiedName().toString());
            if (explicit == null) {
                error(method, "@Join.from must be the host entity or an earlier joined entity");
            }
            return explicit;
        }
        if (on.isEmpty()) {
            return available.get(info.model.entityQualifiedName());
        }
        // 未指定：推断 local 字段所属连接。available 同一次连接有两个键（实体名/别名），
        // 按限定符去重，避免同一连接被计成两个候选。
        Map<String, AbstractGenerator.JoinRef> deduped = new LinkedHashMap<>();
        for (AbstractGenerator.JoinRef candidate : available.values()) {
            deduped.putIfAbsent(candidate.qualifier(), candidate);
        }
        List<AbstractGenerator.JoinRef> candidates = new ArrayList<>();
        for (AbstractGenerator.JoinRef candidate : deduped.values()) {
            if (on.stream().allMatch(pair -> findColumn(candidate.model(), pair.local()) != null)) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        String locals = on.stream().map(Join.On::local).distinct().toList().toString();
        if (candidates.isEmpty()) {
            error(method, "@Join.from cannot be inferred: no host or earlier joined entity declares "
                    + locals + " (specify @Join.from or @Join.fromAlias explicitly)");
        } else {
            error(method, "@Join.from is ambiguous: " + locals + " exists in multiple joined entities "
                    + candidates.stream()
                            .map(c -> c.model().entitySimpleName() + "(" + c.qualifier() + ")").toList()
                    + " (specify @Join.from or @Join.fromAlias explicitly)");
        }
        return null;
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

    /** 按限定符（别名或表名）限定列表达式——ON 子句两侧都用它。 */
    private static String qualifiedColumn(String qualifier, EntityModel model, String field, DialectSupport dialect) {
        EntityModel.ColumnModel column = findColumn(model, field);
        return qualifier + "." + SqlCodegen.quoteIdentifier(dialect, column.columnName);
    }

    /**
     * 一个已解析的连接。
     *
     * @param from       ON 左侧连接（含其限定符）
     * @param target     目标实体
     * @param type       连接类型
     * @param on         ON 字段对
     * @param alias      本连接的别名（空串 = 无别名）
     * @param qualifier  本连接的 SQL 限定符（别名或表名，已按方言包裹）
     */
    private record JoinSpec(AbstractGenerator.JoinRef from, EntityModel target, JoinType type,
            List<Join.On> on, String alias, String qualifier) { }

}

package io.github.erdsgfc.jforge.processor.generator;

import com.palantir.javapoet.*;
import io.github.erdsgfc.jforge.annotation.BatchSize;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.utils.Nullability;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;
import io.github.erdsgfc.jforge.processor.utils.TypeNameUtils;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.github.erdsgfc.jforge.processor.ClassEnum.JDBC_RESULT_SET;
import static io.github.erdsgfc.jforge.processor.ClassEnum.ORM_EXCEPTION;

/**
 * 生成仓库 impl 类的 CRUD 方法（继承自 {@code BaseRepository} 的 13 个方法）以及行映射 helper
 * （{@code mapRow}/{@code countById}）与 JDBC 批处理辅助。
 *
 * <p>只依赖 {@link JForgeConfigHelper}（批大小解析）与静态工具类 {@link SqlCodegen}/
 * {@link TypeNameUtils}；其余信息经 {@link JForgeProcessor.DaoInfo} 参数传入。</p>
 */
public final class CrudGenerator {


    private final JForgeConfigHelper configHelper;

    /**
     * @param configHelper 共享的 ORM 配置助手（用于批大小解析）
     */
    public CrudGenerator(JForgeConfigHelper configHelper) {
        this.configHelper = configHelper;
    }

    /**
     * 构建继承自 {@code BaseRepository} 的全部 13 个 CRUD 方法。
     *
     * @param info             仓库信息
     * @param entityImpl       生成的实体 impl 类
     * @param connection       Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet        ResultSet 类
     * @param sqlException     SQLException 类
     * @return CRUD 方法规格
     */
    public List<MethodSpec> crudMethods(JForgeProcessor.DaoInfo info, ClassName entityImpl,
                                        ClassName connection, ClassName preparedStatement, ClassName resultSet,
                                        ClassName sqlException) {
        List<MethodSpec> methods = new ArrayList<>();
        methods.add(saveMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(saveAllMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(deleteMethod(info));
        methods.add(deleteManyMethod(info));
        methods.add(deleteByIdMethod(info, connection, preparedStatement, sqlException));
        methods.add(deleteByIdsMethod(info, connection, preparedStatement, sqlException));
        methods.add(updateMethod(info, entityImpl, connection, preparedStatement, sqlException));
        methods.add(findByIdMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(findByIdsMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(findAllMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(countMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(existsByIdMethod(info));
        methods.add(createEntityMethod(info, entityImpl));
        return methods;
    }

    /**
     * 构建 {@code createEntity()}：返回一个新的空实体 impl 实例——供不能直接引用 impl 类的
     * 调用方作为实体工厂使用。
     *
     * @param info       仓库信息
     * @param entityImpl 生成的实体 impl 类
     * @return createEntity 方法规格
     */
    private MethodSpec createEntityMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl) {
        return MethodSpec.methodBuilder("createEntity")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNonNull(info.entityType))
                .addStatement("return new $T()", entityImpl)
                .build();
    }

    /**
     * 构建 {@code save(T)}：INSERT（SQL 取 {@code saveSql} 字段）+ 类型精确绑定；
     * id 为生成策略时使用 {@code RETURN_GENERATED_KEYS} 并回写主键。
     *
     * @param info             仓库信息
     * @param connection       Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException     SQLException 类
     * @return save 方法规格
     */
    private MethodSpec saveMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        // PG/SQLite 方言:saveSql 含 RETURNING,executeQuery 单语句拿生成键(无需 executeUpdate
        // 后再 getGeneratedKeys);H2/MySQL 方言走 JDBC 标准路径。
        boolean returning = model.idGenerated()
                && configHelper.dialectSupport(info.element).supportsReturningKeys();
        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNonNull(info.entityType))
                .addParameter(Nullability.withNonNull(info.entityType), "entity");
        // @NonNull 契约参数快速失败:null 时明确 NPE,而非在 getter 解引用处抛模糊异常。
        Nullability.requireNonNull(method, "save", "entity", "entity");
        // 空列守卫:实体无可写列(全只读/策略排除)——编译期已确定,直接抛配置错误,
        // 避免生成 "INSERT INTO t () VALUES ()" 语法错误(纯只读实体仓库仍可定义)。
        if (insertColumns.isEmpty()) {
            method.addStatement("throw new $T($T.Code.CONFIGURATION, $S)",
                    ORM_EXCEPTION.getJavaPoetClassName(), ORM_EXCEPTION.getJavaPoetClassName(),
                    "save on table '" + model.tableName() + "': entity has no writable columns "
                            + "(all columns are read-only or excluded by write policy)");
            return method.build();
        }
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "saveSql",
                model.idGenerated() && !returning, configHelper.logSql(info.element));
        int index = 1;
        for (EntityModel.ColumnModel column : insertColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, getterCall(model, column, entityImpl, "entity"),
                    index++, column.nullable, column.isEnum,
                    column.converter != null ? SqlCodegen.converterFieldName(model, column) : null));
            method.addCode("\n");
        }
        if (!returning) {
            method.addStatement("ps.executeUpdate()");
        }
        // 生成键回写:接口有 setter 直接调用;只读 id(无 setter)强转到嵌套类调用
        // private 填充 setter(nestmates 允许宿主类访问嵌套类私有成员)。
        if (model.idGenerated()) {
            if (returning) {
                method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
                method.beginControlFlow("if (rs.next())");
                method.addStatement("$L.$L(rs.$L(1))", idWritebackReceiver(model, entityImpl, "entity"),
                        model.idColumn().setterName, TypeNameUtils.jdbcGetter(model.idColumn().typeName));
                method.endControlFlow();
                method.endControlFlow();
            } else {
                method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", JDBC_RESULT_SET.getJavaPoetClassName());
                method.beginControlFlow("if (keys.next())");
                method.addStatement("$L.$L(keys.$L(1))", idWritebackReceiver(model, entityImpl, "entity"),
                        model.idColumn().setterName, TypeNameUtils.jdbcGetter(model.idColumn().typeName));
                method.endControlFlow();
                method.endControlFlow();
            }
        }
        method.addStatement("return entity");
        SqlCodegen.endTxBlockExpr(method, sqlException, "save", info.model.tableName(),
                "saveSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建批量 {@code save(List<T>)}:在单个连接上插入全部实体——无论是否批处理,
     * 批量插入都不会为每行付出一次连接池往返(早期版本循环调用 {@code save(entity)},
     * 每个实体取一次连接)。
     *
     * <p>配置了正数的批处理大小(见 {@link #batchSizeFor})时,行经
     * {@code addBatch()/executeBatch()} 按该大小分块 flush;生成的主键从每个分块的
     * generated-keys 结果集回写,驱动按插入顺序返回这些行(H2 与 PostgreSQL 如此;
     * 注意事项见 {@code JForgeConfig.batchSize()})。为 {@code 0}(不批处理)时,
     * 行在共享连接上逐条插入。</p>
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return 批量 save 方法规格
     */
    private MethodSpec saveAllMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        boolean idGenerated = model.idGenerated();
        int batchSize = batchSizeFor(info);
        boolean batch = batchSize > 0;

        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType)))
                .addParameter(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType)), "entities");
        // @NonNull 契约参数快速失败(null → NPE);空列表是合法场景(直接返回)。
        Nullability.requireNonNull(method, "saveAll", "entities", "entities");
        method.beginControlFlow("if (entities.isEmpty())")
                .addStatement("return entities")
                .endControlFlow();
        // 空列守卫:与 save 相同——无可写列时批量插入同样是坏 SQL,直接抛配置错误。
        if (insertColumns.isEmpty()) {
            method.addStatement("throw new $T($T.Code.CONFIGURATION, $S)",
                    ORM_EXCEPTION.getJavaPoetClassName(), ORM_EXCEPTION.getJavaPoetClassName(),
                    "save on table '" + model.tableName() + "': entity has no writable columns "
                            + "(all columns are read-only or excluded by write policy)");
            return method.build();
        }
        // 批量走 saveAllSql(永不含 RETURNING)——批量生成键回写统一走 JDBC 标准
        // getGeneratedKeys,带 RETURNING 的批量结果读取跨驱动差异大。
        // beginTxBlock 统一生成连接获取 + logSql 的 DEBUG 日志 + try 资源块
        // (generatedKeys 参数等价于 prepareStatement(sql, RETURN_GENERATED_KEYS))。
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "saveAllSql",
                idGenerated, configHelper.logSql(info.element));

        if (batch) {
            // 分块 addBatch/executeBatch：每 batchSize 行冲刷一次，剩余行再冲刷一次；
            // 按插入顺序回读每块的生成键。
            method.addStatement("int batchSize = $L", batchSize);
            method.addStatement("int batchStart = 0");
            method.beginControlFlow("for ($T entity : entities)", info.entityType);
            bindColumns(method, model, entityImpl, insertColumns);
            method.addStatement("ps.addBatch()");
            method.addStatement("batchStart++");
            method.beginControlFlow("if (batchStart % batchSize == 0)");
            method.addStatement("ps.executeBatch()");
            appendBatchKeysWriteback(method, info, entityImpl, resultSet, "batchStart - batchSize", "batchStart");
            method.endControlFlow();
            method.endControlFlow();
            method.beginControlFlow("if (batchStart % batchSize != 0)");
            method.addStatement("ps.executeBatch()");
            appendBatchKeysWriteback(method, info, entityImpl, resultSet, "batchStart - batchStart % batchSize", "batchStart");
            method.endControlFlow();
        } else {
            // 不批处理：在共享连接上每行执行一次 executeUpdate。
            method.beginControlFlow("for ($T entity : entities)", info.entityType);
            bindColumns(method, model, entityImpl, insertColumns);
            method.addStatement("ps.executeUpdate()");
            if (idGenerated) {
                method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
                method.beginControlFlow("if (keys.next())");
                method.addStatement("$L.$L(keys.$L(1))", idWritebackReceiver(model, entityImpl, "entity"),
                        model.idColumn().setterName, TypeNameUtils.jdbcGetter(model.idColumn().typeName));
                method.endControlFlow();
                method.endControlFlow();
            }
            method.endControlFlow();
        }

        method.addStatement("return entities");
        SqlCodegen.endTxBlockExpr(method, sqlException, "save", info.model.tableName(),
                "saveAllSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 按列顺序追加单个实体行的 {@code PreparedStatement} 绑定调用（单个与批量 save 生成器共用）。
     *
     * @param method  方法构建器
     * @param columns INSERT 列
     */
    private void bindColumns(MethodSpec.Builder method, EntityModel model, ClassName entityImpl,
            List<EntityModel.ColumnModel> columns) {
        int index = 1;
        for (EntityModel.ColumnModel column : columns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, getterCall(model, column, entityImpl, "entity"),
                    index++, column.nullable, column.isEnum,
                    column.converter != null ? SqlCodegen.converterFieldName(model, column) : null));
            method.addCode("\n");
        }
    }

    /**
     * 绑定表达式中的 getter 调用:default 属性列强转到嵌套类调用默认值方法
     * ({@code ((StampUser_Impl) entity).defaultCreatedAt()},内部经 接口.super.getter()
     * 取默认值——宿主类不实现实体接口,TypeName.super 语法只能在嵌套类里用);
     * 普通属性列用 {@code receiver.getter()} 读实体字段。
     */
    private static String getterCall(EntityModel model, EntityModel.ColumnModel column,
            ClassName entityImpl, String receiver) {
        if (column.defaultGetter) {
            return "((" + entityImpl.simpleName() + ") " + receiver + ")."
                    + EntityModel.defaultMethodName(column.getterName) + "()";
        }
        return receiver + "." + column.getterName + "()";
    }

    /**
     * 生成基于固定 SQL 前缀的 IN 占位符列表。调用方须先处理空列表，
     * 以避免生成没有任何占位符的 IN () 语句。
     */
    private static void appendInSql(MethodSpec.Builder method, String baseSqlField) {
        // 预分配精确容量：baseSql.length() + ids.size()*2（每个 "?," + 末尾 ")"），避免扩容拷贝。
        method.addStatement("$T sql = new $T($N.length() + ids.size() * 2)",
                ClassName.get(StringBuilder.class), ClassName.get(StringBuilder.class), baseSqlField);
        method.addStatement("sql.append($N)", baseSqlField);
        // 首项外提:"?" 后循环追加 ",?"——省去逐项 if(i > 0) 判断(空列表由调用方先行处理)。
        method.addStatement("sql.append($S)", "?");
        method.beginControlFlow("for (int i = 1; i < ids.size(); i++)");
        method.addStatement("sql.append($S)", ",?");
        method.endControlFlow();
        method.addStatement("sql.append($S)", ")");
    }

    /** 生成 IN 参数绑定代码；索引变量与占位符数量严格一致。 */
    private static void appendInBindings(MethodSpec.Builder method, JForgeProcessor.DaoInfo info) {
        method.addStatement("int i = 1");
        method.beginControlFlow("for ($T id : ids)", info.idType);
        method.addCode(idBindParam(info, "id", "i"));
        method.addCode("\n");
        method.addStatement("i++");
        method.endControlFlow();
    }

    /**
     * 绑定主键参数并复用主键列的枚举/可空/转换器元数据，避免 ID CRUD 路径绕过
     * {@code @Convert} 或对可空包装类型错误地调用 setXxx(null)。
     */
    private static CodeBlock idBindParam(JForgeProcessor.DaoInfo info,
            String expr, int index) {
        return idBindParam(info, expr, String.valueOf(index));
    }

    private static CodeBlock idBindParam(JForgeProcessor.DaoInfo info,
            String expr, String indexExpr) {
        EntityModel.ColumnModel id = info.model.idColumn();
        // Repository ID parameters use a boxed type when the entity getter is primitive.
        // A nullable binding prevents JDBC setter auto-unboxing from throwing on null.
        boolean boxedRepositoryId = id.returnType.getKind().isPrimitive() && info.idType.isBoxedPrimitive();
        return SqlCodegen.bindParam(id.typeName, expr, indexExpr, id.nullable || boxedRepositoryId, id.isEnum,
                id.converter != null ? SqlCodegen.converterFieldName(info.model, id) : null);
    }

    /**
     * 为一个已冲刷的批次块追加生成键回写：遍历该块的 keys 结果集，把每个键赋给块中对应的
     * 实体（键按插入顺序返回），起始位置为块内相对索引表达式 {@code startExpr}。
     * 实体没有生成主键时不生成任何代码。
     *
     * @param method    方法构建器
     * @param info      仓库信息
     * @param entityImpl 生成的实体 impl 嵌套类（只读 id 强转回写用）
     * @param resultSet ResultSet 类
     * @param startExpr 块首实体在列表中的索引
     * @param endExpr   块尾实体索引（回写上限，防驱动键数与块行数不符时错位）
     */
    private void appendBatchKeysWriteback(MethodSpec.Builder method, JForgeProcessor.DaoInfo info,
            ClassName entityImpl, ClassName resultSet, String startExpr, String endExpr) {
        if (!info.model.idGenerated()) {
            return;
        }
        method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
        method.addStatement("int i = $L", startExpr);
        // 回写上限 = 本块尾:驱动返回的键数与块行数不符时(驱动差异/失败),防止
        // 越界把键错位写进下一块实体或静默漏写——宁可少写也不写错。
        method.beginControlFlow("while (keys.next() && i < $L)", endExpr);
        method.addStatement("$L.$L(keys.$L(1))", idWritebackReceiver(info.model, entityImpl, "entities.get(i)"),
                info.model.idColumn().setterName,
                TypeNameUtils.jdbcGetter(info.model.idColumn().typeName));
        method.addStatement("i++");
        method.endControlFlow();
        method.endControlFlow();
    }

    /**
     * 生成键回写语句的接收者表达式:接口声明了 id setter 时直接调用接口方法;
     * 只读 id(接口无 setter)时强转到嵌套类类型调用 private 填充 setter——
     * 运行时实体必然是嵌套类实例(经 {@code createEntity()}/行映射产生),
     * nestmates 允许宿主类访问嵌套类的私有成员。
     *
     * @param model     实体模型
     * @param entityImpl 实体 impl 嵌套类名
     * @param expr      实体引用表达式(如 {@code "entity"} 或 {@code "entities.get(i)"})
     * @return 回写接收者表达式
     */
    private static String idWritebackReceiver(EntityModel model, ClassName entityImpl, String expr) {
        return model.idColumn().hasSetter ? expr : "((" + entityImpl.simpleName() + ") " + expr + ")";
    }

    // ---- 批大小解析 ----------------------------------------------------------

    /**
     * 为可批处理的生成方法解析 JDBC 批大小，优先级依次为：重声明的批量方法上的
     * {@code @BatchSize}（如仓库上以相同签名重声明的 {@code save(List<T>)}）、仓库接口自身的
     * {@code @BatchSize}、{@code JForgeConfig.batchSize()}（元素级或包级）、默认值
     * （{@code 50}）。可批处理的方法是继承自 {@code BaseRepository} 的生成 CRUD 方法；
     * 目前只有 {@code save(List<T>)} 会批处理。
     *
     * @param info 仓库信息
     * @return 解析后的批大小（{@code 0} = 不批处理）
     */
    private int batchSizeFor(JForgeProcessor.DaoInfo info) {
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                BatchSize batchSize = method.getAnnotation(BatchSize.class);
                if (batchSize != null && isBatchableRedeclaration(method)) {
                    return batchSize.value();
                }
            }
        }
        BatchSize typeLevel = info.element.getAnnotation(BatchSize.class);
        if (typeLevel != null) {
            return typeLevel.value();
        }
        return configHelper.batchSize(info.element);
    }

    /**
     * 判断用户声明的方法是否重声明了可批处理的生成 CRUD 方法——即带单个 {@code List} 参数的
     * {@code save}。只有此类重声明可以携带方法级 {@code @BatchSize}；签名不匹配的声明本就无法
     * 针对 {@code BaseRepository} 编译通过。
     *
     * @param method 用户声明的方法
     * @return 该方法是重声明的 {@code save(List)} 时返回 {@code true}
     */
    private static boolean isBatchableRedeclaration(ExecutableElement method) {
        if (!method.getSimpleName().contentEquals("save")) {
            return false;
        }
        List<? extends VariableElement> params = method.getParameters();
        return params.size() == 1
                && params.get(0).asType().getKind() == TypeKind.DECLARED
                && ((DeclaredType) params.get(0).asType()).asElement().getSimpleName().contentEquals("List");
    }

    /**
     * 构建 {@code delete(T)}:按实体的 id 删除。
     *
     * @param info 仓库信息
     * @return delete 方法规格
     */
    private MethodSpec deleteMethod(JForgeProcessor.DaoInfo info) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("delete")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Nullability.withNonNull(info.entityType), "entity");
        Nullability.requireNonNull(method, "delete", "entity", "entity");
        method.addStatement("return deleteById(entity.$L())", info.model.idColumn().getterName);
        return method.build();
    }

    /**
     * 构建批量 {@code delete(List<T>)}:收集所有 id 并委托给 deleteByIds。
     *
     * @param info 仓库信息
     * @return 批量 delete 方法规格
     */
    private MethodSpec deleteManyMethod(JForgeProcessor.DaoInfo info) {
        EntityModel.ColumnModel idColumn = info.model.idColumn();
        MethodSpec.Builder method = MethodSpec.methodBuilder("delete")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addParameter(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType)), "entities");
        Nullability.requireNonNull(method, "delete", "entities", "entities");
        method.addStatement("$T<$T> ids = new $T<>()",
                ClassName.get(List.class), info.idType, ClassName.get(ArrayList.class));
        method.beginControlFlow("for ($T entity : entities)", info.entityType);
        method.addStatement("ids.add(entity.$L())", idColumn.getterName);
        method.endControlFlow();
        method.addStatement("return deleteByIds(ids)");
        return method.build();
    }

    /**
     * 构建 {@code deleteById(ID)}:{@code DELETE ... WHERE id=?}(SQL 取 {@code deleteByIdSql})。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     * @return deleteById 方法规格
     */
    private MethodSpec deleteByIdMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Nullability.withNonNull(info.idType), "id");
        Nullability.requireNonNull(method, "deleteById", "id", "id");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "deleteByIdSql", false, configHelper.logSql(info.element));
        method.addCode(idBindParam(info, "id", 1));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        SqlCodegen.endTxBlockExpr(method, sqlException, "deleteById", info.model.tableName(), "deleteByIdSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code deleteByIds(List<ID>)}:{@code DELETE ... WHERE id IN (?,...)} 带
     * 动态构建的占位符列表(基础 SQL 取 {@code deleteByIdsBaseSql} 字段)。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     * @return deleteByIds 方法规格
     */
    private MethodSpec deleteByIdsMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteByIds")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addParameter(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.idType)), "ids");
        Nullability.requireNonNull(method, "deleteByIds", "ids", "ids");
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return 0");
        method.endControlFlow();
        appendInSql(method, "deleteByIdsBaseSql");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "sql.toString()", false, configHelper.logSql(info.element));
        appendInBindings(method, info);
        method.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlockExpr(method, sqlException, "deleteByIds", info.model.tableName(),
                "sql.toString()", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code update(T)}:{@code UPDATE t SET c1=?,... WHERE id=?}(SQL 取 {@code updateSql}),
     * 先绑定所有非 id 可写列与 default 列,再绑定 id(纯只读列与 INSERT_ONLY 列不进 SET)。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     * @return update 方法规格
     */
    private MethodSpec updateMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        EntityModel model = info.model;
        // 与 updateSql 的 SET 子句保持同源:排除 id、纯只读列(无值来源)与
        // INSERT_ONLY/NONE 策略列。
        List<EntityModel.ColumnModel> updateColumns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!column.isId && column.updatable
                    && (column.hasSetter || column.defaultGetter)) {
                updateColumns.add(column);
            }
        }
        MethodSpec.Builder method = MethodSpec.methodBuilder("update")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Nullability.withNonNull(info.entityType), "entity");
        Nullability.requireNonNull(method, "update", "entity", "entity");
        // 空列守卫:SET 无列时 "UPDATE t SET WHERE ..." 是语法错误——编译期已确定,
        // 直接抛配置错误。
        if (updateColumns.isEmpty()) {
            method.addStatement("throw new $T($T.Code.CONFIGURATION, $S)",
                    ORM_EXCEPTION.getJavaPoetClassName(), ORM_EXCEPTION.getJavaPoetClassName(),
                    "update on table '" + model.tableName() + "': no updatable columns "
                            + "(all columns are read-only or excluded by write policy)");
            return method.build();
        }
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "updateSql", false, configHelper.logSql(info.element));
        int index = 1;
        for (EntityModel.ColumnModel column : updateColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, getterCall(model, column, entityImpl, "entity"),
                    index++, column.nullable, column.isEnum,
                    column.converter != null ? SqlCodegen.converterFieldName(model, column) : null));
            method.addCode("\n");
        }
        method.addCode(idBindParam(info, "entity." + model.idColumn().getterName + "()", index));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        SqlCodegen.endTxBlockExpr(method, sqlException, "update", info.model.tableName(), "updateSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findById(ID)}:{@code SELECT cols FROM t WHERE id=?}(SQL 取 {@code findByIdSql}),
     * 经 {@code mapRow} 映射单行,或返回 {@code null}。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findById 方法规格
     */
    private MethodSpec findByIdMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNullable(info.entityType))
                .addParameter(Nullability.withNonNull(info.idType), "id");
        Nullability.requireNonNull(method, "findById", "id", "id");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "findByIdSql", false, configHelper.logSql(info.element));
        method.addCode(idBindParam(info, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("if (!rs.next())");
        method.addStatement("return null");
        method.endControlFlow();
        method.addStatement("return mapRow(rs)");
        method.endControlFlow();
        SqlCodegen.endTxBlockExpr(method, sqlException, "findById", info.model.tableName(), "findByIdSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findByIds(List<ID>)}:{@code SELECT cols FROM t WHERE id IN (?,...)}
     * (基础 SQL 取 {@code findByIdsBaseSql} 字段)。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findByIds 方法规格
     */
    private MethodSpec findByIdsMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findByIds")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType)))
                .addParameter(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.idType)), "ids");
        Nullability.requireNonNull(method, "findByIds", "ids", "ids");
        // null 快速失败;空列表是合法场景(返回空结果)。
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return $T.of()", ClassName.get(List.class));
        method.endControlFlow();
        appendInSql(method, "findByIdsBaseSql");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "sql.toString()", false, configHelper.logSql(info.element));
        appendInBindings(method, info);
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get(ArrayList.class));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        SqlCodegen.endTxBlockExpr(method, sqlException, "findByIds", info.model.tableName(),
                "sql.toString()", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findAll()}:{@code SELECT cols FROM t}(SQL 取 {@code findAllSql})。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findAll 方法规格
     */
    private MethodSpec findAllMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(Nullability.withNonNull(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType)));
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "findAllSql", false, configHelper.logSql(info.element));
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get(ArrayList.class));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        SqlCodegen.endTxBlockExpr(method, sqlException, "findAll", info.model.tableName(), "findAllSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code count()}:{@code SELECT COUNT(*) FROM t}(SQL 取 {@code countSql})。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return count 方法规格
     */
    private MethodSpec countMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG);
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "countSql", false, configHelper.logSql(info.element));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        SqlCodegen.endTxBlockExpr(method, sqlException, "count", info.model.tableName(), "countSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code existsById(ID)}:委托给私有 {@code countById} helper。
     *
     * @param info         仓库信息
     * @return existsById 方法规格
     */
    private MethodSpec existsByIdMethod(JForgeProcessor.DaoInfo info) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("existsById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Nullability.withNonNull(info.idType), "id");
        Nullability.requireNonNull(method, "existsById", "id", "id");
        method.addStatement("return countById(id) > 0");
        return method.build();
    }

    /**
     * 构建供 {@code existsById} 使用的私有 {@code countById} helper
     * (SQL 取 {@code countByIdSql} 字段)。
     *
     * @param info              仓库信息
     * @param sqlException      SQLException 类
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @return countById 方法规格
     */
    public MethodSpec countByIdMethod(JForgeProcessor.DaoInfo info, ClassName sqlException,
                                      ClassName connection, ClassName preparedStatement, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("countById")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.LONG)
                .addParameter(Nullability.withNonNull(info.idType), "id");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "countByIdSql", false,
                configHelper.logSql(info.element));
        method.addCode(idBindParam(info, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        SqlCodegen.endTxBlockExpr(method, sqlException, "countById", info.model.tableName(),
                "countByIdSql", configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建私有 {@code mapRow} 方法:按列索引把当前 ResultSet 行映射为新的实体 impl
     * (列顺序始终等于字段顺序)。
     *
     * @param info         仓库信息
     * @param entityImpl   生成的实体 impl 类
     * @param sqlException SQLException 类
     * @param resultSet    ResultSet 类
     * @return mapRow 方法规格
     */
    public MethodSpec rowMapperMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl,
                                      ClassName sqlException, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("mapRow")
                .addModifiers(Modifier.PRIVATE)
                .returns(entityImpl)
                .addParameter(resultSet, "rs")
                .addException(sqlException)
                .addStatement("$T e = new $T()", entityImpl, entityImpl);
        List<EntityModel.ColumnModel> columns = info.model.columns();
        for (int i = 0; i < columns.size(); i++) {
            EntityModel.ColumnModel column = columns.get(i);
            method.addCode(SqlCodegen.readColumn(column.typeName, column.javaType, column.javaClassType,
                    "e", column.setterName, i + 1,
                    column.nullable, column.isEnum,
                    column.converter != null ? SqlCodegen.converterFieldName(info.model, column) : null));
            method.addCode("\n");
        }
        method.addStatement("return e");
        return method.build();
    }
}

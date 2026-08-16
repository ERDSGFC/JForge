package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import io.github.erdsgfc.jforge.annotation.BatchSize;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import java.util.ArrayList;
import java.util.List;

/**
 * 生成仓库 impl 类的 CRUD 方法（继承自 {@code BaseRepository} 的 13 个方法）以及行映射 helper
 * （{@code mapRow}/{@code countById}）与 JDBC 批处理辅助。
 *
 * <p>只依赖 {@link JForgeConfigHelper}（批大小解析）与静态工具类 {@link SqlCodegen}/
 * {@link TypeNameUtils}；其余信息经 {@link JForgeProcessor.DaoInfo} 参数传入。</p>
 */
final class CrudGenerator {

    private static final ClassName ORM_EXCEPTION = ClassName.get("io.github.erdsgfc.jforge", "JForgeException");

    private final JForgeConfigHelper configHelper;

    /**
     * @param configHelper 共享的 ORM 配置助手（用于批大小解析）
     */
    CrudGenerator(JForgeConfigHelper configHelper) {
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
    List<MethodSpec> crudMethods(JForgeProcessor.DaoInfo info, ClassName entityImpl,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException) {
        List<MethodSpec> methods = new ArrayList<>();
        methods.add(saveMethod(info, connection, preparedStatement, sqlException));
        methods.add(saveAllMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(deleteMethod(info));
        methods.add(deleteManyMethod(info));
        methods.add(deleteByIdMethod(info, connection, preparedStatement, sqlException));
        methods.add(deleteByIdsMethod(info, connection, preparedStatement, sqlException));
        methods.add(updateMethod(info, connection, preparedStatement, sqlException));
        methods.add(findByIdMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(findByIdsMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(findAllMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(countMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(existsByIdMethod(info, sqlException));
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
                .returns(info.entityType)
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
    private MethodSpec saveMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.entityType, "entity");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "saveSql", model.idGenerated(), configHelper.logSql(info.element));
        int index = 1;
        for (EntityModel.ColumnModel column : insertColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
        method.addStatement("ps.executeUpdate()");
        if (model.idGenerated()) {
            method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", ClassName.get("java.sql", "ResultSet"));
            method.beginControlFlow("if (keys.next())");
            method.addStatement("entity.$L(keys.get$L(1))", model.idColumn().setterName,
                    TypeNameUtils.jdbcReturnSuffix(model.idColumn().typeName));
            method.endControlFlow();
            method.endControlFlow();
        }
        method.addStatement("return entity");
        SqlCodegen.endTxBlock(method, sqlException, "save", info.model.tableName(), SqlFieldGenerator.saveSql(info), configHelper.logSql(info.element));
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
    private MethodSpec saveAllMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = SqlCodegen.insertColumns(model);
        boolean idGenerated = model.idGenerated();
        int batchSize = batchSizeFor(info);
        boolean batch = batchSize > 0;

        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType))
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType), "entities")
                .beginControlFlow("if (entities.isEmpty())")
                .addStatement("return entities")
                .endControlFlow()
                .addStatement("$T conn = getConnection()", connection);
        if (idGenerated) {
            method.beginControlFlow("try ($T ps = conn.prepareStatement(saveSql, $T.RETURN_GENERATED_KEYS))",
                    preparedStatement, ClassName.get("java.sql", "Statement"));
        } else {
            method.beginControlFlow("try ($T ps = conn.prepareStatement(saveSql))", preparedStatement);
        }

        if (batch) {
            // 分块 addBatch/executeBatch：每 batchSize 行冲刷一次，剩余行再冲刷一次；
            // 按插入顺序回读每块的生成键。
            method.addStatement("int batchSize = $L", batchSize);
            method.addStatement("int batchStart = 0");
            method.beginControlFlow("for ($T entity : entities)", info.entityType);
            bindColumns(method, insertColumns);
            method.addStatement("ps.addBatch()");
            method.addStatement("batchStart++");
            method.beginControlFlow("if (batchStart % batchSize == 0)");
            method.addStatement("ps.executeBatch()");
            appendBatchKeysWriteback(method, info, resultSet, "batchStart - batchSize");
            method.endControlFlow();
            method.endControlFlow();
            method.beginControlFlow("if (batchStart % batchSize != 0)");
            method.addStatement("ps.executeBatch()");
            appendBatchKeysWriteback(method, info, resultSet, "batchStart - batchStart % batchSize");
            method.endControlFlow();
        } else {
            // 不批处理：在共享连接上每行执行一次 executeUpdate。
            method.beginControlFlow("for ($T entity : entities)", info.entityType);
            bindColumns(method, insertColumns);
            method.addStatement("ps.executeUpdate()");
            if (idGenerated) {
                method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
                method.beginControlFlow("if (keys.next())");
                method.addStatement("entity.$L(keys.get$L(1))", model.idColumn().setterName,
                        TypeNameUtils.jdbcReturnSuffix(model.idColumn().typeName));
                method.endControlFlow();
                method.endControlFlow();
            }
            method.endControlFlow();
        }

        method.addStatement("return entities");
        SqlCodegen.endTxBlock(method, sqlException, "save", info.model.tableName(), SqlFieldGenerator.saveSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 按列顺序追加单个实体行的 {@code PreparedStatement} 绑定调用（单个与批量 save 生成器共用）。
     *
     * @param method  方法构建器
     * @param columns INSERT 列
     */
    private void bindColumns(MethodSpec.Builder method, List<EntityModel.ColumnModel> columns) {
        int index = 1;
        for (EntityModel.ColumnModel column : columns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
    }

    /**
     * 为一个已冲刷的批次块追加生成键回写：遍历该块的 keys 结果集，把每个键赋给块中对应的
     * 实体（键按插入顺序返回），起始位置为块内相对索引表达式 {@code startExpr}。
     * 实体没有生成主键时不生成任何代码。
     *
     * @param method    方法构建器
     * @param info      仓库信息
     * @param resultSet ResultSet 类
     * @param startExpr 块首实体在列表中的索引
     */
    private void appendBatchKeysWriteback(MethodSpec.Builder method, JForgeProcessor.DaoInfo info,
            ClassName resultSet, String startExpr) {
        if (!info.model.idGenerated()) {
            return;
        }
        method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
        method.addStatement("int i = $L", startExpr);
        method.beginControlFlow("while (keys.next())");
        method.addStatement("entities.get(i).$L(keys.get$L(1))", info.model.idColumn().setterName,
                TypeNameUtils.jdbcReturnSuffix(info.model.idColumn().typeName));
        method.addStatement("i++");
        method.endControlFlow();
        method.endControlFlow();
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
        return MethodSpec.methodBuilder("delete")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.entityType, "entity")
                .addStatement("return deleteById(entity.$L())", info.model.idColumn().getterName)
                .build();
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
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType), "entities")
                .addStatement("$T<$T> ids = new $T<>()",
                        ClassName.get(List.class), info.idType, ClassName.get("java.util", "ArrayList"))
                .beginControlFlow("for ($T entity : entities)", info.entityType)
                .addStatement("ids.add(entity.$L())", idColumn.getterName)
                .endControlFlow()
                .addStatement("return deleteByIds(ids)");
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
                .addParameter(info.idType, "id");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "deleteByIdSql", false, configHelper.logSql(info.element));
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        SqlCodegen.endTxBlock(method, sqlException, "deleteById", info.model.tableName(), SqlFieldGenerator.deleteByIdSql(info), configHelper.logSql(info.element));
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
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.idType), "ids");
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return 0");
        method.endControlFlow();
        // 预分配精确容量：baseSql.length() + ids.size()*2（每个 id 的 "?," + 末尾 ")"），避免扩容拷贝。
        method.addStatement("$T sql = new $T($N.length() + ids.size() * 2)",
                ClassName.get("java.lang", "StringBuilder"),
                ClassName.get("java.lang", "StringBuilder"), "deleteByIdsBaseSql");
        method.addStatement("sql.append($N)", "deleteByIdsBaseSql");
        method.beginControlFlow("for (int i = 0; i < ids.size(); i++)");
        method.beginControlFlow("if (i > 0)");
        method.addStatement("sql.append($S)", ",");
        method.endControlFlow();
        method.addStatement("sql.append($S)", "?");
        method.endControlFlow();
        method.addStatement("sql.append($S)", ")");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "sql.toString()", false, configHelper.logSql(info.element));
        method.addStatement("int i = 1");
        method.beginControlFlow("for ($T id : ids)", info.idType);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", "i"));
        method.addCode("\n");
        method.addStatement("i++");
        method.endControlFlow();
        method.addStatement("return ps.executeUpdate()");
        SqlCodegen.endTxBlock(method, sqlException, "deleteByIds", info.model.tableName(), SqlFieldGenerator.deleteByIdsBaseSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code update(T)}:{@code UPDATE t SET c1=?,... WHERE id=?}(SQL 取 {@code updateSql}),
     * 先绑定所有非 id 列,再绑定 id。
     *
     * @param info              仓库信息
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param sqlException      SQLException 类
     * @return update 方法规格
     */
    private MethodSpec updateMethod(JForgeProcessor.DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> updateColumns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!column.isId) {
                updateColumns.add(column);
            }
        }
        MethodSpec.Builder method = MethodSpec.methodBuilder("update")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.entityType, "entity");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "updateSql", false, configHelper.logSql(info.element));
        int index = 1;
        for (EntityModel.ColumnModel column : updateColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
        method.addCode(SqlCodegen.bindParam(model.idColumn().typeName,
                "entity." + model.idColumn().getterName + "()", index));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        SqlCodegen.endTxBlock(method, sqlException, "update", info.model.tableName(), SqlFieldGenerator.updateSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findById(ID)}:{@code SELECT cols FROM t WHERE id=?}(SQL 取 {@code findByIdSql}),
     * 经 {@code mapRow} 映射单行,或返回 {@code null}。
     *
     * @param info              仓库信息
     * @param entityImpl        生成的实体 impl 类
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findById 方法规格
     */
    private MethodSpec findByIdMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.idType, "id");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "findByIdSql", false, configHelper.logSql(info.element));
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("if (!rs.next())");
        method.addStatement("return null");
        method.endControlFlow();
        method.addStatement("return mapRow(rs)");
        method.endControlFlow();
        SqlCodegen.endTxBlock(method, sqlException, "findById", info.model.tableName(), SqlFieldGenerator.findByIdSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findByIds(List<ID>)}:{@code SELECT cols FROM t WHERE id IN (?,...)}
     * (基础 SQL 取 {@code findByIdsBaseSql} 字段)。
     *
     * @param info              仓库信息
     * @param entityImpl        生成的实体 impl 类
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findByIds 方法规格
     */
    private MethodSpec findByIdsMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findByIds")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType))
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.idType), "ids");
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return $T.of()", ClassName.get(List.class));
        method.endControlFlow();
        // 预分配精确容量：baseSql.length() + ids.size()*2（每个 id 的 "?," + 末尾 ")"），避免扩容拷贝。
        method.addStatement("$T sql = new $T($N.length() + ids.size() * 2)",
                ClassName.get("java.lang", "StringBuilder"),
                ClassName.get("java.lang", "StringBuilder"), "findByIdsBaseSql");
        method.addStatement("sql.append($N)", "findByIdsBaseSql");
        method.beginControlFlow("for (int i = 0; i < ids.size(); i++)");
        method.beginControlFlow("if (i > 0)");
        method.addStatement("sql.append($S)", ",");
        method.endControlFlow();
        method.addStatement("sql.append($S)", "?");
        method.endControlFlow();
        method.addStatement("sql.append($S)", ")");
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "sql.toString()", false, configHelper.logSql(info.element));
        method.addStatement("int i = 1");
        method.beginControlFlow("for ($T id : ids)", info.idType);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", "i"));
        method.addCode("\n");
        method.addStatement("i++");
        method.endControlFlow();
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get("java.util", "ArrayList"));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        SqlCodegen.endTxBlock(method, sqlException, "findByIds", info.model.tableName(), SqlFieldGenerator.findByIdsBaseSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code findAll()}:{@code SELECT cols FROM t}(SQL 取 {@code findAllSql})。
     *
     * @param info              仓库信息
     * @param entityImpl        生成的实体 impl 类
     * @param connection        Connection 类
     * @param preparedStatement PreparedStatement 类
     * @param resultSet         ResultSet 类
     * @param sqlException      SQLException 类
     * @return findAll 方法规格
     */
    private MethodSpec findAllMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType));
        SqlCodegen.beginTxBlock(method, connection, preparedStatement, "findAllSql", false, configHelper.logSql(info.element));
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get("java.util", "ArrayList"));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        SqlCodegen.endTxBlock(method, sqlException, "findAll", info.model.tableName(), SqlFieldGenerator.findAllSql(info), configHelper.logSql(info.element));
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
        SqlCodegen.endTxBlock(method, sqlException, "count", info.model.tableName(), SqlFieldGenerator.countSql(info), configHelper.logSql(info.element));
        return method.build();
    }

    /**
     * 构建 {@code existsById(ID)}:委托给私有 {@code countById} helper。
     *
     * @param info         仓库信息
     * @param sqlException SQLException 类
     * @return existsById 方法规格
     */
    private MethodSpec existsByIdMethod(JForgeProcessor.DaoInfo info, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("existsById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.idType, "id");
        method.beginControlFlow("try");
        method.addStatement("return countById(id) > 0");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($T.Code.SQL, $S + e.getMessage(), $S, e)",
                ORM_EXCEPTION, ORM_EXCEPTION,
                "existsById on table '" + info.model.tableName() + "' [" + SqlFieldGenerator.countByIdSql(info) + "]: ",
                SqlFieldGenerator.countByIdSql(info));
        method.endControlFlow();
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
    MethodSpec countByIdMethod(JForgeProcessor.DaoInfo info, ClassName sqlException,
            ClassName connection, ClassName preparedStatement, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("countById")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.LONG)
                .addParameter(info.idType, "id")
                .addException(sqlException);
        method.addStatement("$T conn = getConnection()", connection);
        method.beginControlFlow("try ($T ps = conn.prepareStatement($N))", preparedStatement, "countByIdSql");
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($T.Code.SQL, $S + e.getMessage(), $S, e)",
                ORM_EXCEPTION, ORM_EXCEPTION,
                "countById on table '" + info.model.tableName() + "' [" + SqlFieldGenerator.countByIdSql(info) + "]: ",
                SqlFieldGenerator.countByIdSql(info));
        method.nextControlFlow("finally");
        method.addStatement("releaseConnection(conn)");
        method.endControlFlow();
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
    MethodSpec rowMapperMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl,
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
            method.addCode(SqlCodegen.readColumn(column.typeName, "e", column.setterName, i + 1));
            method.addCode("\n");
        }
        method.addStatement("return e");
        return method.build();
    }
}

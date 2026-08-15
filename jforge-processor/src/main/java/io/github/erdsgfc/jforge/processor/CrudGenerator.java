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
     * @param configHelper the shared ORM config helper (for batch-size resolution)
     */
    CrudGenerator(JForgeConfigHelper configHelper) {
        this.configHelper = configHelper;
    }

    /**
     * Builds all 13 CRUD methods inherited from {@code BaseRepository}.
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the CRUD method specifications
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
     * Builds {@code createEntity()}: returns a new empty entity impl instance —
     * the entity factory for callers that must not reference the impl class directly.
     *
     * @param info       the repository info
     * @param entityImpl the generated entity impl class
     * @return the createEntity method specification
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
     * Builds {@code save(T)}: INSERT (SQL 取 {@code saveSql} 字段) with type-exact binds;
     * when the id is generated, uses {@code RETURN_GENERATED_KEYS} and writes the key back.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the save method specification
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
     * Builds the batch {@code save(List<T>)}: inserts all entities on a single
     * connection — with or without batching, so a batch insert never pays a pool
     * round-trip per row (earlier versions looped {@code save(entity)} and
     * acquired one connection per entity).
     *
     * <p>With a positive configured batch size (see {@link #batchSizeFor}) the
     * rows are flushed via {@code addBatch()/executeBatch()} in chunks of that
     * size; generated ids are written back from each chunk's generated-keys
     * result set, whose rows drivers return in insertion order (H2 and PostgreSQL
     * do; see {@code JForgeConfig.batchSize()} for the caveat). With {@code 0}
     * (no batching) the rows are inserted one by one on the shared connection.</p>
     *
     * @param info              the repository info
     * @param connection        the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet         the ResultSet class
     * @param sqlException      the SQLException class
     * @return the batch save method specification
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
            // Chunked addBatch/executeBatch: flush every batchSize rows, then the
            // remainder; read each chunk's generated keys back in insertion order.
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
            // No batching: one executeUpdate per row on the shared connection.
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
     * Appends the {@code PreparedStatement} bind calls for one entity row, in
     * column order (shared by the single and batch save generators).
     *
     * @param method  the method builder
     * @param columns the insert columns
     */
    private void bindColumns(MethodSpec.Builder method, List<EntityModel.ColumnModel> columns) {
        int index = 1;
        for (EntityModel.ColumnModel column : columns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
    }

    /**
     * Appends the generated-keys write-back for one flushed batch chunk: iterates
     * the chunk's keys result set and assigns each key to the corresponding entity
     * of the chunk (keys are returned in insertion order), starting at the
     * chunk-relative index expression {@code startExpr}. Emits nothing when the
     * entity has no generated id.
     *
     * @param method     the method builder
     * @param info       the repository info
     * @param resultSet  the ResultSet class
     * @param startExpr  the list index of the chunk's first entity
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

    // ---- Batch size resolution ----------------------------------------------

    /**
     * Resolves the JDBC batch size for a batchable generated method, in order:
     * a {@code @BatchSize} on a redeclared batch method (e.g. {@code save(List<T>)}
     * redeclared on the repository with an identical signature), then a
     * {@code @BatchSize} on the repository interface itself, then
     * {@code JForgeConfig.batchSize()} (element or package), then the default
     * ({@code 50}). Batchable methods are the generated CRUD methods inherited
     * from {@code BaseRepository}; today only {@code save(List<T>)} batches.
     *
     * @param info the repository info
     * @return the resolved batch size ({@code 0} = no batching)
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
     * Returns whether the user-declared method redeclares a batchable generated
     * CRUD method — a {@code save} with a single {@code List} parameter. Only such
     * redeclarations can carry a method-level {@code @BatchSize}; a mismatched
     * signature would not compile against {@code BaseRepository} anyway.
     *
     * @param method the user-declared method
     * @return {@code true} when the method is a redeclared {@code save(List)}
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
     * Builds {@code delete(T)}: deletes by the entity's id.
     *
     * @param info the repository info
     * @return the delete method specification
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
     * Builds the batch {@code delete(List<T>)}: collects ids and delegates to deleteByIds.
     *
     * @param info the repository info
     * @return the batch delete method specification
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
     * Builds {@code deleteById(ID)}: {@code DELETE ... WHERE id=?} (SQL 取 {@code deleteByIdSql}).
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the deleteById method specification
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
     * Builds {@code deleteByIds(List<ID>)}: {@code DELETE ... WHERE id IN (?,...)} with a
     * dynamically built placeholder list (base SQL 取 {@code deleteByIdsBaseSql} 字段)。
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the deleteByIds method specification
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
     * Builds {@code update(T)}: {@code UPDATE t SET c1=?,... WHERE id=?} (SQL 取 {@code updateSql}),
     * binding all non-id columns then the id.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the update method specification
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
     * Builds {@code findById(ID)}: {@code SELECT cols FROM t WHERE id=?} (SQL 取 {@code findByIdSql}),
     * mapping a single row via {@code mapRow} or returning {@code null}.
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findById method specification
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
     * Builds {@code findByIds(List<ID>)}: {@code SELECT cols FROM t WHERE id IN (?,...)}
     * (base SQL 取 {@code findByIdsBaseSql} 字段).
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findByIds method specification
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
     * Builds {@code findAll()}: {@code SELECT cols FROM t} (SQL 取 {@code findAllSql}).
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findAll method specification
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
     * Builds {@code count()}: {@code SELECT COUNT(*) FROM t} (SQL 取 {@code countSql}).
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the count method specification
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
     * Builds {@code existsById(ID)}: delegates to the private {@code countById} helper.
     *
     * @param info         the repository info
     * @param sqlException the SQLException class
     * @return the existsById method specification
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
     * Builds the private {@code countById} helper used by {@code existsById}
     * (SQL 取 {@code countByIdSql} 字段).
     *
     * @param info             the repository info
     * @param sqlException     the SQLException class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @return the countById method specification
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
     * Builds the private {@code mapRow} method: maps the current ResultSet row to a
     * new entity impl by column index (column order always equals field order).
     *
     * @param info         the repository info
     * @param entityImpl   the generated entity impl class
     * @param sqlException the SQLException class
     * @param resultSet    the ResultSet class
     * @return the mapRow method specification
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

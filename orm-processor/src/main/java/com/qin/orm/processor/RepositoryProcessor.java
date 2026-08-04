package com.qin.orm.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;
import com.qin.orm.annotation.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.*;

/**
 * Generates the concrete implementation for each {@code @Dao} repository interface:
 * CRUD methods inherited from {@code com.qin.orm.core.BaseRepository} (parameterized
 * with the entity and id types) plus {@link Query}-annotated custom methods. The
 * generated code is direct JDBC with type-exact binders/readers — no reflection.
 */
@AutoService(javax.annotation.processing.Processor.class)
@SupportedAnnotationTypes("com.qin.orm.annotation.Dao")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class RepositoryProcessor extends AbstractProcessor {

    private static final String BASE_REPOSITORY = "com.qin.orm.core.BaseRepository";
    private static final ClassName ORM_EXCEPTION = ClassName.get("com.qin.orm", "OrmException");
    private static final ClassName TX_MANAGER = ClassName.get("com.qin.orm", "TransactionManager");

    private final List<DaoInfo> daos = new ArrayList<>();
    private int lastFactoriesSize;
    private OrmConfigHelper configHelper;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        configHelper = new OrmConfigHelper(processingEnv);
    }

    private static final class DaoInfo {
        TypeElement element;
        String daoQualifiedName;
        String daoSimpleName;
        String daoPackage;
        TypeName entityType;
        TypeName idType;
        String idTypeName;
        EntityModel model;
        String implName;
    }

    /**
     * Scans the current round's root elements for {@code @Dao} interfaces and
     * generates their implementation classes, plus the {@code Repositories} factory.
     *
     * @param annotations the annotation types requested by this processor
     * @param roundEnv    the current processing round
     * @return {@code true} (the @Dao annotation is claimed by this processor)
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        for (Element element : roundEnv.getElementsAnnotatedWith(Dao.class)) {
            if (element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            processDao((TypeElement) element);
        }
        if (daos.size() != lastFactoriesSize) {
            writeFactories();
            lastFactoriesSize = daos.size();
        }
        return true;
    }

    /**
     * Generates the implementation class for one {@code @Dao} repository interface:
     * resolves the entity/id types from {@code BaseRepository<T, ID>}, parses the
     * entity model, and emits the CRUD + {@code @Query} methods.
     *
     * @param dao the {@code @Dao} repository interface
     */
    private void processDao(TypeElement dao) {
        DaoInfo info = new DaoInfo();
        info.element = dao;
        info.daoQualifiedName = dao.getQualifiedName().toString();
        info.daoSimpleName = dao.getSimpleName().toString();
        info.daoPackage = packageOf(info.daoQualifiedName);
        info.implName = info.daoSimpleName + "_Impl";

        // Resolve BaseRepository<T, ID> type arguments.
        TypeMirror entityMirror = null;
        TypeMirror idMirror = null;
        for (TypeMirror iface : dao.getInterfaces()) {
            if (iface.getKind() == TypeKind.DECLARED) {
                DeclaredType declared = (DeclaredType) iface;
                TypeElement ifaceElement = (TypeElement) declared.asElement();
                if (ifaceElement.getQualifiedName().contentEquals(BASE_REPOSITORY)
                        && declared.getTypeArguments().size() == 2) {
                    entityMirror = declared.getTypeArguments().get(0);
                    idMirror = declared.getTypeArguments().get(1);
                }
            }
        }
        if (entityMirror == null || idMirror == null) {
            error(dao, "@Dao interface must extend BaseRepository<T, ID>");
            return;
        }

        TypeElement entityElement = (TypeElement) ((DeclaredType) entityMirror).asElement();
        if (entityElement.getAnnotation(Table.class) == null) {
            error(dao, "Entity type " + entityElement.getQualifiedName() + " must be annotated with @Table");
            return;
        }
        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return;
        }

        info.model = model;
        info.entityType = ClassName.get(model.entityPackage(), model.entitySimpleName());
        info.idType = TypeNameUtils.toTypeName(idMirror.toString());
        info.idTypeName = idMirror.toString();

        TypeSpec typeSpec = buildImpl(info);
        try {
            JavaFile.builder(info.daoPackage, typeSpec)
                    .addFileComment("Generated at compile time by RepositoryProcessor. Do not edit.")
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(dao, "Failed to generate " + info.implName + ": " + e.getMessage());
        }
        daos.add(info);
    }

    // ==================== Implementation class ====================

    /**
     * Assembles the repository implementation class: data source field, constructor,
     * shared row mapper, count helper, the 13 CRUD methods, and the {@code @Query} methods.
     *
     * @param info the parsed repository info
     * @return the generated class specification
     */
    private TypeSpec buildImpl(DaoInfo info) {
        ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
        ClassName dataSource = ClassName.get("javax.sql", "DataSource");
        ClassName entityImpl = ClassName.get(info.model.entityPackage(),
                EntityModel.implNameOf(info.model.entitySimpleName(), info.model.implSuffix()));
        ClassName connection = ClassName.get("java.sql", "Connection");
        ClassName preparedStatement = ClassName.get("java.sql", "PreparedStatement");
        ClassName resultSet = ClassName.get("java.sql", "ResultSet");
        ClassName sqlException = ClassName.get("java.sql", "SQLException");
        ClassName statement = ClassName.get("java.sql", "Statement");

        TypeSpec.Builder builder = TypeSpec.classBuilder(info.implName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(daoClass)
                .addField(FieldSpec.builder(dataSource, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build())
                .addMethod(MethodSpec.constructorBuilder()
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(dataSource, "dataSource")
                        .addStatement("this.dataSource = dataSource")
                        .build());

        // Transaction-aware connection helpers.
        builder.addMethod(MethodSpec.methodBuilder("getConnection")
                .addModifiers(Modifier.PRIVATE)
                .returns(connection)
                .addStatement("return $T.current().connection(dataSource)", TX_MANAGER)
                .build());
        builder.addMethod(MethodSpec.methodBuilder("releaseConnection")
                .addModifiers(Modifier.PRIVATE)
                .addParameter(connection, "conn")
                .addStatement("$T.current().release(conn, dataSource)", TX_MANAGER)
                .build());

        // Programmatic transaction methods inherited from TransactionOperations.
        for (MethodSpec method : txMethods()) {
            builder.addMethod(method);
        }

        // Shared private row mapper for entity rows (column order = field order).
        builder.addMethod(rowMapperMethod(info, entityImpl, sqlException, resultSet));
        builder.addMethod(countByIdMethod(info, sqlException, connection, preparedStatement, resultSet));
        for (MethodSpec method : crudMethods(info, entityImpl, connection, preparedStatement, resultSet,
                sqlException, statement)) {
            builder.addMethod(method);
        }
        queryMethods(info, builder, connection, preparedStatement, resultSet, sqlException, statement);

        return builder.build();
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
    private MethodSpec rowMapperMethod(DaoInfo info, ClassName entityImpl,
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

    /**
     * Builds the private {@code countById} helper used by {@code existsById}.
     *
     * @param info             the repository info
     * @param sqlException     the SQLException class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @return the countById method specification
     */
    private MethodSpec countByIdMethod(DaoInfo info, ClassName sqlException,
            ClassName connection, ClassName preparedStatement, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("countById")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.LONG)
                .addParameter(TypeNameUtils.toTypeName(info.idTypeName), "id")
                .addException(sqlException);
        method.addStatement("$T conn = getConnection()", connection);
        method.beginControlFlow("try");
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement,
                "SELECT COUNT(*) FROM " + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?");
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "count failed");
        method.nextControlFlow("finally");
        method.addStatement("releaseConnection(conn)");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds the four programmatic-transaction methods inherited from
     * {@code TransactionOperations}: begin/commit/rollback/isTransactionActive,
     * delegating to the global {@link TransactionManager}. The {@code execute}
     * template is a {@code default} interface method and therefore needs no
     * generated implementation.
     *
     * @return the four transaction method specifications
     */
    private List<MethodSpec> txMethods() {
        return List.of(
                MethodSpec.methodBuilder("beginTransaction")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("$T.current().begin(dataSource)", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("commit")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("$T.current().commit()", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("rollback")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("$T.current().rollback()", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("isTransactionActive")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.BOOLEAN)
                        .addStatement("return $T.current().isActive()", TX_MANAGER)
                        .build());
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
     * @param statement        the Statement class
     * @return the CRUD method specifications
     */
    private List<MethodSpec> crudMethods(DaoInfo info, ClassName entityImpl,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException, ClassName statement) {
        List<MethodSpec> methods = new ArrayList<>();
        methods.add(saveMethod(info, connection, preparedStatement, sqlException, statement));
        methods.add(saveAllMethod(info, connection, preparedStatement, sqlException, statement));
        methods.add(deleteMethod(info));
        methods.add(deleteManyMethod(info));
        methods.add(deleteByIdMethod(info, connection, preparedStatement, sqlException));
        methods.add(deleteByIdsMethod(info, connection, preparedStatement, sqlException));
        methods.add(updateMethod(info, connection, preparedStatement, sqlException));
        methods.add(findByIdMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(findByIdsMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(findAllMethod(info, entityImpl, connection, preparedStatement, resultSet, sqlException));
        methods.add(countMethod(info, connection, preparedStatement, resultSet, sqlException));
        methods.add(existsByIdMethod(info, connection, preparedStatement, sqlException));
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
    private MethodSpec createEntityMethod(DaoInfo info, ClassName entityImpl) {
        return MethodSpec.methodBuilder("createEntity")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addStatement("return new $T()", entityImpl)
                .build();
    }

    /**
     * Builds {@code save(T)}: INSERT with type-exact binds; when the id is generated,
     * uses {@code RETURN_GENERATED_KEYS} and writes the key back into the entity.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @param statement        the Statement class
     * @return the save method specification
     */
    private MethodSpec saveMethod(DaoInfo info, ClassName connection, ClassName preparedStatement,
            ClassName sqlException, ClassName statement) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = insertColumns(model);
        String sql = "INSERT INTO " + model.tableName() + " ("
                + SqlCodegen.joinColumns(namesOf(insertColumns)) + ") VALUES ("
                + SqlCodegen.placeholders(insertColumns.size()) + ")";

        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.entityType, "entity");
        beginTxBlock(method, connection);
        if (model.idGenerated()) {
            method.addStatement("$T ps = conn.prepareStatement($S, $T.RETURN_GENERATED_KEYS)",
                    preparedStatement, sql, statement);
        } else {
            method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        }
        int index = 1;
        for (EntityModel.ColumnModel column : insertColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
        method.addStatement("ps.executeUpdate()");
        if (model.idGenerated()) {
            method.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSetOf(preparedStatement));
            method.beginControlFlow("if (keys.next())");
            method.addStatement("entity.$L(keys.get$L(1))", model.idColumn().setterName,
                    jdbcReturnSuffix(model.idColumn().typeName));
            method.endControlFlow();
            method.endControlFlow();
        }
        method.addStatement("return entity");
        endTxBlock(method, sqlException, "save");
        return method.build();
    }

    /**
     * Builds the batch {@code save(List<T>)}: iterates and delegates to the single save.
     *
     * @param info the repository info
     * @return the batch save method specification
     */
    private MethodSpec saveAllMethod(DaoInfo info, ClassName connection, ClassName preparedStatement,
            ClassName sqlException, ClassName statement) {
        return MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType))
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType), "entities")
                .beginControlFlow("for ($T entity : entities)", info.entityType)
                .addStatement("save(entity)")
                .endControlFlow()
                .addStatement("return entities")
                .build();
    }

    /**
     * Builds {@code delete(T)}: deletes by the entity's id.
     *
     * @param info the repository info
     * @return the delete method specification
     */
    private MethodSpec deleteMethod(DaoInfo info) {
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
    private MethodSpec deleteManyMethod(DaoInfo info) {
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
     * Builds {@code deleteById(ID)}: {@code DELETE ... WHERE id=?}.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the deleteById method specification
     */
    private MethodSpec deleteByIdMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        String sql = "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.idType, "id");
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "deleteById failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code deleteByIds(List<ID>)}: {@code DELETE ... WHERE id IN (?,...)} with
     * a dynamically built placeholder list.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the deleteByIds method specification
     */
    private MethodSpec deleteByIdsMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteByIds")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.idType), "ids");
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return 0");
        method.endControlFlow();
        method.addStatement("$T sql = new $T($S)", ClassName.get("java.lang", "StringBuilder"),
                ClassName.get("java.lang", "StringBuilder"),
                "DELETE FROM " + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + " IN (");
        method.beginControlFlow("for (int i = 0; i < ids.size(); i++)");
        method.beginControlFlow("if (i > 0)");
        method.addStatement("sql.append($S)", ",");
        method.endControlFlow();
        method.addStatement("sql.append($S)", "?");
        method.endControlFlow();
        method.addStatement("sql.append($S)", ")");
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement(sql.toString())", preparedStatement);
        method.addStatement("int i = 1");
        method.beginControlFlow("for ($T id : ids)", info.idType);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", "i"));
        method.addCode("\n");
        method.addStatement("i++");
        method.endControlFlow();
        method.addStatement("return ps.executeUpdate()");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "deleteByIds failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code update(T)}: {@code UPDATE t SET c1=?,... WHERE id=?}, binding all
     * non-id columns then the id.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the update method specification
     */
    private MethodSpec updateMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> updateColumns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!column.isId) {
                updateColumns.add(column);
            }
        }
        StringBuilder sets = new StringBuilder();
        for (int i = 0; i < updateColumns.size(); i++) {
            if (i > 0) {
                sets.append(",");
            }
            sets.append(updateColumns.get(i).columnName).append("=?");
        }
        String sql = "UPDATE " + model.tableName() + " SET " + sets + " WHERE "
                + model.idColumn().columnName + "=?";

        MethodSpec.Builder method = MethodSpec.methodBuilder("update")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.entityType, "entity");
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        int index = 1;
        for (EntityModel.ColumnModel column : updateColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
        method.addCode(SqlCodegen.bindParam(info.model.idColumn().typeName,
                "entity." + info.model.idColumn().getterName + "()", index));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "update failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code findById(ID)}: {@code SELECT cols FROM t WHERE id=?}, mapping a
     * single row via {@code mapRow} or returning {@code null}.
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findById method specification
     */
    private MethodSpec findByIdMethod(DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?";
        MethodSpec.Builder method = MethodSpec.methodBuilder("findById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.idType, "id");
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("if (!rs.next())");
        method.addStatement("return null");
        method.endControlFlow();
        method.addStatement("return mapRow(rs)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "findById failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code findByIds(List<ID>)}: {@code SELECT cols FROM t WHERE id IN (?,...)}.
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findByIds method specification
     */
    private MethodSpec findByIdsMethod(DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String select = "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + " IN (";
        MethodSpec.Builder method = MethodSpec.methodBuilder("findByIds")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType))
                .addParameter(ParameterizedTypeName.get(ClassName.get(List.class), info.idType), "ids");
        method.beginControlFlow("if (ids.isEmpty())");
        method.addStatement("return $T.of()", ClassName.get(List.class));
        method.endControlFlow();
        method.addStatement("$T sql = new $T($S)", ClassName.get("java.lang", "StringBuilder"),
                ClassName.get("java.lang", "StringBuilder"), select);
        method.beginControlFlow("for (int i = 0; i < ids.size(); i++)");
        method.beginControlFlow("if (i > 0)");
        method.addStatement("sql.append($S)", ",");
        method.endControlFlow();
        method.addStatement("sql.append($S)", "?");
        method.endControlFlow();
        method.addStatement("sql.append($S)", ")");
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement(sql.toString())", preparedStatement);
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
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "findByIds failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code findAll()}: {@code SELECT cols FROM t}, mapping every row.
     *
     * @param info             the repository info
     * @param entityImpl       the generated entity impl class
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the findAll method specification
     */
    private MethodSpec findAllMethod(DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName();
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType));
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get("java.util", "ArrayList"));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "findAll failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code count()}: {@code SELECT COUNT(*) FROM t}.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @return the count method specification
     */
    private MethodSpec countMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT COUNT(*) FROM " + info.model.tableName();
        MethodSpec.Builder method = MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG);
        beginTxBlock(method, connection);
        method.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "count failed");
        method.endControlFlow();
        return method.build();
    }

    /**
     * Builds {@code existsById(ID)}: delegates to the private {@code countById} helper.
     *
     * @param info             the repository info
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlException     the SQLException class
     * @return the existsById method specification
     */
    private MethodSpec existsByIdMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("existsById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.idType, "id");
        method.beginControlFlow("try");
        method.addStatement("return countById(id) > 0");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "existsById failed");
        method.endControlFlow();
        return method.build();
    }

    // ==================== @Query methods ====================

    /**
     * Adds a generated method for every {@code @Query}-annotated method on the repository.
     *
     * @param info             the repository info
     * @param builder          the impl class builder receiving the methods
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @param statement        the Statement class
     */
    private void queryMethods(DaoInfo info, TypeSpec.Builder builder, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException,
            ClassName statement) {
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            builder.addMethod(queryMethod(info, method, query, connection, preparedStatement,
                    resultSet, sqlException, statement));
        }
    }

    /**
     * Builds one {@code @Query} method implementation: converts named placeholders to
     * {@code ?}, binds each {@code @Bind} parameter by type, and maps the result
     * according to the return type (entity, DTO record, scalar, or update count).
     *
     * @param info             the repository info
     * @param method           the annotated repository method
     * @param query            the @Query annotation
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     * @param statement        the Statement class
     * @return the query method specification
     */
    private MethodSpec queryMethod(DaoInfo info, ExecutableElement method, Query query,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException, ClassName statement) {
        String methodName = method.getSimpleName().toString();
        List<String> placeholders = new ArrayList<>();
        String sql = SqlCodegen.convertPlaceholders(query.value(), placeholders);

        // Map placeholder name → method parameter (by @Bind).
        Map<String, VariableElement> binds = new HashMap<>();
        for (VariableElement parameter : method.getParameters()) {
            Bind bind = parameter.getAnnotation(Bind.class);
            if (bind != null) {
                binds.put(bind.value(), parameter);
            }
        }

        TypeMirror returnType = method.getReturnType();
        // SELECT queries read a ResultSet; anything else (INSERT/UPDATE/DELETE) returns a count.
        boolean isUpdate = !query.value().trim().toUpperCase().startsWith("SELECT");

        MethodSpec.Builder spec = MethodSpec.methodBuilder(methodName)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(toTypeNameWithGenerics(returnType));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(toTypeNameWithGenerics(parameter.asType()),
                    parameter.getSimpleName().toString());
        }

        boolean generatedKeys = method.getAnnotation(ReturnGeneratedKeys.class) != null;
        beginTxBlock(spec, connection);
        if (generatedKeys) {
            spec.addStatement("$T ps = conn.prepareStatement($S, $T.RETURN_GENERATED_KEYS)",
                    preparedStatement, sql, statement);
        } else {
            spec.addStatement("$T ps = conn.prepareStatement($S)", preparedStatement, sql);
        }

        for (int i = 0; i < placeholders.size(); i++) {
            VariableElement parameter = binds.get(placeholders.get(i));
            if (parameter == null) {
                error(method, "No @Bind(\"" + placeholders.get(i) + "\") parameter for query placeholder");
                continue;
            }
            spec.addCode(SqlCodegen.bindParam(parameter.asType().toString(),
                    parameter.getSimpleName().toString(), i + 1));
            spec.addCode("\n");
        }

        if (generatedKeys) {
            spec.addStatement("ps.executeUpdate()");
            spec.beginControlFlow("try ($T keys = ps.getGeneratedKeys())", resultSet);
            spec.beginControlFlow("if (keys.next())");
            spec.addStatement("$L", generatedKeysWriteback(info, method));
            spec.endControlFlow();
            spec.endControlFlow();
            if (returnType.getKind() == TypeKind.VOID) {
                spec.addStatement("return");
            } else {
                spec.addStatement("return null");
            }
        } else if (isUpdate) {
            spec.addStatement("return ps.executeUpdate()");
        } else {
            spec.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
            appendResultMapping(spec, info, method, returnType);
            spec.endControlFlow();
        }

        spec.nextControlFlow("catch ($T e)", sqlException);
        spec.addStatement("throw new $T($S, e)", ORM_EXCEPTION, methodName + " failed");
        spec.endControlFlow();
        return spec.build();
    }

    /**
     * Builds the generated-key write-back expression for a {@code @ReturnGeneratedKeys}
     * method: finds the entity parameter and returns an assignment to its id setter.
     *
     * @param info   the repository info
     * @param method the annotated method
     * @return the write-back expression, or a no-op comment when no entity parameter exists
     */
    private String generatedKeysWriteback(DaoInfo info, ExecutableElement method) {
        for (VariableElement parameter : method.getParameters()) {
            TypeMirror type = parameter.asType();
            if (type.getKind() == TypeKind.DECLARED) {
                Element element = ((DeclaredType) type).asElement();
                if (element.getKind() == ElementKind.INTERFACE
                        && element.getAnnotation(Table.class) != null) {
                    EntityModel.ColumnModel idColumn = info.model.idColumn();
                    return parameter.getSimpleName() + "." + idColumn.setterName + "(keys.get"
                            + jdbcReturnSuffix(idColumn.typeName) + "(1))";
                }
            }
        }
        return "/* no entity parameter to write back the generated key */";
    }

    /**
     * Appends result-mapping code for a SELECT {@code @Query} based on the return type:
     * entity interface (by column name), DTO record (by component order), or scalar.
     *
     * @param spec       the method builder receiving the mapping code
     * @param info       the repository info
     * @param method     the annotated method
     * @param returnType the method's return type
     */
    private void appendResultMapping(MethodSpec.Builder spec, DaoInfo info, ExecutableElement method,
            TypeMirror returnType) {
        boolean isList = returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().getSimpleName().contentEquals("List");
        TypeMirror elementType = isList
                ? ((DeclaredType) returnType).getTypeArguments().get(0)
                : returnType;

        TypeElement element = elementType.getKind() == TypeKind.DECLARED
                ? (TypeElement) ((DeclaredType) elementType).asElement()
                : null;

        if (element != null && element.getAnnotation(Table.class) != null) {
            // Entity interface: map by column name (custom SELECT column order is user-controlled).
            appendEntityMapping(spec, info, elementType, isList);
        } else if (element != null && element.getKind() == ElementKind.RECORD) {
            // DTO record: component order maps to SELECT column order by index.
            appendRecordMapping(spec, element, isList);
        } else if (elementType.getKind() != TypeKind.VOID) {
            // Single value (String/Long/...).
            if (isList) {
                spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                        TypeNameUtils.toTypeName(elementType.toString()), ClassName.get("java.util", "ArrayList"));
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
     * Appends row mapping for an entity-interface result type, reading columns by name
     * (custom SELECT order is user-controlled in @Query SQL).
     *
     * @param spec       the method builder receiving the mapping code
     * @param info       the repository info
     * @param entityType the entity interface type
     * @param isList     whether the method returns a list
     */
    private void appendEntityMapping(MethodSpec.Builder spec, DaoInfo info, TypeMirror entityType,
            boolean isList) {
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return;
        }
        ClassName impl = ClassName.get(model.entityPackage(), EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()));
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    TypeNameUtils.toTypeName(entityType.toString()), ClassName.get("java.util", "ArrayList"));
            spec.beginControlFlow("while (rs.next())");
            spec.addStatement("$T e = new $T()", impl, impl);
            for (EntityModel.ColumnModel column : model.columns()) {
                spec.addCode(SqlCodegen.readColumnByName(column.typeName, "e", column.setterName, column.columnName));
                spec.addCode("\n");
            }
            spec.addStatement("result.add(e)");
            spec.endControlFlow();
            spec.addStatement("return result");
        } else {
            spec.beginControlFlow("if (!rs.next())");
            spec.addStatement("return null");
            spec.endControlFlow();
            spec.addStatement("$T e = new $T()", impl, impl);
            for (EntityModel.ColumnModel column : model.columns()) {
                spec.addCode(SqlCodegen.readColumnByName(column.typeName, "e", column.setterName, column.columnName));
                spec.addCode("\n");
            }
            spec.addStatement("return e");
        }
    }

    /**
     * Appends row mapping for a DTO record result type: record component order maps to
     * SELECT column order by index.
     *
     * @param spec   the method builder receiving the mapping code
     * @param record the DTO record element
     * @param isList whether the method returns a list
     */
    private void appendRecordMapping(MethodSpec.Builder spec, TypeElement record, boolean isList) {
        ClassName recordClass = ClassName.get(record);
        List<? extends Element> components = record.getRecordComponents();
        if (isList) {
            spec.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class),
                    recordClass, ClassName.get("java.util", "ArrayList"));
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
     * Builds the constructor argument list for a record from the current row.
     *
     * @param components the record components
     * @return comma-joined {@code rs.getXxx(i)} expressions
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

    // ==================== Factories ====================

    /**
     * Emits one {@code Repositories} factory class per dao package, with a
     * {@code createXxxRepository(DataSource)} method for each processed dao.
     */
    private void writeFactories() {
        // Group daos by package and emit one Repositories class per package.
        Map<String, List<DaoInfo>> byPackage = new java.util.LinkedHashMap<>();
        for (DaoInfo info : daos) {
            byPackage.computeIfAbsent(info.daoPackage, p -> new ArrayList<>()).add(info);
        }
        for (Map.Entry<String, List<DaoInfo>> entry : byPackage.entrySet()) {
            TypeSpec.Builder factories = TypeSpec.classBuilder("Repositories")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                    .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build());
            ClassName dataSource = ClassName.get("javax.sql", "DataSource");
            for (DaoInfo info : entry.getValue()) {
                ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
                factories.addMethod(MethodSpec.methodBuilder("create" + info.daoSimpleName)
                        .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                        .returns(daoClass)
                        .addParameter(dataSource, "dataSource")
                        .addStatement("return new $T(dataSource)", ClassName.get(info.daoPackage, info.implName))
                        .build());
            }
            try {
                JavaFile.builder(entry.getKey(), factories.build())
                        .addFileComment("Generated at compile time by RepositoryProcessor. Do not edit.")
                        .build()
                        .writeTo(processingEnv.getFiler());
            } catch (IOException e) {
                error(null, "Failed to generate Repositories: " + e.getMessage());
            }
        }
    }

    // ==================== Helpers ====================

    private static List<EntityModel.ColumnModel> insertColumns(EntityModel model) {
        List<EntityModel.ColumnModel> columns = new ArrayList<>();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!(column.isId && model.idGenerated())) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static List<String> namesOf(List<EntityModel.ColumnModel> columns) {
        List<String> names = new ArrayList<>();
        for (EntityModel.ColumnModel column : columns) {
            names.add(column.columnName);
        }
        return names;
    }

    private static String jdbcReturnSuffix(String typeName) {
        String getter = TypeNameUtils.jdbcGetter(typeName);
        return getter.substring(3); // "getLong" → "Long", "getString" → "String"
    }

    private ClassName resultSetOf(ClassName preparedStatement) {
        return ClassName.get("java.sql", "ResultSet");
    }

    /**
     * Converts a TypeMirror to a JavaPoet TypeName, preserving generic arguments
     * (e.g. {@code List<UserEntity>}).
     *
     * @param type the type mirror
     * @return the corresponding JavaPoet type
     */
    private static TypeName toTypeNameWithGenerics(TypeMirror type) {
        if (type.getKind() == TypeKind.DECLARED) {
            DeclaredType declared = (DeclaredType) type;
            if (!declared.getTypeArguments().isEmpty()) {
                List<TypeName> args = new ArrayList<>();
                for (TypeMirror arg : declared.getTypeArguments()) {
                    args.add(toTypeNameWithGenerics(arg));
                }
                return ParameterizedTypeName.get(
                        ClassName.get((TypeElement) declared.asElement()), args.toArray(new TypeName[0]));
            }
        }
        return TypeNameUtils.toTypeName(type.toString());
    }

    /**
     * Extracts the package name from a fully qualified name.
     *
     * @param qualifiedName the fully qualified name
     * @return the package name, or an empty string for the default package
     */
    private static String packageOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    // ---- Tx block helpers ---------------------------------------------------

    /** Starts a tx-aware try block in the generated method: {@code Connection conn = getConnection(); try \{}. */
    private MethodSpec.Builder beginTxBlock(MethodSpec.Builder method, ClassName connection) {
        return method.addStatement("$T conn = getConnection()", connection).beginControlFlow("try");
    }

    /** Closes the tx-aware try block with catch + finally. */
    private void endTxBlock(MethodSpec.Builder method, ClassName sqlException, String operation) {
        method.nextControlFlow("catch ($T e)", sqlException)
                .addStatement("throw new $T($S, e)", ORM_EXCEPTION, operation + " failed")
                .nextControlFlow("finally")
                .addStatement("releaseConnection(conn)")
                .endControlFlow();
    }

    /**
     * Reports a compile-time error attached to the given element (null = global).
     *
     * @param element the offending element, or {@code null}
     * @param message the error message
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

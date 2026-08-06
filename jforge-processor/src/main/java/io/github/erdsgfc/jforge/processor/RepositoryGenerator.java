package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.annotation.Bind;
import io.github.erdsgfc.jforge.annotation.Query;
import io.github.erdsgfc.jforge.annotation.ReturnGeneratedKeys;
import io.github.erdsgfc.jforge.annotation.Table;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 {@code @Dao} 仓库的实现类 {@code XxxRepository_Impl}：直写 JDBC 的 CRUD 方法（继承自
 * {@code BaseRepository}）+ {@code @Query} 自定义方法 + 事务方法。固定的 SQL 生成
 * {@code private final String xxxSql = "..."} 字段（命名、可读，避免散落字面量），方法体引用之。
 */
final class RepositoryGenerator {

    private static final ClassName ORM_EXCEPTION = ClassName.get("io.github.erdsgfc.jforge", "OrmException");
    private static final ClassName TX_MANAGER = ClassName.get("io.github.erdsgfc.jforge", "TransactionManager");

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;

    RepositoryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
    }

    /**
     * 为一个 {@code @Dao} 生成仓库实现类并写入源文件。
     *
     * @param info the parsed repository info
     */
    void generate(JForgeProcessor.DaoInfo info) {
        TypeSpec typeSpec = buildImpl(info);
        try {
            JavaFile.builder(info.daoPackage, typeSpec)
                    .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(info.element, "Failed to generate " + info.implName + ": " + e.getMessage());
        }
    }

    /**
     * 组装仓库实现类：dataSource 字段、固定 SQL 常量字段、构造器、连接 helper、事务方法、
     * 行映射、CRUD 方法与 {@code @Query} 方法。
     *
     * @param info the parsed repository info
     * @return the generated class specification
     */
    private TypeSpec buildImpl(JForgeProcessor.DaoInfo info) {
        ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
        ClassName dataSource = ClassName.get("javax.sql", "DataSource");
        ClassName entityImpl = ClassName.get(info.model.entityPackage(),
                EntityModel.implNameOf(info.model.entitySimpleName(), info.model.implSuffix()));
        ClassName connection = ClassName.get("java.sql", "Connection");
        ClassName preparedStatement = ClassName.get("java.sql", "PreparedStatement");
        ClassName resultSet = ClassName.get("java.sql", "ResultSet");
        ClassName sqlException = ClassName.get("java.sql", "SQLException");

        // 适配 Spring Boot：配置 springBeans=true 时，生成的 impl 标 @Repository + @Autowired 构造器，
        // 并去掉 final（final 类无法被 Spring CGLIB 代理），由组件扫描自动注入容器。
        boolean springBeans = configHelper.springBeans(info.element);
        TypeSpec.Builder builder = TypeSpec.classBuilder(info.implName)
                .addModifiers(springBeans
                        ? new Modifier[] {Modifier.PUBLIC}
                        : new Modifier[] {Modifier.PUBLIC, Modifier.FINAL})
                .addSuperinterface(daoClass)
                .addField(FieldSpec.builder(dataSource, "dataSource", Modifier.PRIVATE, Modifier.FINAL).build());
        if (springBeans) {
            builder.addAnnotation(AnnotationSpec.builder(
                    ClassName.get("org.springframework.stereotype", "Repository")).build());
        }
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(dataSource, "dataSource")
                .addStatement("this.dataSource = dataSource");
        if (springBeans) {
            constructor.addAnnotation(AnnotationSpec.builder(
                    ClassName.get("org.springframework.beans.factory.annotation", "Autowired")).build());
        }
        builder.addMethod(constructor.build());

        // 固定 SQL 常量字段（命名引用，避免方法体内散落字符串字面量）。
        for (FieldSpec field : sqlFields(info)) {
            builder.addField(field);
        }

        // Transaction-aware connection helpers; private implementation detail used by
        // the generated CRUD methods and beginTransaction — never exposed on the
        // repository interface so callers cannot leak connection ownership.
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
        for (MethodSpec method : txMethods(connection)) {
            builder.addMethod(method);
        }

        // Shared private row mapper for entity rows (column order = field order).
        builder.addMethod(rowMapperMethod(info, entityImpl, sqlException, resultSet));
        builder.addMethod(countByIdMethod(info, sqlException, connection, preparedStatement, resultSet));
        for (MethodSpec method : crudMethods(info, entityImpl, connection, preparedStatement, resultSet,
                sqlException)) {
            builder.addMethod(method);
        }
        queryMethods(info, builder, connection, preparedStatement, resultSet, sqlException);

        return builder.build();
    }

    // ==================== 固定 SQL 常量字段 ====================

    /**
     * 收集仓库类的固定 SQL 常量字段：CRUD 各方法的 SQL + 每个 {@code @Query} 方法的 SQL。
     * IN 查询（findByIds/deleteByIds）只把固定前缀提取为常量，占位符部分仍在方法内动态拼接。
     *
     * @param info the parsed repository info
     * @return the SQL constant field specifications
     */
    private List<FieldSpec> sqlFields(JForgeProcessor.DaoInfo info) {
        List<FieldSpec> fields = new ArrayList<>();
        fields.add(sqlField("saveSql", saveSql(info)));
        fields.add(sqlField("deleteByIdSql", deleteByIdSql(info)));
        fields.add(sqlField("deleteByIdsBaseSql", deleteByIdsBaseSql(info)));
        fields.add(sqlField("updateSql", updateSql(info)));
        fields.add(sqlField("findByIdSql", findByIdSql(info)));
        fields.add(sqlField("findByIdsBaseSql", findByIdsBaseSql(info)));
        fields.add(sqlField("findAllSql", findAllSql(info)));
        fields.add(sqlField("countSql", countSql(info)));
        fields.add(sqlField("countByIdSql", countByIdSql(info)));
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD) {
                ExecutableElement method = (ExecutableElement) enclosed;
                if (method.getAnnotation(Query.class) != null) {
                    fields.add(sqlField(method.getSimpleName() + "Sql", querySql(method)));
                }
            }
        }
        return fields;
    }

    /** 构造一个 {@code private final String name = "sql";} 字段。 */
    private static FieldSpec sqlField(String name, String sql) {
        return FieldSpec.builder(String.class, name, Modifier.PRIVATE, Modifier.FINAL)
                .initializer("$S", sql)
                .build();
    }

    private static String saveSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        List<EntityModel.ColumnModel> insertColumns = insertColumns(model);
        return "INSERT INTO " + model.tableName() + " ("
                + SqlCodegen.joinColumns(namesOf(insertColumns)) + ") VALUES ("
                + SqlCodegen.placeholders(insertColumns.size()) + ")";
    }

    private static String deleteByIdSql(JForgeProcessor.DaoInfo info) {
        return "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
    }

    private static String deleteByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        return "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + " IN (";
    }

    private static String updateSql(JForgeProcessor.DaoInfo info) {
        EntityModel model = info.model;
        StringBuilder sets = new StringBuilder();
        for (EntityModel.ColumnModel column : model.columns()) {
            if (!column.isId) {
                if (sets.length() > 0) {
                    sets.append(",");
                }
                sets.append(column.columnName).append("=?");
            }
        }
        return "UPDATE " + model.tableName() + " SET " + sets + " WHERE "
                + model.idColumn().columnName + "=?";
    }

    private static String findByIdSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?";
    }

    private static String findByIdsBaseSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + " IN (";
    }

    private static String findAllSql(JForgeProcessor.DaoInfo info) {
        return "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName();
    }

    private static String countSql(JForgeProcessor.DaoInfo info) {
        return "SELECT COUNT(*) FROM " + info.model.tableName();
    }

    private static String countByIdSql(JForgeProcessor.DaoInfo info) {
        return "SELECT COUNT(*) FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
    }

    /** {@code @Query} 方法的 SQL：命名占位符转 {@code ?}（返回转换后的字符串）。 */
    private static String querySql(ExecutableElement method) {
        return SqlCodegen.convertPlaceholders(method.getAnnotation(Query.class).value(), new ArrayList<>());
    }

    // ==================== 事务方法 ====================

    /**
     * Builds the programmatic-transaction and connection-scope methods inherited
     * from {@code TransactionOperations}: begin/commit/rollback/isTransactionActive/
     * markRollbackOnly/isRollbackOnly plus beginConnectionScope/endConnectionScope,
     * all delegating to the global {@code TransactionManager}.
     * {@code beginTransaction} returns the transaction-bound connection so the
     * inherited {@code execute} default template can hand it to the callback;
     * {@code beginConnectionScope} returns the shared scope connection so the
     * inherited {@code executeWithoutTransaction} template can hand it to its
     * callback.
     *
     * @param connection the Connection class
     * @return the transaction and connection-scope method specifications
     */
    private List<MethodSpec> txMethods(ClassName connection) {
        return List.of(
                MethodSpec.methodBuilder("beginTransaction")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(connection)
                        .addStatement("$T.current().begin(dataSource)", TX_MANAGER)
                        .addStatement("return getConnection()")
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
                        .build(),
                MethodSpec.methodBuilder("markRollbackOnly")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("$T.current().markRollbackOnly()", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("isRollbackOnly")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(TypeName.BOOLEAN)
                        .addStatement("return $T.current().isRollbackOnly()", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("beginConnectionScope")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .returns(connection)
                        .addStatement("return $T.current().beginScope(dataSource)", TX_MANAGER)
                        .build(),
                MethodSpec.methodBuilder("endConnectionScope")
                        .addAnnotation(Override.class)
                        .addModifiers(Modifier.PUBLIC)
                        .addStatement("$T.current().endScope(dataSource)", TX_MANAGER)
                        .build());
    }

    // ==================== CRUD 方法 ====================

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
    private List<MethodSpec> crudMethods(JForgeProcessor.DaoInfo info, ClassName entityImpl,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException) {
        List<MethodSpec> methods = new ArrayList<>();
        methods.add(saveMethod(info, connection, preparedStatement, sqlException));
        methods.add(saveAllMethod(info));
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
        List<EntityModel.ColumnModel> insertColumns = insertColumns(model);
        MethodSpec.Builder method = MethodSpec.methodBuilder("save")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.entityType, "entity");
        beginTxBlock(method, connection, preparedStatement, "saveSql", model.idGenerated());
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
    private MethodSpec saveAllMethod(JForgeProcessor.DaoInfo info) {
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
        beginTxBlock(method, connection, preparedStatement, "deleteByIdSql", false);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        endTxBlock(method, sqlException, "deleteById");
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
        beginTxBlock(method, connection, preparedStatement, "sql.toString()", false);
        method.addStatement("int i = 1");
        method.beginControlFlow("for ($T id : ids)", info.idType);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", "i"));
        method.addCode("\n");
        method.addStatement("i++");
        method.endControlFlow();
        method.addStatement("return ps.executeUpdate()");
        endTxBlock(method, sqlException, "deleteByIds");
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
        beginTxBlock(method, connection, preparedStatement, "updateSql", false);
        int index = 1;
        for (EntityModel.ColumnModel column : updateColumns) {
            method.addCode(SqlCodegen.bindParam(column.typeName, "entity." + column.getterName + "()", index++));
            method.addCode("\n");
        }
        method.addCode(SqlCodegen.bindParam(model.idColumn().typeName,
                "entity." + model.idColumn().getterName + "()", index));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        endTxBlock(method, sqlException, "update");
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
        beginTxBlock(method, connection, preparedStatement, "findByIdSql", false);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("if (!rs.next())");
        method.addStatement("return null");
        method.endControlFlow();
        method.addStatement("return mapRow(rs)");
        method.endControlFlow();
        endTxBlock(method, sqlException, "findById");
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
        beginTxBlock(method, connection, preparedStatement, "sql.toString()", false);
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
        endTxBlock(method, sqlException, "findByIds");
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
        beginTxBlock(method, connection, preparedStatement, "findAllSql", false);
        method.addStatement("$T<$T> result = new $T<>()", ClassName.get(List.class), info.entityType,
                ClassName.get("java.util", "ArrayList"));
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.beginControlFlow("while (rs.next())");
        method.addStatement("result.add(mapRow(rs))");
        method.endControlFlow();
        method.endControlFlow();
        method.addStatement("return result");
        endTxBlock(method, sqlException, "findAll");
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
        beginTxBlock(method, connection, preparedStatement, "countSql", false);
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        endTxBlock(method, sqlException, "count");
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
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "existsById failed");
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
    private MethodSpec countByIdMethod(JForgeProcessor.DaoInfo info, ClassName sqlException,
            ClassName connection, ClassName preparedStatement, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("countById")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.LONG)
                .addParameter(TypeNameUtils.toTypeName(info.idTypeName), "id")
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
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "count failed");
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
    private MethodSpec rowMapperMethod(JForgeProcessor.DaoInfo info, ClassName entityImpl,
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

    // ==================== @Query 方法 ====================

    /**
     * Adds a generated method for every {@code @Query}-annotated method on the repository
     * (SQL 取 {@code <方法名>Sql} 字段).
     *
     * @param info             the repository info
     * @param builder          the impl class builder receiving the methods
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param resultSet        the ResultSet class
     * @param sqlException     the SQLException class
     */
    private void queryMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
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
                    resultSet, sqlException));
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
     * @return the query method specification
     */
    private MethodSpec queryMethod(JForgeProcessor.DaoInfo info, ExecutableElement method, Query query,
            ClassName connection, ClassName preparedStatement, ClassName resultSet,
            ClassName sqlException) {
        String methodName = method.getSimpleName().toString();
        String sqlField = methodName + "Sql";
        List<String> placeholders = new ArrayList<>();
        SqlCodegen.convertPlaceholders(query.value(), placeholders);

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
        beginTxBlock(spec, connection, preparedStatement, sqlField, generatedKeys);

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

        endTxBlock(spec, sqlException, methodName);
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
    private String generatedKeysWriteback(JForgeProcessor.DaoInfo info, ExecutableElement method) {
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
    private void appendResultMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info, ExecutableElement method,
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
    private void appendEntityMapping(MethodSpec.Builder spec, JForgeProcessor.DaoInfo info, TypeMirror entityType,
            boolean isList) {
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return;
        }
        ClassName impl = ClassName.get(model.entityPackage(),
                EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()));
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

    // ---- Tx block helpers ---------------------------------------------------

    /**
     * Starts the tx-aware block in the generated method: acquires the connection and opens the
     * {@code PreparedStatement} as a try-with-resources resource so it is always closed —
     * {@code Connection conn = getConnection(); try (PreparedStatement ps = conn.prepareStatement(...)) \{}.
     *
     * @param method           the method builder
     * @param connection       the Connection class
     * @param preparedStatement the PreparedStatement class
     * @param sqlExpr          the SQL expression passed to prepareStatement (a SQL field name, or
     *                         {@code sql.toString()} for dynamically built IN queries)
     * @param generatedKeys    whether to use {@code RETURN_GENERATED_KEYS}
     */
    private MethodSpec.Builder beginTxBlock(MethodSpec.Builder method, ClassName connection,
            ClassName preparedStatement, String sqlExpr, boolean generatedKeys) {
        method.addStatement("$T conn = getConnection()", connection);
        if (generatedKeys) {
            return method.beginControlFlow("try ($T ps = conn.prepareStatement($L, $T.RETURN_GENERATED_KEYS))",
                    preparedStatement, sqlExpr, ClassName.get("java.sql", "Statement"));
        }
        return method.beginControlFlow("try ($T ps = conn.prepareStatement($L))", preparedStatement, sqlExpr);
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
     * Reports a compile-time error attached to the given element.
     *
     * @param element the offending element
     * @param message the error message
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

package com.qin.orm.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.qin.orm.annotation.Bind;
import com.qin.orm.annotation.Dao;
import com.qin.orm.annotation.Query;
import com.qin.orm.annotation.ReturnGeneratedKeys;
import com.qin.orm.annotation.Table;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final List<DaoInfo> daos = new ArrayList<>();
    private int lastFactoriesSize;

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

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        for (Element root : roundEnv.getRootElements()) {
            if (root.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            TypeElement typeElement = (TypeElement) root;
            if (typeElement.getAnnotation(Dao.class) != null) {
                processDao(typeElement);
            }
        }
        if (daos.size() != lastFactoriesSize) {
            writeFactories();
            lastFactoriesSize = daos.size();
        }
        return true;
    }

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
                Diagnostic.Kind.ERROR, processingEnv.getMessager());
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

    private TypeSpec buildImpl(DaoInfo info) {
        ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
        ClassName dataSource = ClassName.get("javax.sql", "DataSource");
        ClassName entityImpl = ClassName.get(info.model.entityPackage(),
                EntityModel.implNameOf(info.model.entitySimpleName()));
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

    /** private Xxx_Impl mapRow(ResultSet rs) throws SQLException — column index = field order. */
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

    /** private long countById(...) — used by existsById and count. */
    private MethodSpec countByIdMethod(DaoInfo info, ClassName sqlException,
            ClassName connection, ClassName preparedStatement, ClassName resultSet) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("countById")
                .addModifiers(Modifier.PRIVATE)
                .returns(TypeName.LONG)
                .addParameter(TypeNameUtils.toTypeName(info.idTypeName), "id")
                .addException(sqlException);
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement,
                "SELECT COUNT(*) FROM " + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?");
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "count failed");
        method.endControlFlow();
        return method.build();
    }

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
        return methods;
    }

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
        if (model.idGenerated()) {
            method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S, $T.RETURN_GENERATED_KEYS))",
                    connection, preparedStatement, sql, statement);
        } else {
            method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                    connection, preparedStatement, sql);
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
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "save failed");
        method.endControlFlow();
        return method.build();
    }

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

    private MethodSpec deleteMethod(DaoInfo info) {
        return MethodSpec.methodBuilder("delete")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.entityType, "entity")
                .addStatement("return deleteById(entity.$L())", info.model.idColumn().getterName)
                .build();
    }

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

    private MethodSpec deleteByIdMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName sqlException) {
        String sql = "DELETE FROM " + info.model.tableName() + " WHERE "
                + info.model.idColumn().columnName + "=?";
        MethodSpec.Builder method = MethodSpec.methodBuilder("deleteById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(info.idType, "id");
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement, sql);
        method.addCode(SqlCodegen.bindParam(info.idTypeName, "id", 1));
        method.addCode("\n");
        method.addStatement("return ps.executeUpdate() > 0");
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "deleteById failed");
        method.endControlFlow();
        return method.build();
    }

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
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement(sql.toString()))",
                connection, preparedStatement);
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
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement, sql);
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

    private MethodSpec findByIdMethod(DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName() + " WHERE " + info.model.idColumn().columnName + "=?";
        MethodSpec.Builder method = MethodSpec.methodBuilder("findById")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(info.entityType)
                .addParameter(info.idType, "id");
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement, sql);
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
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement(sql.toString()))",
                connection, preparedStatement);
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

    private MethodSpec findAllMethod(DaoInfo info, ClassName entityImpl, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT " + SqlCodegen.joinColumns(namesOf(info.model.columns())) + " FROM "
                + info.model.tableName();
        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), info.entityType));
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement, sql);
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

    private MethodSpec countMethod(DaoInfo info, ClassName connection,
            ClassName preparedStatement, ClassName resultSet, ClassName sqlException) {
        String sql = "SELECT COUNT(*) FROM " + info.model.tableName();
        MethodSpec.Builder method = MethodSpec.methodBuilder("count")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.LONG);
        method.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                connection, preparedStatement, sql);
        method.beginControlFlow("try ($T rs = ps.executeQuery())", resultSet);
        method.addStatement("rs.next()");
        method.addStatement("return rs.getLong(1)");
        method.endControlFlow();
        method.nextControlFlow("catch ($T e)", sqlException);
        method.addStatement("throw new $T($S, e)", ORM_EXCEPTION, "count failed");
        method.endControlFlow();
        return method.build();
    }

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
        if (generatedKeys) {
            spec.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S, $T.RETURN_GENERATED_KEYS))",
                    connection, preparedStatement, sql, statement);
        } else {
            spec.beginControlFlow("try ($T conn = dataSource.getConnection(); $T ps = conn.prepareStatement($S))",
                    connection, preparedStatement, sql);
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

    /** Writes back the generated key into the entity parameter's id setter. */
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

    private void appendEntityMapping(MethodSpec.Builder spec, DaoInfo info, TypeMirror entityType,
            boolean isList) {
        TypeElement entityElement = (TypeElement) ((DeclaredType) entityType).asElement();
        EntityModel model = EntityModel.parse(entityElement, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager());
        if (model == null) {
            return;
        }
        ClassName impl = ClassName.get(model.entityPackage(), EntityModel.implNameOf(model.entitySimpleName()));
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

    /** TypeMirror → JavaPoet TypeName, preserving generics (e.g. {@code List<UserEntity>}). */
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

    private static String packageOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? "" : qualifiedName.substring(0, dot);
    }

    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

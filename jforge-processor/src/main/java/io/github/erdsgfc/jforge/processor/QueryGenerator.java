package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
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
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成仓库 impl 类的 {@code @Query} 方法：命名占位符转 {@code ?}、按类型绑定 {@code @Bind} 参数、
 * 按返回类型映射结果（实体 / DTO record / 标量 / 更新计数）。
 *
 * <p>依赖 {@link ProcessingEnvironment}（{@code @Query} 实体结果映射时重新解析实体模型）与
 * {@link JForgeConfigHelper}；其余经 {@link JForgeProcessor.DaoInfo} 参数传入。</p>
 */
final class QueryGenerator {

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;

    /**
     * @param processingEnv the processing environment (for entity-model re-parse and error reporting)
     * @param configHelper  the shared ORM config helper
     */
    QueryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
    }

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
    void queryMethods(JForgeProcessor.DaoInfo info, TypeSpec.Builder builder, ClassName connection,
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
                .returns(TypeNameUtils.toTypeNameWithGenerics(returnType));
        for (VariableElement parameter : method.getParameters()) {
            spec.addParameter(TypeNameUtils.toTypeNameWithGenerics(parameter.asType()),
                    parameter.getSimpleName().toString());
        }

        boolean generatedKeys = method.getAnnotation(ReturnGeneratedKeys.class) != null;
        SqlCodegen.beginTxBlock(spec, connection, preparedStatement, sqlField, generatedKeys, configHelper.logSql(info.element));

        for (int i = 0; i < placeholders.size(); i++) {
            VariableElement parameter = binds.get(placeholders.get(i));
            if (parameter == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "No @Bind(\"" + placeholders.get(i) + "\") parameter for query placeholder", method);
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

        SqlCodegen.endTxBlock(spec, sqlException, methodName, info.model.tableName(), SqlFieldGenerator.querySql(method), configHelper.logSql(info.element));
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
                            + TypeNameUtils.jdbcReturnSuffix(idColumn.typeName) + "(1))";
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
        ClassName impl = ClassName.get(model.implPackage(),
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
}

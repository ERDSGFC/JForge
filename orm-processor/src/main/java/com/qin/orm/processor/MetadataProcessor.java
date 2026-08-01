package com.qin.orm.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
import com.qin.orm.annotation.Column;
import com.qin.orm.annotation.GeneratedValue;
import com.qin.orm.annotation.Id;
import com.qin.orm.annotation.Table;
import com.qin.orm.annotation.Transient;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Compile-time metadata generator: scans {@code @Table} entities and generates, for each,
 * a {@code Xxx_Metadata} class implementing {@code com.qin.orm.core.GeneratedMetadata}
 * (pre-built SQL strings + field accessors that call the entity's getters/setters directly —
 * no reflection, GraalVM Native Image safe), plus a {@code GeneratedMetadataRegistry}.
 *
 * Source files are emitted with JavaPoet; annotations are referenced directly
 * (this module depends on orm-annotation).
 */
@AutoService(javax.annotation.processing.Processor.class)
@SupportedAnnotationTypes("com.qin.orm.annotation.Table")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class MetadataProcessor extends AbstractProcessor {

    private static final String GENERATED_PACKAGE = "com.qin.orm.generated";
    private static final String REGISTRY_CLASS = "GeneratedMetadataRegistry";
    private static final ClassName FIELD_ACCESSOR = ClassName.get("com.qin.orm.core", "FieldAccessor");
    private static final ClassName GENERATED_METADATA = ClassName.get("com.qin.orm.core", "GeneratedMetadata");

    private final List<EntityInfo> entities = new ArrayList<>();
    private boolean registryWritten;

    private static final class EntityInfo {
        TypeElement element;
        String tableName;
        String entityQualifiedName;
        String simpleName;
        List<ColumnInfo> columns = new ArrayList<>();
        ColumnInfo idColumn;
        boolean idGenerated;

        String generatedClassName() {
            return simpleName + "_Metadata";
        }
    }

    private static final class ColumnInfo {
        String fieldName;
        String columnName;
        String typeName;      // TypeMirror#toString, e.g. "java.lang.String", "int", "byte[]"
        boolean isId;
        boolean generated;
        String getterName;
        String setterName;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }
        for (Element root : roundEnv.getRootElements()) {
            if (root.getKind() != ElementKind.CLASS && root.getKind() != ElementKind.RECORD) {
                continue;
            }
            TypeElement typeElement = (TypeElement) root;
            if (typeElement.getAnnotation(Table.class) != null) {
                processEntity(typeElement);
            }
        }
        // Write the registry during a processing round (not processingOver) so javac compiles
        // it in the following round, where EntityMetadata's reference to it is resolved.
        if (!registryWritten) {
            writeRegistry();
            registryWritten = true;
        }
        return true;
    }

    /** Parses one @Table entity and generates its metadata class (errors at compile time). */
    private void processEntity(TypeElement entity) {
        Table table = entity.getAnnotation(Table.class);
        EntityInfo info = new EntityInfo();
        info.element = entity;
        info.simpleName = entity.getSimpleName().toString();
        info.entityQualifiedName = entity.getQualifiedName().toString();
        info.tableName = table.name();
        if (info.tableName.isEmpty()) {
            error(entity, "@Table name() is required");
            return;
        }

        for (Element enclosed : entity.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.FIELD) {
                continue;
            }
            VariableElement field = (VariableElement) enclosed;
            if (field.getModifiers().contains(Modifier.STATIC)
                    || field.getModifiers().contains(Modifier.TRANSIENT)
                    || field.getAnnotation(Transient.class) != null) {
                continue;
            }
            ColumnInfo column = new ColumnInfo();
            column.fieldName = field.getSimpleName().toString();
            Column columnAnnotation = field.getAnnotation(Column.class);
            column.columnName = columnAnnotation != null ? columnAnnotation.name() : column.fieldName;
            column.isId = field.getAnnotation(Id.class) != null;
            column.generated = field.getAnnotation(GeneratedValue.class) != null;
            column.typeName = field.asType().toString();

            String capitalized = Character.toUpperCase(column.fieldName.charAt(0)) + column.fieldName.substring(1);
            column.getterName = "get" + capitalized;
            column.setterName = "set" + capitalized;

            if (column.isId) {
                if (info.idColumn != null) {
                    error(field, "Multiple @Id fields on " + info.entityQualifiedName);
                    return;
                }
                info.idColumn = column;
                info.idGenerated = column.generated;
            }
            info.columns.add(column);
        }
        if (info.idColumn == null) {
            error(entity, "No @Id field on " + info.entityQualifiedName);
            return;
        }

        for (ColumnInfo column : info.columns) {
            validateAccessors(entity, column);
            if (column.typeName.contains("<") || column.typeName.contains(">")) {
                error(entity, "Generic field type not supported by the generator: " + column.typeName);
            }
        }
        if (hasErrors) {
            return;
        }

        writeMetadata(info);
        entities.add(info);
    }

    /** Verifies the entity has public getter/setter for the field; errors at compile time otherwise. */
    private void validateAccessors(TypeElement entity, ColumnInfo column) {
        boolean hasGetter = false;
        boolean hasSetter = false;
        for (Element enclosed : entity.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            javax.lang.model.element.ExecutableElement method =
                    (javax.lang.model.element.ExecutableElement) enclosed;
            if (!method.getModifiers().contains(Modifier.PUBLIC)) {
                continue;
            }
            String name = method.getSimpleName().toString();
            if (name.equals(column.getterName) && method.getParameters().isEmpty()) {
                hasGetter = true;
            }
            if (name.equals(column.setterName) && method.getParameters().size() == 1) {
                hasSetter = true;
            }
        }
        if (!hasGetter) {
            error(entity, "Field '" + column.fieldName + "' needs a public getter '" + column.getterName + "()' "
                    + "(required for compile-time generated metadata)");
        }
        if (!hasSetter) {
            error(entity, "Field '" + column.fieldName + "' needs a public setter '" + column.setterName + "(...)' "
                    + "(required for compile-time generated metadata)");
        }
    }

    // ==================== JavaPoet code generation ====================

    /** Emits the Xxx_Metadata source file (JavaPoet) for one entity. */
    private void writeMetadata(EntityInfo info) {
        ClassName generatedClass = ClassName.get(GENERATED_PACKAGE, info.generatedClassName());
        ClassName entityClass = ClassName.bestGuess(info.entityQualifiedName);

        TypeSpec.Builder builder = TypeSpec.classBuilder(info.generatedClassName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(GENERATED_METADATA)
                .addField(FieldSpec.builder(generatedClass, "INSTANCE",
                        Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                        .initializer("new $T()", generatedClass)
                        .build());

        builder.addMethod(override("entityClass")
                .returns(ClassName.get(Class.class))
                .addStatement("return $T.class", entityClass)
                .build());
        builder.addMethod(override("newInstance")
                .returns(ClassName.get(Object.class))
                .addStatement("return new $T()", entityClass)
                .build());
        builder.addMethod(override("tableName")
                .returns(String.class)
                .addStatement("return $S", info.tableName)
                .build());
        builder.addMethod(override("idColumn")
                .returns(String.class)
                .addStatement("return $S", info.idColumn.columnName)
                .build());
        builder.addMethod(override("idGenerated")
                .returns(boolean.class)
                .addStatement("return $L", info.idGenerated)
                .build());
        builder.addMethod(sqlMethod("insertSql", insertSql(info)));
        builder.addMethod(sqlMethod("updateSql", updateSql(info)));
        builder.addMethod(sqlMethod("deleteSql", deleteSql(info)));
        builder.addMethod(sqlMethod("selectByIdSql", selectByIdSql(info)));
        builder.addMethod(sqlMethod("selectAllSql", selectAllSql(info)));
        builder.addMethod(accessorsMethod(info, entityClass));

        try {
            JavaFile.builder(GENERATED_PACKAGE, builder.build())
                    .addFileComment("Generated at compile time by MetadataProcessor. Do not edit.")
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(info.element, "Failed to generate " + info.generatedClassName() + ": " + e.getMessage());
        }
    }

    /** Emits the GeneratedMetadataRegistry source file listing all processed entities. */
    private void writeRegistry() {
        MethodSpec.Builder find = MethodSpec.methodBuilder("find")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(GENERATED_METADATA)
                .addParameter(ParameterizedTypeName.get(ClassName.get(Class.class),
                        WildcardTypeName.subtypeOf(Object.class)), "entityClass");
        for (EntityInfo info : entities) {
            ClassName entityClass = ClassName.bestGuess(info.entityQualifiedName);
            ClassName generatedClass = ClassName.get(GENERATED_PACKAGE, info.generatedClassName());
            find.addStatement("if (entityClass == $T.class) return $T.INSTANCE", entityClass, generatedClass);
        }
        find.addStatement("return null");

        TypeSpec registry = TypeSpec.classBuilder(REGISTRY_CLASS)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
                .addMethod(find.build())
                .build();

        try {
            JavaFile.builder(GENERATED_PACKAGE, registry)
                    .addFileComment("Generated at compile time by MetadataProcessor. Do not edit.")
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(null, "Failed to generate " + REGISTRY_CLASS + ": " + e.getMessage());
        }
    }

    private static MethodSpec.Builder override(String name) {
        return MethodSpec.methodBuilder(name)
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC);
    }

    /** Builds a single-value SQL accessor method returning the pre-generated string. */
    private static MethodSpec sqlMethod(String name, String sql) {
        return override(name).returns(String.class).addStatement("return $S", sql).build();
    }

    /** Builds the accessors() method: one FieldAccessor.of(...) per mapped field. */
    private static MethodSpec accessorsMethod(EntityInfo info, ClassName entityClass) {
        CodeBlock.Builder cb = CodeBlock.builder();
        cb.add("return $T.of(\n", List.class);
        for (int i = 0; i < info.columns.size(); i++) {
            ColumnInfo column = info.columns.get(i);
            TypeName fieldType = toTypeName(column.typeName);
            cb.add("    $T.of($S, $L, $T.class,\n", FIELD_ACCESSOR, column.columnName, column.isId, fieldType);
            cb.add("        (Object e) -> (($T) e).$L(),\n", entityClass, column.getterName);
            cb.add("        (Object e, Object v) -> (($T) e).$L(($T) v))", entityClass, column.setterName,
                    fieldType.box());
            cb.add(i < info.columns.size() - 1 ? ",\n" : "\n");
        }
        cb.add(");");
        return override("accessors")
                .returns(ParameterizedTypeName.get(ClassName.get(List.class), FIELD_ACCESSOR))
                .addCode(cb.build())
                .build();
    }

    /** TypeMirror string → JavaPoet TypeName (primitive, array, or class). */
    private static TypeName toTypeName(String typeName) {
        if (typeName.equals("byte[]")) {
            return ArrayTypeName.of(TypeName.BYTE);
        }
        switch (typeName) {
            case "byte":
                return TypeName.BYTE;
            case "short":
                return TypeName.SHORT;
            case "int":
                return TypeName.INT;
            case "long":
                return TypeName.LONG;
            case "float":
                return TypeName.FLOAT;
            case "double":
                return TypeName.DOUBLE;
            case "boolean":
                return TypeName.BOOLEAN;
            case "char":
                return TypeName.CHAR;
            default:
                return ClassName.bestGuess(typeName);
        }
    }

    // ==================== SQL building (mirrors SqlGenerator rules) ====================

    private static String insertSql(EntityInfo info) {
        List<String> columns = new ArrayList<>();
        for (ColumnInfo column : info.columns) {
            if (!(column.isId && info.idGenerated)) {
                columns.add(column.columnName);
            }
        }
        return "INSERT INTO " + info.tableName + " (" + String.join(",", columns)
                + ") VALUES (" + placeholders(columns.size()) + ")";
    }

    private static String updateSql(EntityInfo info) {
        List<String> sets = new ArrayList<>();
        for (ColumnInfo column : info.columns) {
            if (!column.isId) {
                sets.add(column.columnName + "=?");
            }
        }
        return "UPDATE " + info.tableName + " SET " + String.join(",", sets)
                + " WHERE " + info.idColumn.columnName + "=?";
    }

    private static String deleteSql(EntityInfo info) {
        return "DELETE FROM " + info.tableName + " WHERE " + info.idColumn.columnName + "=?";
    }

    private static String selectByIdSql(EntityInfo info) {
        return "SELECT " + allColumns(info) + " FROM " + info.tableName
                + " WHERE " + info.idColumn.columnName + "=?";
    }

    private static String selectAllSql(EntityInfo info) {
        return "SELECT " + allColumns(info) + " FROM " + info.tableName;
    }

    private static String allColumns(EntityInfo info) {
        List<String> names = new ArrayList<>();
        for (ColumnInfo column : info.columns) {
            names.add(column.columnName);
        }
        return String.join(",", names);
    }

    private static String placeholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    private boolean hasErrors;

    /** Reports a compile-time error attached to the given element (null = global). */
    private void error(Element element, String message) {
        hasErrors = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}

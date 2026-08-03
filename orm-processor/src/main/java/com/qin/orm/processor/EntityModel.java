package com.qin.orm.processor;

import com.qin.orm.annotation.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed model of a {@code @Table} entity <em>interface</em>: property methods
 * (getters) and builder-style setters (same name, single param, return the interface).
 * Shared by {@link EntityProcessor} (generates the entity impl) and
 * {@link RepositoryProcessor} (generates JDBC code against the impl).
 */
public final class EntityModel {

    /**
     * Returns the generated impl class name for an entity interface.
     *
     * @param entitySimpleName the simple name of the entity interface (e.g. {@code UserEntity})
     * @return the impl simple name (e.g. {@code UserEntity_Impl})
     */
    public static String implNameOf(String entitySimpleName) {
        return entitySimpleName + "_Impl";
    }

    private TypeElement element;
    private String tableName;
    private final List<ColumnModel> columns = new ArrayList<>();
    private ColumnModel idColumn;
    private boolean idGenerated;

    public static final class ColumnModel {
        final String fieldName;
        final String columnName;
        final String typeName;   // TypeMirror#toString, e.g. "java.lang.Long", "int"
        final String getterName;
        final String setterName;
        final boolean isId;
        final boolean generated;

        ColumnModel(String fieldName, String columnName, String typeName, boolean isId, boolean generated) {
            this.fieldName = fieldName;
            this.columnName = columnName;
            this.typeName = typeName;
            this.getterName = fieldName;
            this.setterName = fieldName;
            this.isId = isId;
            this.generated = generated;
        }
    }

    /**
     * Parses an entity interface into its mapping model.
     *
     * @param entity    the {@code @Table} entity interface
     * @param types     the type utilities (unused placeholder for future resolution)
     * @param errorKind the diagnostic kind used to report mapping errors
     * @param messager  the annotation-processing messager for compile-time errors
     * @return the parsed model, or {@code null} if the entity is not valid (error reported)
     */
    public static EntityModel parse(TypeElement entity, Types types, Diagnostic.Kind errorKind,
            javax.annotation.processing.Messager messager) {
        EntityModel model = new EntityModel();
        model.element = entity;
        Table table = entity.getAnnotation(Table.class);
        if (table == null || table.name().isEmpty()) {
            messager.printMessage(errorKind, "@Table(name=...) is required on " + entity.getQualifiedName(), entity);
            return null;
        }
        model.tableName = table.name();

        for (Element enclosed : entity.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            if (method.getModifiers().contains(Modifier.STATIC)
                    || method.getModifiers().contains(Modifier.DEFAULT)
                    || method.getAnnotation(Transient.class) != null) {
                continue;
            }
            if (method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID) {
                // Getter: <name>() — the field name is the method name.
                model.addGetter(method, messager, errorKind);
            } else if (method.getParameters().size() == 1 && isSelfReturning(method, entity)) {
                // Builder setter: <name>(T value) returning the interface — skip here,
                // handled via the matching getter (same name).
            }
        }
        if (model.idColumn == null) {
            messager.printMessage(errorKind, "No @Id getter on entity interface " + entity.getQualifiedName(), entity);
            return null;
        }
        return model;
    }

    /**
     * Whether the method is a builder-style setter, i.e. takes one parameter and
     * returns the entity interface itself (e.g. {@code UserEntity id(Long id)}).
     *
     * @param method the candidate method
     * @param entity the entity interface
     * @return {@code true} if the method returns the entity interface
     */
    private static boolean isSelfReturning(ExecutableElement method, TypeElement entity) {
        TypeMirror returnType = method.getReturnType();
        return returnType.getKind() == TypeKind.DECLARED
                && ((DeclaredType) returnType).asElement().equals(entity);
    }

    /**
     * Records a property getter method as a mapped column.
     *
     * @param method    the getter method ({@code Long id()})
     * @param messager  the messager for compile-time errors
     * @param errorKind the diagnostic kind for errors
     */
    private void addGetter(ExecutableElement method, javax.annotation.processing.Messager messager,
            Diagnostic.Kind errorKind) {
        String fieldName = method.getSimpleName().toString();
        Column column = method.getAnnotation(Column.class);
        String columnName = column != null ? column.name() : fieldName;
        boolean isId = method.getAnnotation(Id.class) != null;
        boolean generated = method.getAnnotation(GeneratedValue.class) != null;

        if (isId && idColumn != null) {
            messager.printMessage(errorKind, "Multiple @Id getters on " + element.getQualifiedName(), method);
            return;
        }
        ColumnModel columnModel = new ColumnModel(fieldName, columnName,
                method.getReturnType().toString(), isId, generated);
        if (isId) {
            idColumn = columnModel;
            idGenerated = generated;
        }
        columns.add(columnModel);
    }

    public TypeElement element() {
        return element;
    }

    public String tableName() {
        return tableName;
    }

    public List<ColumnModel> columns() {
        return columns;
    }

    public ColumnModel idColumn() {
        return idColumn;
    }

    public boolean idGenerated() {
        return idGenerated;
    }

    /** Full qualified name of the entity interface, e.g. com.qin.demo.UserEntity. */
    public String entityQualifiedName() {
        return element.getQualifiedName().toString();
    }

    /** Simple name of the entity interface, e.g. UserEntity. */
    public String entitySimpleName() {
        return element.getSimpleName().toString();
    }

    /** Package of the entity interface. */
    public String entityPackage() {
        String qualified = entityQualifiedName();
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    /** Full qualified name of the generated impl class. */
    public String implQualifiedName() {
        String pkg = entityPackage();
        return pkg.isEmpty() ? implNameOf(entitySimpleName()) : pkg + "." + implNameOf(entitySimpleName());
    }
}

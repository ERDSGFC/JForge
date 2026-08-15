package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.*;

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
 * (getters) and builder-style setters (same name, single parameter, returning the
 * interface). Consumed by {@link JForgeProcessor} (via {@link EntityGenerator}
 * for the entity impl and the repository generators for the JDBC code).
 */
public final class EntityModel {

    /**
     * Returns the generated impl class name for an entity interface.
     *
     * @param entitySimpleName the simple name of the entity interface (e.g. {@code UserEntity})
     * @param suffix the configured impl suffix (e.g. {@code "_Impl"})
     * @return the impl simple name (e.g. {@code UserEntity_Impl})
     */
    public static String implNameOf(String entitySimpleName, String suffix) {
        return entitySimpleName + suffix;
    }

    private TypeElement element;
    private String tableName;
    private final List<ColumnModel> columns = new ArrayList<>();
    private ColumnModel idColumn;
    private boolean idGenerated;
    private JForgeConfigHelper config;
    private Types types;

    public static final class ColumnModel {
        final String fieldName;
        final String columnName;
        final String typeName;   // TypeMirror#toString, e.g. "java.lang.Long", "int"
        final TypeMirror returnType; // the getter's return type, for setter-type validation
        final String getterName;
        final String setterName;
        final boolean isId;
        final boolean generated;

        ColumnModel(String fieldName, String columnName, TypeMirror returnType, boolean isId, boolean generated) {
            this.fieldName = fieldName;
            this.columnName = columnName;
            this.typeName = returnType.toString();
            this.returnType = returnType;
            this.getterName = fieldName;
            this.setterName = fieldName;
            this.isId = isId;
            this.generated = generated;
        }
    }

    /**
     * Parses an entity interface into its mapping model, validating the shape:
     * every method must be a property getter, a builder setter matching a getter,
     * or a {@code static}/{@code default} method.
     *
     * @param entity    the {@code @Table} entity interface
     * @param types     the type utilities (used to compare setter parameter types with getter return types)
     * @param errorKind the diagnostic kind used to report mapping errors
     * @param messager  the annotation-processing messager for compile-time errors
     * @param config    the ORM configuration helper for the entity's package
     * @return the parsed model, or {@code null} if the entity is not valid (error reported)
     */
    public static EntityModel parse(TypeElement entity, Types types, Diagnostic.Kind errorKind,
            javax.annotation.processing.Messager messager, JForgeConfigHelper config) {
        EntityModel model = new EntityModel();
        model.element = entity;
        model.config = config;
        model.types = types;
        Table table = entity.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            model.tableName = table.name();
        } else {
            model.tableName = JForgeConfigHelper.camelToSnake(entity.getSimpleName().toString());
        }

        // Pass 1: collect the property getters (setters may be declared before their
        // getter, so validation of setters waits until all getters are known).
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method == null || isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                model.addGetter(method, messager, errorKind);
            }
        }

        // Pass 2: validate every builder setter against its getter, and reject any
        // method that is neither getter, setter nor ignored — a silently skipped
        // method would otherwise surface as an obscure "does not override" error on
        // the generated impl.
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method == null || isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                continue; // handled in pass 1
            }
            if (isBuilderSetter(method, entity)) {
                model.validateSetter(method, messager, errorKind);
            } else {
                messager.printMessage(errorKind,
                        "Unsupported method '" + method.getSimpleName() + "' on entity interface "
                                + entity.getQualifiedName() + ": only property getters, builder setters, "
                                + "static and default methods are allowed (helper logic belongs in "
                                + "default methods)", method);
            }
        }

        if (model.idColumn == null) {
            messager.printMessage(errorKind, "No @Id getter on entity interface " + entity.getQualifiedName(), entity);
            return null;
        }
        return model;
    }

    /** Casts the element to a method, or returns {@code null} for non-method elements. */
    private static ExecutableElement asMethod(Element enclosed) {
        return enclosed.getKind() == ElementKind.METHOD ? (ExecutableElement) enclosed : null;
    }

    /** Methods that are not part of the property contract and are skipped silently. */
    private static boolean isIgnored(ExecutableElement method) {
        // static/default methods carry their own implementation, so the generated
        // impl does not need to (and must not) override them; the interface itself
        // is the place for helper logic.
        return method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.DEFAULT);
    }

    /** Whether the method is a property getter: no parameters, non-void return. */
    private static boolean isGetter(ExecutableElement method) {
        return method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID;
    }

    /**
     * Whether the method is a builder-style setter, i.e. takes one parameter and
     * returns the entity interface itself (e.g. {@code UserEntity id(Long id)}).
     *
     * @param method the candidate method
     * @param entity the entity interface
     * @return {@code true} if the method returns the entity interface
     */
    private static boolean isBuilderSetter(ExecutableElement method, TypeElement entity) {
        if (method.getParameters().size() != 1) {
            return false;
        }
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
        String columnName = column != null
                ? column.name()                                         // explicit @Column
                : config.columnName(element, fieldName);               // naming strategy
        boolean isId = method.getAnnotation(Id.class) != null;
        boolean generated = method.getAnnotation(GeneratedValue.class) != null;

        if (isId && idColumn != null) {
            messager.printMessage(errorKind, "Multiple @Id getters on " + element.getQualifiedName(), method);
            return;
        }
        for (ColumnModel existing : columns) {
            if (existing.columnName.equals(columnName)) {
                messager.printMessage(errorKind,
                        "Duplicate column name '" + columnName + "' on " + element.getQualifiedName()
                                + " (getters '" + existing.fieldName + "' and '" + fieldName + "')", method);
                return;
            }
        }
        ColumnModel columnModel = new ColumnModel(fieldName, columnName,
                method.getReturnType(), isId, generated);
        if (isId) {
            idColumn = columnModel;
            idGenerated = generated;
        }
        columns.add(columnModel);
    }

    /**
     * Validates a builder setter against its getter: the setter must have a matching
     * getter of the same name, and its parameter type must equal the getter's return
     * type — otherwise the generated impl would fail to compile with an obscure
     * "does not override" error, or silently diverge from the interface contract.
     *
     * @param method    the builder setter to validate
     * @param messager  the messager for compile-time errors
     * @param errorKind the diagnostic kind for errors
     */
    private void validateSetter(ExecutableElement method,
            javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind) {
        String name = method.getSimpleName().toString();
        ColumnModel getter = null;
        for (ColumnModel column : columns) {
            if (column.fieldName.equals(name)) {
                getter = column;
                break;
            }
        }
        if (getter == null) {
            // The name is not in getterNames either: this is a setter without a getter.
            messager.printMessage(errorKind,
                    "Builder setter '" + name + "(...)' on " + element.getQualifiedName()
                            + " has no matching getter '" + name + "()'", method);
            return;
        }
        TypeMirror paramType = method.getParameters().get(0).asType();
        if (!types.isSameType(paramType, getter.returnType)) {
            messager.printMessage(errorKind,
                    "Builder setter '" + name + "' parameter type " + paramType + " does not match "
                            + "getter '" + name + "()' return type " + getter.returnType
                            + " on " + element.getQualifiedName(), method);
        }
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

    /** Full qualified name of the entity interface, e.g. io.github.erdsgfc.jforge.lambda.demo.UserEntity. */
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

    /** The active impl suffix (from @JForgeConfig or default "_Impl"). */
    public String implSuffix() {
        return config.implSuffix(element);
    }

    /**
     * Package where the generated impl class is written: {@code @JForgeConfig.generatedPackage}
     * when configured, otherwise the entity's own package. Both the file output
     * ({@link EntityGenerator}) and the repository generators' references to the
     * impl class must use this, so a configured {@code generatedPackage} keeps the
     * whole chain consistent.
     */
    public String implPackage() {
        String generated = config.generatedPackage(element);
        return generated.isEmpty() ? entityPackage() : generated;
    }

    /** Full qualified name of the generated impl class (in its actual output package). */
    public String implQualifiedName() {
        String pkg = implPackage();
        String name = implNameOf(entitySimpleName(), implSuffix());
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}

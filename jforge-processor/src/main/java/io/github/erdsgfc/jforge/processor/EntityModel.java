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
 * {@code @Table} 实体<em>接口</em>的解析模型:属性方法(getter)与 builder 风格 setter
 * (同名、单参数、返回接口自身)。由 {@link JForgeProcessor} 消费(经 {@link EntityGenerator}
 * 生成实体 impl,仓库生成器生成 JDBC 代码)。
 */
public final class EntityModel {

    /**
     * 返回实体接口的生成实现类名。
     *
     * @param entitySimpleName 实体接口的简单名,如 {@code UserEntity}
     * @param suffix           配置的实现后缀,如 {@code "_Impl"}
     * @return 实现的简单名,如 {@code UserEntity_Impl}
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
        final String typeName;   // TypeMirror#toString,如 "java.lang.Long"、"int"
        final TypeMirror returnType; // getter 的返回类型,用于 setter 类型校验
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
     * 把实体接口解析为映射模型,并校验其形态:每个方法必须是属性 getter、
     * 与 getter 匹配的 builder setter、或 {@code static}/{@code default} 方法。
     *
     * @param entity    {@code @Table} 实体接口
     * @param types     类型工具(用于比较 setter 参数类型与 getter 返回类型)
     * @param errorKind 上报映射错误所用的诊断级别
     * @param messager  注解处理 messager(编译期错误)
     * @param config    实体所在包的 ORM 配置 helper
     * @return 解析后的模型;若实体不合法则返回 {@code null}(已报错)
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
            model.tableName = CommonUtils.camelToSnake(entity.getSimpleName().toString());
        }

        // 第一遍:收集属性 getter(setter 可能声明在 getter 之前,
        // 因此 setter 的校验要等所有 getter 已知后再进行)。
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method == null || isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                model.addGetter(method, messager, errorKind);
            }
        }

        // 第二遍:校验每个 builder setter 与 getter 的匹配,并拒绝任何既非 getter、
        // 又非 setter、也非忽略项的方法——被静默跳过的方法会在生成的 impl 上表现为
        // 晦涩的 "does not override" 错误。
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method == null || isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                continue; // 第一遍已处理
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

    private static ExecutableElement asMethod(Element enclosed) {
        return enclosed.getKind() == ElementKind.METHOD ? (ExecutableElement) enclosed : null;
    }

    private static boolean isIgnored(ExecutableElement method) {
        // static/default 方法自带实现,生成的 impl 无需(也不应)覆写它们;
        // 接口本身就是辅助逻辑的归属地。
        return method.getModifiers().contains(Modifier.STATIC)
                || method.getModifiers().contains(Modifier.DEFAULT);
    }

    /** 该方法是否为属性 getter:无参数、返回非 void。 */
    private static boolean isGetter(ExecutableElement method) {
        return method.getParameters().isEmpty() && method.getReturnType().getKind() != TypeKind.VOID;
    }

    /**
     * 该方法是否为 builder 风格 setter,即单参数且返回实体接口自身
     * (如 {@code UserEntity id(Long id)})。
     *
     * @param method 候选方法
     * @param entity 实体接口
     * @return 若方法返回实体接口则返回 {@code true}
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
     * 把属性 getter 方法记录为一个映射列。
     *
     * @param method    getter 方法({@code Long id()})
     * @param messager  编译期错误的 messager
     * @param errorKind 错误的诊断级别
     */
    private void addGetter(ExecutableElement method, javax.annotation.processing.Messager messager,
            Diagnostic.Kind errorKind) {
        String fieldName = method.getSimpleName().toString();
        Column column = method.getAnnotation(Column.class);
        String columnName = column != null
                ? column.name()                                         // 显式 @Column
                : config.columnName(element, fieldName);               // 命名策略
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
     * 校验 builder setter 与 getter 的匹配:setter 必须有同名的匹配 getter,且其参数类型
     * 必须等于 getter 的返回类型——否则生成的 impl 会以晦涩的 "does not override" 错误
     * 编译失败,或静默偏离接口契约。
     *
     * @param method   待校验的 builder setter
     * @param messager 编译期错误的 messager
     * @param errorKind 错误的诊断级别
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
            // 名字也不在 getterNames 中:这是没有 getter 的 setter。
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

    /** 实体接口的全限定名,如 io.github.erdsgfc.jforge.lambda.demo.UserEntity。 */
    public String entityQualifiedName() {
        return element.getQualifiedName().toString();
    }

    /** 实体接口的简单名,如 UserEntity。 */
    public String entitySimpleName() {
        return element.getSimpleName().toString();
    }

    /** 实体接口所在的包。 */
    public String entityPackage() {
        return CommonUtils.packageOf(entityQualifiedName());
    }

    /** 生效的实现后缀(来自 @JForgeConfig 或默认 "_Impl")。 */
    public String implSuffix() {
        return config.implSuffix(element);
    }

    /**
     * 生成的实现类写入的包:配置了 {@code @JForgeConfig.generatedPackage} 时用配置值,
     * 否则用实体所在包。文件输出({@link EntityGenerator})与仓库生成器对实现类的引用
     * 都必须用它,使配置了 {@code generatedPackage} 时整条链路保持一致。
     */
    public String implPackage() {
        String generated = config.generatedPackage(element);
        return generated.isEmpty() ? entityPackage() : generated;
    }

    /** 生成的实现类的全限定名(在其实际输出包中)。 */
    public String implQualifiedName() {
        String pkg = implPackage();
        String name = implNameOf(entitySimpleName(), implSuffix());
        return pkg.isEmpty() ? name : pkg + "." + name;
    }
}

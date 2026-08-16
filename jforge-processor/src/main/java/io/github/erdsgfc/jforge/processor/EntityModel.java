package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code @Table} 实体<em>接口</em>的解析模型:属性方法(getter)与 builder 风格 setter
 * (同名、单参数、返回接口自身)。实体接口可以继承父接口——父接口声明的属性同样参与映射
 * (列顺序 = 继承层次顺序:祖先/父接口属性在前)。由 {@link JForgeProcessor} 消费(经
 * {@link EntityGenerator} 生成实体 impl 作为仓库 impl 的嵌套类,仓库生成器生成 JDBC 代码)。
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
        String typeName;   // TypeMirror#toString,如 "java.lang.Long"、"int"（协变重声明时更新）
        TypeMirror returnType; // getter 的返回类型,用于 setter 类型校验（协变重声明时更新）
        final String getterName;
        final String setterName;
        final boolean isId;
        final boolean generated;
        /** builder setter 的返回类型——父接口声明的 setter 返回父接口类型,生成的
         *  {@code @Override} setter 必须匹配声明处的返回类型;{@code null} = 无 setter。 */
        TypeMirror setterReturnType;

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

        /**
         * 子接口协变重声明父接口同名 getter 时,以子声明的返回类型为准:
         * 生成的 getter 必须实现子接口的签名(列位置与列名保持父声明)。
         */
        void overrideReturnType(TypeMirror returnType) {
            this.returnType = returnType;
            this.typeName = returnType.toString();
        }
    }

    /**
     * 把实体接口解析为映射模型,并校验其形态:每个方法必须是属性 getter、
     * 与 getter 匹配的 builder setter、或 {@code static}/{@code default} 方法。
     * 实体接口的父接口(递归)声明的属性同样参与映射。
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

        // 收集实体接口及其全部父接口(递归)声明的方法,父接口在前、子接口在后:
        // 继承的属性先于子接口属性成为列(列顺序 = 继承层次顺序)。
        List<ExecutableElement> methods = allMethods(entity);
        Set<String> seen = new HashSet<>();

        // 第一遍:收集属性 getter(setter 可能声明在 getter 之前,
        // 因此 setter 的校验要等所有 getter 已知后再进行)。
        for (ExecutableElement method : methods) {
            if (isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                String name = method.getSimpleName().toString();
                if (!seen.add(name)) {
                    // 子接口重声明父接口同名 getter(协变返回):位置/列名以父声明为准,
                    // 返回类型以子声明为准(生成的 getter 必须实现子接口的签名)。
                    model.overrideGetterReturnType(name, method.getReturnType());
                    continue;
                }
                model.addGetter(method, messager, errorKind);
            }
        }

        // 第二遍:校验每个 builder setter 与 getter 的匹配,并拒绝任何既非 getter、
        // 又非 setter、也非忽略项的方法——被静默跳过的方法会在生成的 impl 上表现为
        // 晦涩的 "does not override" 错误。
        for (ExecutableElement method : methods) {
            if (isIgnored(method)) {
                continue;
            }
            if (isGetter(method)) {
                continue; // 第一遍已处理(含重声明的 getter)
            }
            if (isBuilderSetter(method, entity, types)) {
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

    /**
     * 收集实体接口及其全部父接口(递归)声明的所有方法,按"祖先 → 直接父接口 → 自身"
     * 顺序排列:继承的属性先于子接口属性成为列。
     *
     * @param entity 实体接口
     * @return 全部声明方法(含 static/default,由调用方过滤)
     */
    private static List<ExecutableElement> allMethods(TypeElement entity) {
        List<ExecutableElement> methods = new ArrayList<>();
        collectSuperMethods(entity, methods);
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method != null) {
                methods.add(method);
            }
        }
        return methods;
    }

    /** 递归收集 {@code type} 的全部父接口方法(祖先优先,直接父接口最后)。 */
    private static void collectSuperMethods(TypeElement type, List<ExecutableElement> out) {
        for (TypeMirror iface : type.getInterfaces()) {
            if (iface.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement superType = (TypeElement) ((DeclaredType) iface).asElement();
            collectSuperMethods(superType, out);
            for (Element enclosed : superType.getEnclosedElements()) {
                ExecutableElement method = asMethod(enclosed);
                if (method != null) {
                    out.add(method);
                }
            }
        }
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
     * 该方法是否为 builder 风格 setter,即单参数且返回实体接口自身或其任一父接口
     * (如 {@code UserEntity id(Long id)},或父接口声明的 {@code BaseEntity id(Long id)})。
     *
     * @param method 候选方法
     * @param entity 实体接口
     * @param types  类型工具(子类型判断)
     * @return 若方法返回实体接口(或其父接口)则返回 {@code true}
     */
    private static boolean isBuilderSetter(ExecutableElement method, TypeElement entity, Types types) {
        if (method.getParameters().size() != 1) {
            return false;
        }
        TypeMirror returnType = method.getReturnType();
        if (returnType.getKind() != TypeKind.DECLARED) {
            return false;
        }
        TypeElement returnElement = (TypeElement) ((DeclaredType) returnType).asElement();
        // 返回类型必须是接口且是实体的超类型(实体本身或其父接口)——排除 Object 等非接口类型。
        return returnElement.getKind() == ElementKind.INTERFACE
                && types.isSubtype(entity.asType(), returnType);
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
        // 记录 setter 声明的返回类型:父接口声明的 setter 返回父接口类型,
        // 生成的 @Override setter 必须返回声明处的类型才能编译。
        getter.setterReturnType = method.getReturnType();
    }

    /**
     * 子接口协变重声明父接口同名 getter 时,以子声明的返回类型更新已有列
     * (列位置与列名保持父声明)。
     *
     * @param fieldName  属性名(方法名)
     * @param returnType 子接口 getter 声明的返回类型
     */
    private void overrideGetterReturnType(String fieldName, TypeMirror returnType) {
        for (ColumnModel column : columns) {
            if (column.fieldName.equals(fieldName)) {
                column.overrideReturnType(returnType);
                return;
            }
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
}

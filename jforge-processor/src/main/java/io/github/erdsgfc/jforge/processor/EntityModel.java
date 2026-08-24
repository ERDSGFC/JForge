package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
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
 * (列顺序 = 继承层次顺序:子接口自身属性在前,父接口属性在后)。由 {@link JForgeProcessor}
 * 消费(经 {@link EntityGenerator} 生成实体 impl 作为仓库 impl 的嵌套类,仓库生成器生成
 * JDBC 代码)。
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

    /**
     * default 属性(默认值来源)在生成的嵌套类中的默认值方法名:
     * {@code default} + getter 名首字母大写(如 {@code createdAt} → {@code defaultCreatedAt})。
     */
    public static String defaultMethodName(String getterName) {
        return "default" + Character.toUpperCase(getterName.charAt(0)) + getterName.substring(1);
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
        /** builder setter 的返回类型——父接口声明的 setter 返回父接口类型,生成的
         *  {@code @Override} setter 必须匹配声明处的返回类型;{@code null} = 无 setter。 */
        TypeMirror setterReturnType;
        /** 接口是否声明了该属性的 builder setter。{@code false} = 只读属性:
         *  生成的 impl 仍生成 {@code private} 填充 setter(供行映射内部调用),
         *  但不带 {@code @Override}、也不参与生成键回写。 */
        boolean hasSetter;
        /** 属性 getter 是否为 {@code default} 方法(带 @Column/@Id 注解的默认值来源):
         *  save/update 绑定经 接口.super.getter() 强制调用默认实现取值。 */
        boolean defaultGetter;
        /** 列是否参与 INSERT(save)——由 {@code @Column.write()} 策略派生。 */
        boolean insertable;
        /** 列是否参与 UPDATE SET(update)——由 {@code @Column.write()} 策略派生。 */
        boolean updatable;

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

        // 收集实体接口及其全部父接口(递归)声明的方法,子接口在前、父接口在后:
        // 子接口声明的属性先成为列(列顺序 = 继承层次顺序)。
        // 父接口的泛型类型变量已按其实际实参替换(见 MethodInfo 注释)。
        List<MethodInfo> methods = allMethods(entity, types, messager, errorKind);
        Set<String> seen = new HashSet<>();

        // 第一遍:收集属性 getter(setter 可能声明在 getter 之前,
        // 因此 setter 的校验要等所有 getter 已知后再进行)。
        for (MethodInfo method : methods) {
            if (isIgnored(method.element)) {
                continue;
            }
            if (isGetter(method)) {
                String name = method.element.getSimpleName().toString();
                if (!seen.add(name)) {
                    // 父接口重声明(协变)的同名 getter:子接口声明已先收集,
                    // 直接跳过——Java 覆盖语义,列位置与类型均以子声明为准。
                    continue;
                }
                model.addGetter(method, messager, errorKind);
            }
        }

        // 第二遍:校验每个 builder setter 与 getter 的匹配,并拒绝任何既非 getter、
        // 又非 setter、也非忽略项的方法——被静默跳过的方法会在生成的 impl 上表现为
        // 晦涩的 "does not override" 错误。
        for (MethodInfo method : methods) {
            if (isIgnored(method.element)) {
                continue;
            }
            if (isGetter(method)) {
                continue; // 第一遍已处理(含重声明的 getter)
            }
            if (isBuilderSetter(method, entity, types)) {
                model.validateSetter(method, messager, errorKind);
            } else {
                messager.printMessage(errorKind,
                        "Unsupported method '" + method.element.getSimpleName() + "' on entity interface "
                                + entity.getQualifiedName() + ": only property getters, builder setters, "
                                + "static and default methods are allowed (helper logic belongs in "
                                + "default methods)", method.element);
            }
        }

        if (model.idColumn == null) {
            messager.printMessage(errorKind, "No @Id getter on entity interface " + entity.getQualifiedName(), entity);
            return null;
        }
        return model;
    }

    /**
     * 收集到的一个声明方法：原始元素（读注解/修饰符）+ 经父接口泛型实参替换后的签名。
     * 泛型父接口（如 {@code interface BaseEntity<T extends BaseEntity<T>>}）中返回/参数
     * 类型里的类型变量 {@code T}，在 {@code interface UserEntity extends BaseEntity<UserEntity>}
     * 场景下经 {@link Types#asMemberOf} 替换为实际实参（{@code UserEntity}），使父接口的
     * builder setter（{@code T name(String)}）生成的 {@code @Override} 方法返回子接口类型。
     *
     * @param signature 泛型替换后的方法签名（返回类型/参数类型已具体化）。
     */
    private record MethodInfo(ExecutableElement element, ExecutableType signature) { }

    /**
     * 收集实体接口及其全部父接口(递归)声明的所有方法,按"自身 → 直接父接口 → 祖先"
     * 顺序排列:子接口声明的属性先成为列;子接口重声明父接口同名属性时以子声明为准
     * (Java 覆盖语义,见第一遍收集的去重)。
     *
     * @param entity    实体接口
     * @param types     类型工具(asMemberOf 泛型替换)
     * @param messager  编译期错误的 messager(泛型父接口约束校验)
     * @param errorKind 错误的诊断级别
     * @return 全部声明方法(含 static/default,由调用方过滤);不合法的泛型父接口被跳过并报错
     */
    private static List<MethodInfo> allMethods(TypeElement entity, Types types,
            javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind) {
        List<MethodInfo> methods = new ArrayList<>();
        // 实体接口自身的类型(无类型变量),asMemberOf 返回原签名;父接口方法经
        // collectSuperMethods 以具体化的父接口类型替换类型变量。
        DeclaredType entityType = (DeclaredType) entity.asType();
        for (Element enclosed : entity.getEnclosedElements()) {
            ExecutableElement method = asMethod(enclosed);
            if (method != null) {
                methods.add(new MethodInfo(method, (ExecutableType) types.asMemberOf(entityType, method)));
            }
        }
        collectSuperMethods((DeclaredType) entity.asType(), entity, types, messager, errorKind, methods);
        return methods;
    }

    /**
     * 递归收集 {@code declared} 的全部父接口方法(直接父接口优先,祖先最后)。
     * 对每个父接口校验泛型约束:类型实参最多一个、且必须是实体接口自身
     * (CRTP 自限定模式 {@code BaseEntity<UserEntity>})——不满足时报错并跳过该父接口;
     * 方法签名经 {@link Types#asMemberOf} 以父接口的实际实参替换类型变量。
     * 递归经 {@link Types#directSupertypes} 传递已替换的父接口类型,使多层泛型继承链
     * (如 {@code BaseEntity<T> extends AbstractEntity<T>})保持替换。
     */
    private static void collectSuperMethods(DeclaredType declared, TypeElement entity, Types types,
            javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind,
            List<MethodInfo> out) {
        for (TypeMirror iface : types.directSupertypes(declared)) {
            if (iface.getKind() != TypeKind.DECLARED) {
                continue;
            }
            DeclaredType superDeclared = (DeclaredType) iface;
            TypeElement superType = (TypeElement) superDeclared.asElement();
            if (superType.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            List<? extends TypeMirror> typeArgs = superDeclared.getTypeArguments();
            if (typeArgs.size() > 1) {
                messager.printMessage(errorKind,
                        "Generic super-interface " + superType.getQualifiedName() + " of entity "
                                + entity.getQualifiedName() + " must have at most one type argument "
                                + "(only the self-referential pattern is supported, e.g. "
                                + "BaseEntity<UserEntity>)", superType);
                continue;
            }
            if (typeArgs.size() == 1 && !types.isSameType(typeArgs.getFirst(), entity.asType())) {
                messager.printMessage(errorKind,
                        "Type argument of generic super-interface " + superType.getQualifiedName()
                                + " must be the entity itself: " + typeArgs.getFirst() + " != " + entity.getQualifiedName()
                                + " (self-referential pattern, e.g. BaseEntity<UserEntity>)", superType);
                continue;
            }
            for (Element enclosed : superType.getEnclosedElements()) {
                ExecutableElement method = asMethod(enclosed);
                if (method != null) {
                    out.add(new MethodInfo(method, (ExecutableType) types.asMemberOf(superDeclared, method)));
                }
            }
            collectSuperMethods(superDeclared, entity, types, messager, errorKind, out);
        }
    }

    private static ExecutableElement asMethod(Element enclosed) {
        return enclosed.getKind() == ElementKind.METHOD ? (ExecutableElement) enclosed : null;
    }

    private static boolean isIgnored(ExecutableElement method) {
        // static 方法忽略;default 方法默认忽略(辅助逻辑的归属地),但带列注解
        // (@Column/@Id)的 default getter 是"属性默认值来源"——参与映射,
        // save/update 时经 接口.super.getter() 强制调用默认实现取值绑定。
        if (method.getModifiers().contains(Modifier.STATIC)) {
            return true;
        }
        return method.getModifiers().contains(Modifier.DEFAULT)
                && method.getAnnotation(Column.class) == null
                && method.getAnnotation(Id.class) == null;
    }

    /** 该方法是否为属性 getter:无参数、返回非 void。 */
    private static boolean isGetter(MethodInfo method) {
        return method.signature.getParameterTypes().isEmpty()
                && method.signature.getReturnType().getKind() != TypeKind.VOID;
    }

    /**
     * 该方法是否为 builder 风格 setter,即单参数且返回实体接口自身或其任一父接口
     * (如 {@code UserEntity id(Long id)},或父接口声明的 {@code BaseEntity id(Long id)};
     * 泛型父接口场景返回的是替换后的子接口类型)。
     *
     * @param method 候选方法
     * @param entity 实体接口
     * @param types  类型工具(子类型判断)
     * @return 若方法返回实体接口(或其父接口)则返回 {@code true}
     */
    private static boolean isBuilderSetter(MethodInfo method, TypeElement entity, Types types) {
        if (method.signature.getParameterTypes().size() != 1) {
            return false;
        }
        TypeMirror returnType = method.signature.getReturnType();
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
     * @param method    getter 方法({@code Long id()},泛型父接口场景签名已替换)
     * @param messager  编译期错误的 messager
     * @param errorKind 错误的诊断级别
     */
    private void addGetter(MethodInfo method, javax.annotation.processing.Messager messager,
            Diagnostic.Kind errorKind) {
        ExecutableElement element = method.element;
        String fieldName = element.getSimpleName().toString();
        Column column = element.getAnnotation(Column.class);
        String columnName = column != null
                ? column.name()                                         // 显式 @Column
                : config.columnName(this.element, fieldName);          // 命名策略
        boolean isId = element.getAnnotation(Id.class) != null;
        boolean generated = element.getAnnotation(GeneratedValue.class) != null;

        if (isId && idColumn != null) {
            messager.printMessage(errorKind, "Multiple @Id getters on " + this.element.getQualifiedName(), element);
            return;
        }
        for (ColumnModel existing : columns) {
            if (existing.columnName.equals(columnName)) {
                messager.printMessage(errorKind,
                        "Duplicate column name '" + columnName + "' on " + this.element.getQualifiedName()
                                + " (getters '" + existing.fieldName + "' and '" + fieldName + "')", element);
                return;
            }
        }
        ColumnModel columnModel = new ColumnModel(fieldName, columnName,
                method.signature.getReturnType(), isId, generated);
        columnModel.defaultGetter = element.getModifiers().contains(Modifier.DEFAULT);
        // 写入策略:显式 @Column.write() 派生 INSERT/UPDATE 参与位;
        // 无 @Column 的列(命名策略推断)与 @Id 列缺省 BOTH。无值来源(无 setter 无
        // default)的纯只读列由 insertColumns/updateSql 再按值来源排除。
        WritePolicy write = column != null ? column.write() : WritePolicy.BOTH;
        columnModel.insertable = write != WritePolicy.UPDATE_ONLY && write != WritePolicy.NONE;
        columnModel.updatable = write != WritePolicy.INSERT_ONLY && write != WritePolicy.NONE;
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
    private void validateSetter(MethodInfo method,
            javax.annotation.processing.Messager messager, Diagnostic.Kind errorKind) {
        ExecutableElement element = method.element;
        String name = element.getSimpleName().toString();
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
                    "Builder setter '" + name + "(...)' on " + this.element.getQualifiedName()
                            + " has no matching getter '" + name + "()'", element);
            return;
        }
        TypeMirror paramType = method.signature.getParameterTypes().getFirst();
        if (!types.isSameType(paramType, getter.returnType)) {
            messager.printMessage(errorKind,
                    "Builder setter '" + name + "' parameter type " + paramType + " does not match "
                            + "getter '" + name + "()' return type " + getter.returnType
                            + " on " + this.element.getQualifiedName(), element);
        }
        // 记录 setter 声明的返回类型:父接口声明的 setter 返回父接口类型(泛型父接口场景
        // 为替换后的子接口类型),生成的 @Override setter 必须返回声明处的类型才能编译。
        getter.setterReturnType = method.signature.getReturnType();
        getter.hasSetter = true;
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

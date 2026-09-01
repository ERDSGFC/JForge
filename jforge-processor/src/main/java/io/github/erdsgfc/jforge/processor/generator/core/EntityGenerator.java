package io.github.erdsgfc.jforge.processor.generator.core;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.erdsgfc.jforge.processor.EntityModel;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 把 {@code @Table} 实体接口组装成实现类 {@code Xxx_Impl} 的类规格（TypeSpec）：
 * 每个属性方法对应一个私有字段 + getter + builder setter（返回实体接口）。
 *
 * <p>纯静态工具、不写文件——{@link RepositoryGenerator} 把 {@link #buildImpl} 返回的
 * TypeSpec 作为 {@code private static final} 嵌套类嵌入仓库实现类（{@code XxxRepository_Impl}），
 * 因此实体 impl 不再是顶层类：用户代码无法直接 {@code new Xxx_Impl()}，只能经
 * {@code repo.createEntity()} 获取实体实例。多个仓库共享同一实体时各仓库各嵌一份副本
 * （私有嵌套类无法共享）。</p>
 */
public final class EntityGenerator {

    private EntityGenerator() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 组装 {@code Xxx_Impl} 类：实体接口的所有属性 → 私有字段 + getter + builder setter。
     *
     * @param model 已解析的实体模型
     * @return 实体 impl 类规格（作为仓库 impl 的 private static final 嵌套类）
     */
    public static TypeSpec buildImpl(EntityModel model) {
        ClassName entityClass = ClassName.get(model.entityPackage(), model.entitySimpleName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(
                EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(entityClass).addSuperinterface(ClassName.get(Serializable.class));

        for (EntityModel.ColumnModel column : model.columns()) {
            TypeName type = TypeName.get(column.returnType);
            builder.addField(FieldSpec.builder(type, column.fieldName, Modifier.PRIVATE).build());
            // getter
            builder.addMethod(MethodSpec.methodBuilder(column.getterName)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(type)
                    .addStatement("return $N", column.fieldName)
                    .build());
            // setter 返回类型以声明处为准:父接口声明的 setter 返回父接口类型,
            // 生成的 @Override setter 必须匹配;无 setter 的属性(只有 getter)默认真体接口。
            TypeName setterReturn = column.setterReturnType != null
                    ? TypeName.get(column.setterReturnType)
                    : entityClass;
            MethodSpec.Builder setter = MethodSpec.methodBuilder(column.setterName)
                    .returns(setterReturn)
                    .addParameter(type, "value")
                    .addStatement("this.$N = value", column.fieldName)
                    .addStatement("return this");
            if (column.hasSetter) {
                // 接口声明了 setter:@Override + public(接口契约的一部分)。
                setter.addAnnotation(Override.class).addModifiers(Modifier.PUBLIC);
            } else {
                // 只读属性(接口只有 getter):生成 private 填充 setter 供行映射内部调用
                // (外部类可访问嵌套类私有成员),不带 @Override——接口上没有该方法。
                setter.addModifiers(Modifier.PRIVATE);
            }
            builder.addMethod(setter.build());
            if (column.defaultGetter) {
                // default 属性(默认值来源):生成私有方法返回 接口.super.getter()——
                // 嵌套类实现了实体接口,TypeName.super 语法合法;宿主类的 save/update
                // 绑定经强转调用它取得默认值(查询读回走覆盖的 getter,返回字段值)。
                builder.addMethod(MethodSpec.methodBuilder(EntityModel.defaultMethodName(column.getterName))
                        .addModifiers(Modifier.PRIVATE)
                        .returns(TypeName.get(column.returnType))
                        .addStatement("return $T.super.$N()", entityClass, column.getterName)
                        .build());
            }
        }
        List<EntityModel.ColumnModel> columns = model.columns();
        builder.addMethod(toStringMethod(entityClass, columns));
        builder.addMethod(equalsMethod(entityClass, columns));
        builder.addMethod(hashCodeMethod(columns));
        return builder.build();
    }

    /**
     * 生成 {@code toString()}:{@code Xxx_Impl{id=1, name=qin}}——全部字段拼接
     * (String 拼接对 null 字段输出 {@code "null"},不会 NPE)。
     */
    private static MethodSpec toStringMethod(ClassName entityClass, List<EntityModel.ColumnModel> columns) {
        CodeBlock.Builder body = CodeBlock.builder();
        body.add("return $S", entityClass.simpleName() + "{");
        for (int i = 0; i < columns.size(); i++) {
            EntityModel.ColumnModel column = columns.get(i);
            if (i > 0) {
                body.add(" + $S", ", ");
            }
            body.add(" + $S + $N", column.fieldName + "=", column.fieldName);
        }
        body.add(" + $S", "}");
        return MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement(body.build())
                .build();
    }

    /**
     * 生成 {@code equals()}:按实体接口契约比较(instanceof 实体接口 + getter 逐个比较)
     * ——不同仓库各嵌的 impl 副本(不同类)也能互相判等;引用类型使用
     * {@code Objects.equals},基本类型避免装箱,浮点类型使用 {@code compare} 保持
     * {@code NaN} 与 {@code -0.0} 的相等语义;default 列比较的是覆盖后的 getter
     * (返回字段值),不是默认值来源。
     */
    private static MethodSpec equalsMethod(ClassName entityClass, List<EntityModel.ColumnModel> columns) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(boolean.class)
                .addParameter(Object.class, "o");
        method.beginControlFlow("if (this == o)");
        method.addStatement("return true");
        method.endControlFlow();
        method.beginControlFlow("if (!(o instanceof $T))", entityClass);
        method.addStatement("return false");
        method.endControlFlow();
        method.addStatement("$T that = ($T) o", entityClass, entityClass);
        CodeBlock.Builder cond = CodeBlock.builder();
        for (int i = 0; i < columns.size(); i++) {
            EntityModel.ColumnModel column = columns.get(i);
            if (i > 0) {
                cond.add(" && ");
            }
            switch (column.returnType.getKind()) {
                case FLOAT:
                    cond.add("$T.compare(this.$N, that.$N()) == 0", Float.class,
                            column.fieldName, column.getterName);
                    break;
                case DOUBLE:
                    cond.add("$T.compare(this.$N, that.$N()) == 0", Double.class,
                            column.fieldName, column.getterName);
                    break;
                case BOOLEAN:
                case BYTE:
                case SHORT:
                case INT:
                case LONG:
                case CHAR:
                    cond.add("this.$N == that.$N()", column.fieldName, column.getterName);
                    break;
                default:
                    cond.add("$T.equals(this.$N, that.$N())", Objects.class,
                            column.fieldName, column.getterName);
                    break;
            }
        }
        method.addStatement("return $L", cond.build());
        return method.build();
    }

    /**
     * 生成 {@code hashCode()}:按 JDK 常用的 31 质数累加各字段哈希值。
     *
     * <p>相较于 {@code Objects.hash(...)}，生成代码不需要创建 varargs 数组；
     * 基本类型使用包装类的静态 {@code hashCode} 方法，引用类型则显式处理
     * {@code null}。</p>
     */
    private static MethodSpec hashCodeMethod(List<EntityModel.ColumnModel> columns) {
        MethodSpec.Builder method = MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(int.class)
                .addStatement("int result = 1");
        for (EntityModel.ColumnModel column : columns) {
            TypeName type = TypeName.get(column.returnType);
            if (type.isPrimitive()) {
                method.addStatement("result = 31 * result + $T.hashCode($N)",
                        type.box(), column.fieldName);
            } else {
                method.addStatement("result = 31 * result + ($N == null ? 0 : $N.hashCode())",
                        column.fieldName, column.fieldName);
            }
        }
        return method.addStatement("return result")
                .build();
    }
}

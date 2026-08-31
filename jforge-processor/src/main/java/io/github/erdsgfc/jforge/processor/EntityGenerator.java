package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

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
                .addSuperinterface(entityClass);

        for (EntityModel.ColumnModel column : model.columns()) {
            TypeName type = TypeName.get(column.returnType);
            builder.addField(FieldSpec.builder(type, column.fieldName, Modifier.PRIVATE).build());
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
        return builder.build();
    }
}

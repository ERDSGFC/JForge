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
final class EntityGenerator {

    private EntityGenerator() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 组装 {@code Xxx_Impl} 类：实体接口的所有属性 → 私有字段 + getter + builder setter。
     *
     * @param model 已解析的实体模型
     * @return 实体 impl 类规格（作为仓库 impl 的 private static final 嵌套类）
     */
    static TypeSpec buildImpl(EntityModel model) {
        ClassName entityClass = ClassName.get(model.entityPackage(), model.entitySimpleName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(
                EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()))
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .addSuperinterface(entityClass);

        for (EntityModel.ColumnModel column : model.columns()) {
            // TypeName.get(TypeMirror) 无损转换;toString + bestGuess 会在泛型/嵌套类上出错。
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
            builder.addMethod(MethodSpec.methodBuilder(column.setterName)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(setterReturn)
                    .addParameter(type, "value")
                    .addStatement("this.$N = value", column.fieldName)
                    .addStatement("return this")
                    .build());
        }
        return builder.build();
    }
}

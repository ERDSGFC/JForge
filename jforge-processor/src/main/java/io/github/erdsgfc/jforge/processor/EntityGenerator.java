package io.github.erdsgfc.jforge.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import javax.lang.model.element.Modifier;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.Set;

/**
 * 生成 {@code @Table} 实体的实现类 {@code Xxx_Impl}：每个属性方法对应一个私有字段 +
 * getter + builder setter（返回实体接口）。
 *
 * <p>由 {@link JForgeProcessor} 在解析每个 {@code @Dao} 时经 {@code BaseRepository<T, ID>}
 * 定位实体后调用——实体 impl 的存在性由仓库保证，不会生成"孤儿"实体。多个仓库共享同一实体时
 * 通过全限定名集合去重，只生成一次。</p>
 */
final class EntityGenerator {

    private final ProcessingEnvironment processingEnv;
    private final JForgeConfigHelper configHelper;
    /** 已生成过的实体 impl 全限定名，避免重复生成。 */
    private final Set<String> generatedEntities;

    /**
     * @param processingEnv   the processing environment (for the file writer / messager)
     * @param configHelper    the shared ORM config helper
     * @param generatedEntities the set of already-generated entity impl qualified names
     */
    EntityGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper,
            Set<String> generatedEntities) {
        this.processingEnv = processingEnv;
        this.configHelper = configHelper;
        this.generatedEntities = generatedEntities;
    }

    /**
     * 为实体模型生成 impl 类。同一实体被多个仓库引用时只生成一次（返回不做任何事）。
     *
     * @param model the parsed entity model
     */
    void generate(EntityModel model) {
        if (!generatedEntities.add(model.implQualifiedName())) {
            return; // 已由其他仓库生成过
        }
        TypeSpec typeSpec = buildImpl(model);
        // 输出包与去重 key 同源（model.implPackage()）：
        // 配置了 @JForgeConfig.generatedPackage 时两者一致，否则都是实体同包。
        String outputPkg = model.implPackage();
        try {
            JavaFile.builder(outputPkg, typeSpec)
                    .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate " + model.implQualifiedName() + ": " + e.getMessage());
        }
    }

    /**
     * 组装 {@code Xxx_Impl} 类：实体接口的所有属性 → 私有字段 + getter + builder setter。
     *
     * @param model the parsed entity model
     * @return the generated entity impl class specification
     */
    private static TypeSpec buildImpl(EntityModel model) {
        ClassName entityClass = ClassName.get(model.entityPackage(), model.entitySimpleName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(
                EntityModel.implNameOf(model.entitySimpleName(), model.implSuffix()))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(entityClass);

        for (EntityModel.ColumnModel column : model.columns()) {
            TypeName type = TypeNameUtils.toTypeName(column.typeName);
            builder.addField(FieldSpec.builder(type, column.fieldName, Modifier.PRIVATE).build());
            builder.addMethod(MethodSpec.methodBuilder(column.getterName)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(type)
                    .addStatement("return $N", column.fieldName)
                    .build());
            builder.addMethod(MethodSpec.methodBuilder(column.setterName)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(entityClass)
                    .addParameter(type, "value")
                    .addStatement("this.$N = value", column.fieldName)
                    .addStatement("return this")
                    .build());
        }
        return builder.build();
    }
}

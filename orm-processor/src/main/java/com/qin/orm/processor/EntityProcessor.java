package com.qin.orm.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;
import com.qin.orm.annotation.Table;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.Set;

/**
 * Generates the implementation class for each {@code @Table} entity interface:
 * {@code UserEntity_Impl implements UserEntity} with a private field per property
 * method, the getter, and the builder-style setter (returns the interface).
 */
@AutoService(javax.annotation.processing.Processor.class)
@SupportedAnnotationTypes("com.qin.orm.annotation.Table")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class EntityProcessor extends AbstractProcessor {

    /**
     * Scans the current round's root elements for {@code @Table} interfaces and
     * generates their impl classes.
     *
     * @param annotations the annotation types requested by this processor
     * @param roundEnv    the current processing round
     * @return {@code true} (the @Table annotation is claimed by this processor)
     */
    private OrmConfigHelper configHelper;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        configHelper = new OrmConfigHelper(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getElementsAnnotatedWith(Table.class)) {
            if (root.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            TypeElement typeElement = (TypeElement) root;
            if (typeElement.getAnnotation(Table.class) != null) {
                processEntity(typeElement);
            }
        }
        return true;
    }

    /**
     * Generates the impl class for one entity interface.
     *
     * @param entity the {@code @Table} entity interface
     */
    private void processEntity(TypeElement entity) {
        EntityModel model = EntityModel.parse(entity, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager(), configHelper);
        if (model == null) {
            return;
        }
        TypeSpec typeSpec = buildImpl(model);
        // Package from @OrmConfig.generatedPackage (or same package as entity).
        String outputPkg = model.generatedPackage();
        if (outputPkg.isEmpty()) {
            outputPkg = model.entityPackage();
        }
        try {
            JavaFile.builder(outputPkg, typeSpec)
                    .addFileComment("Generated at compile time by EntityProcessor. Do not edit.")
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate " + model.implQualifiedName() + ": " + e.getMessage(), entity);
        }
    }

    /**
     * Builds the {@code Xxx_Impl} class implementing the entity interface.
     *
     * @param model the parsed entity model
     * @return the generated class specification
     */
    static TypeSpec buildImpl(EntityModel model) {
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

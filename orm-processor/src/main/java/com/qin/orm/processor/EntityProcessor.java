package com.qin.orm.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.qin.orm.annotation.Table;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
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

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (Element root : roundEnv.getRootElements()) {
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

    private void processEntity(TypeElement entity) {
        EntityModel model = EntityModel.parse(entity, processingEnv.getTypeUtils(),
                Diagnostic.Kind.ERROR, processingEnv.getMessager());
        if (model == null) {
            return;
        }
        TypeSpec typeSpec = buildImpl(model);
        try {
            JavaFile.builder(model.entityPackage(), typeSpec)
                    .addFileComment("Generated at compile time by EntityProcessor. Do not edit.")
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to generate " + model.implQualifiedName() + ": " + e.getMessage(), entity);
        }
    }

    /** Builds the {@code Xxx_Impl} class implementing the entity interface. */
    static TypeSpec buildImpl(EntityModel model) {
        ClassName entityClass = ClassName.get(model.entityPackage(), model.entitySimpleName());
        TypeSpec.Builder builder = TypeSpec.classBuilder(EntityModel.implNameOf(model.entitySimpleName()))
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

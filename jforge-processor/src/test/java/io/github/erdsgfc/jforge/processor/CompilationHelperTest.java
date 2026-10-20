package io.github.erdsgfc.jforge.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CompilationHelper} 的编译产物捕获与输出隔离验证。
 */
class CompilationHelperTest {

    @Test
    void capturesGeneratedSourcesResourcesAndClasses() throws Exception {
        String source = """
                package test;
                @Deprecated
                public interface UserRepository {
                }
                """;

        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository", source, new GeneratingProcessor());

        assertTrue(result.success, () -> result.diagnostics.toString());
        assertTrue(result.generatedSources.containsKey("com.java.bad.Generated"),
                () -> result.generatedSources.keySet().toString());
        assertTrue(result.generatedSources.get("com.java.bad.Generated")
                        .contains("package com.java.bad;"),
                "生成源码内容应与处理器写入内容一致");

        assertEquals("generated-resource",
                new String(result.generatedResources.get("com/java/bad/generated.txt"),
                        StandardCharsets.UTF_8));

        assertTrue(result.generatedClasses.containsKey("test.UserRepository"),
                () -> result.generatedClasses.keySet().toString());
        assertTrue(result.generatedClasses.containsKey("com.java.bad.Generated"),
                () -> result.generatedClasses.keySet().toString());
        assertTrue(result.generatedClasses.get("test.UserRepository").length > 0);
    }

    @Test
    void writesClassesAndResourcesToExplicitOutputDirectory(@TempDir Path tempDir) throws Exception {
        Path classOutput = tempDir.resolve("classes");

        CompilationHelper.CompilationResult result = CompilationHelper.compile(
                "test.UserRepository",
                "package test; @Deprecated public interface UserRepository { }",
                runtimeClasspath(),
                classOutput,
                new GeneratingProcessor());

        assertTrue(result.success, () -> result.diagnostics.toString());
        assertTrue(Files.exists(classOutput.resolve("test/UserRepository.class")),
                "显式输出目录应由调用方保留 class 文件");
        assertTrue(Files.exists(classOutput.resolve("com/java/bad/generated.txt")),
                "生成资源应写入调用方指定的输出目录");
    }

    private static List<String> runtimeClasspath() {
        return List.of(System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator));
    }

    /**
     * 生成一个位于包含 {@code java} 包段名下的源码，以及一个资源文件，
     * 用于验证结果 Map 的键不会从 URI 反向解析而丢失包段。
     */
    private static final class GeneratingProcessor extends AbstractProcessor {
        private boolean generated;

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of(Deprecated.class.getName());
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations,
                               RoundEnvironment roundEnv) {
            if (generated || roundEnv.processingOver()) {
                return false;
            }
            generated = true;
            try {
                JavaFileObject source = processingEnv.getFiler()
                        .createSourceFile("com.java.bad.Generated");
                try (Writer writer = source.openWriter()) {
                    writer.write("""
                            package com.java.bad;
                            public interface Generated {
                            }
                            """);
                }

                FileObject resource = processingEnv.getFiler().createResource(
                        StandardLocation.CLASS_OUTPUT,
                        "com.java.bad",
                        "generated.txt");
                try (OutputStream output = resource.openOutputStream()) {
                    output.write("generated-resource".getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new IllegalStateException("生成测试编译产物失败", e);
            }
            return false;
        }
    }

}

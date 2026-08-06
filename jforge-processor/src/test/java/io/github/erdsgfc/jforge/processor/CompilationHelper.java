package io.github.erdsgfc.jforge.processor;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 通用编译工具 —— 在内存中编译源码、运行注解处理器、捕获生成文件与编译诊断。
 *
 * <p>适用于任何 {@link Processor} 的单元测试，不依赖特定处理器。
 *
 * <pre>{@code
 * // 简单用法（classpath 取当前 JVM 运行时）
 * CompilationResult result = CompilationHelper.compile(
 *         "test.User", sourceCode, new MyProcessor());
 *
 * // 正向用例：断言生成文件
 * String gen = result.generatedSources.get("com.example.generated.User_Metadata");
 * assertNotNull(gen);
 *
 * // 异常用例：断言编译诊断
 * assertFalse(result.success);
 * assertTrue(result.diagnostics.stream()
 *         .anyMatch(d -> d.getMessage(null).contains("expected error")));
 * }</pre>
 */
public final class CompilationHelper {

    private CompilationHelper() {}

    /** 默认 .class 输出目录 */
    private static final Path DEFAULT_CLASS_OUTPUT = Path.of("target/apt-test-classes");

    // ============================================================
    // 公开 API（重载链）
    // ============================================================

    /**
     * 编译源码并运行处理器，classpath 自动取当前 JVM 运行时。
     *
     * @param className  全限定类名，如 "test.User"
     * @param sourceCode 源码文本
     * @param processors 要运行的注解处理器（至少一个）
     * @return 包含生成文件、编译诊断和成功标志的结果
     */
    public static CompilationResult compile(String className, String sourceCode,
                                             Processor... processors) throws IOException {
        return compile(className, sourceCode, runtimeClasspath(), processors);
    }

    /**
     * 编译源码并运行处理器，指定 classpath。
     *
     * @param className  全限定类名
     * @param sourceCode 源码文本
     * @param classpath  编译所需的 classpath 列表（jar 或目录）
     * @param processors 要运行的注解处理器（至少一个）
     * @return 包含生成文件、编译诊断和成功标志的结果
     */
    public static CompilationResult compile(String className, String sourceCode,
                                             List<String> classpath,
                                             Processor... processors) throws IOException {
        return compile(className, sourceCode, classpath, DEFAULT_CLASS_OUTPUT, processors);
    }

    /**
     * 完整参数版本。
     *
     * @param className   全限定类名
     * @param sourceCode  源码文本
     * @param classpath   编译 classpath
     * @param classOutput .class 输出目录
     * @param processors  注解处理器
     * @return 包含生成文件、编译诊断和成功标志的结果
     */
    public static CompilationResult compile(String className, String sourceCode,
                                             List<String> classpath, Path classOutput,
                                             Processor... processors) throws IOException {
        Objects.requireNonNull(processors, "至少需要一个注解处理器");
        if (processors.length == 0) {
            throw new IllegalArgumentException("至少需要一个注解处理器");
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("无法获取系统 JavaCompiler，请使用 JDK 而非 JRE 运行测试");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        try (StandardJavaFileManager fileManager =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {

            Files.createDirectories(classOutput);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOutput.toFile()));

            GeneratedFileManager gfm = new GeneratedFileManager(fileManager);

            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            JavaFileObject sourceFile =
                    SimpleJavaFileObject.forSource(URI.create(simpleName + ".java"), sourceCode);

            List<String> options = buildOptions(classpath);

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, gfm, diagnostics, options, null, List.of(sourceFile));
            task.setProcessors(Arrays.asList(processors));

            boolean success = task.call();

            return new CompilationResult(
                    new LinkedHashMap<>(gfm.generatedSources),
                    new ArrayList<>(diagnostics.getDiagnostics()),
                    success);
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private static List<String> buildOptions(List<String> classpath) {
        String release = System.getProperty("java.specification.version", "25");
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(release);
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, classpath));
        }
        return options;
    }

    private static List<String> runtimeClasspath() {
        return List.of(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    // ============================================================
    // CompilationResult
    // ============================================================

    /**
     * 编译结果：生成源文件、编译诊断、成功标志。
     */
    public static final class CompilationResult {
        /** 全限定类名 → 生成源文件内容 */
        public final Map<String, String> generatedSources;

        /** 编译期间的所有诊断消息 */
        public final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        /** 编译是否成功 */
        public final boolean success;

        CompilationResult(Map<String, String> generatedSources,
                          List<Diagnostic<? extends JavaFileObject>> diagnostics,
                          boolean success) {
            this.generatedSources = generatedSources;
            this.diagnostics = diagnostics;
            this.success = success;
        }
    }

    // ============================================================
    // GeneratedFileManager
    // ============================================================

    /**
     * 装饰 StandardJavaFileManager，拦截 SOURCE 输出存入内存。
     */
    static class GeneratedFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        final Map<String, String> generatedSources = new LinkedHashMap<>();

        GeneratedFileManager(StandardJavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location, String className,
                JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            if (kind == JavaFileObject.Kind.SOURCE) {
                return new GeneratedSource(className);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        class GeneratedSource extends SimpleJavaFileObject {
            private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

            GeneratedSource(String className) {
                super(URI.create("generated:///" + className.replace('.', '/') + ".java"),
                      Kind.SOURCE);
            }

            @Override
            public OutputStream openOutputStream() { return baos; }

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                String content = baos.toString(StandardCharsets.UTF_8);
                generatedSources.put(qualifiedName(), content);
                return content;
            }

            private String qualifiedName() {
                return uri.getPath().replace("/", ".").replace(".java", "").replaceAll("^\\.+", "");
            }
        }
    }
}

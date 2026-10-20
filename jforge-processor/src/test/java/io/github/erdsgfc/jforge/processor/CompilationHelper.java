package io.github.erdsgfc.jforge.processor;

import javax.annotation.processing.Processor;
import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 通用编译工具 —— 在内存中编译源码、运行注解处理器、捕获生成文件与编译诊断。
 *
 * <p>适用于基于 classpath 的 {@link Processor} 单元测试，不依赖特定处理器。
 * 默认使用当前 JVM 的 classpath，并为每次编译创建独立的临时 class 输出目录；
 * 编译目标版本默认为 Java 25，可通过系统属性
 * {@code jforge.compilation.release} 覆盖。
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
 * // 断言生成资源
 * byte[] metadata = result.generatedResources.get("com/example/user.metadata");
 *
 * // 断言编译产物中的类
 * assertTrue(result.generatedClasses.containsKey("com.example.User"));
 *
 * // 异常用例：断言编译诊断
 * assertFalse(result.success);
 * assertTrue(result.diagnostics.stream()
 *         .anyMatch(d -> d.getMessage(null).contains("expected error")));
 * }</pre>
 */
public final class CompilationHelper {

    private CompilationHelper() {}

    /** 未显式配置时使用的 Java 编译目标版本，与项目 maven.compiler.target 保持一致。 */
    private static final String DEFAULT_JAVA_RELEASE = "25";

    /** 可通过 JVM 系统属性覆盖编译目标版本，避免测试随运行 JDK 版本漂移。 */
    private static final String JAVA_RELEASE_PROPERTY = "jforge.compilation.release";

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
        Path classOutput = Files.createTempDirectory("jforge-compilation-");
        try {
            return compile(className, sourceCode, classpath, classOutput, processors);
        } finally {
            deleteRecursively(classOutput);
        }
    }

    /**
     * 完整参数版本。
     *
     * @param className   全限定类名
     * @param sourceCode  源码文本
     * @param classpath   编译 classpath
     * @param classOutput .class 输出目录；调用方负责管理和清理
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

            GeneratedFileManager gfm = new GeneratedFileManager(fileManager, classOutput);

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
                    new LinkedHashMap<>(gfm.generatedResources),
                    new LinkedHashMap<>(gfm.generatedClasses),
                    new ArrayList<>(diagnostics.getDiagnostics()),
                    success);
        }
    }

    // ============================================================
    // 内部工具
    // ============================================================

    private static List<String> buildOptions(List<String> classpath) {
        List<String> options = new ArrayList<>();
        options.add("--release");
        options.add(javaRelease());
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(String.join(File.pathSeparator, classpath));
        }
        return options;
    }

    private static String javaRelease() {
        String configured = System.getProperty(JAVA_RELEASE_PROPERTY);
        return configured == null || configured.isBlank()
                ? DEFAULT_JAVA_RELEASE
                : configured.trim();
    }

    private static List<String> runtimeClasspath() {
        return List.of(System.getProperty("java.class.path").split(File.pathSeparator));
    }

    /**
     * 删除本次编译使用的临时输出目录。
     *
     * @param root 临时目录
     */
    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths
                    .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ============================================================
    // CompilationResult
    // ============================================================

    /**
     * 编译结果：生成源码、生成资源、编译 class、编译诊断和成功标志。
     */
    public static final class CompilationResult {
        /** 全限定类名 → 生成源文件内容 */
        public final Map<String, String> generatedSources;

        /** 资源路径（以 / 分隔）→ 生成资源内容 */
        public final Map<String, byte[]> generatedResources;

        /** 全限定类名 → 生成的 class 文件内容 */
        public final Map<String, byte[]> generatedClasses;

        /** 编译期间的所有诊断消息 */
        public final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        /** 编译是否成功 */
        public final boolean success;

        CompilationResult(Map<String, String> generatedSources,
                          Map<String, byte[]> generatedResources,
                          Map<String, byte[]> generatedClasses,
                          List<Diagnostic<? extends JavaFileObject>> diagnostics,
                          boolean success) {
            this.generatedSources = generatedSources;
            this.generatedResources = generatedResources;
            this.generatedClasses = generatedClasses;
            this.diagnostics = diagnostics;
            this.success = success;
        }
    }

    // ============================================================
    // GeneratedFileManager
    // ============================================================

    /**
     * 装饰 StandardJavaFileManager，拦截源码、资源和 class 输出存入内存。
     */
    static class GeneratedFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        final Map<String, String> generatedSources = new LinkedHashMap<>();
        final Map<String, byte[]> generatedResources = new LinkedHashMap<>();
        final Map<String, byte[]> generatedClasses = new LinkedHashMap<>();
        private final Path classOutput;

        GeneratedFileManager(StandardJavaFileManager fileManager, Path classOutput) {
            super(fileManager);
            this.classOutput = classOutput;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                Location location, String className,
                JavaFileObject.Kind kind, FileObject sibling) throws IOException {
            if (kind == JavaFileObject.Kind.SOURCE) {
                return new GeneratedSource(className);
            }
            if (kind == JavaFileObject.Kind.CLASS) {
                return new GeneratedClass(className);
            }
            return super.getJavaFileForOutput(location, className, kind, sibling);
        }

        @Override
        public FileObject getFileForOutput(
                Location location, String packageName,
                String relativeName, FileObject sibling) throws IOException {
            String resourceName = packageName.isEmpty()
                    ? relativeName
                    : packageName.replace('.', '/') + "/" + relativeName;
            return new GeneratedResource(resourceName);
        }

        class GeneratedSource extends SimpleJavaFileObject {
            private final String className;
            private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

            GeneratedSource(String className) {
                super(URI.create("generated:///" + className.replace('.', '/') + ".java"),
                      Kind.SOURCE);
                this.className = className;
            }

            @Override
            public OutputStream openOutputStream() {
                return new CapturingOutputStream(baos, this::capture);
            }

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                capture();
                return baos.toString(StandardCharsets.UTF_8);
            }

            private void capture() {
                generatedSources.put(className, baos.toString(StandardCharsets.UTF_8));
            }
        }

        class GeneratedResource extends SimpleJavaFileObject {
            private final String resourceName;
            private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            private final Path outputFile;
            private boolean captured;

            GeneratedResource(String resourceName) {
                super(URI.create("generated-resource:///"
                                + resourceName.replace("\\", "/")),
                        Kind.OTHER);
                this.resourceName = resourceName.replace("\\", "/");
                this.outputFile = classOutput.resolve(this.resourceName).normalize();
                if (!outputFile.startsWith(classOutput)) {
                    throw new IllegalArgumentException("生成资源路径越界: " + resourceName);
                }
            }

            @Override
            public OutputStream openOutputStream() {
                return new CapturingOutputStream(baos, this::capture);
            }

            private void capture() throws IOException {
                if (captured) {
                    return;
                }
                captured = true;
                byte[] content = baos.toByteArray();
                generatedResources.put(resourceName, content);
                Files.createDirectories(outputFile.getParent());
                Files.write(outputFile, content);
            }
        }

        class GeneratedClass extends SimpleJavaFileObject {
            private final String className;
            private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            private final Path outputFile;
            private boolean captured;

            GeneratedClass(String className) {
                super(URI.create("generated-class:///" + className.replace('.', '/') + ".class"),
                      Kind.CLASS);
                this.className = className;
                this.outputFile = classOutput
                        .resolve(className.replace('.', '/') + ".class")
                        .normalize();
            }

            @Override
            public OutputStream openOutputStream() {
                return new CapturingOutputStream(baos, this::capture);
            }

            private void capture() throws IOException {
                if (captured) {
                    return;
                }
                captured = true;
                byte[] content = baos.toByteArray();
                generatedClasses.put(className, content);
                Files.createDirectories(outputFile.getParent());
                Files.write(outputFile, content);
            }
        }

        /**
         * 将输出写入内存，并在关闭时通知所属文件对象完成捕获和持久化。
         */
        static class CapturingOutputStream extends FilterOutputStream {
            private final IOAction capture;
            private boolean closed;

            CapturingOutputStream(OutputStream out, IOAction capture) {
                super(out);
                this.capture = capture;
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                super.close();
                capture.run();
            }
        }

        @FunctionalInterface
        interface IOAction {
            void run() throws IOException;
        }
    }
}

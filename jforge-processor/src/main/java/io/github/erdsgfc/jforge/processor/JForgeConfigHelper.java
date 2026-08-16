package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.Dialect;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@link JForgeConfig} for the annotation processor.
 *
 * <p>{@code @JForgeConfig} may be placed on a package ({@code package-info.java})
 * or on an entity/repository interface. The effective configuration of an element
 * is resolved in two steps: the element's <em>own</em> annotation (interface-level)
 * wins; otherwise the package chain is walked upward (by qualified name:
 * {@code com.example.sub} → {@code com.example} → {@code com}) and the nearest
 * package with a {@code @JForgeConfig} on its {@code package-info} applies.
 * Putting a configuration on a common parent package therefore covers all of its
 * sub-packages, and any interface can override it for itself. No match → defaults;
 * configurations are never merged or silently combined.</p>
 *
 * <p>Package occurrences are collected once at the start of processing via
 * {@link #collect}; interface occurrences need no collection — {@link #configFor}
 * reads them directly with {@code getAnnotation}. {@code @JForgeConfig} is
 * {@code SOURCE}-retained and cannot change during compilation, so later rounds
 * must not re-collect. The processor is single-threaded, so a plain
 * {@code HashMap} is sufficient.</p>
 */
public final class JForgeConfigHelper {

    private final Elements elementUtils;

    /** {@code @JForgeConfig} occurrences mapped by qualified package name. */
    private final Map<String, JForgeConfig> configs = new HashMap<>();

    private boolean collected;

    /** Creates the helper bound to the given processing environment. */
    public JForgeConfigHelper(ProcessingEnvironment processingEnv) {
        this.elementUtils = processingEnv.getElementUtils();
    }

    // ---- public API --------------------------------------------------------

    /** Reads the configured dialect (default {@link Dialect#POSTGRESQL}). */
    public Dialect dialect(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.dialect() : Dialect.POSTGRESQL;
    }

    /** Reads the configured naming strategy (default {@link NamingStrategy#NONE}). */
    public NamingStrategy naming(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.naming() : NamingStrategy.NONE;
    }

    /**
     * Returns the package where generated impl classes should be placed.
     * An empty string means "same package as the source".
     */
    public String generatedPackage(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.generatedPackage() : "";
    }

    /** Returns the impl class suffix (default {@code "_Impl"}). */
    public String implSuffix(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.implSuffix() : "_Impl";
    }

    /** Whether generated repository impls should be Spring {@code @Repository} beans. */
    public boolean springBeans(Element element) {
        JForgeConfig config = configFor(element);
        return config != null && config.springBeans();
    }

    /** Whether generated repository impls should emit SQL logging (default {@code false}). */
    public boolean logSql(Element element) {
        JForgeConfig config = configFor(element);
        return config != null && config.logSql();
    }

    /**
     * Reads the configured JDBC batch size.
     * The default ({@code 50}) matches {@code JForgeConfig.batchSize()};
     * {@code 0} means "no batching" (rows inserted one by one on one connection).
     */
    public int batchSize(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.batchSize() : 50;
    }

    // ---- Naming ------------------------------------------------------------

    /**
     * Applies the naming strategy to a property method name.
     *
     * @param element the context element (determines which package chain to search)
     * @param methodName the property method name (e.g. {@code "userName"})
     * @return the column name
     */
    public String columnName(Element element, String methodName) {
        NamingStrategy strategy = naming(element);
        if (strategy == NamingStrategy.CAMEL_TO_SNAKE) {
            return CommonUtils.camelToSnake(methodName);
        }
        return methodName;
    }

    // ---- Internals ---------------------------------------------------------

    /**
     * Collects every {@code @JForgeConfig} occurrence
     * (from {@code roundEnv.getElementsAnnotatedWith(JForgeConfig.class)}) and maps
     * it to a package: {@code package-info} annotations to their own package,
     * annotations on any other element to its containing package (so an annotation
     * on a class in {@code com.example} configures {@code com.example} and all of
     * its sub-packages). A {@code package-info} annotation wins over a non-package
     * annotation in the same package. Called once at the start of processing; later
     * rounds are no-ops.
     *
     * @param annotated the elements annotated with {@code @JForgeConfig} in the first round
     */
    void collect(Set<? extends Element> annotated) {
        if (collected) {
            return;
        }
        collected = true;
        for (Element element : annotated) {
            if (element.getKind() == ElementKind.PACKAGE) {
                // 只收集包上的配置进包链查找表;接口上的配置不预收集——
                // configFor 查询时直接 getAnnotation 读元素自身(接口自身优先)。
                PackageElement pkg = (PackageElement) element;
                configs.put(pkg.getQualifiedName().toString(),
                        pkg.getAnnotation(JForgeConfig.class));
            }
        }
    }

    /**
     * Resolves the effective configuration for the element: the nearest ancestor
     * package (including the element's own package) with a mapped
     * {@code @JForgeConfig} occurrence, or {@code null} (defaults). The ancestor
     * walk is a plain string lookup on the collected table, so it is deterministic
     * regardless of how javac models the package hierarchy.
     */
    private JForgeConfig configFor(Element element) {
        // 1. 接口上直接标注的配置优先(仓库/实体接口自身)。
        JForgeConfig own = element.getAnnotation(JForgeConfig.class);
        if (own != null) {
            return own;
        }
        // 2. 否则沿包链向上(所在包 → 父包 → …)找最近的包级配置。
        String pkgName = elementUtils.getPackageOf(element).getQualifiedName().toString();
        while (true) {
            JForgeConfig config = configs.get(pkgName);
            if (config != null) {
                return config;
            }
            int dot = pkgName.lastIndexOf('.');
            if (dot < 0) {
                return null;
            }
            pkgName = pkgName.substring(0, dot);
        }
    }

}

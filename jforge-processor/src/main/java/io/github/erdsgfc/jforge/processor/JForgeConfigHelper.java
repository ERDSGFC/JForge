package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.Dialect;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Reads per-package / per-element {@link JForgeConfig} for the annotation processor.
 *
 * <p>Lookup order: an annotation on the element itself (entity/repository interface)
 * first; otherwise the annotation on the element's package ({@code package-info.java});
 * otherwise the defaults. Both lookups are cached — javac materialises a fresh
 * annotation proxy on every {@code getAnnotation} call, and this helper is invoked
 * once per entity getter (naming), per repository and per SQL field, so the same
 * annotation would otherwise be re-parsed dozens of times per compilation. Resolving
 * once and caching (including the "no annotation" result) turns that into a map hit.
 * The processor is single-threaded and annotation values are immutable, so a plain
 * {@code HashMap} / {@code IdentityHashMap} is sufficient.</p>
 */
public final class JForgeConfigHelper {

    private final Elements elementUtils;

    /**
     * Element-level annotations, keyed by element identity. Element objects are
     * stable within a processing round and never re-annotated, so identity
     * semantics are both correct and cheaper than {@code equals} (javac symbols
     * do not override it anyway). The value is {@code null} when the element
     * carries no {@code @JForgeConfig}.
     */
    private final Map<Element, JForgeConfig> elementConfigs = new IdentityHashMap<>();

    /**
     * Package-level annotations, keyed by qualified package name (stable across
     * rounds, unlike {@code PackageElement} instances). The value is {@code null}
     * when the package carries no {@code @JForgeConfig}.
     */
    private final Map<String, JForgeConfig> packageConfigs = new HashMap<>();

    /** Creates the helper bound to the given processing environment. */
    public JForgeConfigHelper(ProcessingEnvironment processingEnv) {
        this.elementUtils = processingEnv.getElementUtils();
    }

    // ---- public API --------------------------------------------------------

    /** Reads the configured dialect for the package of {@code element}. */
    public Dialect dialect(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.dialect() : Dialect.POSTGRESQL;
    }

    /** Reads the configured naming strategy for the package of {@code element}. */
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
     * Reads the configured JDBC batch size for the package of {@code element}.
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
     * @param element the context element (determines which package's config to use)
     * @param methodName the property method name (e.g. {@code "userName"})
     * @return the column name
     */
    public String columnName(Element element, String methodName) {
        NamingStrategy strategy = naming(element);
        if (strategy == NamingStrategy.CAMEL_TO_SNAKE) {
            return camelToSnake(methodName);
        }
        return methodName;
    }

    // ---- Internals ---------------------------------------------------------

    /**
     * Resolves the effective configuration for {@code element}, cached per
     * element and per package (including "no annotation" results, which are
     * frequent — most elements and packages are unconfigured).
     */
    private JForgeConfig configFor(Element element) {
        if (!elementConfigs.containsKey(element)) {
            // 元素自身的注解优先（可直接把 @JForgeConfig 标在实体/仓库接口上）。
            elementConfigs.put(element, element.getAnnotation(JForgeConfig.class));
        }
        JForgeConfig config = elementConfigs.get(element);
        if (config != null) {
            return config;
        }
        // 元素无注解（或缓存的 null）时，取所在包 package-info 上的注解——包级也有缓存，
        // 所以"元素无注解"这一最常见情形也只付出两次 map 命中。
        return packageConfig(elementUtils.getPackageOf(element));
    }

    /** Resolves the {@code package-info} annotation for the given package, cached by name. */
    private JForgeConfig packageConfig(PackageElement pkg) {
        String name = pkg.getQualifiedName().toString();
        if (packageConfigs.containsKey(name)) {
            return packageConfigs.get(name);
        }
        JForgeConfig config = pkg.getAnnotation(JForgeConfig.class);
        packageConfigs.put(name, config);
        return config;
    }

    /**
     * Converts a camelCase identifier to snake_case, e.g. {@code userName} →
     * {@code user_name}: each upper-case letter is prefixed with an underscore
     * (except at the start) and lower-cased. Shared by column-name inference
     * ({@link #columnName}) and table-name inference in {@code EntityModel}.
     *
     * @param name the camelCase identifier, e.g. {@code "userName"}
     * @return the snake_case form, e.g. {@code "user_name"}
     */
    static String camelToSnake(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, len = name.length(); i < len; i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

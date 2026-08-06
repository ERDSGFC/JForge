package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.Dialect;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;

/**
 * Reads per-package {@link JForgeConfig} from a {@code package-info.java}.
 *
 * <p>Lookup order: the element's own package first; if no {@code @JForgeConfig} is
 * found there the defaults are used.  Config values are resolved once per
 * package and cached in processor state.</p>
 */
public final class JForgeConfigHelper {

    private final Elements elementUtils;

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

    private JForgeConfig configFor(Element element) {
        // 元素自身的注解优先（可直接把 @JForgeConfig 标在实体/仓库接口上）；
        // 否则取所在包 package-info 上的注解。
        JForgeConfig config = element.getAnnotation(JForgeConfig.class);
        if (config != null) {
            return config;
        }
        return elementUtils.getPackageOf(element).getAnnotation(JForgeConfig.class);
    }

    private static String camelToSnake(String name) {
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
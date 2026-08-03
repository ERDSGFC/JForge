package com.qin.orm.processor;

import com.qin.orm.annotation.Dialect;
import com.qin.orm.annotation.NamingStrategy;
import com.qin.orm.annotation.OrmConfig;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;

/**
 * Reads per-package {@link OrmConfig} from a {@code package-info.java}.
 *
 * <p>Lookup order: the element's own package first; if no {@code @OrmConfig} is
 * found there the defaults are used.  Config values are resolved once per
 * package and cached in processor state.</p>
 */
public final class OrmConfigHelper {

    private final Elements elementUtils;

    /** Creates the helper bound to the given processing environment. */
    public OrmConfigHelper(ProcessingEnvironment processingEnv) {
        this.elementUtils = processingEnv.getElementUtils();
    }

    // ---- public API --------------------------------------------------------

    /** Reads the configured dialect for the package of {@code element}. */
    public Dialect dialect(Element element) {
        OrmConfig config = configFor(element);
        return config != null ? config.dialect() : Dialect.POSTGRESQL;
    }

    /** Reads the configured naming strategy for the package of {@code element}. */
    public NamingStrategy naming(Element element) {
        OrmConfig config = configFor(element);
        return config != null ? config.naming() : NamingStrategy.NONE;
    }

    /**
     * Returns the package where generated impl classes should be placed.
     * An empty string means "same package as the source".
     */
    public String generatedPackage(Element element) {
        OrmConfig config = configFor(element);
        return config != null ? config.generatedPackage() : "";
    }

    /** Returns the impl class suffix (default {@code "_Impl"}). */
    public String implSuffix(Element element) {
        OrmConfig config = configFor(element);
        return config != null ? config.implSuffix() : "_Impl";
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

    private OrmConfig configFor(Element element) {
        PackageElement pkg = elementUtils.getPackageOf(element);
        // Walk up the package hierarchy — outermost first is irrelevant here;
        // we just want the @OrmConfig placed on the *exact* package.
        for (AnnotationMirror mirror : pkg.getAnnotationMirrors()) {
            if (mirror.getAnnotationType().toString().equals(OrmConfig.class.getCanonicalName())) {
                return pkg.getAnnotation(OrmConfig.class);
            }
        }
        return null;
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
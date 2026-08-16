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
 * 为注解处理器读取 {@link JForgeConfig} 配置。
 *
 * <p>{@code @JForgeConfig} 可放在包({@code package-info.java})或实体/仓库接口上。
 * 元素的有效配置分两步解析:元素<em>自身</em>的标注(接口级)优先;否则沿包链向上
 * (按全限定名:{@code com.example.sub} → {@code com.example} → {@code com}),
 * 取最近的、其 {@code package-info} 上有 {@code @JForgeConfig} 的包。
 * 因此把配置放在公共父包即可覆盖其全部子包,任何接口都可以为自身覆盖。无匹配 → 默认值;
 * 配置从不合并或静默组合。</p>
 *
 * <p>包上的标注在 processing 开始时经 {@link #collect} 收集一次;接口上的标注无需收集——
 * {@link #configFor} 直接以 {@code getAnnotation} 读取。{@code @JForgeConfig} 是
 * {@code SOURCE} 保留期,编译期间不可变,因此后续轮次不得重新收集。处理器单线程,
 * 普通 {@code HashMap} 足够。</p>
 */
public final class JForgeConfigHelper {

    private final Elements elementUtils;

    /** 按限定包名映射的 {@code @JForgeConfig} 出现位置。 */
    private final Map<String, JForgeConfig> configs = new HashMap<>();

    private boolean collected;

    /** 创建绑定到给定处理环境的 helper。 */
    public JForgeConfigHelper(ProcessingEnvironment processingEnv) {
        this.elementUtils = processingEnv.getElementUtils();
    }

    // ---- public API --------------------------------------------------------

    /** 读取配置的方言(默认 {@link Dialect#POSTGRESQL})。 */
    public Dialect dialect(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.dialect() : Dialect.POSTGRESQL;
    }

    /** 读取配置的命名策略(默认 {@link NamingStrategy#NONE})。 */
    public NamingStrategy naming(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.naming() : NamingStrategy.NONE;
    }

    /**
     * 返回生成的实现类应放置的包。空字符串表示"与源码同包"。
     */
    public String generatedPackage(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.generatedPackage() : "";
    }

    /** 返回实现类后缀(默认 {@code "_Impl"})。 */
    public String implSuffix(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.implSuffix() : "_Impl";
    }

    /** 生成的仓库实现是否为 Spring {@code @Repository} bean。 */
    public boolean springBeans(Element element) {
        JForgeConfig config = configFor(element);
        return config != null && config.springBeans();
    }

    /** 生成的仓库实现是否输出 SQL 日志(默认 {@code false})。 */
    public boolean logSql(Element element) {
        JForgeConfig config = configFor(element);
        return config != null && config.logSql();
    }

    /**
     * 读取配置的 JDBC 批处理大小。默认({@code 50})与 {@code JForgeConfig.batchSize()} 一致;
     * {@code 0} 表示不批处理(在一个连接上逐条插入)。
     */
    public int batchSize(Element element) {
        JForgeConfig config = configFor(element);
        return config != null ? config.batchSize() : 50;
    }

    // ---- Naming ------------------------------------------------------------

    /**
     * 对属性方法名应用命名策略。
     *
     * @param element    上下文元素(决定搜索哪条包链)
     * @param methodName 属性方法名,如 {@code "userName"}
     * @return 列名
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
     * 收集每个 {@code @JForgeConfig} 出现位置(来自
     * {@code roundEnv.getElementsAnnotatedWith(JForgeConfig.class)}):只把标在
     * {@code package-info} 上的按包名入表,供包链查找使用;接口上的标注不入表——
     * {@link #configFor} 查询时直接读元素自身。在 processing 开始时调用一次;
     * 后续轮次为 no-op。
     *
     * @param annotated 第一轮中标注了 {@code @JForgeConfig} 的元素
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
     * 解析元素的有效配置:元素自身标注优先;否则取最近的、有 {@code @JForgeConfig}
     * 映射的祖先包(含元素所在包);都没有则返回 {@code null}(默认值)。祖先包查找是
     * 对收集表的纯字符串查找,因此不依赖 javac 如何建模包层次,行为确定。
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

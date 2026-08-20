package io.github.erdsgfc.jforge.processor;

import io.github.erdsgfc.jforge.annotation.Dialect;
import io.github.erdsgfc.jforge.annotation.NamingStrategy;
import io.github.erdsgfc.jforge.annotation.JForgeConfig;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
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
 * <p>包与类型(接口/类/枚举/record)上的标注都在 processing 开始时经 {@link #collect}
 * 收集一次,按全限定名入表;{@link #configFor} 纯查表——元素自身(含嵌套类型的
 * 外层类)命中优先,否则沿包链向上。{@code @JForgeConfig} 是 {@code SOURCE}
 * 保留期,编译期间不可变,因此后续轮次不得重新收集。处理器单线程,
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
     * {@code roundEnv.getElementsAnnotatedWith(JForgeConfig.class)}):包上的按包名、
     * 类型上的(接口/类/枚举/record——{@code @Target(TYPE)})按全限定名入表,
     * 供 {@link #configFor} 查找。在 processing 开始时调用一次;后续轮次为 no-op
     * (所有用户源码的标注第一轮即可见,生成的 impl 不带该注解)。
     *
     * @param annotated 第一轮中标注了 {@code @JForgeConfig} 的元素
     */
    void collect(Set<? extends Element> annotated) {
        if (collected) {
            return;
        }
        collected = true;
        for (Element element : annotated) {
            if (element instanceof PackageElement pkg) {
                configs.put(pkg.getQualifiedName().toString(),
                        pkg.getAnnotation(JForgeConfig.class));
            } else if (element instanceof TypeElement type) {
                // 必须收全部类型(不限于 INTERFACE):@JForgeConfig 的 @Target 含 TYPE,
                // 标在普通类/枚举上同样生效(如测试里的全局批处理配置)。
                configs.put(type.getQualifiedName().toString(),
                        type.getAnnotation(JForgeConfig.class));
            }
        }
    }

    /**
     * 解析元素的有效配置:元素自身标注(按全限定名查表)优先;否则沿 {@code enclosing}
     * 链向上查外层类型(嵌套接口标在外层类上的场景);都没有则沿包链向上找最近的
     * 包级配置;仍无匹配返回 {@code null}(默认值)。包链查找是纯字符串前缀匹配,
     * 不依赖 javac 如何建模包层次,行为确定。
     */
    private JForgeConfig configFor(Element element) {
        // 1. 元素自身(包或类型)的直接标注,以及嵌套类型的 enclosings 外层类标注。
        while (element != null && element.getKind() != ElementKind.PACKAGE) {
            if (element instanceof QualifiedNameable qn) {
                JForgeConfig config = configs.get(qn.getQualifiedName().toString());
                if (config != null) {
                    return config;
                }
            }
            element = element.getEnclosingElement();
        }
        // 2. 沿包链向上(所在包 → 父包 → …)找最近的包级配置。
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

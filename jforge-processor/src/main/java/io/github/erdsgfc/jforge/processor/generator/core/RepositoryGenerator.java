package io.github.erdsgfc.jforge.processor.generator.core;

import com.palantir.javapoet.*;
import io.github.erdsgfc.jforge.annotation.*;
import io.github.erdsgfc.jforge.processor.ClassEnum;
import io.github.erdsgfc.jforge.processor.EntityModel;
import io.github.erdsgfc.jforge.processor.JForgeConfigHelper;
import io.github.erdsgfc.jforge.processor.JForgeProcessor;
import io.github.erdsgfc.jforge.processor.generator.*;
import io.github.erdsgfc.jforge.processor.utils.SqlCodegen;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.github.erdsgfc.jforge.processor.ClassEnum.*;

/**
 * 生成 {@code @Dao} 仓库的实现类 {@code XxxRepository_Impl}（继承 {@code AbstractRepository}，
 * 直写 JDBC 的 CRUD + {@code @Query}）。
 *
 * <p>本类是编排层：组装类的整体结构（类声明、Spring 注解、构造器），把宿主的实体 impl
 * 作为 {@code private static final} 嵌套类嵌入（{@link EntityGenerator}），并把其余生成职责
 * 委托给 {@link SqlFieldGenerator}（固定 SQL 字段）、{@link CrudGenerator}（CRUD + 行映射）与
 * {@link QueryGenerator}（{@code @Query} + 结果映射，必要时现场嵌入其他实体）。</p>
 */
public final class RepositoryGenerator {


    private final ProcessingEnvironment processingEnv;
    private final Elements elements;
    private final JForgeConfigHelper configHelper;
    private final CrudGenerator crudGenerator;
    private final QueryGenerator queryGenerator;
    private final SelectGenerator selectGenerator;
    private final UpdateGenerator updateGenerator;
    private final DeleteGenerator deleteGenerator;

    public RepositoryGenerator(ProcessingEnvironment processingEnv, JForgeConfigHelper configHelper) {
        this.processingEnv = processingEnv;
        this.elements = processingEnv.getElementUtils();
        this.configHelper = configHelper;
        this.crudGenerator = new CrudGenerator(configHelper);
        this.queryGenerator = new QueryGenerator(processingEnv, configHelper);
        this.selectGenerator = new SelectGenerator(processingEnv, configHelper, queryGenerator);
        this.updateGenerator = new UpdateGenerator(processingEnv, configHelper);
        this.deleteGenerator = new DeleteGenerator(processingEnv, configHelper);
    }

    /**
     * 为一个 {@code @Dao} 生成仓库实现类并写入源文件。
     *
     * @param info 已解析的仓库信息
     */
    public void generate(JForgeProcessor.DaoInfo info) {
        TypeSpec typeSpec = buildImpl(info);
        try {
            JavaFile.builder(info.daoPackage, typeSpec)
                    .addFileComment("Generated at compile time by JForgeProcessor. Do not edit.")
                    .skipJavaLangImports(true)
                    .build()
                    .writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            error(info.element, "Failed to generate " + info.implName + ": " + e.getMessage());
        }
    }

    /**
     * 组装仓库实现类：类声明、Spring 注解、构造器，然后委托各生成器产出固定 SQL 字段、
     * 行映射、CRUD 方法与 {@code @Query} 方法。
     *
     * @param info 已解析的仓库信息
     * @return 生成的类规格
     */
    private TypeSpec buildImpl(JForgeProcessor.DaoInfo info) {
        ClassName daoClass = ClassName.get(info.daoPackage, info.daoSimpleName);
        // 实体 impl 作为本仓库 impl 的嵌套类：全限定名为 daoPackage.ImplName.EntityImplName，
        // 仓库内部以简单名引用（mapRow/createEntity 的 new Xxx_Impl() 无需 import）。
        ClassName entityImpl = ClassName.get(info.daoPackage, info.implName,
                EntityModel.implNameOf(info.model.entitySimpleName(), info.model.implSuffix()));
        ClassName connection = JDBC_CONNECTION.getJavaPoetClassName();
        ClassName preparedStatement = JDBC_PREPARED_STATEMENT.getJavaPoetClassName();
        ClassName resultSet = JDBC_RESULT_SET.getJavaPoetClassName();
        ClassName sqlException = JDBC_SQLEXCEPTION.getJavaPoetClassName();

        // 适配 Spring Boot：配置 springBeans=true 时，生成的 impl 标 @Repository + @Autowired 构造器，
        // 并去掉 final（final 类无法被 Spring CGLIB 代理），由组件扫描自动注入容器。
        boolean springBeans = configHelper.springBeans(info.element);
        TypeSpec.Builder builder = TypeSpec.classBuilder(info.implName)
                .addModifiers(springBeans
                        ? new Modifier[] {Modifier.PUBLIC}
                        : new Modifier[] {Modifier.PUBLIC, Modifier.FINAL})
                .addSuperinterface(daoClass)
                .superclass(ABSTRACT_REPOSITORY.getJavaPoetClassName());
        if (springBeans) {
            info.springBean = true;
            builder.addAnnotation(AnnotationSpec.builder(SPRING_REPOSITORY.getJavaPoetClassName()).build());
        }
        // 宿主实体 impl 作为 private static final 嵌套类嵌入仓库 impl（私有嵌套类无法共享，
        // 多个仓库引用同一实体时各嵌一份副本）；embedded 表预置宿主实体，供 @Query 结果
        // 映射复用，并用于"非宿主实体"的现场嵌入去重。
        builder.addType(EntityGenerator.buildImpl(info.model));
        Map<String, QueryGenerator.EmbeddedEntity> embedded = new HashMap<>();
        embedded.put(info.model.entityQualifiedName(),
                new QueryGenerator.EmbeddedEntity(entityImpl, info.model));
        // 构造器注入 DataSource + TransactionManager 并传给父类 AbstractRepository
        //（父类持有 protected final 字段 + 连接/事务方法），impl 只留实体特定代码。
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(JDBC_DATA_SOURCE.getJavaPoetClassName(), "dataSource")
                .addParameter(TRANSACTION_MANAGER.getJavaPoetClassName(), "transactionManager")
                .addStatement("super(dataSource, transactionManager)");
        if (springBeans) {
            constructor.addAnnotation(AnnotationSpec.builder(
                    SPRING_AUTOWIRED.getJavaPoetClassName()).build());
        }
        builder.addMethod(constructor.build());

        // 配置 logSql=true 时生成 SLF4J Logger 字段，SQL 方法里 emit DEBUG/WARN 日志；
        // 默认 false 不生成任何日志代码（保持与手写 JDBC 等效的零开销）。
        if (configHelper.logSql(info.element)) {
            builder.addField(FieldSpec.builder(
                    SLF4J_LOGGER.getJavaPoetClassName(), "log",
                    Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$T.getLogger($T.class)",
                            SLF4J_LOGGER_FACTORY.getJavaPoetClassName(),
                            ClassName.get(info.daoPackage, info.implName))
                    .build());
        }

        // 固定 SQL 常量字段（命名引用，避免方法体内散落字符串字面量）。
        for (FieldSpec field : SqlFieldGenerator.sqlFields(info, configHelper)) {
            builder.addField(field);
        }

        // @Convert 列的转换器实例字段（宿主实体；@Query 嵌入实体在 QueryGenerator 生成）。
        for (EntityModel.ColumnModel column : info.model.columns()) {
            if (column.converter != null) {
                builder.addField(SqlCodegen.converterField(info.model, column));
            }
        }
        // 单次遍历完成全部 SQL 语义注解方法的校验与生成分发：接口方法只被扫描一遍
        // （原来每个生成器都把 getEnclosedElements + 同名序号计数完整走一次）。
        // 同名序号对所有同名抽象方法统一计数（含无注解与重声明 CRUD 的方法），
        // 与原先各生成器内部"每个方法都计数"的语义一致——SQL 常量字段名的确定性
        // 不变。实现方法按 DAO 声明顺序逐个直接生成（生成代码顺序与接口声明一致）。
        Map<String, Integer> seen = new HashMap<>();
        // @Query 转换器字段去重（DAO 级：多个 @Query 方法可能生成同名字段）。
        Set<String> addedConverters = new HashSet<>();
        for (Element enclosed : info.element.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) enclosed;
            boolean query = method.getAnnotation(Query.class) != null;
            boolean select = method.getAnnotation(Select.class) != null;
            boolean update = method.getAnnotation(Update.class) != null;
            boolean delete = method.getAnnotation(Delete.class) != null;
            if (!method.getModifiers().contains(Modifier.ABSTRACT)) {
                // 默认/私有/静态方法：接口自带实现，生成器从不生成它们。SQL 语义注解
                // 只对抽象方法有意义——标在非抽象方法上是矛盾声明，显式报错而非静默忽略。
                if (query || select  || update || delete) {
                    error(method, "@Dao SQL annotations (@Query/@Select/@Update/@Delete) require an abstract"
                            + " method: default/private/static methods are user-implemented, not generated");
                }
                continue;
            }
            if (!query && !select && !update && !delete) {
                // 无 SQL 注解的抽象方法 impl 无从实现——与其等 javac 报笼统的
                // "is not abstract and does not override abstract method"，不如在这里
                // 给出可定位到方法的错误。合法的无注解形态只剩重声明 BaseRepository
                // 的 CRUD 方法（如 @BatchSize 覆盖 save(List)，实现由 CrudGenerator
                // 无条件生成，见 overridesBaseRepositoryMethod）。
                if (!overridesBaseRepositoryMethod(info, method)) {
                    error(method, "@Dao method must declare one of @Query, @Select, @Update, or @Delete,"
                            + " or redeclare a BaseRepository CRUD method");
                }
                continue;
            }
            int overloadIndex = seen.merge(method.getSimpleName().toString(), 1, Integer::sum) - 1;

            // 互斥校验：一个方法只能带一种 SQL 语义注解。
            if ((query ? 1 : 0) + (select ? 1 : 0) + (update ? 1 : 0) + (delete ? 1 : 0) > 1) {
                error(method, "@Dao method must declare exactly one of @Query/@Select/@Update/@Delete,"
                        + " found multiple on the same method");
                continue;
            }
            DaoMethod call = new DaoMethod(method, overloadIndex);
            if (query) {
                queryGenerator.queryMethod(info, call, addedConverters, builder, embedded, connection,
                        preparedStatement, resultSet, sqlException);
            } else if (select) {
                selectGenerator.selectMethod(info, call, builder, embedded, connection, preparedStatement,
                        resultSet, sqlException);
            } else if (update) {
                updateGenerator.updateMethod(info, call, builder, connection, preparedStatement, sqlException);
            } else {
                deleteGenerator.deleteMethod(info, call, builder, connection, preparedStatement, sqlException);
            }
        }
        // 行映射 helper 与 CRUD 方法。
        builder.addMethod(crudGenerator.rowMapperMethod(info, entityImpl, sqlException, resultSet));
        builder.addMethod(crudGenerator.countByIdMethod(info, sqlException, connection, preparedStatement, resultSet));
        for (MethodSpec method : crudGenerator.crudMethods(info, entityImpl, connection, preparedStatement,
                resultSet, sqlException)) {
            builder.addMethod(method);
        }
        return builder.build();
    }

    /**
     * 上报绑定到指定元素的编译期错误。
     *
     * @param element 出错的元素
     * @param message 错误消息
     */
    private void error(Element element, String message) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    /**
     * 方法是否重声明了 {@code BaseRepository} 的 CRUD 方法（如 {@code @BatchSize}
     * 覆盖 {@code save(List<T>)}）：此类方法的实现由 {@code CrudGenerator} 无条件生成，
     * 无需 SQL 注解。经 {@code Elements.overrides} 匹配（按继承层替换泛型实参并比较
     * 签名）；签名不匹配的同名声明不会命中，落入"无 SQL 注解"报错。
     *
     * @param info   仓库信息（dao 元素是覆盖发生的类型上下文）
     * @param method 待判定的仓库方法
     * @return 方法在 dao 中覆盖了某个 BaseRepository 方法时返回 {@code true}
     */
    private boolean overridesBaseRepositoryMethod(JForgeProcessor.DaoInfo info, ExecutableElement method) {
        // @Dao 直接继承 BaseRepository（parseDao 校验），类型必在编译类路径上。
        TypeElement baseRepository = elements.getTypeElement(ClassEnum.BASE_REPOSITORY.getFullClassName());
        if (baseRepository == null) {
            return false;
        }
        for (Element enclosed : baseRepository.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD
                    && elements.overrides(method, (ExecutableElement) enclosed, info.element)) {
                return true;
            }
        }
        return false;
    }
}

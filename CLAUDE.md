# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

**JForge**（`io.github.erdsgfc:jforge`）—— 编译期代码生成、运行时零反射、极致性能、适配 AOT 的 Java 极速 ORM。运行期性能目标与**手工硬编码 JDBC 完全等效**（实测框架开销 −0.1% ~ +3.5%），将发布到 Maven 中央仓库。

## 构建命令

```bash
# 编译所有模块
mvn clean compile

# 全量构建 + 运行全部测试
mvn clean install

# 运行 ORM 测试（注意：测试在 jforge-bench 与 jforge-spring-boot-starter，不在 jforge-core；
# jforge-spring-boot-pgsql 是真 PG 集成测试——本地 PG 不可达时自动跳过）
mvn test -pl jforge-bench,jforge-spring-boot-starter
mvn test -pl jforge-spring-boot-pgsql    # 需本地 PG；连接参数 -Djforge.pgsql.url/-Djforge.pgsql.user/-Djforge.pgsql.password

# 运行单个测试类（JUnit 5）
mvn test -pl jforge-bench -Dtest=TransactionTest
mvn test -pl jforge-spring-boot-starter -Dtest=SpringTransactionManagerTest

# 打包 + 运行 jforge-lambda（对象创建策略 JMH 基准，uber-jar）
mvn clean package -pl jforge-lambda
java -jar jforge-lambda/target/benchmarks.jar 'LambdaBenchmark\.(allArgsConstructor|reflectionConstructor)'

# 打包 + 运行 ORM vs 裸 JDBC 基准（jforge-bench）
mvn clean package -pl jforge-bench
java -jar jforge-bench/target/jforge-benchmarks.jar 'OrmVsJdbcBenchmark'

# Maven Central 发布（source/javadoc 附加 + GPG 签名；GPG 密钥与 ossrh 凭证在 ~/.m2/settings.xml）
mvn -Prelease deploy
```

## 架构：实体接口 + @Dao 仓库 + 编译期生成

用户定义 `@Table` 实体**接口**（getter + builder setter，**可继承父接口**——父接口属性同样映射为列，列顺序 = 继承层次顺序：子接口自身属性在前；父接口支持泛型自限定 CRTP 模式 `BaseEntity<T extends BaseEntity<T>>`，类型实参必须只有一个且为子接口自身，`types.asMemberOf` 替换后 setter 返回子接口类型、链式调用不中断；**只读列**=不写 setter（INSERT/UPDATE 排除该列，查询读回）；**default getter（带 @Column）=属性默认值来源**——save 自动调用取值绑定，经嵌套类私有桥接方法 `接口.super.getter()` 实现（宿主类不实现实体接口，`TypeName.super` 语法只能在嵌套类内用））与 `@Dao` 仓库**接口**（继承 `BaseRepository<T, ID>`），注解处理器在编译期生成**直写 JDBC 的仓库实现类**（`XxxRepository_Impl`，继承 `AbstractRepository`，只含实体特定代码：SQL 常量、行映射、CRUD、`@Query`；实体的 `Xxx_Impl` 作为其 **private static final 嵌套类**随仓库文件生成，用户只能经 `repo.createEntity()` 获取实体实例）——运行时零反射、零元数据查找、零动态分发，AOT（GraalVM Native Image）友好。仓库经统一门面 `JForge`（持有 `private final` `DataSource`/`TransactionManager`、缓存全部仓库）创建：`new JForge(ds).repository(UserRepository.class)`；**管理器由门面构造器注入生成实现（实例传递，无全局单例）**，创建分发由固定包 `io.github.erdsgfc.jforge.generated.Repositories` 承担（框架 jar 自带同名空壳占位，用户 target/classes 的真实实现按类加载优先级覆盖）。**与手写 JDBC 等价的生成代码就是性能上限**。

## 模块

| 模块 | 职责 |
|---|---|
| `jforge-annotation` | 注解：`@Table/@Id/@Column/@GeneratedValue`（标注接口方法）+ `@Dao/@Query/@Bind/@ReturnGeneratedKeys` + `@Select/@Update/@UpdateSet/@Delete/@Condition/@Where/@And/@Or/Op`（声明式查询与写操作；`@Condition`/`@UpdateSet` 的 `rawSql` 属性支持原生 SQL 片段）+ `@Convert`/`JForgeConverter<X>`（getter 自定义类型转换：单泛型、双方向 Object——绑定 setObject(i, v, CONV.sqlType())，SQL 类型由转换器决定、默认 Types.OTHER，读取裸 getObject 经 toEntity，适配任意数据库类型如 PG jsonb；编译期生成调用，转换器以 static final 字段嵌入 impl）+ JSpecify 依赖（`@Nullable` 动态条件：@Query 方括号 `[ ]` 显式段 + 自动推断，@Select/@Update/@Delete 参数条件） |
| `jforge-processor` | 编译期生成器（javapoet + auto-service，provided）：`JForgeProcessor`（入口，**只处理 @Dao**，经 `BaseRepository<T,ID>` 定位实体）+ `EntityGenerator`（实体→Impl 嵌套类 TypeSpec）+ `RepositoryGenerator`（@Dao→CRUD + @Query + @Select/@Update/@Delete 声明式 SQL + 条件对象展开（`CriteriaGenerator`：嵌套括号/`@And/@Or`/Optional IS NULL）+ DTO record 投影 + 固定 SQL 常量字段 + 实体 impl 嵌套类 + Repositories 工厂） |
| `jforge-core` | 框架库（**无 Spring 依赖**）：`TransactionManager`（SPI）、`SimpleTransactionManager`、`JForgeException`（带 `Code` 错误码分类 + SQL 上下文）、`JForge` 门面（`io.github.erdsgfc.jforge`）；`BaseRepository`、`TransactionOperations`、`AbstractRepository`、回调接口（`io.github.erdsgfc.jforge.core`） |
| `jforge-bench` | ORM 集成测试（`RepositoryCrudTest`/`TransactionTest`）+ ORM vs 裸 JDBC JMH 基准 |
| `jforge-spring-boot-starter` | Spring Boot 自动配置：注册 `SpringTransactionManager` bean（包装 `PlatformTransactionManager`），经 `@Autowired` 注入生成的仓库实现（构造器注入，无全局状态） |
| `jforge-spring-boot-pgsql` | **真 PostgreSQL 集成测试**（不发布）：连接本地 PG 验证引用符 SQL / `INSERT ... RETURNING` 生成键 / 批量键回写 / Spring 事务 join；连接参数 `-Djforge.pgsql.url/-Djforge.pgsql.user/-Djforge.pgsql.password`（默认 `localhost:5432/jforge`、`jforge`/`jforge`），无 PG 时测试经 Assumptions 自动跳过、构建保持绿色 |
| `jforge-lambda` | 对象创建策略的 JMH 基准（历史方法学验证） |

## 编程式事务

`BaseRepository` 继承 `TransactionOperations`（`io.github.erdsgfc.jforge.core`），**线程级事务**：同一线程、同一 `DataSource` 的多个仓库共享同一连接与事务边界；**不支持嵌套事务**（嵌套 begin 抛 `JForgeException`）。

- **手动**：`beginTransaction()`（返回事务绑定 `Connection`，供原生 JDBC 控制）/ `commit()` / `rollback()` / `isTransactionActive()` / `markRollbackOnly()`（条件性回滚，标记回滚但回调正常返回）/ `isRollbackOnly()`
- **模板**（default 方法，推荐）：`execute(ConnectionCallback<T>)`（回调接收 `Connection`，成功自动 commit、异常自动 rollback 并重抛）、`execute(P, ConnectionParamCallback<T,P>)`（外部参数）、无返回值版本 `run(ConnectionRunnable)` / `run(P, ConnectionParamRunnable)`。**事务与作用域共用同一组回调接口**（`ConnectionCallback` 等 4 个，方法名 `doInConnection`），lambda 无需区分上下文；**无 Connection 变体**用 JDK 接口（`execute(Supplier<T>)` / `execute(P, Function<P,T>)` / `run(Runnable)` / `run(P, Consumer<P>)`）——事务族与作用域族各 8 个方法覆盖"参数/返回值/conn"全组合，全部委托两个私有核心（`inTransaction` / `inScope`），生命周期逻辑各只有一份
- **连接作用域**（无事务共享连接）：`executeWithoutTransaction(ConnectionCallback<T>)`（回调接收共享 `Connection`）——借用 1 个连接供回调内所有仓库调用共享，autocommit 不变、**无原子性**（异常前的语句保持已提交），finally 总是归还；用于"多 SQL 只需省池往返"的场景。void 版 `runWithoutTransaction(ConnectionRunnable)` / 无参数版 `runWithoutTransaction(Runnable)` / 带参数版 `runWithoutTransaction(P, ConnectionParamRunnable)`。嵌套作用域复用外层连接；事务内开作用域退化为 no-op（复用事务连接）；作用域内 `beginTransaction()` 抛 `JForgeException`
- **JDBC 批处理**：`save(List<T>)` 总是**单连接**（不再每实体借还连接）；`@JForgeConfig(batchSize=N)`（**全局配置**——可标在任意位置，合并后对全部仓库生效）启用 `addBatch()/executeBatch()` 分块，**默认 50**，每 N 行 flush 一次并把生成键按插入序回写实体。覆盖优先级：方法级 `@BatchSize(N)`（在仓库接口里**重声明** `save(List<T>)`）> 仓库接口类型级 `@BatchSize(N)` > 全局 `@JForgeConfig.batchSize` > 默认 50；`batchSize=0`/`@BatchSize(0)` 显式关闭批处理（逐条但单连接）。注意：生成键批处理依赖驱动支持（H2/PostgreSQL 支持；MySQL Connector/J 旧版只返回批量中最后一条的键）
- 事务/作用域经 `TransactionManager`（SPI）驱动；生成的 impl 继承 `AbstractRepository`（`implements TransactionOperations`，持有 `protected final` `DataSource`/`TransactionManager` 字段 + 8 个 `final` 事务/作用域方法），经 `transactionManager` 字段取连接/委托事务——**管理器由 `JForge` 门面构造器注入（无全局单例、无静态查找），`private final` 字段利于 JIT 内联**；`SimpleTransactionManager` 用**单 ThreadLocal 槽位**（tx/scope 两个可空字段，热路径一次 `get()`，实测 findById +2.2%，见 `BENCHMARK_RESULTS.md` Run ORM-2）；**未开启事务且无作用域时零开销**（等价裸 JDBC）
- **日志**（slf4j-api 门面，用户自选实现）：低频日志（门面初始化 INFO、事务边界 DEBUG）始终可用；SQL 日志经 `@JForgeConfig(logSql=true)` 编译期开关（默认关闭——不生成任何日志代码，保持零开销），开启时生成 `Logger` 字段 + 每条 SQL 的 DEBUG（执行）/ WARN（失败）日志

## Spring 接入（jforge-spring-boot-starter）

引入 starter 后自动配置注册 `SpringTransactionManager` bean（包装 `PlatformTransactionManager`），**经 `@Autowired` 构造器注入生成的仓库实现**——仓库持有的 `transactionManager` 字段就是 Spring 包装器，其 `connection()`/`release()` 委托 `DataSourceUtils`，因此用户可直接用 `@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 控制仓库操作（自动 join 外层 Spring 事务）。无全局单例：非 `springBeans` 场景下经 `new JForge(ds, new SpringTransactionManager(ptm))` 显式传入。

**仓库自动注入**：`@JForgeConfig(springBeans = true)`（`jforge-annotation` 注解，原 `OrmConfig` 已改名，**全局配置**——任意位置标注，合并后对全部仓库生效）生成的 `XxxRepository_Impl` 会标 `@Repository` + `@Autowired` 构造器（并去掉 `final` 以允许 Spring CGLIB 代理），Spring Boot 组件扫描自动注册为 bean，无需手写 `Repositories.createXxxRepository`。处理器在 `@SupportedAnnotationTypes` 中声明了 `@JForgeConfig`。

已知限制：`@Transactional(timeout=…)` 对 ORM 生成的直写 JDBC 语句**不生效**（Spring 靠 `JdbcTemplate`→`applyTimeout` 强制执行，生成代码不经 JdbcTemplate）。

## 关键文件

- **`USAGE.md`** — 框架使用文档（依赖、实体/仓库定义、配置、事务/作用域/批处理、Spring 集成、已知限制）
- **`ORM_PLAN.md`** — 开发计划：架构、事务设计（含 Spring 事务控制/已知限制）、性能原则、路线图（Phase 3 关联映射 / Phase 4 L1 缓存 / Phase 6 GraalVM 验证）
- **`BENCHMARK_RESULTS.md`** / **`JMH_USAGE.md`** / **`EXECUTION_STEPS.md`** — JMH 基准台账、CLI 用法、执行步骤
- **`LambdaBenchmark.java`** — jforge-lambda 的 11 个 `@Benchmark` 方法（7 种创建方式 + 4 个 instance 字段对照组）；方法学：禁止手写循环、句柄用 `static final`、MethodHandle 用 `invokeExact`（详见 `BENCHMARK_RESULTS.md` 的"方法学教训"）
- **`benchmark_run9_data.csv`** — Run 9 的 350 个原始测量点
- **`RELEASE.md`** — Maven Central 发布流程（GPG/凭证、Central Portal 与 OSSRH 两条路线、验证与排错）
- **`DIALECT.md`** — 数据库方言架构：编译期决策原则、各数据库差异全景（标识符/自增/生成键/分页/UPSERT/布尔/日期/锁/NULL 排序/类型映射）、`DialectSupport` 能力表设计与演进路线

## 代码规范

- **类、方法、属性必须加完整 Javadoc**：类注释说明职责与设计意图（如双路径、所有权语义）；方法用完整标签风格（行为说明 + `@param`/`@return`/`@throws`）；复杂逻辑注释解释"为什么"（机制、权衡、坑）
- **生成代码除外**（`io.github.erdsgfc.jforge.generated` 包及 processor 生成的 `XxxRepository_Impl` 文件，含其嵌套的实体 impl）：文件头标"Do not edit"（嵌套类无独立文件头，同样不得手改）
- **性能导向**：句柄/调用点可被 JIT 内联（`invokeExact` > `invoke`）、`static final` 引用（利于常量折叠）、类型决策编译期完成（精确 setter/getter）、生成代码是直写 JDBC

## 构建细节

- 依赖版本统一在根 POM 的 `<properties>` 集中管理（`jmh.version`/`h2.version`/`spring-boot.version` 等），子模块不写版本号；Spring Boot 3.5.6 BOM 也在根 `dependencyManagement` 里 import
- 插件版本统一在根 POM 的 `<pluginManagement>` 管理（compiler/shade/source/javadoc/gpg）；Maven Central 发布用 `mvn -Prelease deploy`（`<licenses>/<developers>/<scm>/<distributionManagement>` 已配置，SCM 地址需按实际仓库核对）
- **改过注解处理器后**：`mvn install -pl jforge-annotation,jforge-processor -am` 后必须对消费模块 `mvn clean test`——Maven 3.9+ 增量编译在输入源码无变化时跳过 javac，`target/generated-sources` 会停留在旧 processor 生成的版本（表现为"改动没生效"，实测踩过）
- `maven-compiler-plugin` 显式声明注解处理器（JDK 23+ 默认关闭自动注解处理）
- 测试用内存 H2（`BIGSERIAL` 自增，PostgreSQL 模式 + `DATABASE_TO_LOWER=TRUE`——H2 的 PG 模式不折叠小写，需该参数模拟真 PG 的无引号小写折叠，与生成 SQL 的方言引用符精确匹配）；**H2 测试实体所在包必须标 `@JForgeConfig(dialect = Dialect.H2)`**（POSTGRESQL 方言现启用 RETURNING，H2 2.3 不支持——不标会语法错误；有自身 package-info 的包要逐个补，配置不合并）

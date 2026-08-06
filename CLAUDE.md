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

# 运行 ORM 测试（注意：测试在 jforge-bench 与 jforge-spring-boot-starter，不在 jforge-core）
mvn test -pl jforge-bench,jforge-spring-boot-starter

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

用户定义 `@Table` 实体**接口**（getter + builder setter）与 `@Dao` 仓库**接口**（继承 `BaseRepository<T, ID>`），注解处理器在编译期生成**直写 JDBC 的实现类**（`Xxx_Impl`）——运行时零反射、零元数据查找、零动态分发，AOT（GraalVM Native Image）友好。生成代码经 `Repositories.createXxxRepository(DataSource)` 工厂创建，**与手写 JDBC 等价的生成代码就是性能上限**。

## 模块

| 模块 | 职责 |
|---|---|
| `jforge-annotation` | 注解：`@Table/@Id/@Column/@GeneratedValue/@Transient`（标注接口方法）+ `@Dao/@Query/@Bind/@ReturnGeneratedKeys` |
| `jforge-processor` | 编译期生成器（javapoet + auto-service，provided）：`JForgeProcessor`（入口，**只处理 @Dao**，经 `BaseRepository<T,ID>` 定位实体并顺带生成实体 impl，按全限定名去重）+ `EntityGenerator`（实体→Impl）+ `RepositoryGenerator`（@Dao→CRUD + @Query + DTO record 投影 + 固定 SQL 常量字段 + Repositories 工厂） |
| `jforge-core` | 框架库（**无 Spring 依赖**）：`TransactionManager`（SPI）、`SimpleTransactionManager`、`OrmException`（`io.github.erdsgfc.jforge`）；`BaseRepository`、`TransactionOperations`、回调接口（`io.github.erdsgfc.jforge.core`） |
| `jforge-bench` | ORM 集成测试（`RepositoryCrudTest`/`TransactionTest`）+ ORM vs 裸 JDBC JMH 基准 |
| `jforge-spring-boot-starter` | Spring Boot 自动配置：启动时把全局 `TransactionManager` 换成 `SpringTransactionManager`（包装 `PlatformTransactionManager`） |
| `jforge-lambda` | 对象创建策略的 JMH 基准（历史方法学验证） |

## 编程式事务

`BaseRepository` 继承 `TransactionOperations`（`io.github.erdsgfc.jforge.core`），**线程级事务**：同一线程、同一 `DataSource` 的多个仓库共享同一连接与事务边界；**不支持嵌套事务**（嵌套 begin 抛 `OrmException`）。

- **手动**：`beginTransaction()`（返回事务绑定 `Connection`，供原生 JDBC 控制）/ `commit()` / `rollback()` / `isTransactionActive()` / `markRollbackOnly()`（条件性回滚，标记回滚但回调正常返回）/ `isRollbackOnly()`
- **模板**（default 方法，推荐）：`execute(TransactionCallback<T>)`（回调接收 `Connection`，成功自动 commit、异常自动 rollback 并重抛）、`execute(P, TransactionParamCallback<T,P>)`（外部参数）、无返回值版本 `run(TransactionRunnable)` / `run(P, TransactionParamRunnable)`
- 事务经 `TransactionManager`（全局单例 SPI）驱动，生成代码用 `TransactionManager.current().connection(dataSource)` 取连接；**未开启事务时零开销**（等价裸 JDBC）

## Spring 接入（jforge-spring-boot-starter）

引入 starter 后自动配置把全局 `TransactionManager` 替换为 `SpringTransactionManager`（经 `TransactionSynchronizationManager` 检测，`afterSingletonsInstantiated` 时 `TransactionManager.set(...)`）。用户可直接用 `@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 控制仓库操作（生成代码经 `DataSourceUtils.getConnection` 自动 join 外层事务）。

**仓库自动注入**：在实体/仓库所在包的 `package-info.java` 加 `@JForgeConfig(springBeans = true)`（`jforge-annotation` 注解，原 `OrmConfig` 已改名），生成的 `XxxRepository_Impl` 会标 `@Repository` + `@Autowired` 构造器（并去掉 `final` 以允许 Spring CGLIB 代理），Spring Boot 组件扫描自动注册为 bean，无需手写 `Repositories.createXxxRepository`。

已知限制：`@Transactional(timeout=…)` 对 ORM 生成的直写 JDBC 语句**不生效**（Spring 靠 `JdbcTemplate`→`applyTimeout` 强制执行，生成代码不经 JdbcTemplate）。

## 关键文件

- **`ORM_PLAN.md`** — 开发计划：架构、事务设计（含 Spring 事务控制/已知限制）、性能原则、路线图（Phase 3 关联映射 / Phase 4 L1 缓存 / Phase 6 GraalVM 验证）
- **`BENCHMARK_RESULTS.md`** / **`JMH_USAGE.md`** / **`EXECUTION_STEPS.md`** — JMH 基准台账、CLI 用法、执行步骤
- **`LambdaBenchmark.java`** — jforge-lambda 的 11 个 `@Benchmark` 方法（7 种创建方式 + 4 个 instance 字段对照组）；方法学：禁止手写循环、句柄用 `static final`、MethodHandle 用 `invokeExact`（详见 `BENCHMARK_RESULTS.md` 的"方法学教训"）
- **`benchmark_run9_data.csv`** — Run 9 的 350 个原始测量点
- **`RELEASE.md`** — Maven Central 发布流程（GPG/凭证、Central Portal 与 OSSRH 两条路线、验证与排错）

## 代码规范

- **类、方法、属性必须加完整 Javadoc**：类注释说明职责与设计意图（如双路径、所有权语义）；方法用完整标签风格（行为说明 + `@param`/`@return`/`@throws`）；复杂逻辑注释解释"为什么"（机制、权衡、坑）
- **生成代码除外**（`io.github.erdsgfc.jforge.generated` 包及 processor 生成的 `*_Impl` 类）：文件头标"Do not edit"
- **性能导向**：句柄/调用点可被 JIT 内联（`invokeExact` > `invoke`）、`static final` 引用（利于常量折叠）、类型决策编译期完成（精确 setter/getter）、生成代码是直写 JDBC

## 构建细节

- 依赖版本统一在根 POM 的 `<properties>` 集中管理（`jmh.version`/`h2.version`/`spring-boot.version` 等），子模块不写版本号；Spring Boot 3.5.6 BOM 也在根 `dependencyManagement` 里 import
- 插件版本统一在根 POM 的 `<pluginManagement>` 管理（compiler/shade/source/javadoc/gpg）；Maven Central 发布用 `mvn -Prelease deploy`（`<licenses>/<developers>/<scm>/<distributionManagement>` 已配置，SCM 地址需按实际仓库核对）
- `maven-compiler-plugin` 显式声明注解处理器（JDK 23+ 默认关闭自动注解处理）
- 测试用内存 H2（`BIGSERIAL` 自增，PostgreSQL 模式）

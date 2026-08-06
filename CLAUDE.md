# CLAUDE.md

本文件为 Claude Code（claude.ai/code）在此仓库工作时提供指引。

## 构建命令

```bash
# 编译所有模块
mvn clean compile

# 打包 lambda 模块（生成含 JMH benchmark 的 uber-jar）
mvn clean package -pl lambda

# 运行全部 benchmark（注解配置：5×3s 预热、10×2s 测量、5 forks）
java -jar lambda/target/benchmarks.jar

# 运行选中的 benchmark（正则筛选，入口为 jmh.Main，支持完整 JMH CLI）
java -jar lambda/target/benchmarks.jar 'LambdaBenchmark\.(allArgsConstructor|reflectionConstructor)'

# 覆盖配置运行 + GC 剖析
java -jar lambda/target/benchmarks.jar LambdaBenchmark.allArgsConstructor -i 10 -w 5 -f 3 -prof gc

# 运行 orm 模块测试
mvn test -pl orm
```

## 项目架构

基于 Java 25 的多模块 Maven 项目（`com.qin:benchmark`）：

- **`lambda`** — JVM 对象创建策略的 JMH 基准（10 字段 `User` POJO）。每个 benchmark 方法只创建 **1 个** `User` 并返回，由 JMH 自动循环调用并消费返回值（禁止手写循环——见 `BENCHMARK_RESULTS.md` 的"方法学教训"）。
- **`orm`** — 性能优先的微型 ORM（目标：与裸 JDBC 一样快），H2 PostgreSQL 兼容模式 + HikariCP。开发计划与性能原则见 `ORM_PLAN.md`。
- **`orm-spring-boot-starter`** — Spring Boot starter：启动时把全局 `TransactionManager` 替换为 Spring `PlatformTransactionManager` 的包装器（`SpringTransactionManager` + `OrmTransactionAutoConfiguration`），ORM 编程式事务无缝接入 Spring 事务管理，且用户可直接用 Spring 的 `@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 控制仓库操作（生成代码经 `DataSourceUtils.getConnection` 自动 join）；自动配置经 `META-INF/spring/...AutoConfiguration.imports` 注册，依赖 Spring Boot 3.5.6 BOM

### 关键文件

- **`LambdaBenchmark.java`** — 11 个 `@Benchmark` 方法：
  - 7 个使用 `MyState` 句柄（`allArgsConstructor` 为无句柄的基础锚点，`lambdaMetafactoryConstructor`、`reflectionConstructor`、`methodHandleConstructor`、`lambdaMetafactoryWithSetters`、`methodHandleWithSetters`、`noArgConstructorWithSetters`）
  - 4 个对照组方法（`reflectionConstructorInstance`、`methodHandleConstructorInstance`、`lambdaMetafactoryWithSettersInstance`、`methodHandleWithSettersInstance`），逻辑相同但句柄为 **instance 字段**（非 `static final`），用于测量常量折叠效应（Run 12）
  - 句柄均为 `static final`（JIT 常量折叠——instance 字段实测慢 17-84%）；MethodHandle 调用使用 `invokeExact` + 精确参数类型（`invoke` 的 asType 适配实测慢 28-73%）
  - 无 `main()`；jar 入口为 `org.openjdk.jmh.Main`。JMH 按**方法名字母序**执行（早期用 `A01`/`B01` 前缀控制顺序，验证顺序不影响结果后已移除）
- **`lambda/src/main/java/com/qin/fun/NewUser.java`** — LambdaMetafactory 使用的函数式接口（已从 benchmark 类中移出）
- **`BENCHMARK_RESULTS.md`** — 结果台账（Run 8-12）+ 可信结论 + 方法学教训。Run 1-7 数据已**删除**（手写 50 万次循环改变了排名）。当前结论：7 种创建方式统计等价（~214-223M 对象/s）；`invokeExact` vs `invoke`、`static final` vs instance 才是真正的性能杠杆。新增轮次按既有格式追加并更新可信结论
- **`JMH_USAGE.md`** — JMH CLI 速查 + "关键经验与教训"（12 条实证结论）
- **`EXECUTION_STEPS.md`** — Run 8-12 完整执行步骤（命令、参数解析、结果）
- **`ORM_PLAN.md`** — ORM 开发计划：分阶段、性能设计原则、验收标准（相对裸 JDBC 框架开销 <5%）
- **`benchmark_run9_data.csv`** — 增强版 Run 9 的 350 个原始测量点

### orm 模块（Repository 接口 + 编译期生成架构）

- **用户 API**：`@Table` 实体**接口**（getter + builder setter）+ `@Dao` 仓库接口继承 `BaseRepository<T, ID>`（com.qin.orm.core，用户定义）；自定义查询用 `@Query("SQL :param")` + `@Bind`
- **编程式事务**：`BaseRepository` 继承 `TransactionOperations`（com.qin.orm.core）——`beginTransaction()` **返回事务绑定连接** `Connection`，`commit/rollback/isTransactionActive`，`execute(TransactionCallback)`（default 模板，回调接收该连接，供隔离级别/savepoint/裸 SQL 等原生 JDBC 控制）+ `execute(param, TransactionParamCallback)` 重载（支持外部参数传入事务回调）+ 无返回值版本 `run(...)`（对应 `execute`，回调无需 `return null`）。内置默认 `SimpleTransactionManager` 基于 ThreadLocal（`TransactionManager` 全局单例），同 DataSource 的多个仓库共享同一连接与事务边界；**不支持嵌套事务**。生成代码经私有 `getConnection()/releaseConnection()` 自动加入激活事务，未开启事务时等价于裸 JDBC（零开销）。引入 `orm-spring-boot-starter` 后自动替换为 Spring `PlatformTransactionManager` 包装器，用户事务代码零改动
- **`orm-processor`** 编译期生成直写 JDBC 的实现类：`EntityProcessor`（实体接口 → `Xxx_Impl`）、`RepositoryProcessor`（@Dao → `XxxRepository_Impl`，BaseRepository 13 个 CRUD 方法 + @Query 方法 + DTO record 投影）、`Repositories` 工厂（`Repositories.createXxxRepository(DataSource)`）
- **性能**：生成代码与手写 JDBC 等价（基准实测 -0.1% ~ +3.5%）；运行时零反射 → AOT（GraalVM Native Image）友好
- 注解在 `orm-annotation` 模块：`@Table/@Id/@Column/@GeneratedValue/@Transient`（可标注在接口方法上）+ `@Dao/@Query/@Bind/@ReturnGeneratedKeys`
- 测试：`mvn test -pl orm`（内存 H2，`BIGSERIAL` 自增，PostgreSQL 模式）；详情见 `ORM_PLAN.md`

### 代码规范

- **类、方法、属性都必须加文档注释（Javadoc）**：类注释说明职责与设计意图（如双路径、所有权语义）；字段注释说明用途
- **方法 Javadoc 必须使用完整标签风格**：行为说明 + `@param`（每个参数）+ `@return` + `@throws`（如有）——参考 `BaseRepository.java` 的写法，不允许只写一句话描述
- **方法中的复杂逻辑必须加代码注释**：解释"为什么"（机制、权衡、坑），不只写"做了什么"——如 JIT 相关设计、连接所有权、事务连接不关闭等关键决策
- 生成代码（`com.qin.orm.generated` 包及 processor 生成的 `*_Impl` 类）除外：由处理器自动生成，文件头标注"Do not edit"

### 构建细节

- 根 POM 在 `dependencyManagement` 管理 JMH 1.37（`scope>test</scope>`）；`lambda` 模块覆盖为 `compile`
- `maven-compiler-plugin` 显式声明 JMH 注解处理器（`jmh-generator-annprocess`），因为 JDK 23+ 默认关闭自动注解处理
- `maven-shade-plugin` 产出 `lambda/target/benchmarks.jar`（uber-jar，入口 `org.openjdk.jmh.Main`，支持完整 JMH CLI）
- `orm` 依赖 H2 2.3.232、HikariCP 5.1.0、JUnit 5（test）

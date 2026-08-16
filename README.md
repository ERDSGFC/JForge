# JForge

**编译期代码生成 · 运行时零反射 · 极致性能 · 适配 AOT** 的 Java 极速 ORM 框架。

JForge 的**运行期性能目标是完全等效于纯手工编写的硬编码 JDBC 代码**（实测框架开销 −0.1% ~ +3.5%），并将作为开源项目发布到 Maven 中央仓库。

## 核心特性

- **编译期代码生成**：用户定义 `@Table` 实体**接口** + `@Dao` 仓库**接口**（继承 `BaseRepository`），注解处理器在编译期生成**直写 JDBC 的实现类**——运行时零反射、零元数据查找、零动态分发。
- **极致性能**：生成代码与手写 JDBC 等价，是性能上限；管理器经门面**构造器注入**（`private final` 字段，无全局单例、无静态查找），未开启事务时零开销。
- **AOT 友好**：生成代码为纯静态方法调用、直接构造、无反射、无 MethodHandle、无 `Class.forName` → GraalVM Native Image 友好。
- **编程式事务**：线程级事务管理器，`execute`/`run` 模板（成功自动提交、异常自动回滚）——事务与连接作用域共用同一组回调接口，覆盖"参数/返回值/Connection"全组合（各 8 个变体）；手动 `beginTransaction`/`commit`/`rollback`、条件性回滚 `markRollbackOnly`、回调直接接收事务绑定 `Connection` 做原生 JDBC 控制（隔离级别 / savepoint / 裸 SQL）。
- **连接作用域**：`executeWithoutTransaction`/`runWithoutTransaction`——借用 1 个连接供多个仓库调用共享（无事务、无原子性），省去多 SQL 场景的池往返。
- **JDBC 批处理**：`save(List<T>)` 单连接 + `addBatch()/executeBatch()` 分块（默认 50，`@JForgeConfig.batchSize` / `@BatchSize` 可调），生成键按插入序回写。
- **Spring 无缝集成**：`jforge-spring-boot-starter` 注册 `SpringTransactionManager` bean 注入生成的仓库——`@Transactional` / `TransactionTemplate` / `PlatformTransactionManager` 三种方式直接控制仓库操作。

## 模块

| 模块 | 说明 |
|---|---|
| `jforge-annotation` | 注解：`@Table/@Id/@Column/@GeneratedValue`（可标注在接口方法上）+ `@Dao/@Query/@Bind/@ReturnGeneratedKeys` |
| `jforge-processor` | 编译期生成器（javapoet + auto-service，provided，不进运行时） |
| `jforge-core` | 框架库：`BaseRepository`、`TransactionManager`、编程式事务 |
| `jforge-bench` | ORM vs 裸 JDBC 基准 |
| `jforge-spring-boot-starter` | Spring Boot 自动配置，把 ORM 事务接入 Spring 事务管理 |
| `jforge-lambda` | JVM 对象创建策略的 JMH 基准（方法学验证） |

## 快速开始

```xml
<dependency>
    <groupId>io.github.erdsgfc</groupId>
    <artifactId>jforge-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

```java
// 1. 实体（接口 + builder setter）
@Table(name = "users")
public interface UserEntity {
    @Id @GeneratedValue Long id();
    UserEntity id(Long id);
    @Column(name = "user_name") String name();
    UserEntity name(String name);
    Integer age();
    UserEntity age(Integer age);
}

// 2. 仓库（继承 BaseRepository 获得 CRUD + 编程式事务；@Query 自定义查询）
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);
}

// 3. 使用（统一门面 JForge：持有 DataSource/TransactionManager，缓存全部仓库）
JForge jforge = new JForge(dataSource);
UserRepository repo = jforge.repository(UserRepository.class);
UserEntity user = repo.save(repo.createEntity().name("qin").age(25));
UserEntity found = repo.findById(1L);

// 4. 事务：模板（自动提交/回滚），回调直接拿事务连接做原生控制
repo.execute(conn -> {
    repo.save(a);
    conn.setSavepoint("sp");          // 原生 JDBC 控制
    if (/* 业务判定中止 */) repo.markRollbackOnly();   // 条件性回滚
    return null;
});
```

## 性能

| 操作 | JForge（生成代码） | 裸 JDBC | JForge/JDBC |
|---|---:|---:|---:|
| Insert | 523,305 | 531,755 | −1.6%（噪声内） |
| FindById | 1,601,691 | 1,585,588 | **+1.0%** |
| FindAll | 1,636,751 | 1,575,445 | **+3.9%** |

> 实测条件：H2 内存库 + HikariCP（语句缓存开启），10 次测量迭代 × 2s（数据与方法学详见 `BENCHMARK_RESULTS.md` Run ORM-1/ORM-2）。编译期生成直写 JDBC = 手写 JDBC 性能（等效或略优）。

## 路线图

- ✅ Repository 架构（实体接口 + @Dao + 编译期生成）
- ✅ 基准验证（ORM vs 裸 JDBC 达标）
- ✅ 编程式事务 + Spring Boot starter 接入
- ⬜ Phase 3 关联映射（接口实体懒加载，`java.lang.reflect.Proxy`）
- ⬜ Phase 4 一级缓存（L1，事务级 identity map，仅事务激活时生效）
- ⬜ Phase 6 GraalVM Native Image 实测验证

## 文档

- `USAGE.md` — **框架使用文档**（依赖引入、实体/仓库定义、配置、事务/作用域/批处理、Spring 集成、已知限制）
- `ORM_PLAN.md` — 开发计划（架构、事务设计、性能原则、已知限制）
- `CLAUDE.md` — 项目说明与代码规范
- `RELEASE.md` — Maven Central 发布流程
- `JMH_USAGE.md` / `EXECUTION_STEPS.md` / `BENCHMARK_RESULTS.md` — JMH 基准文档

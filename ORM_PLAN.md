# JForge 开发计划

**性能优先**的微型 ORM（`jforge-core` 模块）：
- 目标 1：与裸 JDBC 一样快（框架开销 <5%）— ✅ **已达成**
- 目标 2：兼容 AOT（GraalVM Native Image 可构建、可运行）

## 架构：Repository 接口继承 + 编译期生成实现

```
用户代码                              Processor 自动生成                    orm 框架库
────────                             ────────────────                    ──────────
@Dao                                UserRepository_Impl                  BaseRepository<T,ID>
public interface UserRepository      implements UserRepository               ↓
    extends BaseRepository<U,Long>       ↓                              @Dao/@Query/@Bind
{                                     直接 JDBC 实现                    注解层
    @Query(...)                        → new UserEntity_Impl()
    List<User> findByAge(int age);       rs.getLong(1)
}                                       ps.setString(2,...)

UserEntity (interface)              UserEntity_Impl
```

**核心思想**：用户定义 `@Table` 实体接口 + `@Dao` 仓库接口继承 `BaseRepository`，processor 在编译期生成**直写 JDBC 的实现类**——运行时零反射、零元数据查找、零动态分发。

## 模块结构

```
jforge-annotation/      # 注解：@Table/@Id/@Column/@GeneratedValue/@Transient（METHOD 作用域）
                     #       @Dao/@Query/@Bind/@ReturnGeneratedKeys
jforge-processor/       # 编译期生成器（javapoet + auto-service，provided，不进运行时）
  ├── JForgeProcessor.java      # 入口：只处理 @Dao，经 BaseRepository<T,ID> 定位实体并生成实体 impl（去重）
  ├── EntityGenerator.java      # 实体接口 → UserEntity_Impl（字段 + getter + builder setter）
  ├── RepositoryGenerator.java  # @Dao → UserRepository_Impl（CRUD 13 方法 + @Query + 固定 SQL 常量字段）
  ├── EntityModel.java          # 共享实体模型解析
  ├── SqlCodegen.java           # 绑定/读取代码块生成（编译期类型决策）
  └── TypeNameUtils.java        # 类型 → javapoet TypeName / JDBC getter/setter 映射
jforge-core/             # 框架库：BaseRepository（用户文件）、OrmException
jforge-bench/           # ORM vs 裸 JDBC 基准
jforge-spring-boot-starter/  # Spring Boot starter：SpringTransactionManager 包装 PlatformTransactionManager + 自动配置
```

## 用法示例

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

// 2. 仓库（继承 BaseRepository 获得 CRUD；@Query 自定义查询）
@Dao
public interface UserRepository extends BaseRepository<UserEntity, Long> {
    @Query("SELECT id, user_name, age FROM users WHERE age > :age")
    List<UserEntity> findByAgeGreaterThan(@Bind("age") int age);

    @Query("SELECT id, user_name FROM users WHERE id = :id")
    UserNameDto findNameById(@Bind("id") long id);   // record DTO 投影
}

// 3. 使用（Repositories 工厂编译期生成）
UserRepository repo = Repositories.createUserRepository(dataSource);
UserEntity user = repo.save(new UserEntity_Impl().name("qin").age(25));
UserEntity found = repo.findById(1L);
repo.update(found);
repo.deleteById(1L);
```

## 编程式事务

仓库继承 `TransactionOperations`，事务是**线程级**的（`TransactionManager` ThreadLocal）：同一线程上、绑定同一 `DataSource` 的多个仓库共享一个连接与事务边界。

```java
// 模板式（推荐：成功自动 commit，异常自动 rollback 并重抛）
repo.execute(() -> { repo.save(a); repo.save(b); return null; });

// 手动式
repo.beginTransaction();
try { repo.save(a); repo.commit(); }
catch (Exception e) { repo.rollback(); throw e; }

if (repo.isTransactionActive()) { ... }
```

设计要点：
- **零声明式**：无 `@Transactional`；事务边界完全由调用方代码控制
- **零开销**：未开启事务时 `getConnection()` 从池取连接，生成代码与裸 JDBC 等价
- **不支持嵌套**：事务已激活时再次 `begin` 抛 `OrmException`
- **跨 DataSource 隔离**：`connection(ds)` 仅当 `ds` 与事务来源 DataSource 相同才复用事务连接
- **条件性回滚**：`markRollbackOnly()` 标记当前事务回滚但不抛异常——`execute`/`run` 回调正常返回结果，事务在完成点回滚（适合业务规则判定中止）；`isRollbackOnly()` 查询标记（Spring 路径下 join 的外层事务被标记也算）

**Connection 访问（`beginTransaction` 返回连接，`execute` 回调接收）**：为补齐"核心不加事务参数控制"的取舍，`beginTransaction()` 返回**事务绑定**的 `Connection`，`execute(TransactionCallback)`（default 模板）把它传给回调——用于设隔离级别、savepoint、只读、查询超时、裸 SQL 等原生 JDBC 控制（裸 SQL 自动 join 当前事务）。另提供 `execute(param, TransactionParamCallback)` 重载，支持把**外部参数**传入事务回调（连接 + 参数一起交给回调）；以及**无返回值版本** `run(TransactionRunnable)` / `run(param, TransactionParamRunnable)`（对应 `execute`，回调无需 `return null`）。连接所有权由事务管理（commit/rollback 释放、事务连接保持不关），回调可抛 `SQLException` 由框架包装为 `OrmException`；`getConnection`/`releaseConnection` 保持生成实现私有、不暴露。测试：`TransactionTest`/`SpringTransactionManagerTest` 的 savepoint + 裸 SQL + 外部参数 + `run` 用例（内置与 Spring 两条路径）。

已知限制（内置 `SimpleTransactionManager`；引入 `jforge-spring-boot-starter` 后由 Spring 补齐）：

> **设计决策（已确认）：核心 jforge-core 有意不加事务参数控制（隔离级别/只读/超时）**——保持"最小 + 性能优先"定位，这些能力由 starter（Spring 的 `@Transactional`/`TransactionTemplate`）提供；纯 ORM 用户可用 `execute` 回调接收的 `Connection` 参数通过原生 JDBC 自行施加（见上）。默认 `begin()` 固定用 DataSource/连接池默认值，零开销路径不受影响。

- **无隔离级别参数**：`begin` 固定使用 DataSource/连接池默认隔离级别（HikariCP 默认 `READ_COMMITTED`），不提供 `setIsolation` 入口
- **无事务超时**：不限制事务最长时长；内置 `SimpleTransactionManager` 下手动 `beginTransaction()` 忘 `commit/rollback`（异常路径漏收尾）时连接会一直留在线程上——线程池场景即永久泄漏，推荐统一用 `execute`/`run` 模板自动收尾；Spring 路径下 `SpringTransactionManager` 已注册事务完成钩子（`TransactionSynchronization.afterCompletion`），外层 Spring 事务收尾时自动清理 ORM 状态（连接由 Spring 释放），不会跨事务误报 "already active"
- **无 savepoint**：不支持部分回滚，与"不支持嵌套事务"一致

## Spring 事务控制（jforge-spring-boot-starter）

引入 starter 后，仓库操作可直接由 **Spring 的三种事务机制**控制，无需调用 ORM 的 `beginTransaction/commit`：

前置条件：
- 应用已有 `DataSource` 与 `PlatformTransactionManager` bean（典型做法：依赖 `spring-boot-starter-jdbc`，由 `DataSourceAutoConfiguration` + `DataSourceTransactionManagerAutoConfiguration` 自动配置）
- 仓库用**同一个** `DataSource` 创建（`Repositories.createXxxRepository(ds)`）
- starter 的自动配置检测到 `PlatformTransactionManager` 后，把全局 ORM `TransactionManager` 替换为 Spring 包装器

**为什么能 join**：生成代码取连接走 `TransactionManager.current().connection(ds)` → `DataSourceUtils.getConnection(ds)`；Spring 通过 `TransactionSynchronizationManager` 把事务连接绑定到当前线程，所以以下三种方式激活的事务，仓库操作自动复用同一连接：

```java
// 1. 声明式：@Transactional（服务方法上，隔离级别/超时/savepoint 全部可用）
@Transactional
public void transfer() {
    repoA.save(a); repoB.save(b);   // 同线程 join 同一事务
}

// 2. 编程式模板：TransactionTemplate
transactionTemplate.execute(status -> {
    repoA.save(a);
    return null;
});

// 3. 底层手动：PlatformTransactionManager
TransactionStatus s = txManager.getTransaction(new DefaultTransactionDefinition());
try { repoA.save(a); txManager.commit(s); }
catch (Exception e) { txManager.rollback(s); throw e; }
```

ORM 自身的 `repo.execute(...)` / `beginTransaction()` 与上述方式**可混用且正确组合**：在 Spring 事务内调用会 join（`PROPAGATION_REQUIRED`），不会开新事务。

测试覆盖：`SpringTransactionControlTest`（三种机制提交/回滚端到端）、`OrmTransactionAutoConfigurationTest`（自动配置注册 + imports 文件发现）、`SpringTransactionManagerTest`（包装器单元集成）。

⚠️ 若未引入 starter（全局仍是 `SimpleTransactionManager`），`@Transactional` 等**不会**生效——仓库会拿到独立连接、脱离 Spring 事务边界。这是 starter 存在的意义。

⚠️ **Spring 路径已知限制——事务超时不强制执行**：隔离级别、只读、传播行为都自动生效（Spring 在 `DataSourceTransactionManager.doBegin()` 直接把隔离级别/只读设置到绑定的连接上，ORM 生成代码 join 同一连接）；但**事务超时（`@Transactional(timeout=…)`、`TransactionTemplate` 超时、`TransactionDefinition.getTimeout()`）对 ORM 生成的直写 JDBC 语句不生效**。原因：Spring 对 JDBC 的事务超时本质是"语句级近似"——把超时记录在 `TransactionSynchronizationManager`，待**每条语句执行时**由 `DataSourceUtils.applyTimeout(ps, ds, timeout)` → `Statement.setQueryTimeout()` 强制应用，而调用它的是 `JdbcTemplate`；生成代码是直写 JDBC（`conn.prepareStatement` + `executeUpdate`），不经 `JdbcTemplate`，也无人调用 `applyTimeout`。后续若需强制执行，要让生成代码识别 Spring 并补调 `applyTimeout`（成本高），当前以文档标注为准。

## 基准结果（ORM vs 裸 JDBC，`-f 3 -i 5`，15 次测量）

| 操作 | ORM（生成代码） | 裸 JDBC | ORM/JDBC | 状态 |
|---|---:|---:|---:|---:|
| Insert | 529,893 | 512,190 | **+3.5%** | ✅ |
| FindById | 1,558,908 | 1,560,231 | **-0.1%** | ✅ 持平 |
| FindAll | 1,658,211 | 1,620,312 | **+2.3%** | ✅ |

**结论**：编译期生成直写 JDBC = 手写 JDBC 性能（甚至略优）。目标 1 达成。

## 阶段状态

- ✅ **Repository 架构重构**（原 Phase 1/1.5 全部替换）：实体接口 + @Dao 仓库 + 编译期生成（CRUD 13 方法 + @Query + DTO 投影 + Repositories 工厂；只处理 @Dao，实体 impl 经仓库类型参数定位并生成）
- ✅ **基准验证**：ORM vs 裸 JDBC 全部达标
- ✅ **编程式事务**：`BaseRepository` 继承 `TransactionOperations`（begin/commit/rollback/isTransactionActive + `execute` 模板）；ThreadLocal 共享、跨仓库原子；`@Transactional` 注解已移除（项目不做声明式事务）
- ✅ **Spring Starter 接入**（`jforge-spring-boot-starter`）：启动时把全局 `TransactionManager` 替换为 Spring `PlatformTransactionManager` 的包装器（`SpringTransactionManager` + `OrmTransactionAutoConfiguration`，经 `AutoConfiguration.imports` 注册）——生成代码经 `TransactionManager.current()` 无感切换，`execute`/`beginTransaction` 可 join 外部 Spring 事务；`@Transactional` 声明式能力由 Spring 提供；自动配置测试 + 集成测试全绿（依赖 Spring Boot 3.5.6 BOM，仅该模块引入）
- ⬜ **Phase 3 关联映射**：实体是接口 → 懒加载用 `java.lang.reflect.Proxy`（无需字节码库）
- ⬜ **Phase 4 一级缓存（L1 Cache）**：事务级 identity map——`findById` 按 (实体, 主键) 缓存，事务内重复查询只发一次 SQL 且返回同一实例；写操作失效/更新；`rollback` 清空缓存；**仅事务激活时生效，未开启事务零开销**（详细设计见下节）
- ⬜ **Phase 6 GraalVM 验证**：native-image 构建 + native 下测试（生成代码零反射，预期直接通过）

## Phase 4 一级缓存（L1 Cache）设计

**目标**：事务内的 identity map——同一事务内对同一主键的 `findById` 只发一次 SQL，且多次返回**同一实例**（对象身份一致）。与 Hibernate Session 一级缓存同思路；**不做跨事务的二级缓存**（如需，另立 Phase 规划）。

**范围与语义**：
- **事务级**：缓存生命周期跟随当前线程的事务（挂 ThreadLocal），`begin` 时创建、`commit/rollback` 时销毁——绝不跨事务复用，避免把已回滚/过期数据暴露给后续事务
- **键**：`(实体类型, 主键值)`；值：实体实例
- **命中路径**：`findById(id)` 命中 → 直接返回缓存实例，不查库
- **写路径**：`save`（写回生成主键后入缓存）、`update`（更新缓存或失效）、`delete`/`deleteById`（失效）

**关键约束（延续本项目性能原则）**：
- **仅事务激活时启用**：未开启事务（自动提交）时零开销——不分配缓存结构、不查缓存，保持裸 JDBC 等价
- **生命周期一致性**：缓存必须随事务结束而清空，否则已回滚/过期数据会被后续查询读到（脏数据）。"**谁驱动事务，谁负责告诉缓存事务结束了**"：
  - 内置 `SimpleTransactionManager`：缓存挂 ORM 事务状态，`begin` 创建、`commit/rollback` 清空——无需 Spring
  - **Spring 路径（starter）**：事务由 Spring 驱动（`@Transactional`/`TransactionTemplate`/`PlatformTransactionManager` 任一种），ORM 的 `commit()/rollback()` 不会被调用 → 必须用 `TransactionSynchronizationManager.registerSynchronization()` 注册 `afterCompletion` 回调清空；三种方式都经 `AbstractPlatformTransactionManager` 触发 synchronizations，一个回调全覆盖
- 保持 AOT 友好：纯内存结构（`HashMap`），无反射

**实现位置**（结合上面的生命周期约束）：
- 独立 `L1Cache` 组件 + `TransactionManager` 暴露"按线程取/建缓存 + 事务完成回调"的生命周期钩子，生成代码经静态入口读写——SPI 干净
- **钩子的 Spring 实现即 `TransactionSynchronizationManager`**（仅 starter 模块依赖 Spring），核心 `jforge-core` 保持零 Spring 依赖：内置实现由 ORM 管理器驱动，`SpringTransactionManager` 内部用 `registerSynchronization` 驱动

**验收标准**：
- 事务内同主键多次 `findById` 只发一次 SQL（mock/统计 PreparedStatement 执行次数验证）
- 事务内 `save` 后 `findById` 返回同一实例（identity guarantee）
- `rollback` 后缓存清空，后续事务查不到已回滚数据
- 未开启事务时与现有基准持平（框架开销不增加）

## 性能设计原则（源自本项目 JMH 基准结论）

1. 句柄/调用点必须可被 JIT 内联：`invokeExact` > `invoke`（慢 28-73%）
2. `static final` 引用（instance 字段慢 17-84%）
3. 类型决策编译期完成（精确 setter/getter，避免 `setObject`/`findColumn`）
4. PreparedStatement 复用（statement cache）
5. 生成代码是直写 JDBC —— 与手写等价是性能上限

## AOT 状态

- 生成代码：纯静态方法调用、直接构造、无反射、无 MethodHandle、无 Class.forName → Native Image 友好
- 待 Phase 6 实测验证

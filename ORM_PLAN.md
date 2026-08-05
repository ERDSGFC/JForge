# ORM 开发计划

**性能优先**的微型 ORM（`orm` 模块）：
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
orm-annotation/      # 注解：@Table/@Id/@Column/@GeneratedValue/@Transient（METHOD 作用域）
                     #       @Dao/@Query/@Bind/@ReturnGeneratedKeys
orm-processor/       # 编译期生成器（javapoet + auto-service，provided，不进运行时）
  ├── EntityProcessor.java      # @Table 接口 → UserEntity_Impl（字段 + getter + builder setter）
  ├── RepositoryProcessor.java  # @Dao 接口 → UserRepository_Impl（CRUD 12 方法 + @Query）
  ├── EntityModel.java          # 共享实体模型解析
  ├── SqlCodegen.java           # 绑定/读取代码块生成（编译期类型决策）
  └── TypeNameUtils.java        # 类型 → javapoet TypeName / JDBC getter/setter 映射
orm/                 # 框架库：BaseRepository（用户文件）、OrmException
orm-bench/           # ORM vs 裸 JDBC 基准
orm-spring-boot-starter/  # Spring Boot starter：SpringTransactionManager 包装 PlatformTransactionManager + 自动配置
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

已知限制（内置 `SimpleTransactionManager`；引入 `orm-spring-boot-starter` 后由 Spring 补齐）：
- **无隔离级别参数**：`begin` 固定使用 DataSource/连接池默认隔离级别（HikariCP 默认 `READ_COMMITTED`），不提供 `setIsolation` 入口
- **无事务超时**：不限制事务最长时长；手动 `beginTransaction()` 后忘记 `commit/rollback`（异常路径漏收尾）时连接会一直留在线程上——线程池场景即永久泄漏，推荐统一用 `execute` 模板自动收尾
- **无 savepoint**：不支持部分回滚，与"不支持嵌套事务"一致

## 基准结果（ORM vs 裸 JDBC，`-f 3 -i 5`，15 次测量）

| 操作 | ORM（生成代码） | 裸 JDBC | ORM/JDBC | 状态 |
|---|---:|---:|---:|---:|
| Insert | 529,893 | 512,190 | **+3.5%** | ✅ |
| FindById | 1,558,908 | 1,560,231 | **-0.1%** | ✅ 持平 |
| FindAll | 1,658,211 | 1,620,312 | **+2.3%** | ✅ |

**结论**：编译期生成直写 JDBC = 手写 JDBC 性能（甚至略优）。目标 1 达成。

## 阶段状态

- ✅ **Repository 架构重构**（原 Phase 1/1.5 全部替换）：实体接口 + @Dao 仓库 + 编译期生成（CRUD 12 方法 + @Query + DTO 投影 + Repositories 工厂）
- ✅ **基准验证**：ORM vs 裸 JDBC 全部达标
- ✅ **编程式事务**：`BaseRepository` 继承 `TransactionOperations`（begin/commit/rollback/isTransactionActive + `execute` 模板）；ThreadLocal 共享、跨仓库原子；`@Transactional` 注解已移除（项目不做声明式事务）
- ✅ **Spring Starter 接入**（`orm-spring-boot-starter`）：启动时把全局 `TransactionManager` 替换为 Spring `PlatformTransactionManager` 的包装器（`SpringTransactionManager` + `OrmTransactionAutoConfiguration`，经 `AutoConfiguration.imports` 注册）——生成代码经 `TransactionManager.current()` 无感切换，`execute`/`beginTransaction` 可 join 外部 Spring 事务；`@Transactional` 声明式能力由 Spring 提供；自动配置测试 + 集成测试全绿（依赖 Spring Boot 3.5.6 BOM，仅该模块引入）
- ⬜ **Phase 3 关联映射**：实体是接口 → 懒加载用 `java.lang.reflect.Proxy`（无需字节码库）
- ⬜ **Phase 4 缓存层**：L1 按主键缓存，写操作失效
- ⬜ **Phase 6 GraalVM 验证**：native-image 构建 + native 下测试（生成代码零反射，预期直接通过）

## 性能设计原则（源自本项目 JMH 基准结论）

1. 句柄/调用点必须可被 JIT 内联：`invokeExact` > `invoke`（慢 28-73%）
2. `static final` 引用（instance 字段慢 17-84%）
3. 类型决策编译期完成（精确 setter/getter，避免 `setObject`/`findColumn`）
4. PreparedStatement 复用（statement cache）
5. 生成代码是直写 JDBC —— 与手写等价是性能上限

## AOT 状态

- 生成代码：纯静态方法调用、直接构造、无反射、无 MethodHandle、无 Class.forName → Native Image 友好
- 待 Phase 6 实测验证

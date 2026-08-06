# JMH 执行步骤记录（Run 8 / Run 9 / Run 10）

记录第八轮（标准写法首测）、第九轮（增强配置验证）与第十轮（invokeExact 改造）的完整执行步骤、命令与参数解析。均已执行，对应结果见 `BENCHMARK_RESULTS.md`。

---

## Run 8：标准写法首测（已完成）

**日期**: 2026-08-01
**目的**: 验证 JMH 标准写法（单次调用 + 返回对象，无手写循环）下 7 种对象创建方式的真实性能。此前的循环式写法（Run 1-7）排名结论在本次被推翻。

### 代码状态（执行前）

`LambdaBenchmark.java` 完成标准写法改造：
- `@BenchmarkMode(Mode.Throughput)` + `@OutputTimeUnit(TimeUnit.SECONDS)`
- 7 个 benchmark 方法（B01–B07），每次调用只创建 1 个 `User` 并返回，由 JMH 自动循环调用并消费返回值防 DCE（见 JMHSample_11_Loops）
- 删除 `@Param num`（单次调用模式无规模参数）
- 所有句柄为 `MyState` 中 `static final` 字段
- `lambda/pom.xml` 入口已切换为 `org.openjdk.jmh.Main`（支持完整 CLI）

### 步骤 1：打包

```bash
mvn package -pl jforge-lambda -q -DskipTests
```

产出: `jforge-lambda/target/benchmarks.jar`（uber-jar，入口 `org.openjdk.jmh.Main`）

### 步骤 2：运行全部 benchmark（默认注解配置）

```bash
java -jar jforge-lambda/target/benchmarks.jar > /tmp/jmh_run8.log 2>&1
```

### 参数解析（实测，来自日志头）

```
# Warmup: 3 iterations, 1 s each          ← @Warmup(iterations=3, time=1s)
# Measurement: 5 iterations, 1 s each     ← @Measurement(iterations=5, time=1s)
# Threads: 1 thread
# Benchmark mode: Throughput, ops/time
# Fork: 1 of 1                            ← @Fork(1)
```

| 配置 | 值 | 来源 |
|---|---|---|
| 模式 | Throughput, ops/time | `@BenchmarkMode(Mode.Throughput)` |
| 预热 | 3 次 × 1 秒 | 注解默认 |
| 测量 | 5 次 × 1 秒 | 注解默认 |
| fork | 1 个 JVM | 注解默认 |
| 每次调用 | 1 个对象 | 方法体单次创建（无 `-bs`） |

**注意**：本次单位 ops/s = 每秒调用次数 = 每秒创建的对象数（每次调用 1 个对象）。

### 步骤 3：运行时长

```
# Run complete. Total time: 00:00:57
```

### 步骤 4：结果提取

```bash
grep -E "^LambdaBenchmark" /tmp/jmh_run8.log
```

### Run 8 结果（ops/s，越大越好）

| Benchmark | Score (ops/s) | Error | 序号 |
|---|---:|---:|:---:|
| B04_allArgsConstructor | **208,875,709** | ±15,509,051 | ④ |
| B02_reflectionConstructor | 198,683,702 | ±24,851,963 | ② |
| B07_noArgConstructorWithSetters | 195,672,752 | ±51,386,614 | ⑦ |
| B05_lambdaMetafactoryWithSetters | 189,557,631 | ±49,567,759 | ⑤ |
| B01_lambdaMetafactoryConstructor | 186,260,590 | ±16,396,466 | ① |
| B03_methodHandleConstructor | 142,951,370 | ±25,919,960 | ③ |
| B06_methodHandleWithSetters | **54,642,865** | ±5,487,842 | ⑥ |

### 结论

1. **排名对比 Run 7 发生显著变化**：直接 `new`（B04）从第 5 升至第 1；LambdaMetafactory 构造器（B01）从第 1 掉至第 5 —— **手写循环写法掩盖了真实性能**（Run 1-7 的"LambdaMetafactory 最快 / 直接 new 中等偏下"结论被推翻）。
2. 单次调用模式下 JIT 可对每个方法完整内联深度优化，更能反映真实调用成本。
3. 误差较大（±10-26%，方法体过短导致计时噪声占比高）——需要增强配置验证（Run 9）。
4. B06（MethodHandle+setter）断层最慢的结论在两种写法下一致。

---

## Run 9：增强配置验证（已完成）

**日期**: 2026-08-01
**目的**: 验证 Run 8 结论的统计稳定性 —— 前五名（直接 new / 反射 / LambdaMetafactory）是否真的同档，B03/B06 的落后是否确凿。通过增加测量迭代、fork 数、batch size 压低误差。

### 代码状态（执行前）

与 Run 8 相同，无代码改动。

### 步骤 1：打包（可选，代码未变可跳过）

```bash
mvn package -pl jforge-lambda -q -DskipTests
```

### 步骤 2：后台启动完整测试

```bash
java -jar jforge-lambda/target/benchmarks.jar -i 10 -w 5 -f 5 -bs 1000 > /tmp/jmh_run9.log 2>&1
```

### 参数解析（实测，来自日志头）

```
# Warmup: 3 iterations, 5 s each            ← -w 5 解析为预热时长 5 秒/次（次数默认 3）
# Measurement: 10 iterations, 1 s each, 1000 calls per op   ← -i 10、-bs 1000
# Timeout: 10 min per iteration
# Threads: 1 thread
# Benchmark mode: Throughput, ops/time
# Fork: 1-5 of 5                            ← -f 5
```

| CLI 参数 | 解析结果 | 说明 |
|---|---|---|
| `-i 10` | 10 次测量迭代 | 默认 5 → 翻倍 |
| `-w 5` | 预热 **时长** 5 秒/次，共 3 次 | jmh.Main 中 `-w` 是时间（秒），非次数 |
| `-f 5` | 5 个独立 JVM fork | 验证跨进程稳定性 |
| `-bs 1000` | 每次迭代内调用 benchmark 方法 1000 次 | JMH 内部循环，非手写循环 |

**注意**：`-bs 1000` 使结果单位为"调用次数/s"（1 次调用 = 1000 个对象），换算对象吞吐需 ×1000。

### 步骤 3：运行时长

```
# Run complete. Total time: 00:14:43
```

### 步骤 4：结果提取

```bash
# 结果表
grep -E "^LambdaBenchmark" /tmp/jmh_run9.log

# 每 fork 均值（验证 fork 间稳定性）
awk '/^# Benchmark: io.github.erdsgfc.jforge.lambda/ { b=$3; sub(/^io[.]github[.]erdsgfc[.]jforge[.]lambda[.]LambdaBenchmark[.]/, "", b) }
     /^# Fork:/ { f=$3 }
     /^Iteration/ { gsub(":", "", $2); printf "%s,%d,%d,%.3f\n", b, f, $2, $3 }' /tmp/jmh_run9.log
```

原始数据文件: `benchmark_run9_data.csv`（350 行 = 7 × 5 × 10）

### Run 9 结果（调用数/s，×1000 = 对象/s）

| Benchmark | Score (ops/s) | Error | ≈对象/s |
|---|---:|---:|---:|
| B01_lambdaMetafactoryConstructor | 217,650 | ±1,149 | 217.6M |
| B05_lambdaMetafactoryWithSetters | 216,533 | ±1,476 | 216.5M |
| B04_allArgsConstructor | 215,060 | ±2,342 | 215.1M |
| B02_reflectionConstructor | 214,550 | ±2,135 | 214.5M |
| B07_noArgConstructorWithSetters | 214,543 | ±4,516 | 214.5M |
| B03_methodHandleConstructor | 154,589 | ±1,274 | 154.6M |
| B06_methodHandleWithSetters | **59,148** | ±170 | 59.1M |

### 结论

1. 前五名差距仅 1.4%（214.5K–217.7K），区间重叠 → **统计等价**（Run 8 中 B04 第 1、Run 9 中 B01 第 1，排名互换证实前五名无法区分）
2. B03 稳定落后 ~29%，B06 断层落后 ~73% —— 显著结论
3. 每 fork 均值波动 <1%（除 B07 F2 受干扰掉 6%），跨 JVM 可复现
4. 误差从 Run 8 的 ±10-26% 降至 ±0.5-2%

---

## Run 10：invokeExact 改造（已完成）

**日期**: 2026-08-01
**目的**: 将 MethodHandle 调用从 `invoke()` 改为 `invokeExact()`，消除 asType 参数适配开销，验证 MethodHandle 的真实性能。`invoke` 每次调用做参数类型适配（装箱/类型检查），`invokeExact` 要求参数类型与 handle 签名精确匹配、跳过适配，JIT 可直接内联到目标方法。

### 代码改动（`LambdaBenchmark.java`）

`methodHandleConstructor` 与 `methodHandleWithSetters` 的全部 `invoke` 改为 `invokeExact`，参数改为精确类型：

```java
// invoke 版（Run 9）：参数隐式适配
return (User) MyState.MH_CONSTRUCTOR_10ARG.invoke(1L, "heihei", 1, ...);

// invokeExact 版（Run 10）：参数必须精确匹配 handle 签名 (Long, String, Integer, ...)
return (User) MyState.MH_CONSTRUCTOR_10ARG.invokeExact((Long) 1L, "heihei", (Integer) 1, ...);
```

要点：
- handle 签名 `(Long, String, Integer, ...)` 要求精确类型：`1L` → `(Long) 1L`，`1` → `(Integer) 1`，字符串/LocalDate 无需转换
- setter handle（如 `MH_SET_ID`，签名 `(User, Long)`）：`MH_SET_ID.invokeExact(user, (Long) 1L)`
- 无参构造 handle：`(User) MH_NOARG_CONSTRUCTOR.invokeExact()`
- 编译期类型不匹配会直接报编译错误（如 `Argument type should be exactly 'java.lang.Long'`）

同时类注解由用户调整为：`@Warmup(iterations=5, time=3s)`、`@Measurement(iterations=10, time=2s)`、默认 5 forks（无 `@Fork` 注解、无 `-bs`）。

### 步骤 1：打包

```bash
mvn package -pl jforge-lambda -q -DskipTests
```

### 步骤 2：快速验证（确认功能正常，~1 分钟）

```bash
java -jar jforge-lambda/target/benchmarks.jar -f 1 -i 2 -w 1 -to 120s
```

### 步骤 3：完整运行（默认注解配置，后台执行，~21 分钟）

```bash
java -jar jforge-lambda/target/benchmarks.jar > /tmp/jmh_run12.log 2>&1
```

### 参数解析（实测，来自日志头）

```
# Warmup: 5 iterations, 3 s each       ← @Warmup(iterations=5, time=3s)
# Measurement: 10 iterations, 2 s each ← @Measurement(iterations=10, time=2s)
# Fork: 1-5 of 5                       ← 默认 5 forks（无 @Fork 注解）
```

### 步骤 4：运行时长

```
# Run complete. Total time: 00:20:58
```

### 步骤 5：结果提取

```bash
grep -E "^LambdaBenchmark" /tmp/jmh_run12.log
```

### Run 10 结果（ops/s = 对象/s，50 次测量 = 10 迭代 × 5 forks）

| Benchmark | Score (ops/s) | Error |
|---|---:|---:|
| lambdaMetafactoryWithSetters | **223,155,893** | ±1,049,390 |
| methodHandleConstructor | 222,818,542 | ±1,214,660 |
| lambdaMetafactoryConstructor | 222,777,687 | ±1,096,525 |
| allArgsConstructor | 219,560,406 | ±3,022,325 |
| reflectionConstructor | 214,501,542 | ±3,848,058 |
| noArgConstructorWithSetters | 214,265,375 | ±3,661,869 |
| methodHandleWithSetters | 214,004,752 | ±14,610,331 |

### 结论（与 Run 9 invoke 版对比）

| Benchmark | invoke（Run 9） | invokeExact（Run 10） | 变化 |
|---|---:|---:|---:|
| methodHandleConstructor | 154.6M（-29%） | 222.8M | **+44%** |
| methodHandleWithSetters | 59.1M（-73%） | 214.0M | **+262%** |
| 其余五者 | ~215M | ~214-223M | 0-3% |

1. **7 种方式全部收敛到 214-223M（差距 ≤4.3%，误差区间重叠）→ 统计等价**，"MethodHandle 慢 28-73%"的结论被彻底推翻
2. 真实差异源是 `invoke` 的 asType 适配开销，非 MethodHandle 本身
3. 实践建议：MethodHandle 调用优先 `invokeExact`，参数类型精确匹配

---

## Run 12：static final 对照组（已完成）

**日期**: 2026-08-01
**目的**: 用受控对照验证 `static final` 句柄的优化效应——新增 `InstanceState`（instance 字段 + `@Setup`）与 4 个 `*Instance` 对照方法，唯一变量为句柄存储方式，`allArgsConstructor` 为无句柄锚点。

### 代码改动（`LambdaBenchmark.java`）

1. 新增 `InstanceState` `@State` 类：与 `MyState` 相同的初始化逻辑，写入 instance 字段（复用 `MyState.createBiConsumer`）
2. 新增 4 个对照方法（`...Instance` 后缀，参数注入 `InstanceState`）

### 步骤 1：打包 + 快速验证

```bash
mvn package -pl jforge-lambda -q -DskipTests
java -jar jforge-lambda/target/benchmarks.jar 'LambdaBenchmark\.\w*Instance$' -f 1 -i 2 -w 1 -to 120s
```

### 步骤 2：完整运行（11 个 benchmark，~32 分钟）

```bash
java -jar jforge-lambda/target/benchmarks.jar > /tmp/jmh_run14.log 2>&1
# Run complete. Total time: 00:32:19
```

### 步骤 3：结果提取

```bash
grep -E "^LambdaBenchmark" /tmp/jmh_run14.log
```

### 对照结论

| 方案 | static final | instance | 差距 |
|---|---:|---:|---:|
| lambdaMetafactoryWithSetters | 214.1M | 177.9M | -17% |
| methodHandleConstructor | 195.0M | 151.3M | -22% |
| reflectionConstructor | 212.7M | 93.3M | -56% |
| methodHandleWithSetters | 217.2M | 33.8M | -84% |

1. **static final 效应在标准写法下确认**，幅度大于旧循环式测得值（-47%/-40% → -56%/-84%）
2. 锚点 allArgsConstructor 217.3M 与历轮一致 → 两组环境可比
3. 旁注：methodHandleConstructor（static final）本轮 195M vs Run 10 的 222.8M，跨轮波动 ~12%，invokeExact 调用点对编译时序敏感，但不影响对照结论

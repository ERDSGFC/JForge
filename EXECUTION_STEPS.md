# JMH 执行步骤记录（Run 8 / Run 9）

记录第八轮（标准写法首测）与第九轮（增强配置验证）的完整执行步骤、命令与参数解析。两轮均已执行，对应结果见 `BENCHMARK_RESULTS.md`。

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
mvn package -pl lambda -q -DskipTests
```

产出: `lambda/target/benchmarks.jar`（uber-jar，入口 `org.openjdk.jmh.Main`）

### 步骤 2：运行全部 benchmark（默认注解配置）

```bash
java -jar lambda/target/benchmarks.jar > /tmp/jmh_run8.log 2>&1
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
mvn package -pl lambda -q -DskipTests
```

### 步骤 2：后台启动完整测试

```bash
java -jar lambda/target/benchmarks.jar -i 10 -w 5 -f 5 -bs 1000 > /tmp/jmh_run9.log 2>&1
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
awk '/^# Benchmark: com.qin/ { b=$3; sub(/^com[.]qin[.]LambdaBenchmark[.]/, "", b) }
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

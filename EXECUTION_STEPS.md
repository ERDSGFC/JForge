# JMH 执行步骤记录（Run 9 / Run 10）

记录第九轮（已完成）与第十轮（计划）的完整执行步骤、命令与参数解析。

---

## Run 9：增强配置验证轮（已完成）

**日期**: 2026-08-01
**目的**: 验证 Run 8 结论的统计稳定性 —— 前五名（直接 new / 反射 / LambdaMetafactory）是否真的同档，B03/B06 的落后是否确凿。通过增加测量迭代、fork 数、batch size 压低误差。

### 代码状态（执行前）

`LambdaBenchmark.java` 为标准写法：
- `@BenchmarkMode(Mode.Throughput)` + `@OutputTimeUnit(TimeUnit.SECONDS)`
- 7 个 benchmark 方法（B01–B07），每次调用只创建 1 个 `User` 并返回，由 JMH 自动循环调用并消费返回值
- 所有句柄为 `MyState` 中 `static final` 字段

### 步骤 1：打包

```bash
mvn package -pl lambda -q -DskipTests
```

产出: `lambda/target/benchmarks.jar`（uber-jar，入口 `org.openjdk.jmh.Main`）

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

1. 前五名差距仅 1.4%（214.5K–217.7K），区间重叠 → **统计等价**
2. B03 稳定落后 ~29%，B06 断层落后 ~73% —— 显著结论
3. 每 fork 均值波动 <1%（除 B07 F2 受干扰掉 6%），跨 JVM 可复现
4. 误差从 Run 8 的 ±10-26% 降至 ±0.5-2%

---

## Run 10：static final 效应重测轮（计划）

**目的**: 验证 Run 1→2 的发现 —— "`static final` 句柄使反射提速 -47%、MH+setter 提速 -40%"（旧循环式写法下测得）—— 在标准单次调用写法下是否仍成立。若成立，`static final` 优化的结论跨写法稳健；若不成立，该效应只是循环式写法的产物。

**方法**: 对照组设计 —— 新增 C01–C07 benchmark，与 B01–B07 逻辑完全相同，但 `MyState` 的句柄改为 **instance 字段**（非 static，`@Setup` 中初始化），其他一律不变。B vs C 差 = static final 效应。

### 步骤 1：代码改动（`LambdaBenchmark.java`）

在 `MyState` 中新增对照成员（instance 字段版）：

```java
@State(Scope.Thread)
public static class InstanceState {
    private Constructor<User> allArgsConstructor;
    private MethodHandle mhConstructor10Arg;
    private NewUser factory;
    private Supplier<User> supplier;
    private BiConsumer<User, Long> setId;
    // ... 其余 9 个 setter ...

    @Setup
    public void init() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        // 与 MyState.static{} 相同的初始化逻辑，赋值给 instance 字段
    }
}
```

新增 C 系列 benchmark 方法（C01–C07，与 B01–B07 一一对应，通过 `@State InstanceState` 参数注入访问 instance 句柄）：

```java
@Benchmark
public User C01_lambdaMetafactoryConstructor(InstanceState state) {
    return state.factory.apply(1L, "heihei", 1, "17374957973", 1,
            LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
}
// ... C02-C07 同理
```

命名 C 前缀保证执行顺序：C01 → C02 → ... → C07 紧跟在 B07 之后（字母序）。

### 步骤 2：打包

```bash
mvn package -pl lambda -q -DskipTests
```

### 步骤 3：运行（比对轮，配置同 Run 9 的量级，耗时约 3-5 分钟）

```bash
java -jar lambda/target/benchmarks.jar -i 5 -w 2 -f 3 -bs 1000 > /tmp/jmh_run10.log 2>&1
```

只跑 B/C 系列的相关对比也可：

```bash
java -jar lambda/target/benchmarks.jar 'LambdaBenchmark\.(B|C)(0[1-3]|06).*' -i 5 -f 3 -bs 1000
```

（反射、MH 构造器、MH+setter 是 Run 2 中受影响最大的三个方案）

### 步骤 4：结果提取与对比

```bash
grep -E "^LambdaBenchmark" /tmp/jmh_run10.log
```

逐对对比 Bn vs Cn（同方案、唯一差异 = static final vs instance）：

| 对比对 | 关注点 | Run 2 循环式下的效应 |
|---|---|---|
| B02 vs C02 | 反射 | -47% |
| B03 vs C03 | MH 构造器 | -9% |
| B06 vs C06 | MH+setter | -40% |
| B01 vs C01 | LambdaMetafactory | +3%（无效应） |

### 预期与分析

- 若 Bn ≈ Cn（差距 <2-3%）：static final 效应在标准写法下消失 → Run 2 结论为循环式写法特例，需修正 BENCHMARK_RESULTS.md 关键发现 ②
- 若 Bn 显著优于 Cn（差距 >5%）：static final 常量折叠效应跨写法成立 → 结论强化，且表明标准写法下静态句柄仍是实践建议

### 步骤 5：记录

结果写入 `BENCHMARK_RESULTS.md` Run 10 章节，原始数据导出 `benchmark_run10_data.csv`。

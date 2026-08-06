# JMH 使用说明

本项目 JMH benchmark 的 CLI 参数与用法速查。完整选项列表可用 `java -jar jforge-lambda/target/benchmarks.jar -h` 查看。

## 构建

```bash
mvn clean package -pl jforge-lambda          # 生成 jforge-lambda/target/benchmarks.jar
```

## 常用参数

| 参数 | 含义 | 本项目注解对应 |
|---|---|---|
| `-w <int>` | 预热迭代**次数** | `@Warmup(iterations = 3)` |
| `-wi <time>` | 每次预热迭代**时长** | `@Warmup(time = 1, unit = SECONDS)` |
| `-i <int>` | 正式测量迭代**次数** | `@Measurement(iterations = 5)` |
| `-r <time>` | 每次测量迭代**时长** | `@Measurement(time = 1, unit = SECONDS)` |
| `-f <int>` | **fork**（独立 JVM 进程）数量 | `@Fork(1)` |
| `-prof <profiler>` | 附加剖析器，收集额外数据 | 无 |
| `-l` | 只**列出**匹配的 benchmark，不运行 | 无 |
| `-li` | 列出版本，附详细描述 | 无 |
| `-e <regexp>` | 排除匹配的 benchmark | 无 |
| `-rf <format>` | 结果导出格式（json/csv/text） | 无 |
| `-rff <filename>` | 结果导出到文件 | 无 |
| `-t <int>` | worker 线程数 | 无 |

**CLI 参数会覆盖类上的注解配置。**

## 概念

- **warmup（预热）**：JVM 解释执行起步，跑几千次后 JIT 才把热点方法编译成机器码。预热把"冷启动"时间排除在计时之外，让测量反映真实稳态性能。3 次 × 1s 不够 JIT 完全收敛时，测量会带噪声。
- **measurement（测量）**：真正计入最终得分的迭代。JMH 对多次迭代取均值，迭代越多统计越稳（误差 ± 越小）。
- **fork**：每次 fork 开一个全新 JVM 进程，重跑完整预热+测量。隔离进程间状态（如 profile pollution、堆状态），与 BENCHMARK_RESULTS.md Run 5"独立执行"思路一致。耗时 ×fork 数。
- **`-prof`**：不改变计时，只附加采集。

## `-prof` 常用剖析器

| profiler | 用途 |
|---|---|
| `gc` | 每次迭代的 GC 次数、分配速率（测对象创建最实用） |
| `javaalloc` | 精确到方法的分配字节数 |
| `stack` | 热点方法 CPU 采样 |
| `perf` | Linux 下 perf 硬件计数器 |

## 实际用法

```bash
# 列出当前 jar 里所有 benchmark
java -jar jforge-lambda/target/benchmarks.jar -l

# 运行全部
java -jar jforge-lambda/target/benchmarks.jar

# 运行单个
java -jar jforge-lambda/target/benchmarks.jar LambdaBenchmark.allArgsConstructor

# 运行多个（正则筛选）
java -jar jforge-lambda/target/benchmarks.jar 'LambdaBenchmark\.(allArgsConstructor|reflectionConstructor)'

# 覆盖默认参数：10 次测量、5 次预热、3 个 fork、加 GC 剖析、结果导出 JSON
java -jar jforge-lambda/target/benchmarks.jar LambdaBenchmark.allArgsConstructor -i 10 -w 5 -f 3 -prof gc -rf json -rff result.json
```

## 入口（当前配置）

`lambda/pom.xml` 中 shade 插件的 mainClass 已配置为 **`org.openjdk.jmh.Main`**（JMH 内置通用 runner），支持完整 CLI 参数。`LambdaBenchmark.java` 中的 `main()` 仍保留（仅供直接调用或 `mvn exec:java` 使用），但不再是 jar 入口。

| 入口 | 参数解析 | 无参默认行为 |
|---|---|---|
| `org.openjdk.jmh.Main`（当前 POM） | 解析完整 JMH CLI | 跑 jar 内所有 benchmark 类 |
| `io.github.erdsgfc.jforge.lambda.LambdaBenchmark`（类内 main） | 只把参数当 include 模式，不支持 `-i/-w/-f/-prof/-l` 等 | 只跑 LambdaBenchmark 一个类 |

两个入口最终都走同一个 `org.openjdk.jmh.runner.Runner`，差异仅在入口 wrapper 的参数处理方式。

---

## 关键经验与教训（本项目 12 轮基准实证）

> 完整数据与推理见 `BENCHMARK_RESULTS.md`；以下为可直接套用的结论。

### 测量写法（最容易犯的错）

1. **不要手写循环** — 标准写法是方法体单次操作 + 返回对象（JMH 自动循环调用并消费返回值防 DCE）。手写 50 万次循环会改变 JIT 优化行为，**排名结论被证明失真**（Run 8 推翻了 Run 1-7 的全部排名）。
2. **测量方法敏感 → 结论不可信** — 同写法内可复现（跨轮 <8%）不等于结论正确，可能是"确定性地测错了场景"。
3. **短方法误差大**（方法体 ~5ns 时 ±10-26%）— 用 `-bs` 提高每次迭代调用次数 + 增加 `-i`/`-f` 压低（本项目从 ±10-26% 压到 ±0.5-2%）。
4. **跨轮波动 ±4-12%**（JIT 编译时序差异）— 任何 <5% 的 benchmark 间差异都视为噪声，下结论前重复运行。
5. `-w` 是预热**秒数**不是次数（jmh.Main 解析）；`-bs N` 时 ops/s 是"调用数/s"，换算对象吞吐 ×N。
6. 同 JVM 内 JMH 按**字母序**执行 benchmark（源码顺序无效）；fork 隔离跨 benchmark 污染。

### JVM 调用机制（哪些快哪些慢）

7. **对象创建 7 种方式全部等价** — `new`、反射、LambdaMetafactory、MethodHandle（`invokeExact`）、无参+setter 等，JIT 深度预热后同为 ~220M 对象/s，无显著差异。
8. **`MethodHandle.invoke` 慢 28-73%**（asType 参数适配开销）— `invokeExact` 追平直接调用 → **优先 `invokeExact`**。
9. **`static final` 句柄 vs instance 字段：慢 17-84%**（反射 -56%、MethodHandle+setter -84%）→ 频繁调用的反射/MethodHandle 句柄必须 `static final`。
10. **"调用次数多"不是性能问题** — 12 次 lambda 调用 ≈ 1 次 `new` 构造，因为 JIT 级联内联后调用边界消失；关键在调用点能否被内联（`invoke` 不能，`invokeExact`/常规调用能）。
11. **H2 内存库**必须 `DB_CLOSE_DELAY=-1`，否则最后一个连接关闭时库被销毁。
12. **连接所有权**：谁创建谁关闭（Session 自建的连接池才由 Session 关闭），避免资源泄漏。

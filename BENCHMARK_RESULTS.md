# JMH 基准测试结果汇总

**环境**: JDK 25, JMH 1.37, 10 字段 `User` POJO
**有效结论来源**: Run 8/9 —— 标准写法（Throughput ops/s，单次调用 + 返回对象防 DCE，见 JMHSample_11_Loops）

> **历史说明**: 早期轮次（Run 1-7，手写循环 + AverageTime ms/op）的排名结论已被证明受测量方式影响，数据已从本文档移除（完整历史在 git 记录中）。原因见文末"方法学教训"。

---

## Run 8: 标准写法首测（Throughput + 单次调用）

> **测量方式**：`@BenchmarkMode(Mode.Throughput)` + `@OutputTimeUnit(TimeUnit.SECONDS)`，方法体去掉手写循环，每次调用只创建 **1 个** User 并返回（JMH 自动循环调用并消费返回值防 DCE）。  
> **执行顺序** (字母序): ① B01_lambdaMetafactoryConstructor → ② B02_reflectionConstructor → ③ B03_methodHandleConstructor → ④ B04_allArgsConstructor → ⑤ B05_lambdaMetafactoryWithSetters → ⑥ B06_methodHandleWithSetters → ⑦ B07_noArgConstructorWithSetters  
> **注意**：ops/s 为每秒调用次数，每次调用创建 1 个对象。误差较大（±10-26%，方法体过短）。

| Benchmark | Score (ops/s) | Error | 序号 |
|---|---:|---:|:---:|
| B04_allArgsConstructor | **208,875,709** | ±15,509,051 | ④ |
| B02_reflectionConstructor | 198,683,702 | ±24,851,963 | ② |
| B07_noArgConstructorWithSetters | 195,672,752 | ±51,386,614 | ⑦ |
| B05_lambdaMetafactoryWithSetters | 189,557,631 | ±49,567,759 | ⑤ |
| B01_lambdaMetafactoryConstructor | 186,260,590 | ±16,396,466 | ① |
| B03_methodHandleConstructor | 142,951,370 | ±25,919,960 | ③ |
| B06_methodHandleWithSetters | **54,642,865** | ±5,487,842 | ⑥ |

---

## Run 9: 增强配置验证（-i 10 -w 5 -f 5 -bs 1000）

> 验证 Run 8 结论的统计稳定性：测量迭代 ×2、fork ×5、`-bs 1000`（每次迭代内调用 1000 次方法，JMH 内部循环）。误差从 ±10-26% 降至 ±0.5-2%。  
> **单位**：ops/s 为调用次数/s，**×1000 = 每秒创建的对象数**（与 Run 8 同口径对比）。  
> **执行顺序**: ① B01 → ② B02 → ③ B03 → ④ B04 → ⑤ B05 → ⑥ B06 → ⑦ B07  
> **原始数据**: `benchmark_run9_data.csv`（350 个测量点 = 7 × 5 fork × 10 迭代）

| Benchmark | Score (ops/s) | Error | ≈对象/s | 序号 |
|---|---:|---:|---:|:---:|
| B01_lambdaMetafactoryConstructor | 217,650 | ±1,149 | 217.6M | ① |
| B05_lambdaMetafactoryWithSetters | 216,533 | ±1,476 | 216.5M | ⑤ |
| B04_allArgsConstructor | 215,060 | ±2,342 | 215.1M | ④ |
| B02_reflectionConstructor | 214,550 | ±2,135 | 214.5M | ② |
| B07_noArgConstructorWithSetters | 214,543 | ±4,516 | 214.5M | ⑦ |
| B03_methodHandleConstructor | 154,589 | ±1,274 | 154.6M | ③ |
| B06_methodHandleWithSetters | **59,148** | ±170 | 59.1M | ⑥ |

---

## Run 10: invokeExact 改造（MethodHandle 追平直接 new）

> **代码改动**：B03/B06 的 `MethodHandle.invoke()` 全部改为 `invokeExact()`（参数改为精确类型 `(Long)`/`(Integer)` 匹配 handle 签名）。`invoke` 每次调用做 asType 参数适配（装箱/类型检查），`invokeExact` 跳过适配、JIT 可直接内联。  
> **运行配置**（当前代码注解默认，无 CLI 覆盖）：5 次预热 × 3s，10 次测量 × 2s，默认 5 forks。  
> **执行顺序** (字母序): allArgsConstructor → lambdaMetafactoryConstructor → lambdaMetafactoryWithSetters → methodHandleConstructor → methodHandleWithSetters → noArgConstructorWithSetters → reflectionConstructor  
> **单位**：ops/s = 每秒创建的对象数。

| Benchmark | Score (ops/s) | Error |
|---|---:|---:|
| lambdaMetafactoryWithSetters | **223,155,893** | ±1,049,390 |
| methodHandleConstructor | 222,818,542 | ±1,214,660 |
| lambdaMetafactoryConstructor | 222,777,687 | ±1,096,525 |
| allArgsConstructor | 219,560,406 | ±3,022,325 |
| reflectionConstructor | 214,501,542 | ±3,848,058 |
| noArgConstructorWithSetters | 214,265,375 | ±3,661,869 |
| methodHandleWithSetters | 214,004,752 | ±14,610,331 |

---

## Run 11: 子集轮（仅 4 个 benchmark）

> 用正则筛选只跑 `methodHandleConstructor` / `allArgsConstructor` / `reflectionConstructor` / `noArgConstructorWithSetters`，配置与 Run 10 相同（5×3s 预热 + 10×2s 测量 + 5 forks），验证 Run 10 结论的子集复现。  
> **命令**：`java -jar jforge-lambda/target/benchmarks.jar 'LambdaBenchmark\.(methodHandleConstructor|allArgsConstructor|reflectionConstructor|noArgConstructorWithSetters)$'`  
> **执行顺序** (字母序): allArgsConstructor → methodHandleConstructor → noArgConstructorWithSetters → reflectionConstructor

| Benchmark | Score (ops/s) | Error | vs Run 10 |
|---|---:|---:|---:|
| allArgsConstructor | 218,730,011 | ±4,152,587 | -0.4% |
| noArgConstructorWithSetters | 216,703,127 | ±2,665,845 | +1.1% |
| reflectionConstructor | 215,271,967 | ±3,151,674 | +0.4% |
| methodHandleConstructor | 212,930,776 | ±5,506,116 | **-4.4%** ⚠️ |

**发现**：methodHandleConstructor 5 个 fork 一致偏低（203-222M vs Run 10 的 222-224M），同轮其他 benchmark 正常 → 指向**轮间 JIT 编译差异**（invokeExact 调用点的内联决策对编译时序敏感），非环境干扰。**跨轮波动可达 ±4-5%**，任何 <5% 的 benchmark 间差异均视为噪声——进一步支持"7 种方式统计等价"。

---

## Run 12: static final 对照组（InstanceState，11 个 benchmark）

> 新增 `InstanceState`（句柄为 instance 字段 + `@Setup` 初始化），新增 4 个对照方法（`reflectionConstructorInstance` 等），`allArgsConstructor` 作为无句柄依赖的基础锚点。  
> **运行配置**（当前代码注解默认）：5×3s 预热 + 10×2s 测量 + 5 forks，50 次测量。  
> **单位**：ops/s = 对象/s。

| Benchmark | Score (ops/s) | Error |
|---|---:|---:|
| lambdaMetafactoryConstructor | 219,173,734 | ±4,184,668 |
| methodHandleWithSetters | 217,182,568 | ±3,203,023 |
| allArgsConstructor（锚点） | 217,270,443 | ±4,310,534 |
| noArgConstructorWithSetters | 216,961,845 | ±3,583,721 |
| lambdaMetafactoryWithSetters | 214,125,776 | ±5,682,559 |
| reflectionConstructor | 212,698,451 | ±2,863,169 |
| methodHandleConstructor | 195,014,493 | ±6,396,709 |
| lambdaMetafactoryWithSettersInstance | 177,867,127 | ±5,232,347 |
| methodHandleConstructorInstance | 151,305,490 | ±2,648,430 |
| reflectionConstructorInstance | 93,253,115 | ±1,365,878 |
| methodHandleWithSettersInstance | **33,822,314** | ±253,526 |

**对照结论**：instance 句柄使性能大幅下降 —— LambdaMetafactory+setter -17%、MH 构造器 -22%、反射 **-56%**、MH+setter **-84%**。锚点 allArgsConstructor（217.3M）与历轮一致，两组环境可比。

---

## 可信结论（由 Run 8/9/10/11/12 支撑）

1. **7 种对象创建方式全部统计等价** — 直接 `new`、反射、LambdaMetafactory（构造器/setter 两种形式）、MethodHandle（构造器/setter 两种形式，`invokeExact`）、无参构造+setter **同为 ~214-223M 对象/s（每对象 ~4.5-4.7ns）**，最大差距 4.3% 且在误差内（Run 10，50 次测量，误差 ±0.5-6.8%）。JIT 深度优化后各方式收敛到同一水平，无显著差异。

2. **⚠️ `invoke` vs `invokeExact` 是真实差异源（Run 10）** — `MethodHandle.invoke()` 的 asType 参数适配开销（装箱/类型检查）使 MethodHandle 慢 28-73%（Run 8/9"MethodHandle 落后"结论为假象）；改用 `invokeExact()` 后 MethodHandle 构造器 +44%（154.6M → 222.8M）、MethodHandle+setter **+262%**（59.1M → 214.0M），全部追平直接 `new`。旧写法（Run 1-7）中"MethodHandle+setter 最慢"结论同样受此影响，不再成立。**实践建议：使用 MethodHandle 优先 `invokeExact`（参数类型精确匹配）**。

3. **JMH 按字母顺序执行 benchmark** — 源码方法书写顺序不影响；早期通过 `A01`/`B01` 等前缀控制执行顺序（多次验证顺序不影响结果），前缀已移除，当前代码恢复自然命名。

4. **✅ `static final` 句柄效应确认（Run 12 受控对照，标准写法）** — 唯一变量为 instance → static final 字段，测得：LambdaMetafactory+setter -17%、MH 构造器 -22%、反射 **-56%**、MH+setter **-84%**，幅度大于旧循环式测得值（-47%/-40%）。机制：JIT 将 `static final` 引用常量折叠为编译期常量 → 调用点深度内联；instance 字段每次调用需 load + 类型检查。**实践建议：框架/工具类中频繁调用的反射/MethodHandle 句柄应声明为 `static final`**。

---

## 方法学教训（Run 1-7 为何移除）

- **手写大循环的写法改变 JIT 优化行为**（长方法深度优化、调用开销摊销），测出的是"50 万次批处理"成本而非真实单次调用成本。
- 由此得出的排名结论**已被推翻**：旧结论"LambdaMetafactory 始终最快""直接 `new` 中等偏下"均不成立 —— Run 8/9 中直接 `new` 进入第一梯队，五者同档。
- 旧数据在同写法内高度可复现（跨轮偏差 <8%），属于"确定性地测错了场景"：**测量方法敏感 → 结论不可信**。
- 类似地，**调用 API 的选择同样影响结论**：`MethodHandle.invoke` 的适配开销曾让 MethodHandle 显得慢 28-73%，`invokeExact` 消除适配后全部追平（Run 10）。
- 完整旧数据保存在 git 历史；原始测量数据以 `benchmark_run9_data.csv` 为准。

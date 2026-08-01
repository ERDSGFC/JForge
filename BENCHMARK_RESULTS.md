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

## 可信结论（由 Run 8/9 支撑）

1. **第一梯队五者统计等价** — 直接 `new`、反射、LambdaMetafactory（构造器/setter 两种形式）、无参构造+setter **同为 ~215M 对象/s（每对象 ~4.6ns）**，差距仅 1.4% 且在误差内（Run 9 误差 ±0.5-2%）。Run 8 中 B04 第 1、Run 9 中 B01 第 1，排名互换证实前五名无法区分。

2. **MethodHandle 构造器稳定落后 ~29%** — 154.6M 对象/s（每对象 ~6.5ns）。

3. **MethodHandle+setter 断层最慢，落后 ~73%** — 59.1M 对象/s（每对象 ~16.9ns）。该结论在旧写法（Run 1-7）与新写法（Run 8/9）下**均成立**，10 次 `invoke` 的固定开销是真实瓶颈。

4. **JMH 按字母顺序执行 benchmark** — 源码方法书写顺序不影响；通过 `A01`/`B01` 等前缀可控制执行顺序（旧轮次多次验证）。方法命名须保留前缀约定。

5. **`static final` 句柄存在优化效应，幅度待重测** — 旧轮次受控对照（唯一变量为 instance → static final 字段）显示反射 -47%、MH+setter -40%（JIT 常量折叠）；该幅度在循环式写法下测得，**标准写法下的幅度未验证**（可用 `InstanceState` 对照重测，思路见 git 历史中的 EXECUTION_STEPS.md Run 10 计划）。

---

## 方法学教训（Run 1-7 为何移除）

- **手写大循环的写法改变 JIT 优化行为**（长方法深度优化、调用开销摊销），测出的是"50 万次批处理"成本而非真实单次调用成本。
- 由此得出的排名结论**已被推翻**：旧结论"LambdaMetafactory 始终最快""直接 `new` 中等偏下"均不成立 —— Run 8/9 中直接 `new` 进入第一梯队，五者同档。
- 旧数据在同写法内高度可复现（跨轮偏差 <8%），属于"确定性地测错了场景"：**测量方法敏感 → 结论不可信**。
- 仅一条旧结论在两种写法下一致并保留：**MethodHandle+setter 最慢**（见可信结论 3）。
- 完整旧数据保存在 git 历史；新数据以 `benchmark_run9_data.csv` 为准。

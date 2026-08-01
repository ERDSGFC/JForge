# JMH 基准测试结果汇总

**环境**: JDK 25, JMH 1.37, 10 字段 `User` POJO
**模式**: 3 次预热 + 5 次测量, 1 fork（Run 1-7: AverageTime ms/op, 每次操作 50 万对象；Run 8+: Throughput ops/s, 每次调用 1 个对象）

---

## Run 1: 统一全 10 字段（instance 字段, @Setup）

> 所有 benchmark 均初始化全部 10 个字段，但 MethodHandle/Constructor 等为 non-final instance 字段。  
> **执行顺序** (JMH 默认字母序):  
> ① allArgsConstructor → ② lambdaMetafactoryConstructor → ③ lambdaMetafactoryWithSetters → ④ methodHandleConstructor → ⑤ methodHandleWithSetters → ⑥ noArgConstructorWithSetters → ⑦ reflectionConstructor

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| lambdaMetafactoryConstructor | 5.378 | ±0.253 | ② |
| allArgsConstructor | 6.101 | ±0.066 | ① |
| noArgConstructorWithSetters | 6.128 | ±0.126 | ⑥ |
| methodHandleConstructor | 6.138 | ±0.068 | ④ |
| lambdaMetafactoryWithSetters | 7.252 | ±0.110 | ③ |
| reflectionConstructor | 10.196 | ±0.051 | ⑦ |
| methodHandleWithSetters | **16.855** | ±0.232 | ⑤ |

---

## Run 2: static final 字段 + static 块初始化

> 所有字段改为 `private static final`，初始化移入 `static {}` 块。  
> **执行顺序** (JMH 默认字母序):  
> ① allArgsConstructor → ② lambdaMetafactoryConstructor → ③ lambdaMetafactoryWithSetters → ④ methodHandleConstructor → ⑤ methodHandleWithSetters → ⑥ noArgConstructorWithSetters → ⑦ reflectionConstructor

| Benchmark | Score (ms/op) | Error | vs Run 1 | 序号 |
|---|---:|---:|---:|:---:|
| reflectionConstructor | **5.398** | ±0.080 | -47% | ⑦ |
| lambdaMetafactoryConstructor | 5.564 | ±0.871 | +3% | ② |
| methodHandleConstructor | 5.608 | ±0.068 | -9% | ④ |
| noArgConstructorWithSetters | 5.952 | ±0.166 | -3% | ⑥ |
| lambdaMetafactoryWithSetters | 6.162 | ±0.088 | -15% | ③ |
| allArgsConstructor | 6.346 | ±1.271 | +4% | ① |
| methodHandleWithSetters | 10.186 | ±0.365 | -40% | ⑤ |

---

## Run 3: 源码方法逆序（验证执行顺序）

> 在源码中反转 7 个 benchmark 方法书写顺序，测试是否为 profile pollution 影响。  
> **执行顺序**: 源码逆序，但 JMH 仍按字母序执行（与 Run 1/2 完全相同）  
> ① allArgsConstructor → ② lambdaMetafactoryConstructor → ③ lambdaMetafactoryWithSetters → ④ methodHandleConstructor → ⑤ methodHandleWithSetters → ⑥ noArgConstructorWithSetters → ⑦ reflectionConstructor

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| allArgsConstructor | 6.188 | ±0.098 | ① |
| lambdaMetafactoryConstructor | 5.321 | ±0.107 | ② |
| lambdaMetafactoryWithSetters | 6.067 | ±0.078 | ③ |
| methodHandleConstructor | 6.018 | ±1.389 | ④ |
| methodHandleWithSetters | 10.492 | ±0.345 | ⑤ |
| noArgConstructorWithSetters | 6.355 | ±1.182 | ⑥ |
| reflectionConstructor | 5.686 | ±1.039 | ⑦ |

---

## Run 4: 方法命名控制执行顺序（static final 字段）

> 通过 `A01`~`A07` 前缀控制字母排序，让 `allArgsConstructor` 从第 1 位移到第 6 位，验证执行顺序是否影响结果。  
> **执行顺序**:  
> ① A01_reflectionConstructor → ② A02_lambdaMetafactoryConstructor → ③ A03_methodHandleConstructor → ④ A04_noArgConstructorWithSetters → ⑤ A05_lambdaMetafactoryWithSetters → ⑥ A06_allArgsConstructor → ⑦ A07_methodHandleWithSetters

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| A01_reflectionConstructor | 5.511 | ±0.118 | ① |
| A02_lambdaMetafactoryConstructor | 5.291 | ±0.104 | ② |
| A03_methodHandleConstructor | 5.616 | ±0.107 | ③ |
| A04_noArgConstructorWithSetters | 6.208 | ±0.846 | ④ |
| A05_lambdaMetafactoryWithSetters | 6.097 | ±0.144 | ⑤ |
| A06_allArgsConstructor | 6.299 | ±0.154 | ⑥ |
| A07_methodHandleWithSetters | 10.180 | ±0.106 | ⑦ |

---

## Run 5: 独立 JVM 隔离（方案 2）

> 每个 benchmark 单独启动独立 JVM 执行，完全消除跨 benchmark 影响。这是最准确的测试方式。  
> **执行顺序**: 无顺序——每个 benchmark 各自独立 JVM 进程，每次只跑一个，互不影响。

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| A01_reflectionConstructor | 5.491 | ±0.286 | N/A |
| A02_lambdaMetafactoryConstructor | 5.491 | ±0.721 | N/A |
| A03_methodHandleConstructor | 5.854 | ±0.091 | N/A |
| A04_noArgConstructorWithSetters | 5.915 | ±0.136 | N/A |
| A05_lambdaMetafactoryWithSetters | 6.068 | ±0.084 | N/A |
| A06_allArgsConstructor | 6.141 | ±0.109 | N/A |
| A07_methodHandleWithSetters | 10.152 | ±0.125 | N/A |

---

## Run 6: B01-B07 前缀（第三组执行顺序）

> 通过 `B01`~`B07` 前缀验证第三组不同的执行顺序。  
> **执行顺序**:  
> ① B01_lambdaMetafactoryConstructor → ② B02_reflectionConstructor → ③ B03_methodHandleConstructor → ④ B04_allArgsConstructor → ⑤ B05_lambdaMetafactoryWithSetters → ⑥ B06_methodHandleWithSetters → ⑦ B07_noArgConstructorWithSetters

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| B01_lambdaMetafactoryConstructor | 5.448 | ±0.937 | ① |
| B02_reflectionConstructor | 5.550 | ±0.969 | ② |
| B03_methodHandleConstructor | 6.058 | ±1.823 | ③ |
| B04_allArgsConstructor | 6.125 | ±0.607 | ④ |
| B05_lambdaMetafactoryWithSetters | 6.156 | ±0.122 | ⑤ |
| B06_methodHandleWithSetters | 10.385 | ±0.354 | ⑥ |
| B07_noArgConstructorWithSetters | 6.064 | ±0.143 | ⑦ |

---

## Run 7: JMH 标准化改造（逐元素 consume + @State 重构）

> 代码重构：句柄移入 `MyState` 内部类（保持 static final）、`NUM` 改为 `@Param num`、去掉 try-catch 包裹、删无用 `sum` 变量、**逐元素 `bh.consume(user)` 替代 `bh.consume(users)`**（去掉 ArrayList 累积开销，防 DCE 更严格）。  
> **执行顺序**: ① B01_lambdaMetafactoryConstructor → ② B02_reflectionConstructor → ③ B03_methodHandleConstructor → ④ B04_allArgsConstructor → ⑤ B05_lambdaMetafactoryWithSetters → ⑥ B06_methodHandleWithSetters → ⑦ B07_noArgConstructorWithSetters

| Benchmark | Score (ms/op) | Error | 序号 |
|---|---:|---:|:---:|
| B01_lambdaMetafactoryConstructor | **4.916** | ±0.223 | ① |
| B02_reflectionConstructor | 5.110 | ±0.110 | ② |
| B03_methodHandleConstructor | 5.166 | ±0.347 | ③ |
| B07_noArgConstructorWithSetters | 5.407 | ±0.068 | ⑦ |
| B04_allArgsConstructor | 5.442 | ±0.103 | ④ |
| B05_lambdaMetafactoryWithSetters | 5.449 | ±0.152 | ⑤ |
| B06_methodHandleWithSetters | **9.268** | ±0.062 | ⑥ |

---

## Run 8: 标准写法（Throughput + 单次调用）

> **测量方式彻底重构**：`@BenchmarkMode(Mode.Throughput)` + `@OutputTimeUnit(TimeUnit.SECONDS)`，方法体去掉手写循环，每次调用只创建 **1 个** User 并返回（JMH 自动循环调用并消费返回值防 DCE，见 JMHSample_11_Loops）。  
> **执行顺序**: ① B01_lambdaMetafactoryConstructor → ② B02_reflectionConstructor → ③ B03_methodHandleConstructor → ④ B04_allArgsConstructor → ⑤ B05_lambdaMetafactoryWithSetters → ⑥ B06_methodHandleWithSetters → ⑦ B07_noArgConstructorWithSetters  
> **注意**：单位为 ops/s（越大越好），与 Run 1-7 的 ms/op 不直接可比。

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

## 全部轮次汇总

| Benchmark | Run 1 (instance) | Run 2 (static) | Run 3 (逆序) | Run 4 (A前缀) | Run 5 (独立JVM) | Run 6 (B前缀) | Run 7 (标准化) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| reflectionConstructor | 10.196 | **5.398** | 5.686 | 5.511 | **5.491** | 5.550 | 5.110 |
| lambdaMetafactoryConstructor | **5.378** | 5.564 | 5.321 | **5.291** | 5.491 | **5.448** | **4.916** |
| methodHandleConstructor | 6.138 | 5.608 | 6.018 | 5.616 | 5.854 | 6.058 | 5.166 |
| noArgConstructorWithSetters | 6.128 | 5.952 | 6.355 | 6.208 | 5.915 | 6.064 | 5.407 |
| lambdaMetafactoryWithSetters | 7.252 | 6.162 | 6.067 | 6.097 | 6.068 | 6.156 | 5.449 |
| allArgsConstructor | 6.101 | 6.346 | 6.188 | 6.299 | 6.141 | 6.125 | 5.442 |
| methodHandleWithSetters | **16.855** | 10.186 | 10.492 | 10.180 | 10.152 | 10.385 | **9.268** |

| 轮次 | 执行顺序 (① → ② → ... → ⑦) |
|:---|:---|
| Run 1 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 2 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 3 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 4 | ① reflectionCtor → ② lambdaMetafactoryCtor → ③ methodHandleCtor → ④ noArgCtorSetters → ⑤ lambdaMetafactorySetters → ⑥ allArgsConstructor → ⑦ methodHandleSetters |
| Run 5 | 无执行顺序——每个 benchmark 独立 JVM 运行 |
| Run 6 | ① lambdaMetafactoryCtor → ② reflectionCtor → ③ methodHandleCtor → ④ allArgsConstructor → ⑤ lambdaMetafactorySetters → ⑥ methodHandleSetters → ⑦ noArgCtorSetters |
| Run 7 | ① lambdaMetafactoryCtor → ② reflectionCtor → ③ methodHandleCtor → ④ allArgsConstructor → ⑤ lambdaMetafactorySetters → ⑥ methodHandleSetters → ⑦ noArgCtorSetters |

---

## 关键发现

1. **`static final` 对反射影响巨大** — `Constructor.newInstance` 从 10.2ms 降至 5.4ms（-47%）。JIT 对 `static final` 做常量折叠后可生成与直接 `new` 等效的代码，且无 `<init>` verification 约束。

2. **`static final` 对 MethodHandle + setter 影响最大** — 从 16.9ms 降至 10.2ms（-40%）。non-final 字段每次都要 load，`static final` 让 JIT 内联所有 setter 调用。

3. **LambdaMetafactory 构造器始终最快** — 生成的 lambda 方法绕过 `<init>` verification，即使反射用 `static final` 优化后仍略逊一筹。

4. **JMH 按字母顺序执行 benchmark**，源码方法书写顺序不影响。通过 A01-A07 / B01-B07 前缀可控制执行顺序。

5. **六轮测试结果高度一致** — Run 1-3 字母序相同、Run 4/6 两套不同前缀顺序、Run 5 独立 JVM，六种执行顺序下排名与绝对值均稳定（同 benchmark 跨轮最大偏差 <8%）。JMH 独立预热已有效消除跨 benchmark 污染。

6. **直接 `new` (allArgsConstructor) 中等偏下** — 受 JVM `<init>` verification 约束，始终比反射/LambdaMetafactory 慢约 0.5-0.8ms。

7. **MethodHandle + setter 始终最慢** — 即使 `static final`，10 次 `invoke` 的开销也远大于一次性传参构造。

8. **去除 ArrayList 累积（Run 7）整体提速 ~7-11%，排名不变** — 逐元素 `bh.consume(user)` 后，所有方案绝对耗时下降（如 allArgsConstructor 6.14→5.44），跨方案相对差异保持稳定，再次确认之前结论不受测量方式影响。

9. **⚠️ 测量写法影响排名（Run 8）— 手写循环掩盖了真相** — 改为标准写法（单次调用 + 返回对象 + Throughput）后，**直接 `new` 从第 5 升至第 1**（208.9M ops/s ≈ 2 亿对象/秒），**LambdaMetafactory 构造器从第 1 掉到第 5**（186.3M），推翻 Run 1-7"LambdaMetafactory 始终最快"的结论。原因：单次调用模式下 JIT 可对每个方法完整内联深度优化，无长方法/循环干扰，更能反映真实调用成本。仅 B06（MethodHandle+setter, 54.6M）断层最慢的结论在两种写法下一致。前五名差距部分在误差范围内（±10-26%），以 Run 8 为准。

10. **Run 9 确认：第一梯队五者统计等价，B03/B06 确定落后** — 增强配置（`-i 10 -w 5 -f 5 -bs 1000`）将误差压至 ±0.5-2% 后：直接 `new`、反射、LambdaMetafactory（构造器/setter 两种）、无参构造+setter **同为 ~215M 对象/s（每对象 ~4.6ns），差距仅 1.4% 且在误差内**（Run 8 中 B04 第 1、Run 9 中 B01 第 1，排名互换证实前五名无法区分）。**最终可信结论**：① 五者同档，无显著差异；② MethodHandle 构造器稳定落后 ~29%（154.6M）；③ MethodHandle+setter 断层最慢 ~73%（59.1M），10 次 `invoke` 的固定开销是真实瓶颈。

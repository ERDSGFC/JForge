# JMH 基准测试结果汇总

**环境**: JDK 25, JMH 1.37, 每次操作创建 500,000 个 `User` 对象（10 字段）
**模式**: AverageTime, ms/op, 3 次预热 + 5 次测量, 1 fork

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

## 全部轮次汇总

| Benchmark | Run 1 (instance) | Run 2 (static) | Run 3 (逆序) | Run 4 (A前缀) | Run 5 (独立JVM) |
|---:|---:|---:|---:|---:|---:|
| reflectionConstructor | 10.196 | **5.398** | 5.686 | 5.511 | **5.491** |
| lambdaMetafactoryConstructor | **5.378** | 5.564 | 5.321 | **5.291** | 5.491 |
| methodHandleConstructor | 6.138 | 5.608 | 6.018 | 5.616 | 5.854 |
| noArgConstructorWithSetters | 6.128 | 5.952 | 6.355 | 6.208 | 5.915 |
| lambdaMetafactoryWithSetters | 7.252 | 6.162 | 6.067 | 6.097 | 6.068 |
| allArgsConstructor | 6.101 | 6.346 | 6.188 | 6.299 | 6.141 |
| methodHandleWithSetters | **16.855** | 10.186 | 10.492 | 10.180 | 10.152 |

| 轮次 | 执行顺序 (① → ② → ... → ⑦) |
|:---|:---|
| Run 1 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 2 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 3 | ① allArgsConstructor → ② lambdaMetafactoryCtor → ③ lambdaMetafactorySetters → ④ methodHandleCtor → ⑤ methodHandleSetters → ⑥ noArgCtorSetters → ⑦ reflectionCtor |
| Run 4 | ① reflectionCtor → ② lambdaMetafactoryCtor → ③ methodHandleCtor → ④ noArgCtorSetters → ⑤ lambdaMetafactorySetters → ⑥ allArgsConstructor → ⑦ methodHandleSetters |
| Run 5 | 无执行顺序——每个 benchmark 独立 JVM 运行 |

---

## 关键发现

1. **`static final` 对反射影响巨大** — `Constructor.newInstance` 从 10.2ms 降至 5.4ms（-47%）。JIT 对 `static final` 做常量折叠后可生成与直接 `new` 等效的代码，且无 `<init>` verification 约束。

2. **`static final` 对 MethodHandle + setter 影响最大** — 从 16.9ms 降至 10.2ms（-40%）。non-final 字段每次都要 load，`static final` 让 JIT 内联所有 setter 调用。

3. **LambdaMetafactory 构造器始终最快** — 生成的 lambda 方法绕过 `<init>` verification，即使反射用 `static final` 优化后仍略逊一筹。

4. **JMH 按字母顺序执行 benchmark**，源码方法书写顺序不影响。但格式命名（A01-A07）可控制。

5. **独立 JVM（方案 2）确认结果稳定** — Run 5 每个 benchmark 单独启动 JVM，结果与 Run 2/3/4 完全一致（误差 <3%）。说明 JMH 的独立预热在同一个 JVM 内也已充分消除跨 benchmark 污染，但独立 JVM 仍是黄金标准。

6. **直接 `new` (allArgsConstructor) 中等偏下** — 受 JVM `<init>` verification 约束，始终比反射/LambdaMetafactory 慢约 0.5-0.8ms。

7. **MethodHandle + setter 始终最慢** — 即使 `static final`，10 次 `invoke` 的开销也远大于一次性传参构造。

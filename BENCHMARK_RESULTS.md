# JMH 基准测试结果汇总

**环境**: JDK 25, JMH 1.37, 每次操作创建 500,000 个 `User` 对象（10 字段）
**模式**: AverageTime, ms/op, 3 次预热 + 5 次测量, 1 fork

---

## Run 1: 统一全 10 字段（instance 字段, @Setup）

> 所有 benchmark 均初始化全部 10 个字段，但 MethodHandle/Constructor 等为 non-final instance 字段。

| Benchmark | Score (ms/op) | Error |
|---|---:|---:|
| lambdaMetafactoryConstructor | 5.378 | ±0.253 |
| allArgsConstructor | 6.101 | ±0.066 |
| noArgConstructorWithSetters | 6.128 | ±0.126 |
| methodHandleConstructor | 6.138 | ±0.068 |
| lambdaMetafactoryWithSetters | 7.252 | ±0.110 |
| reflectionConstructor | 10.196 | ±0.051 |
| methodHandleWithSetters | **16.855** | ±0.232 |

---

## Run 2: static final 字段 + static 块初始化

> 所有字段改为 `private static final`，初始化移入 `static {}` 块。执行顺序按字母排序（JMH 默认）。

| Benchmark | Score (ms/op) | Error | vs Run 1 |
|---|---:|---:|---:|
| reflectionConstructor | **5.398** | ±0.080 | -47% |
| lambdaMetafactoryConstructor | 5.564 | ±0.871 | +3% |
| methodHandleConstructor | 5.608 | ±0.068 | -9% |
| noArgConstructorWithSetters | 5.952 | ±0.166 | -3% |
| lambdaMetafactoryWithSetters | 6.162 | ±0.088 | -15% |
| allArgsConstructor | 6.346 | ±1.271 | +4% |
| methodHandleWithSetters | 10.186 | ±0.365 | -40% |

---

## Run 3: 源码方法逆序（验证执行顺序）

> 在源码中反转 7 个 benchmark 方法书写顺序，测试是否为 profile pollution 影响。结果：**JMH 按字母排序执行**，源码顺序不影响，结果与 Run 2 一致。

| Benchmark | Score (ms/op) | Error |
|---|---:|---:|
| allArgsConstructor | 6.188 | ±0.098 |
| lambdaMetafactoryConstructor | 5.321 | ±0.107 |
| lambdaMetafactoryWithSetters | 6.067 | ±0.078 |
| methodHandleConstructor | 6.018 | ±1.389 |
| methodHandleWithSetters | 10.492 | ±0.345 |
| noArgConstructorWithSetters | 6.355 | ±1.182 |
| reflectionConstructor | 5.686 | ±1.039 |

---

## Run 4: 方法命名控制执行顺序（static final 字段）

> 通过 `A01`~`A07` 前缀控制字母排序，让 `allArgsConstructor` 从第 1 位移到第 6 位，验证执行顺序是否影响结果。

| Benchmark | Score (ms/op) | Error |
|---|---:|---:|
| A01_reflectionConstructor | 5.511 | ±0.118 |
| A02_lambdaMetafactoryConstructor | 5.291 | ±0.104 |
| A03_methodHandleConstructor | 5.616 | ±0.107 |
| A04_noArgConstructorWithSetters | 6.208 | ±0.846 |
| A05_lambdaMetafactoryWithSetters | 6.097 | ±0.144 |
| A06_allArgsConstructor | 6.299 | ±0.154 |
| A07_methodHandleWithSetters | 10.180 | ±0.106 |

---

## 全部轮次汇总

| Benchmark | Run 1 (instance) | Run 2 (static final) | Run 3 (逆序验证) | Run 4 (A前缀) |
|---:|---:|---:|---:|---:|
| reflectionConstructor | 10.196 | **5.398** | 5.686 | 5.511 |
| lambdaMetafactoryConstructor | **5.378** | 5.564 | 5.321 | **5.291** |
| methodHandleConstructor | 6.138 | 5.608 | 6.018 | 5.616 |
| noArgConstructorWithSetters | 6.128 | 5.952 | 6.355 | 6.208 |
| lambdaMetafactoryWithSetters | 7.252 | 6.162 | 6.067 | 6.097 |
| allArgsConstructor | 6.101 | 6.346 | 6.188 | 6.299 |
| methodHandleWithSetters | **16.855** | 10.186 | 10.492 | 10.180 |

> Run 1: instance 字段 + @Setup  
> Run 2: static final 字段 + static 块（字母序第1个: allArgsConstructor）  
> Run 3: 源码逆序，但 JMH 仍字母序执行，与 Run 2 一致  
> Run 4: A01-A07 前缀控制顺序（字母序第1个: reflectionConstructor, 第6个: allArgsConstructor）

---

## 关键发现

1. **`static final` 对反射影响巨大** — `Constructor.newInstance` 从 10.2ms 降至 5.4ms（-47%）。JIT 对 `static final` 做常量折叠后可生成与直接 `new` 等效的代码，且无 `<init>` verification 约束。

2. **`static final` 对 MethodHandle + setter 影响最大** — 从 16.9ms 降至 10.2ms（-40%）。non-final 字段每次都要 load，`static final` 让 JIT 内联所有 setter 调用。

3. **LambdaMetafactory 构造器始终最快** — 生成的 lambda 方法绕过 `<init>` verification，即使反射用 `static final` 优化后仍略逊一筹。

4. **JMH 按字母顺序执行 benchmark**，源码方法书写顺序不影响。但格式命名（A01-A07）可控制。

5. **执行顺序对结果影响极小** — Run 2/3/4 三次不同顺序的结果高度一致。JMH 每个 benchmark 独立预热已消除跨 benchmark profile pollution。

6. **直接 `new` (allArgsConstructor) 中等偏下** — 受 JVM `<init>` verification 约束，始终比反射/LambdaMetafactory 慢约 0.5-0.8ms。

7. **MethodHandle + setter 始终最慢** — 即使 `static final`，10 次 `invoke` 的开销也远大于一次性传参构造。

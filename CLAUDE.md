# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Compile all modules
mvn clean compile

# Package lambda module (generates uber-jar with JMH benchmarks)
mvn clean package -pl lambda

# Run all benchmarks
java -jar lambda/target/benchmarks.jar

# Run selected benchmarks (regex patterns, full JMH CLI via jmh.Main entry point)
java -jar lambda/target/benchmarks.jar 'LambdaBenchmark\.B0[1-3].*'

# Run with overridden config + GC profiling
java -jar lambda/target/benchmarks.jar LambdaBenchmark.B04 -i 10 -w 5 -f 3 -prof gc
```

## Project Architecture

Multi-module Maven project (`com.qin:benchmark`) benchmarking JVM object-creation strategies on Java 25 (JMH 1.37). Each benchmark call creates **one** `User` (10 fields); JMH loops over the calls per iteration and consumes the returned object.

### Key Files

- **`LambdaBenchmark.java`** — Single class containing everything: 7 `@Benchmark` methods (`B01`–`B07`), the package-private `@FunctionalInterface NewUser<T>`, and the `MyState` handle container. A `main()` is retained for direct invocation, but the jar entry point is `org.openjdk.jmh.Main`. Method names are **deliberately prefixed `B01`–`B07` to control execution order**: JMH runs benchmarks in alphabetical name order, not source order (evidence in BENCHMARK_RESULTS.md Runs 3/4/6). Keep the prefix scheme when adding or renaming benchmarks.
  - All constructors/handles live in `MyState` as `static final`, initialized in a `static {}` block (any failure → `ExceptionInInitializerError`). This was a major optimization: JIT constant-folds `static final` references (Run 2, e.g. reflection −47%).
  - Benchmark methods do **one** creation and return the `User` — no manual loops (JMH consumes the return value to prevent DCE, see JMHSample_11_Loops). Mode is `Throughput` (ops/s, see Run 8); previously used a 500k-object loop under `AverageTime` (Runs 1–7), which measurably changed rankings.
  - `LambdaMetafactory.metafactory` generates `NewUser` (all-args constructor), `Supplier` (no-arg constructor), and 10 `BiConsumer` setters via the private `createBiConsumer` helper in `MyState`.
- **`User.java`** — Plain POJO, 10 fields with getters/setters, no-arg + all-args constructors.
- **`BENCHMARK_RESULTS.md`** — Results ledger. Append new runs in the established format: run section noting execution order, per-benchmark score table, update the "全部轮次汇总" cross-run summary, and add findings to "关键发现". Current conclusion (Run 8, standard JMH style): direct `new`, reflection, and LambdaMetafactory are roughly equivalent (~190-210M ops/s); MethodHandle+setters is by far the slowest (~55M ops/s). Note Runs 1-7 (ms/op, 500k-loop style) are not directly comparable with Run 8 (ops/s, single-call style).

### Build Details

- Root POM manages JMH 1.37 in `dependencyManagement` with `scope>test</scope>`; the `lambda` module overrides to `compile`.
- `maven-compiler-plugin` explicitly declares the JMH annotation processor (`jmh-generator-annprocess`) because JDK 23+ disables automatic annotation processing.
- `maven-shade-plugin` produces `lambda/target/benchmarks.jar` (uber-jar with `org.openjdk.jmh.Main` as main class, enabling the full JMH CLI: `-i/-w/-f/-prof/-p/-l` etc.).

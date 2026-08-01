/
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Compile all modules
mvn clean compile

# Package lambda module (generates uber-jar with JMH benchmarks)
mvn clean package -pl lambda

# Run all benchmarks
java -jar lambda/target/benchmarks.jar

# Run selected benchmarks (main() forwards each arg to OptionsBuilder.include)
java -jar lambda/target/benchmarks.jar LambdaBenchmark.B04_allArgsConstructor LambdaBenchmark.B05_lambdaMetafactoryWithSetters
```

## Project Architecture

Multi-module Maven project (`com.qin:benchmark`) benchmarking JVM object-creation strategies on Java 25 (JMH 1.37). Each operation creates 500,000 `User` objects (10 fields) — see `NUM` in `LambdaBenchmark`.

### Key Files

- **`LambdaBenchmark.java`** — Single class containing everything: 7 `@Benchmark` methods (`B01`–`B07`), the package-private `@FunctionalInterface NewUser<T>`, and `main()`. Method names are **deliberately prefixed `B01`–`B07` to control execution order**: JMH runs benchmarks in alphabetical name order, not source order (evidence in BENCHMARK_RESULTS.md Runs 3/4/6). Keep the prefix scheme when adding or renaming benchmarks.
  - All constructors/handles are `static final`, initialized in a `static {}` block (any failure → `ExceptionInInitializerError`). This was a major optimization: JIT constant-folds `static final` references (Run 2, e.g. reflection −47%).
  - `LambdaMetafactory.metafactory` generates `NewUser` (all-args constructor), `Supplier` (no-arg constructor), and 10 `BiConsumer` setters via the private `createBiConsumer` helper.
- **`User.java`** — Plain POJO, 10 fields with getters/setters, no-arg + all-args constructors.
- **`BENCHMARK_RESULTS.md`** — Results ledger. Append new runs in the established format: run section noting execution order, per-benchmark score table, update the "全部轮次汇总" cross-run summary, and add findings to "关键发现". Key conclusion so far: LambdaMetafactory constructor is fastest; direct `new` is mid-pack; MethodHandle+setters is slowest.

### Build Details

- Root POM manages JMH 1.37 in `dependencyManagement` with `scope>test</scope>`; the `lambda` module overrides to `compile`.
- `maven-compiler-plugin` explicitly declares the JMH annotation processor (`jmh-generator-annprocess`) because JDK 23+ disables automatic annotation processing.
- `maven-shade-plugin` produces `lambda/target/benchmarks.jar` (uber-jar with `LambdaBenchmark` as main class).

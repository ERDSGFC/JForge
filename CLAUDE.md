# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Compile all modules
mvn clean compile

# Package lambda module (generates uber-jar with JMH benchmarks)
mvn clean package -pl lambda

# Run JMH benchmarks (all)
java -jar lambda/target/benchmarks.jar

# Run a single benchmark
java -jar lambda/target/benchmarks.jar LambdaBenchmark.allArgsConstructor

# Run original ad-hoc timing (not JMH)
mvn exec:java -pl lambda -Dexec.mainClass="com.qin.LambdaTest"
```

## Project Architecture

Multi-module Maven project (`com.qin:benchmark`) benchmarking JVM object-creation strategies on Java 25.

### Modules

- **`lambda`** — Benchmarks different ways to instantiate and populate a `User` POJO (~10 fields).

### Key Files

- **`LambdaBenchmark.java`** — JMH benchmark class with 7 `@Benchmark` methods covering:
  - Direct constructor calls (all-args, no-arg + setters)
  - Reflection (`Constructor.newInstance`)
  - `MethodHandles` (constructor only, constructor + setter handles)
  - `LambdaMetafactory` (generating `NewUser` interface, `Supplier` + `BiConsumer` setters)
- **`LambdaTest.java`** — Original ad-hoc timing class using `CalculateTime(Runnable)`. Retained for comparison but not JMH-based.
- **`User.java`** — Standalone POJO (extracted from `LambdaTest` inner class).

### Build Details

- Root POM manages JMH 1.37 dependencies in `dependencyManagement` with `scope>test</scope>`. The `lambda` module overrides to `compile` scope.
- `maven-compiler-plugin` explicitly declares the JMH annotation processor (`jmh-generator-annprocess`) because JDK 23+ disables automatic annotation processing.
- `maven-shade-plugin` produces `lambda/target/benchmarks.jar` (uber-jar with `LambdaBenchmark` as entry point).

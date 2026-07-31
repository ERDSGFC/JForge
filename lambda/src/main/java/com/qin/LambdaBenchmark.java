package com.qin;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

@FunctionalInterface
interface NewUser<T> {
    T apply(Long id, String name, Integer status);
}

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class LambdaBenchmark {

    private static final int NUM = 50_0000;

    // ===== Reflection =====
    private Constructor<User> allArgsConstructor;

    // ===== MethodHandle =====
    private MethodHandle mhConstructor3Arg;
    private MethodHandle mhNoArgConstructor;
    private MethodHandle mhSetId;
    private MethodHandle mhSetName;
    private MethodHandle mhSetStatus;

    // ===== LambdaMetafactory for constructor =====
    private NewUser<User> lambdaFactory;

    // ===== LambdaMetafactory for Supplier + BiConsumer =====
    private Supplier<User> lambdaSupplier;
    private BiConsumer<User, Long> lambdaSetId;
    private BiConsumer<User, String> lambdaSetName;
    private BiConsumer<User, Integer> lambdaSetStatus;

    @Setup
    public void setup() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // -- Reflection --
        allArgsConstructor = User.class.getConstructor(
                Long.class, String.class, Integer.class, String.class, Integer.class,
                LocalDate.class, String.class, Integer.class, String.class, String.class);

        // -- MethodHandle constructors --
        mhConstructor3Arg = lookup.findConstructor(User.class,
                MethodType.methodType(void.class, Long.class, String.class, Integer.class));
        mhNoArgConstructor = lookup.findConstructor(User.class,
                MethodType.methodType(void.class));

        // -- MethodHandle setters --
        mhSetId = lookup.findVirtual(User.class, "setId",
                MethodType.methodType(void.class, Long.class));
        mhSetName = lookup.findVirtual(User.class, "setName",
                MethodType.methodType(void.class, String.class));
        mhSetStatus = lookup.findVirtual(User.class, "setStatus",
                MethodType.methodType(void.class, Integer.class));

        // -- LambdaMetafactory: NewUser<User> (3-arg constructor via interface) --
        MethodType ifaceMethodType = MethodType.methodType(
                Object.class, Long.class, String.class, Integer.class);
        CallSite ctorSite = LambdaMetafactory.metafactory(
                lookup, "apply",
                MethodType.methodType(NewUser.class),
                ifaceMethodType,
                mhConstructor3Arg,
                MethodType.methodType(User.class, Long.class, String.class, Integer.class));
        lambdaFactory = (NewUser<User>) ctorSite.getTarget().invokeExact();

        // -- LambdaMetafactory: Supplier<User> (no-arg constructor) --
        MethodType supplierIfaceType = MethodType.methodType(Object.class);
        CallSite supplierSite = LambdaMetafactory.metafactory(
                lookup, "get",
                MethodType.methodType(Supplier.class),
                supplierIfaceType,
                mhNoArgConstructor,
                MethodType.methodType(User.class));
        lambdaSupplier = (Supplier<User>) supplierSite.getTarget().invokeExact();

        // -- LambdaMetafactory: BiConsumer setters --
        MethodType biconsumerErased = MethodType.methodType(void.class, Object.class, Object.class);

        CallSite setIdSite = LambdaMetafactory.metafactory(
                lookup, "accept",
                MethodType.methodType(BiConsumer.class),
                biconsumerErased, mhSetId,
                MethodType.methodType(void.class, User.class, Long.class));
        lambdaSetId = (BiConsumer<User, Long>) setIdSite.getTarget().invokeExact();

        CallSite setNameSite = LambdaMetafactory.metafactory(
                lookup, "accept",
                MethodType.methodType(BiConsumer.class),
                biconsumerErased, mhSetName,
                MethodType.methodType(void.class, User.class, String.class));
        lambdaSetName = (BiConsumer<User, String>) setNameSite.getTarget().invokeExact();

        CallSite setStatusSite = LambdaMetafactory.metafactory(
                lookup, "accept",
                MethodType.methodType(BiConsumer.class),
                biconsumerErased, mhSetStatus,
                MethodType.methodType(void.class, User.class, Integer.class));
        lambdaSetStatus = (BiConsumer<User, Integer>) setStatusSite.getTarget().invokeExact();
    }

    // ==================== Benchmark methods ====================

    @Benchmark
    public void allArgsConstructor(Blackhole bh) {
        ArrayList<User> users = new ArrayList<>(NUM);
        long sum = 0;
        for (int i = 0; i < NUM; i++) {
            users.add(new User(sum + i, "heihei", i, "17374957973", i,
                    LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
        }
        bh.consume(users);
    }

    @Benchmark
    public void noArgConstructorWithSetters(Blackhole bh) {
        ArrayList<User> users = new ArrayList<>(NUM);
        long sum = 0;
        for (int i = 0; i < NUM; i++) {
            User user = new User();
            user.setId(sum + i);
            user.setName("heihei");
            user.setStatus(i);
            user.setMobile("17374957973");
            user.setAge(i);
            user.setBirthday(LocalDate.MAX);
            user.setIntroduction("introduction");
            user.setSex(1);
            user.setCardID("17374957973");
            user.setAddress("17374957973");
            users.add(user);
        }
        bh.consume(users);
    }

    @Benchmark
    public void reflectionConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add(allArgsConstructor.newInstance(sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
            }
            bh.consume(users);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void methodHandleConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add((User) mhConstructor3Arg.invoke(sum + i, "heihei", i));
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void methodHandleWithSetters(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                User user = (User) mhNoArgConstructor.invoke();
                mhSetId.invoke(user, sum + 1);
                mhSetName.invoke(user, "heihei");
                mhSetStatus.invoke(user, i);
                users.add(user);
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void lambdaMetafactoryConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add(lambdaFactory.apply(sum + i, "heihei", i));
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void lambdaMetafactoryWithSetters(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                User user = lambdaSupplier.get();
                lambdaSetId.accept(user, sum + i);
                lambdaSetName.accept(user, "heihei");
                lambdaSetStatus.accept(user, i);
                users.add(user);
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== Main ====================

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(LambdaBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}

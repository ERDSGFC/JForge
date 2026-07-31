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
    T apply(Long id, String name, Integer status, String mobile, Integer age,
            LocalDate birthday, String introduction, Integer sex, String cardID, String address);
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
    private MethodHandle mhConstructor10Arg;
    private MethodHandle mhNoArgConstructor;
    private MethodHandle mhSetId;
    private MethodHandle mhSetName;
    private MethodHandle mhSetStatus;
    private MethodHandle mhSetMobile;
    private MethodHandle mhSetAge;
    private MethodHandle mhSetBirthday;
    private MethodHandle mhSetIntroduction;
    private MethodHandle mhSetSex;
    private MethodHandle mhSetCardID;
    private MethodHandle mhSetAddress;

    // ===== LambdaMetafactory for 10-arg constructor =====
    private NewUser<User> lambdaFactory;

    // ===== LambdaMetafactory for Supplier + BiConsumer setters =====
    private Supplier<User> lambdaSupplier;
    private BiConsumer<User, Long> lambdaSetId;
    private BiConsumer<User, String> lambdaSetName;
    private BiConsumer<User, Integer> lambdaSetStatus;
    private BiConsumer<User, String> lambdaSetMobile;
    private BiConsumer<User, Integer> lambdaSetAge;
    private BiConsumer<User, LocalDate> lambdaSetBirthday;
    private BiConsumer<User, String> lambdaSetIntroduction;
    private BiConsumer<User, Integer> lambdaSetSex;
    private BiConsumer<User, String> lambdaSetCardID;
    private BiConsumer<User, String> lambdaSetAddress;

    @Setup
    public void setup() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // -- Reflection --
        allArgsConstructor = User.class.getConstructor(
                Long.class, String.class, Integer.class, String.class, Integer.class,
                LocalDate.class, String.class, Integer.class, String.class, String.class);

        // -- MethodHandle constructors --
        mhConstructor10Arg = lookup.findConstructor(User.class,
                MethodType.methodType(void.class, Long.class, String.class, Integer.class,
                        String.class, Integer.class, LocalDate.class, String.class,
                        Integer.class, String.class, String.class));
        mhNoArgConstructor = lookup.findConstructor(User.class,
                MethodType.methodType(void.class));

        // -- MethodHandle setters (all 10) --
        mhSetId = lookup.findVirtual(User.class, "setId",
                MethodType.methodType(void.class, Long.class));
        mhSetName = lookup.findVirtual(User.class, "setName",
                MethodType.methodType(void.class, String.class));
        mhSetStatus = lookup.findVirtual(User.class, "setStatus",
                MethodType.methodType(void.class, Integer.class));
        mhSetMobile = lookup.findVirtual(User.class, "setMobile",
                MethodType.methodType(void.class, String.class));
        mhSetAge = lookup.findVirtual(User.class, "setAge",
                MethodType.methodType(void.class, Integer.class));
        mhSetBirthday = lookup.findVirtual(User.class, "setBirthday",
                MethodType.methodType(void.class, LocalDate.class));
        mhSetIntroduction = lookup.findVirtual(User.class, "setIntroduction",
                MethodType.methodType(void.class, String.class));
        mhSetSex = lookup.findVirtual(User.class, "setSex",
                MethodType.methodType(void.class, Integer.class));
        mhSetCardID = lookup.findVirtual(User.class, "setCardID",
                MethodType.methodType(void.class, String.class));
        mhSetAddress = lookup.findVirtual(User.class, "setAddress",
                MethodType.methodType(void.class, String.class));

        // -- LambdaMetafactory: NewUser<User> (10-arg constructor) --
        MethodType ifaceMethodType = MethodType.methodType(
                Object.class, Long.class, String.class, Integer.class, String.class,
                Integer.class, LocalDate.class, String.class, Integer.class,
                String.class, String.class);
        CallSite ctorSite = LambdaMetafactory.metafactory(
                lookup, "apply",
                MethodType.methodType(NewUser.class),
                ifaceMethodType,
                mhConstructor10Arg,
                MethodType.methodType(User.class, Long.class, String.class, Integer.class,
                        String.class, Integer.class, LocalDate.class, String.class,
                        Integer.class, String.class, String.class));
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

        // -- LambdaMetafactory: BiConsumer setters (all 10) --
        MethodType biconsumerErased = MethodType.methodType(void.class, Object.class, Object.class);

        lambdaSetId = createBiConsumer(lookup, mhSetId, User.class, Long.class);
        lambdaSetName = createBiConsumer(lookup, mhSetName, User.class, String.class);
        lambdaSetStatus = createBiConsumer(lookup, mhSetStatus, User.class, Integer.class);
        lambdaSetMobile = createBiConsumer(lookup, mhSetMobile, User.class, String.class);
        lambdaSetAge = createBiConsumer(lookup, mhSetAge, User.class, Integer.class);
        lambdaSetBirthday = createBiConsumer(lookup, mhSetBirthday, User.class, LocalDate.class);
        lambdaSetIntroduction = createBiConsumer(lookup, mhSetIntroduction, User.class, String.class);
        lambdaSetSex = createBiConsumer(lookup, mhSetSex, User.class, Integer.class);
        lambdaSetCardID = createBiConsumer(lookup, mhSetCardID, User.class, String.class);
        lambdaSetAddress = createBiConsumer(lookup, mhSetAddress, User.class, String.class);
    }

    @SuppressWarnings("unchecked")
    private static <T, U> BiConsumer<T, U> createBiConsumer(MethodHandles.Lookup lookup,
            MethodHandle handle, Class<T> targetType, Class<U> valueType) throws Throwable {
        MethodType erased = MethodType.methodType(void.class, Object.class, Object.class);
        CallSite site = LambdaMetafactory.metafactory(
                lookup, "accept",
                MethodType.methodType(BiConsumer.class),
                erased, handle,
                MethodType.methodType(void.class, targetType, valueType));
        return (BiConsumer<T, U>) site.getTarget().invokeExact();
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
                users.add((User) mhConstructor10Arg.invoke(
                        sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
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
                mhSetId.invoke(user, sum + i);
                mhSetName.invoke(user, "heihei");
                mhSetStatus.invoke(user, i);
                mhSetMobile.invoke(user, "17374957973");
                mhSetAge.invoke(user, i);
                mhSetBirthday.invoke(user, LocalDate.MAX);
                mhSetIntroduction.invoke(user, "introduction");
                mhSetSex.invoke(user, 1);
                mhSetCardID.invoke(user, "17374957973");
                mhSetAddress.invoke(user, "17374957973");
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
                users.add(lambdaFactory.apply(
                        sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
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
                lambdaSetMobile.accept(user, "17374957973");
                lambdaSetAge.accept(user, i);
                lambdaSetBirthday.accept(user, LocalDate.MAX);
                lambdaSetIntroduction.accept(user, "introduction");
                lambdaSetSex.accept(user, 1);
                lambdaSetCardID.accept(user, "17374957973");
                lambdaSetAddress.accept(user, "17374957973");
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

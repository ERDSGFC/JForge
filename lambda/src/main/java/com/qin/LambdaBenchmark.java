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

    private static final Constructor<User> ALL_ARGS_CONSTRUCTOR;
    private static final MethodHandle MH_CONSTRUCTOR_10ARG;
    private static final MethodHandle MH_NOARG_CONSTRUCTOR;
    private static final MethodHandle MH_SET_ID;
    private static final MethodHandle MH_SET_NAME;
    private static final MethodHandle MH_SET_STATUS;
    private static final MethodHandle MH_SET_MOBILE;
    private static final MethodHandle MH_SET_AGE;
    private static final MethodHandle MH_SET_BIRTHDAY;
    private static final MethodHandle MH_SET_INTRODUCTION;
    private static final MethodHandle MH_SET_SEX;
    private static final MethodHandle MH_SET_CARDID;
    private static final MethodHandle MH_SET_ADDRESS;
    private static final NewUser<User> LAMBDA_FACTORY;
    private static final Supplier<User> LAMBDA_SUPPLIER;
    private static final BiConsumer<User, Long> LAMBDA_SET_ID;
    private static final BiConsumer<User, String> LAMBDA_SET_NAME;
    private static final BiConsumer<User, Integer> LAMBDA_SET_STATUS;
    private static final BiConsumer<User, String> LAMBDA_SET_MOBILE;
    private static final BiConsumer<User, Integer> LAMBDA_SET_AGE;
    private static final BiConsumer<User, LocalDate> LAMBDA_SET_BIRTHDAY;
    private static final BiConsumer<User, String> LAMBDA_SET_INTRODUCTION;
    private static final BiConsumer<User, Integer> LAMBDA_SET_SEX;
    private static final BiConsumer<User, String> LAMBDA_SET_CARDID;
    private static final BiConsumer<User, String> LAMBDA_SET_ADDRESS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            ALL_ARGS_CONSTRUCTOR = User.class.getConstructor(
                    Long.class, String.class, Integer.class, String.class, Integer.class,
                    LocalDate.class, String.class, Integer.class, String.class, String.class);

            MH_CONSTRUCTOR_10ARG = lookup.findConstructor(User.class,
                    MethodType.methodType(void.class, Long.class, String.class, Integer.class,
                            String.class, Integer.class, LocalDate.class, String.class,
                            Integer.class, String.class, String.class));
            MH_NOARG_CONSTRUCTOR = lookup.findConstructor(User.class,
                    MethodType.methodType(void.class));

            MH_SET_ID = lookup.findVirtual(User.class, "setId",
                    MethodType.methodType(void.class, Long.class));
            MH_SET_NAME = lookup.findVirtual(User.class, "setName",
                    MethodType.methodType(void.class, String.class));
            MH_SET_STATUS = lookup.findVirtual(User.class, "setStatus",
                    MethodType.methodType(void.class, Integer.class));
            MH_SET_MOBILE = lookup.findVirtual(User.class, "setMobile",
                    MethodType.methodType(void.class, String.class));
            MH_SET_AGE = lookup.findVirtual(User.class, "setAge",
                    MethodType.methodType(void.class, Integer.class));
            MH_SET_BIRTHDAY = lookup.findVirtual(User.class, "setBirthday",
                    MethodType.methodType(void.class, LocalDate.class));
            MH_SET_INTRODUCTION = lookup.findVirtual(User.class, "setIntroduction",
                    MethodType.methodType(void.class, String.class));
            MH_SET_SEX = lookup.findVirtual(User.class, "setSex",
                    MethodType.methodType(void.class, Integer.class));
            MH_SET_CARDID = lookup.findVirtual(User.class, "setCardID",
                    MethodType.methodType(void.class, String.class));
            MH_SET_ADDRESS = lookup.findVirtual(User.class, "setAddress",
                    MethodType.methodType(void.class, String.class));

            MethodType ifaceMethodType = MethodType.methodType(
                    Object.class, Long.class, String.class, Integer.class, String.class,
                    Integer.class, LocalDate.class, String.class, Integer.class,
                    String.class, String.class);
            CallSite ctorSite = LambdaMetafactory.metafactory(
                    lookup, "apply",
                    MethodType.methodType(NewUser.class),
                    ifaceMethodType,
                    MH_CONSTRUCTOR_10ARG,
                    MethodType.methodType(User.class, Long.class, String.class, Integer.class,
                            String.class, Integer.class, LocalDate.class, String.class,
                            Integer.class, String.class, String.class));
            LAMBDA_FACTORY = (NewUser<User>) ctorSite.getTarget().invokeExact();

            MethodType supplierIfaceType = MethodType.methodType(Object.class);
            CallSite supplierSite = LambdaMetafactory.metafactory(
                    lookup, "get",
                    MethodType.methodType(Supplier.class),
                    supplierIfaceType,
                    MH_NOARG_CONSTRUCTOR,
                    MethodType.methodType(User.class));
            LAMBDA_SUPPLIER = (Supplier<User>) supplierSite.getTarget().invokeExact();

            LAMBDA_SET_ID = createBiConsumer(lookup, MH_SET_ID, User.class, Long.class);
            LAMBDA_SET_NAME = createBiConsumer(lookup, MH_SET_NAME, User.class, String.class);
            LAMBDA_SET_STATUS = createBiConsumer(lookup, MH_SET_STATUS, User.class, Integer.class);
            LAMBDA_SET_MOBILE = createBiConsumer(lookup, MH_SET_MOBILE, User.class, String.class);
            LAMBDA_SET_AGE = createBiConsumer(lookup, MH_SET_AGE, User.class, Integer.class);
            LAMBDA_SET_BIRTHDAY = createBiConsumer(lookup, MH_SET_BIRTHDAY, User.class, LocalDate.class);
            LAMBDA_SET_INTRODUCTION = createBiConsumer(lookup, MH_SET_INTRODUCTION, User.class, String.class);
            LAMBDA_SET_SEX = createBiConsumer(lookup, MH_SET_SEX, User.class, Integer.class);
            LAMBDA_SET_CARDID = createBiConsumer(lookup, MH_SET_CARDID, User.class, String.class);
            LAMBDA_SET_ADDRESS = createBiConsumer(lookup, MH_SET_ADDRESS, User.class, String.class);
        } catch (Throwable e) {
            throw new ExceptionInInitializerError(e);
        }
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

    // ==================== Benchmark methods (A-prefix for execution order) ====================

    @Benchmark
    public void A01_reflectionConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add(ALL_ARGS_CONSTRUCTOR.newInstance(sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
            }
            bh.consume(users);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void A02_lambdaMetafactoryConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add(LAMBDA_FACTORY.apply(
                        sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void A03_methodHandleConstructor(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                users.add((User) MH_CONSTRUCTOR_10ARG.invoke(
                        sum + i, "heihei", i, "17374957973", i,
                        LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void A04_noArgConstructorWithSetters(Blackhole bh) {
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
    public void A05_lambdaMetafactoryWithSetters(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                User user = LAMBDA_SUPPLIER.get();
                LAMBDA_SET_ID.accept(user, sum + i);
                LAMBDA_SET_NAME.accept(user, "heihei");
                LAMBDA_SET_STATUS.accept(user, i);
                LAMBDA_SET_MOBILE.accept(user, "17374957973");
                LAMBDA_SET_AGE.accept(user, i);
                LAMBDA_SET_BIRTHDAY.accept(user, LocalDate.MAX);
                LAMBDA_SET_INTRODUCTION.accept(user, "introduction");
                LAMBDA_SET_SEX.accept(user, 1);
                LAMBDA_SET_CARDID.accept(user, "17374957973");
                LAMBDA_SET_ADDRESS.accept(user, "17374957973");
                users.add(user);
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Benchmark
    public void A06_allArgsConstructor(Blackhole bh) {
        ArrayList<User> users = new ArrayList<>(NUM);
        long sum = 0;
        for (int i = 0; i < NUM; i++) {
            users.add(new User(sum + i, "heihei", i, "17374957973", i,
                    LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
        }
        bh.consume(users);
    }

    @Benchmark
    public void A07_methodHandleWithSetters(Blackhole bh) {
        try {
            ArrayList<User> users = new ArrayList<>(NUM);
            long sum = 0;
            for (int i = 0; i < NUM; i++) {
                User user = (User) MH_NOARG_CONSTRUCTOR.invoke();
                MH_SET_ID.invoke(user, sum + i);
                MH_SET_NAME.invoke(user, "heihei");
                MH_SET_STATUS.invoke(user, i);
                MH_SET_MOBILE.invoke(user, "17374957973");
                MH_SET_AGE.invoke(user, i);
                MH_SET_BIRTHDAY.invoke(user, LocalDate.MAX);
                MH_SET_INTRODUCTION.invoke(user, "introduction");
                MH_SET_SEX.invoke(user, 1);
                MH_SET_CARDID.invoke(user, "17374957973");
                MH_SET_ADDRESS.invoke(user, "17374957973");
                users.add(user);
            }
            bh.consume(users);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(LambdaBenchmark.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }
}

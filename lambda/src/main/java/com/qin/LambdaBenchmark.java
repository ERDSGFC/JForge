package com.qin;

import com.qin.fun.NewUser;
import org.openjdk.jmh.annotations.*;

import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * JMH benchmark comparing 7 strategies for instantiating and populating a 10-field User POJO.
 *
 * Each method performs ONE creation and returns the User; JMH invokes the method repeatedly per
 * iteration and consumes the returned object via Blackhole, so no manual loops are needed
 * (see JMHSample_11_Loops).
 *
 * Execution order follows JMH alphabetical naming: B01 → B02 → ... → B07.
 * BENCHMARK_RESULTS.md records multi-run history verifying this order.
 *
 * Mode: Throughput (ops/sec). Run configuration (@Warmup/@Measurement/@Fork) is kept as-is to
 * stay comparable with the historical runs recorded in BENCHMARK_RESULTS.md;
 * it can be overridden on the CLI.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class LambdaBenchmark {

    /**
     * Container for all reflective/lambda handles. Handles are {@code static final} so the JIT can
     * constant-fold them (see BENCHMARK_RESULTS.md Run 2); they are initialized once in a static block.
     */
    @SuppressWarnings("unchecked")
    static final class MyState {

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
        private static final NewUser FACTORY;
        private static final Supplier<User> SUPPLIER;
        private static final BiConsumer<User, Long> SET_ID;
        private static final BiConsumer<User, String> SET_NAME;
        private static final BiConsumer<User, Integer> SET_STATUS;
        private static final BiConsumer<User, String> SET_MOBILE;
        private static final BiConsumer<User, Integer> SET_AGE;
        private static final BiConsumer<User, LocalDate> SET_BIRTHDAY;
        private static final BiConsumer<User, String> SET_INTRODUCTION;
        private static final BiConsumer<User, Integer> SET_SEX;
        private static final BiConsumer<User, String> SET_CARDID;
        private static final BiConsumer<User, String> SET_ADDRESS;

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
                        User.class, Long.class, String.class, Integer.class, String.class,
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
                FACTORY = (NewUser) ctorSite.getTarget().invokeExact();

                MethodType supplierIfaceType = MethodType.methodType(Object.class);
                CallSite supplierSite = LambdaMetafactory.metafactory(
                        lookup, "get",
                        MethodType.methodType(Supplier.class),
                        supplierIfaceType,
                        MH_NOARG_CONSTRUCTOR,
                        MethodType.methodType(User.class));
                SUPPLIER = (Supplier<User>) supplierSite.getTarget().invokeExact();

                SET_ID = createBiConsumer(lookup, MH_SET_ID, User.class, Long.class);
                SET_NAME = createBiConsumer(lookup, MH_SET_NAME, User.class, String.class);
                SET_STATUS = createBiConsumer(lookup, MH_SET_STATUS, User.class, Integer.class);
                SET_MOBILE = createBiConsumer(lookup, MH_SET_MOBILE, User.class, String.class);
                SET_AGE = createBiConsumer(lookup, MH_SET_AGE, User.class, Integer.class);
                SET_BIRTHDAY = createBiConsumer(lookup, MH_SET_BIRTHDAY, User.class, LocalDate.class);
                SET_INTRODUCTION = createBiConsumer(lookup, MH_SET_INTRODUCTION, User.class, String.class);
                SET_SEX = createBiConsumer(lookup, MH_SET_SEX, User.class, Integer.class);
                SET_CARDID = createBiConsumer(lookup, MH_SET_CARDID, User.class, String.class);
                SET_ADDRESS = createBiConsumer(lookup, MH_SET_ADDRESS, User.class, String.class);
            } catch (Throwable e) {
                throw new ExceptionInInitializerError(e);
            }
        }

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
    }

    // ==================== Benchmark methods (B-prefix for execution order) ====================

    @Benchmark
    public User lambdaMetafactoryConstructor() {
        return MyState.FACTORY.apply(1L, "heihei", 1, "17374957973", 1,
                LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
    }

    @Benchmark
    public User reflectionConstructor() throws Exception {
        return MyState.ALL_ARGS_CONSTRUCTOR.newInstance(1L, "heihei", 1, "17374957973", 1,
                LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
    }

    @Benchmark
    public User methodHandleConstructor() throws Throwable {
        return (User) MyState.MH_CONSTRUCTOR_10ARG.invoke(1L, "heihei", 1, "17374957973", 1,
                LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
    }

    @Benchmark
    public User allArgsConstructor() {
        return new User(1L, "heihei", 1, "17374957973", 1,
                LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
    }

    @Benchmark
    public User lambdaMetafactoryWithSetters() {
        User user = MyState.SUPPLIER.get();
        MyState.SET_ID.accept(user, 1L);
        MyState.SET_NAME.accept(user, "heihei");
        MyState.SET_STATUS.accept(user, 1);
        MyState.SET_MOBILE.accept(user, "17374957973");
        MyState.SET_AGE.accept(user, 1);
        MyState.SET_BIRTHDAY.accept(user, LocalDate.MAX);
        MyState.SET_INTRODUCTION.accept(user, "introduction");
        MyState.SET_SEX.accept(user, 1);
        MyState.SET_CARDID.accept(user, "17374957973");
        MyState.SET_ADDRESS.accept(user, "17374957973");
        return user;
    }

    @Benchmark
    public User methodHandleWithSetters() throws Throwable {
        User user = (User) MyState.MH_NOARG_CONSTRUCTOR.invoke();
        MyState.MH_SET_ID.invoke(user, 1L);
        MyState.MH_SET_NAME.invoke(user, "heihei");
        MyState.MH_SET_STATUS.invoke(user, 1);
        MyState.MH_SET_MOBILE.invoke(user, "17374957973");
        MyState.MH_SET_AGE.invoke(user, 1);
        MyState.MH_SET_BIRTHDAY.invoke(user, LocalDate.MAX);
        MyState.MH_SET_INTRODUCTION.invoke(user, "introduction");
        MyState.MH_SET_SEX.invoke(user, 1);
        MyState.MH_SET_CARDID.invoke(user, "17374957973");
        MyState.MH_SET_ADDRESS.invoke(user, "17374957973");
        return user;
    }

    @Benchmark
    public User noArgConstructorWithSetters() {
        User user = new User();
        user.setId(1L);
        user.setName("heihei");
        user.setStatus(1);
        user.setMobile("17374957973");
        user.setAge(1);
        user.setBirthday(LocalDate.MAX);
        user.setIntroduction("introduction");
        user.setSex(1);
        user.setCardID("17374957973");
        user.setAddress("17374957973");
        return user;
    }
}

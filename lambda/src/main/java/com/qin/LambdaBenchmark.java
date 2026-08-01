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
 * JMH executes benchmarks in alphabetical method-name order (source order does not matter);
 * BENCHMARK_RESULTS.md records the run history.
 *
 * Mode: Throughput (ops/sec). Run configuration: 5 warmup iterations of 3 s,
 * 10 measurement iterations of 2 s, default forks; it can be overridden on the CLI.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@Warmup(
        iterations = 5,
        time = 3,
        timeUnit = TimeUnit.SECONDS
)

@Measurement(
        iterations = 10,
        time = 2,
        timeUnit = TimeUnit.SECONDS
)
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

    /**
     * Control group: same handles as {@link MyState} but as plain instance fields initialized in
     * {@code @Setup} (no {@code static final} constant-folding). Used to measure the effect of
     * {@code static final} handles; {@code allArgsConstructor} is the shared baseline needing no handles.
     */
    @State(Scope.Thread)
    public static class InstanceState {

        private Constructor<User> allArgsConstructor;
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
        private MethodHandle mhSetCardId;
        private MethodHandle mhSetAddress;
        private NewUser factory;
        private Supplier<User> supplier;
        private BiConsumer<User, Long> setId;
        private BiConsumer<User, String> setName;
        private BiConsumer<User, Integer> setStatus;
        private BiConsumer<User, String> setMobile;
        private BiConsumer<User, Integer> setAge;
        private BiConsumer<User, LocalDate> setBirthday;
        private BiConsumer<User, String> setIntroduction;
        private BiConsumer<User, Integer> setSex;
        private BiConsumer<User, String> setCardId;
        private BiConsumer<User, String> setAddress;

        @Setup
        public void init() throws Throwable {
            MethodHandles.Lookup lookup = MethodHandles.lookup();

            allArgsConstructor = User.class.getConstructor(
                    Long.class, String.class, Integer.class, String.class, Integer.class,
                    LocalDate.class, String.class, Integer.class, String.class, String.class);

            mhConstructor10Arg = lookup.findConstructor(User.class,
                    MethodType.methodType(void.class, Long.class, String.class, Integer.class,
                            String.class, Integer.class, LocalDate.class, String.class,
                            Integer.class, String.class, String.class));
            mhNoArgConstructor = lookup.findConstructor(User.class,
                    MethodType.methodType(void.class));

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
            mhSetCardId = lookup.findVirtual(User.class, "setCardID",
                    MethodType.methodType(void.class, String.class));
            mhSetAddress = lookup.findVirtual(User.class, "setAddress",
                    MethodType.methodType(void.class, String.class));

            MethodType ifaceMethodType = MethodType.methodType(
                    User.class, Long.class, String.class, Integer.class, String.class,
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
            factory = (NewUser) ctorSite.getTarget().invokeExact();

            MethodType supplierIfaceType = MethodType.methodType(Object.class);
            CallSite supplierSite = LambdaMetafactory.metafactory(
                    lookup, "get",
                    MethodType.methodType(Supplier.class),
                    supplierIfaceType,
                    mhNoArgConstructor,
                    MethodType.methodType(User.class));
            supplier = (Supplier<User>) supplierSite.getTarget().invokeExact();

            setId = MyState.createBiConsumer(lookup, mhSetId, User.class, Long.class);
            setName = MyState.createBiConsumer(lookup, mhSetName, User.class, String.class);
            setStatus = MyState.createBiConsumer(lookup, mhSetStatus, User.class, Integer.class);
            setMobile = MyState.createBiConsumer(lookup, mhSetMobile, User.class, String.class);
            setAge = MyState.createBiConsumer(lookup, mhSetAge, User.class, Integer.class);
            setBirthday = MyState.createBiConsumer(lookup, mhSetBirthday, User.class, LocalDate.class);
            setIntroduction = MyState.createBiConsumer(lookup, mhSetIntroduction, User.class, String.class);
            setSex = MyState.createBiConsumer(lookup, mhSetSex, User.class, Integer.class);
            setCardId = MyState.createBiConsumer(lookup, mhSetCardId, User.class, String.class);
            setAddress = MyState.createBiConsumer(lookup, mhSetAddress, User.class, String.class);
        }
    }

    // ==================== Benchmark methods (executed in alphabetical name order) ====================

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
        return (User) MyState.MH_CONSTRUCTOR_10ARG.invokeExact((Long) 1L, "heihei", (Integer) 1,
                "17374957973", (Integer) 1, LocalDate.MAX, "introduction", (Integer) 1,
                "17374957973", "17374957973");
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
        User user = (User) MyState.MH_NOARG_CONSTRUCTOR.invokeExact();
        MyState.MH_SET_ID.invokeExact(user, (Long) 1L);
        MyState.MH_SET_NAME.invokeExact(user, "heihei");
        MyState.MH_SET_STATUS.invokeExact(user, (Integer) 1);
        MyState.MH_SET_MOBILE.invokeExact(user, "17374957973");
        MyState.MH_SET_AGE.invokeExact(user, (Integer) 1);
        MyState.MH_SET_BIRTHDAY.invokeExact(user, LocalDate.MAX);
        MyState.MH_SET_INTRODUCTION.invokeExact(user, "introduction");
        MyState.MH_SET_SEX.invokeExact(user, (Integer) 1);
        MyState.MH_SET_CARDID.invokeExact(user, "17374957973");
        MyState.MH_SET_ADDRESS.invokeExact(user, "17374957973");
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

    // ==================== Control group: instance handles (no static final) ====================
    // allArgsConstructor above is the shared baseline (needs no handles).

    @Benchmark
    public User reflectionConstructorInstance(InstanceState state) throws Exception {
        return state.allArgsConstructor.newInstance(1L, "heihei", 1, "17374957973", 1,
                LocalDate.MAX, "introduction", 1, "17374957973", "17374957973");
    }

    @Benchmark
    public User methodHandleConstructorInstance(InstanceState state) throws Throwable {
        return (User) state.mhConstructor10Arg.invokeExact((Long) 1L, "heihei", (Integer) 1,
                "17374957973", (Integer) 1, LocalDate.MAX, "introduction", (Integer) 1,
                "17374957973", "17374957973");
    }

    @Benchmark
    public User lambdaMetafactoryWithSettersInstance(InstanceState state) {
        User user = state.supplier.get();
        state.setId.accept(user, 1L);
        state.setName.accept(user, "heihei");
        state.setStatus.accept(user, 1);
        state.setMobile.accept(user, "17374957973");
        state.setAge.accept(user, 1);
        state.setBirthday.accept(user, LocalDate.MAX);
        state.setIntroduction.accept(user, "introduction");
        state.setSex.accept(user, 1);
        state.setCardId.accept(user, "17374957973");
        state.setAddress.accept(user, "17374957973");
        return user;
    }

    @Benchmark
    public User methodHandleWithSettersInstance(InstanceState state) throws Throwable {
        User user = (User) state.mhNoArgConstructor.invokeExact();
        state.mhSetId.invokeExact(user, (Long) 1L);
        state.mhSetName.invokeExact(user, "heihei");
        state.mhSetStatus.invokeExact(user, (Integer) 1);
        state.mhSetMobile.invokeExact(user, "17374957973");
        state.mhSetAge.invokeExact(user, (Integer) 1);
        state.mhSetBirthday.invokeExact(user, LocalDate.MAX);
        state.mhSetIntroduction.invokeExact(user, "introduction");
        state.mhSetSex.invokeExact(user, (Integer) 1);
        state.mhSetCardId.invokeExact(user, "17374957973");
        state.mhSetAddress.invokeExact(user, "17374957973");
        return user;
    }
}

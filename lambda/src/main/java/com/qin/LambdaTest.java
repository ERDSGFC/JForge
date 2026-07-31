package com.qin;


import java.lang.invoke.*;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;


public class LambdaTest {

    public static final int num = 50_0000;

    public void tt() throws Throwable {
        long time = 0;
        int num = 1000;
        for (int i = 0; i < num; i++) {
            time += normal();
        }
        System.out.printf("平均耗时 %d", time/num);
    }

    public long normal() {

        return CalculateTime(() -> {
            ArrayList<User> users = new ArrayList<>(num);
            long sum = 0;
            for (int i = 0; i < num; i++) {
                users.add(new User(sum+i, "heihei", i, "17374957973", i, LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
            }
        });
    }

    public long normal1() {

        return CalculateTime(() -> {
            ArrayList<User> users = new ArrayList<>(num);
            long sum = 0;
            for (int i = 0; i < num; i++) {
                User user = new User();
                user.setId(sum+i);
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
        });
    }
//    private Long id;
//    private String name;
//    private Integer status;
//    private String mobile;
//    private Integer age;
//    private LocalDate birthday;
//    private String introduction;
//    private Integer sex;
//    private String cardID;
//    private String address;
    public long Test1() throws NoSuchMethodException {
        Class<User> userClass = User.class;
        Constructor<User> constructor = userClass.getConstructor(Long.class, String.class, Integer.class, String.class, Integer.class, LocalDate.class, String.class, Integer.class, String.class, String.class);
        return CalculateTime(() -> {
            try {
                ArrayList<User> users = new ArrayList<>(num);
                long sum = 0;
                for (int i = 0; i < num; i++) {
                    users.add(constructor.newInstance(sum+i, "heihei", i, "17374957973", i, LocalDate.MAX, "introduction", 1, "17374957973", "17374957973"));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        });
    }

    public long Test2() throws NoSuchMethodException, IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle constructor = lookup.findConstructor(User.class, MethodType.methodType(void.class, Long.class, String.class, Integer.class));
        return CalculateTime(() -> {
            try {
                ArrayList<User> users = new ArrayList<>(num);
                long sum = 0;
                for (int i = 0; i < num; i++) {
                    users.add((User) constructor.invoke(sum+i, "heihei", i));
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

        });
    }

    public long Test21() throws NoSuchMethodException, IllegalAccessException {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle constructor = lookup.findConstructor(User.class, MethodType.methodType(void.class));
        Supplier<User> newUser = User::new;
        MethodHandle setId = lookup.findVirtual(User.class, "setId", MethodType.methodType(void.class, Long.class));
        MethodHandle setName = lookup.findVirtual(User.class, "setName", MethodType.methodType(void.class, String.class));
        MethodHandle setStatus = lookup.findVirtual(User.class, "setStatus", MethodType.methodType(void.class, Integer.class));
        return CalculateTime(() -> {
            try {
                ArrayList<User> users = new ArrayList<>(num);
                long sum = 0;
                for (int i = 0; i < num; i++) {
//                    User user = (User) constructor.invoke();
                    User user = newUser.get();
                    setId.invoke(user, sum+ 1);
                    setName.invoke(user, "heihei");
                    setStatus.invoke(user, i);
                    users.add(user);
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

        });
    }

    @FunctionalInterface
    public static interface NewUser<T>  {

        T apply(Long id, String name, Integer status);
    }

    public long Test3() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // 2. 定义函数式接口的签名（sam = Single Abstract Method）
        MethodType interfaceMethodType = MethodType.methodType(
                Object.class,      // 接口方法返回类型（NewUser.apply 返回 Object）
                Long.class,      // 接口方法参数（NewUser.apply 接收 Long）
                String.class,      // 接口方法参数（NewUser.apply 接收 String）
                Integer.class       // 接口方法参数（NewUser.apply 接收 Integer）
        );
        MethodHandle constructor = lookup.findConstructor(User.class, MethodType.methodType(void.class, Long.class, String.class, Integer.class));
        // 3. 调用 LambdaMetafactory
        CallSite site = LambdaMetafactory.metafactory(
                lookup,                          // 查找器
                "apply",                         // 接口方法名（Function 中的方法名）
                MethodType.methodType(NewUser.class), // 工厂方法类型：()Function
                interfaceMethodType,             // 接口方法签名（擦除后）
                constructor,               // 目标方法句柄
                MethodType.methodType(User.class, Long.class, String.class, Integer.class) // 目标方法签名（具体类型）
        );
        NewUser<User> factory = (NewUser<User>) site.getTarget().invokeExact();
        return CalculateTime(() -> {
            try {
                ArrayList<User> users = new ArrayList<>(num);
                long sum = 0;
                for (int i = 0; i < num; i++) {
                    users.add(factory.apply(sum+i, "heihei", i));
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

        });
    }


    public long Test31() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        // 2. 定义函数式接口的签名（sam = Single Abstract Method）
        MethodType interfaceMethodType = MethodType.methodType(
                Object.class      // 接口方法返回类型（NewUser.apply 返回 Object）
        );
        MethodHandle constructor = lookup.findConstructor(User.class, MethodType.methodType(void.class));
        // 3. 调用 LambdaMetafactory
        CallSite site = LambdaMetafactory.metafactory(
                lookup,                          // 查找器
                "get",                         // 接口方法名（Supplier 中的方法名）
                MethodType.methodType(Supplier.class), // 工厂方法类型：()Supplier
                interfaceMethodType,             // 接口方法签名（擦除后）
                constructor,               // 目标方法句柄
                MethodType.methodType(User.class) // 目标方法签名（具体类型）
        );
        MethodHandle setId = lookup.findVirtual(User.class, "setId", MethodType.methodType(void.class, Long.class));
        MethodHandle setName = lookup.findVirtual(User.class, "setName", MethodType.methodType(void.class, String.class));
        MethodHandle setStatus = lookup.findVirtual(User.class, "setStatus", MethodType.methodType(void.class, Integer.class));
        CallSite siteSetId = LambdaMetafactory.metafactory(
                lookup,                          // 查找器
                "accept",                         // 接口方法名（accept 中的方法名）
                MethodType.methodType(BiConsumer.class), // 工厂方法类型：()BiConsumer
                MethodType.methodType(void.class, Object.class, Object.class),             // 接口方法签名（擦除后）
                setId,               // 目标方法句柄
                MethodType.methodType(void.class, User.class, Long.class) // 目标方法签名（具体类型）
        );
        CallSite siteSetName = LambdaMetafactory.metafactory(
                lookup,                          // 查找器
                "accept",                         // 接口方法名（Function 中的方法名）
                MethodType.methodType(BiConsumer.class), // 工厂方法类型：()Function
                MethodType.methodType(void.class, Object.class, Object.class),             // 接口方法签名（擦除后）
                setName,               // 目标方法句柄
                MethodType.methodType(void.class, User.class, String.class) // 目标方法签名（具体类型）
        );
        // 3. 调用 LambdaMetafactory
        CallSite siteSetStatus = LambdaMetafactory.metafactory(
                lookup,                          // 查找器
                "accept",                         // 接口方法名（Function 中的方法名）
                MethodType.methodType(BiConsumer.class), // 工厂方法类型：()Function
                MethodType.methodType(void.class, Object.class, Object.class),             // 接口方法签名（擦除后）
                setStatus,               // 目标方法句柄
                MethodType.methodType(void.class, User.class, Integer.class) // 目标方法签名（具体类型）
        );

//        Supplier<User> factory = (Supplier<User>) site.getTarget().invokeExact();
        BiConsumer<User,Long> factoryId = (BiConsumer<User, Long>) siteSetId.getTarget().invokeExact();
        BiConsumer<User,String> factoryName = (BiConsumer<User,String>) siteSetName.getTarget().invokeExact();
        BiConsumer<User,Integer> factoryStatus = (BiConsumer<User,Integer>) siteSetStatus.getTarget().invokeExact();
        Supplier<User>  factory = User::new;
        return CalculateTime(() -> {
            try {
                ArrayList<User> users = new ArrayList<>(num);
                long sum = 0;
                for (int i = 0; i < num; i++) {
                    User user = factory.get();
                    factoryId.accept(user,sum +i);
                    factoryName.accept(user,"heihei");
                    factoryStatus.accept(user, i);
                    users.add(user);
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }

        });
    }


    /**
     * 计算时间
     * @param runnable 执行方法
     */
    private long CalculateTime(Runnable runnable) {
        long startTime = System.currentTimeMillis();
        runnable.run();
        long endTime = System.currentTimeMillis();
        long speedTime = endTime - startTime;
        System.out.printf("运行时间：%d", speedTime);
        System.out.println();

        return speedTime;
    }
}

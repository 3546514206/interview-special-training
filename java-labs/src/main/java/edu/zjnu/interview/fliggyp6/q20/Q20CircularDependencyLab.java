package edu.zjnu.interview.fliggyp6.q20;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Q20 · Spring 循环依赖实验手写三级缓存, 亲眼看到「为什么二级不够」
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.interview.fliggyp6.q20.Q20CircularDependencyLab
 *
 * 验证面试点:
 * 1. 三级缓存: singletonObjects(成品) / earlySingletonObjects(半成品) / singletonFactories(工厂)
 * 2. 二级缓存能解决【普通】循环依赖, 但解决不了 AOP 代理一致性:
 *    二级方案里 B 拿到原始 A, 容器最终暴露的却是代理 A -> B 里的 A 事务失效
 * 3. 三级用 ObjectFactory 延迟生成早期引用, 保证 B 拿到的 == 容器最终暴露的
 * 4. 构造器注入 / prototype 无法被救
 */
public class Q20CircularDependencyLab {

    // ===== 模拟 Bean =====

    interface Service { }

    static class A implements Service {
        B b;
    }

    static class B implements Service {
        Service a; // 类型用接口, 方便观察注入进来的是原始对象还是代理
    }

    /** 模拟 AOP: 生成代理 (真实 Spring 里是 JdkDynamicAopProxy / CGLIB) */
    static Service proxy(Service target) {
        return (Service) java.lang.reflect.Proxy.newProxyInstance(
                Service.class.getClassLoader(), new Class[]{Service.class},
                (p, m, args) -> {
                    if (m.getName().equals("toString")) return "Proxy(" + target.getClass().getSimpleName() + ")";
                    if (m.getName().equals("equals")) return p == args[0];
                    return m.invoke(target, args);
                });
    }

    // ===== 三级缓存容器 (DefaultSingletonBeanRegistry 的骨架) =====

    static class ThreeLevelContainer {
        final Map<String, Object> singletonObjects = new HashMap<>();            // 一级: 成品
        final Map<String, Object> earlySingletonObjects = new HashMap<>();       // 二级: 半成品早期引用
        final Map<String, Supplier<Object>> singletonFactories = new HashMap<>();// 三级: ObjectFactory
        final Map<String, Class<?>> beanClasses = new HashMap<>();
        final Map<String, Boolean> needProxy = new HashMap<>();
        final Map<String, java.util.function.BiConsumer<Object, Object>> injectors = new HashMap<>();

        Object getBean(String name) throws Exception {
            Object bean = singletonObjects.get(name);            // 1. 一级
            if (bean != null) return bean;
            bean = earlySingletonObjects.get(name);              // 2. 二级
            if (bean != null) return bean;
            Supplier<Object> factory = singletonFactories.get(name); // 3. 三级: 调工厂拿早期引用
            if (factory != null) {
                bean = factory.get();
                earlySingletonObjects.put(name, bean);           // 晋升到二级
                singletonFactories.remove(name);
                return bean;
            }
            return createBean(name);                             // 都没有 -> 现场创建
        }

        Object createBean(String name) throws Exception {
            Class<?> clazz = beanClasses.get(name);
            Object instance = clazz.getDeclaredConstructor().newInstance(); // 实例化 (构造器注入的依赖此刻就要 -> 死结)

            // 实例化后: 把 ObjectFactory 放三级 (真实代码: () -> getEarlyBeanReference(beanName, mbd, bean))
            singletonFactories.put(name, () -> {
                Object early = instance;
                if (needProxy.getOrDefault(name, false)) {
                    early = proxy((Service) instance); // 提前生成代理! 这就是三级缓存存在的意义
                    System.out.println("      [三级缓存] 为 " + name + " 提前生成早期代理引用: " + early);
                }
                return early;
            });
            System.out.println("   " + name + " 实例化完成, ObjectFactory 已入三级缓存");

            injectors.get(name).accept(instance, null); // 属性注入 (里面可能递归 getBean -> 循环依赖发生地)

            Object exposed = earlySingletonObjects.containsKey(name)
                    ? earlySingletonObjects.get(name)    // 被别人提前引用过: 暴露和它拿到的是同一个!
                    : (needProxy.getOrDefault(name, false) ? proxy((Service) instance) : instance);
            earlySingletonObjects.remove(name);
            singletonFactories.remove(name);
            singletonObjects.put(name, exposed);         // 成品入一级
            System.out.println("   " + name + " 初始化完成, 入一级缓存, 最终暴露: " + exposed);
            return exposed;
        }
    }

    // ===== 二级缓存容器 (对照组: 证明二级解决不了代理一致性) =====

    static class TwoLevelContainer {
        final Map<String, Object> singletons = new HashMap<>();
        final Map<String, Object> early = new HashMap<>(); // 二级直接放【原始对象】
        final Map<String, Class<?>> beanClasses = new HashMap<>();
        final Map<String, Boolean> needProxy = new HashMap<>();
        final Map<String, java.util.function.BiConsumer<Object, Object>> injectors = new HashMap<>();

        Object getBean(String name) throws Exception {
            Object bean = singletons.get(name);
            if (bean != null) return bean;
            bean = early.get(name);
            if (bean != null) return bean;
            return createBean(name);
        }

        Object createBean(String name) throws Exception {
            Object instance = beanClasses.get(name).getDeclaredConstructor().newInstance();
            early.put(name, instance); // 实例化后直接把原始对象丢二级 (没有延迟, 没有工厂)
            System.out.println("   " + name + " 实例化, 【原始对象】直接入二级缓存");
            injectors.get(name).accept(instance, null);
            early.remove(name);
            // 初始化后才发现要代理 -> 生成代理放进容器
            Object exposed = needProxy.getOrDefault(name, false) ? proxy((Service) instance) : instance;
            singletons.put(name, exposed);
            System.out.println("   " + name + " 初始化完成, 此时才生成代理入容器: " + exposed);
            return exposed;
        }
    }

    public static void main(String[] args) throws Exception {
        threeLevel();
        twoLevel();
        constructorInjection();
    }

    static void threeLevel() throws Exception {
        System.out.println("============ 方案A: 三级缓存 (Spring 的做法) ============");
        ThreeLevelContainer c = new ThreeLevelContainer();
        c.beanClasses.put("a", A.class);
        c.beanClasses.put("b", B.class);
        c.needProxy.put("a", true); // A 有 @Transactional, 需要 AOP 代理
        c.injectors.put("a", (inst, x) -> {
            try { ((A) inst).b = (B) c.getBean("b"); } catch (Exception e) { throw new RuntimeException(e); }
        });
        c.injectors.put("b", (inst, x) -> {
            try { ((B) inst).a = (Service) c.getBean("a"); } catch (Exception e) { throw new RuntimeException(e); }
        });

        c.getBean("a");
        B b = (B) c.singletonObjects.get("b"); // 从容器拿成品 B, 检查它注入的 a
        System.out.println("\n结果: B.a 注入的是 " + b.a);
        System.out.println("       容器一级缓存里的 A = " + c.singletonObjects.get("a"));
        System.out.println("       一致? " + (b.a == c.singletonObjects.get("a")) + "  <- B 拿到的代理 == 容器最终暴露的代理, 事务不失效!\n");
    }

    static void twoLevel() throws Exception {
        System.out.println("============ 方案B: 只有二级缓存 (反例) ============");
        TwoLevelContainer c = new TwoLevelContainer();
        c.beanClasses.put("a", A.class);
        c.beanClasses.put("b", B.class);
        c.needProxy.put("a", true);
        c.injectors.put("a", (inst, x) -> {
            try { ((A) inst).b = (B) c.getBean("b"); } catch (Exception e) { throw new RuntimeException(e); }
        });
        c.injectors.put("b", (inst, x) -> {
            try { ((B) inst).a = (Service) c.getBean("a"); } catch (Exception e) { throw new RuntimeException(e); }
        });

        c.getBean("a");
        B b = (B) c.singletons.get("b");
        System.out.println("\n结果: B.a 注入的是 " + b.a.getClass().getSimpleName() + " (原始对象, 没代理!)");
        System.out.println("       容器里的 A = " + c.singletons.get("a"));
        System.out.println("       一致? " + (b.a == c.singletons.get("a"))
                + "  <- B 调用 a 上的事务方法直接失效! 这就是为什么必须三级\n");
    }

    static void constructorInjection() {
        System.out.println("============ 方案C: 构造器循环依赖 (无解) ============");
        System.out.println("  A 的构造器要 B, B 的构造器要 A —— 实例化都完不成, 三级缓存根本没机会上场");
        System.out.println("  Spring 直接抛 BeanCurrentlyInCreationException");
        System.out.println("  解法: @Lazy 注入代理占位符(调用时才真创建) / 重构消除循环");
        System.out.println("  另: Spring Boot 2.6+ 默认 allow-circular-references=false, 循环依赖启动就报错 —— 框架鼓励你改设计\n");
    }
}

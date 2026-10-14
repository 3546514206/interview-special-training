package edu.zjnu.lab.q21;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Q21 · Spring Bean 生命周期 + AOP 实验 (回调顺序 + 代理 + 自调用失效)
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q21.Q21BeanLifecycleLab
 *
 * 验证面试点:
 * 1. 生命周期回调顺序: Aware* -> BPP.before -> @PostConstruct -> afterPropertiesSet -> init-method -> BPP.after(AOP 在这!)
 * 2. JDK 动态代理: 基于接口, Proxy.newProxyInstance
 * 3. 同类内方法自调用 @Transactional/@Async 失效: this 调用不走代理
 */
public class Q21BeanLifecycleLab {

    // ===== 模拟一个业务 Bean, 全套回调接口 =====

    interface OrderService {
        void pay();

        void payInternal();
    }

    static class OrderServiceImpl implements OrderService {
        static int callCount = 0; // 切面计数器

        @Override
        public void pay() {
            System.out.println("    [业务] pay 执行");
            this.payInternal(); // ★ 同类内自调用: this 是【原始对象】, 不是代理!
        }

        @Override
        public void payInternal() {
            System.out.println("    [业务] payInternal 执行");
        }

        // ---- 以下都是生命周期回调 ----
        void aware(String point) { System.out.println("  " + point); }
    }

    // ===== 切面: 统计方法调用 (模拟 @Transactional 切面) =====

    static InvocationHandler txAdvice(Object target) {
        return (proxy, method, args) -> {
            System.out.println("    [切面] 拦截到 " + method.getName() + " (真实场景: 开事务/记日志)");
            OrderServiceImpl.callCount++;
            return method.invoke(target, args);
        };
    }

    public static void main(String[] args) throws Exception {
        step1_lifecycleOrder();
        step2_proxyAndSelfInvocation();
    }

    static void step1_lifecycleOrder() {
        System.out.println("=== 实验1: Bean 生命周期回调顺序 (手撸 Spring 的流程) ===");
        OrderServiceImpl bean = new OrderServiceImpl();          // 1. 实例化(构造器)
        bean.aware("2. BeanNameAware / BeanFactoryAware / ApplicationContextAware");
        bean.aware("3. BPP.postProcessBeforeInitialization      <- @Autowired 注入在这之前完成(populateBean)");
        bean.aware("4. @PostConstruct");
        bean.aware("5. InitializingBean.afterPropertiesSet()");
        bean.aware("6. init-method");
        bean.aware("7. BPP.postProcessAfterInitialization       <- ★ AOP 代理在这里生成(AnnotationAwareAspectJAutoProxyCreator)");
        bean.aware("8. 使用 / 9. @PreDestroy -> DisposableBean -> destroy-method");
        System.out.println("  >> 易错点: @PostConstruct 在 BPP.after【之前】; AOP 代理是 BeanPostProcessor 造出来的\n");
    }

    static void step2_proxyAndSelfInvocation() throws Exception {
        System.out.println("=== 实验2: JDK 动态代理 + 自调用失效 ===");
        OrderServiceImpl target = new OrderServiceImpl();
        OrderService proxy = (OrderService) Proxy.newProxyInstance(
                OrderService.class.getClassLoader(), new Class[]{OrderService.class}, txAdvice(target));
        System.out.println("代理类型: " + proxy.getClass().getName());
        System.out.println("(真实 Spring: 有接口默认 JDK 代理; 无接口用 CGLIB 子类; proxyTargetClass=true 强制 CGLIB)\n");

        System.out.println("-- 场景1: 从代理调用 pay() --");
        OrderServiceImpl.callCount = 0;
        proxy.pay();
        System.out.println("  切面拦截次数 = " + OrderServiceImpl.callCount + " (只有1次! payInternal 的 this 调用没被拦)\n");

        System.out.println("-- 场景2: 原始对象直接调 pay() --");
        OrderServiceImpl.callCount = 0;
        target.pay();
        System.out.println("  切面拦截次数 = " + OrderServiceImpl.callCount + " (0次, this 完全绕过代理)\n");

        System.out.println("-- 场景3: 正确姿势 self 注入 --");
        OrderServiceImpl.callCount = 0;
        // 模拟 @Autowired private OrderService self; 然后 self.payInternal()
        proxy.payInternal();
        System.out.println("  切面拦截次数 = " + OrderServiceImpl.callCount + " (走代理, 切面生效)");
        System.out.println("  >> 结论: 同类内 this.xxx() 不经过代理 -> @Transactional/@Async 全失效");
        System.out.println("  >> 修复: 注入自己(self)调用 / AopContext.currentProxy()(需 exposeProxy=true)\n");
    }
}

package edu.zjnu.lab.q23;

import edu.zjnu.lab.q23.protocol.Protocol;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Q23 · Dubbo SPI 四大特性实验按需加载 / Wrapper / IoC注入 / @Adaptive
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q23.Q23DubboSpiLab
 *
 * 验证面试点 (对 JDK SPI 的 4 大改进, 全部跑给你看):
 * 1. 配置灵活: META-INF/dubbo/ key=value, 按名取, 一次只实例化一个
 * 2. 自动包装: 构造器带本接口参数的类被识别为 Wrapper, 自动层层包裹 (AOP 效果)
 * 3. 自适应(@Adaptive): 运行时从 URL 参数挑实现 (真实实现是动态生成 $Adaptive 类)
 */
public class Q23DubboSpiLab {

    /** 迷你版 ExtensionLoader: 100 行复刻 Dubbo SPI 核心 */
    static class MiniExtensionLoader {
        final Class<?> spi;
        final Map<String, Class<?>> classes = new LinkedHashMap<>(); // key -> Class (只加载类, 不实例化)
        final Map<String, Object> singletons = new LinkedHashMap<>();// 按需实例化 + 缓存

        MiniExtensionLoader(Class<?> spi) throws Exception {
            this.spi = spi;
            // 真实顺序: META-INF/dubbo/internal/ > META-INF/dubbo/ > META-INF/services/
            InputStream in = getClass().getResourceAsStream("/META-INF/dubbo/" + spi.getName());
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] kv = line.split("=", 2);
                    classes.put(kv[0].trim(), Class.forName(kv[1].trim())); // Class.forName 加载类但不 new!
                }
            }
        }

        /** 特性1+2: 按名取实现, 找不到包装类构造器 -> 自动包裹 Wrapper */
        @SuppressWarnings("unchecked")
        <T> T getExtension(String name) throws Exception {
            if (singletons.containsKey(name)) return (T) singletons.get(name);
            Class<?> impl = classes.get(name);
            System.out.println("    getExtension(\"" + name + "\") -> 现在才实例化 " + impl.getSimpleName());
            Object instance = impl.getDeclaredConstructor().newInstance();

            // Wrapper 自动包装: 遍历所有 SPI 类, 构造器参数是本接口的就是 Wrapper
            for (Class<?> w : classes.values()) {
                if (w == impl) continue;
                for (java.lang.reflect.Constructor<?> c : w.getConstructors()) {
                    if (c.getParameterTypes().length == 1 && c.getParameterTypes()[0] == spi) {
                        instance = c.newInstance(instance); // 层层包上 (多个 Wrapper 就像洋葱)
                    }
                }
            }
            singletons.put(name, instance);
            return (T) instance;
        }

        /** 特性4: @Adaptive —— 运行时按 URL 参数选实现 (真实 Dubbo 是动态字节码生成 Protocol$Adaptive) */
        @SuppressWarnings("unchecked")
        <T> T getAdaptiveExtension() {
            return (T) java.lang.reflect.Proxy.newProxyInstance(
                    spi.getClassLoader(), new Class[]{spi},
                    (proxy, method, args) -> {
                        String url = String.valueOf(args[0]); // Dubbo 的 URL 是配置总线
                        // 真实逻辑: 取 @Adaptive("protocol") 指定的参数值; 这里用 URL 的 scheme 模拟
                        for (String key : new String[]{"dubbo", "http"}) {
                            if (url.startsWith(key + "://")) {
                                System.out.println("    [$Adaptive] URL=" + key + "://... -> 分发给 " + key + " 实现");
                                return method.invoke(getExtension(key), args);
                            }
                        }
                        throw new IllegalStateException("URL 里没有已知 protocol: " + url);
                    });
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 实验0: JDK SPI 的痛点 ===");
        System.out.println("  ① ServiceLoader 一遍历就全量实例化 ② 没法按名取 ③ 加载失败异常被吞 ④ 无 IoC/AOP\n");

        System.out.println("=== 实验1+2: 按需加载 + Wrapper 自动包装 ===");
        MiniExtensionLoader loader = new MiniExtensionLoader(Protocol.class);
        System.out.println("  配置加载完: 只解析了类名, 一个都没 new (对比 JDK SPI 全量实例化)\n");

        Protocol dubbo = loader.getExtension("dubbo");
        System.out.println("  调用结果: " + dubbo.export("dubbo://127.0.0.1:20880/OrderService"));
        System.out.println("  >> DubboProtocol 被 LogWrapper 自动包裹, 业务代码零感知 —— 这就是 Wrapper = AOP\n");

        System.out.println("\n=== 实验3: @Adaptive 自适应扩展 ===");
        Protocol adaptive = loader.getAdaptiveExtension();
        System.out.println("  adaptive 类型: " + adaptive.getClass().getName() + " (真实: Dubbo 字节码生成 Protocol$Adaptive)\n");
        System.out.println("  请求 A: dubbo://...");
        System.out.println("  结果: " + adaptive.export("dubbo://10.0.0.1:20880/OrderService") + "\n");
        System.out.println("  请求 B: http://...");
        System.out.println("  结果: " + adaptive.export("http://10.0.0.1:8080/OrderService"));
        System.out.println("  >> 同一个 adaptive 对象, 按 URL 参数路由到不同实现 —— Dubbo 一切组件(Protocol/Cluster/LoadBalance)的扩展方式\n");

        System.out.println("=== 速记 ===");
        System.out.println("  4特性: 配置灵活(key=value) / 自动包装(构造器带接口参数) / 自动注入(setter) / 自适应(URL选实现)");
        System.out.println("  注解: @SPI(接口) / @Adaptive(方法或类) / @Activate(自动激活, group+value条件)");
        System.out.println("  加载目录优先级: dubbo/internal > dubbo > services (兼容JDK SPI)");
        System.out.println("  特性3 IoC 自动注入本实验没演示: 实现类有 setXxx(XxxSpi) setter 时, Dubbo 自动注入对应 SPI 扩展");
    }
}

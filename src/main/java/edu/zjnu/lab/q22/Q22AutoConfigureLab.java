package edu.zjnu.lab.q22;

import edu.zjnu.lab.q22.spi.Cache;
import edu.zjnu.lab.q22.spi.DefaultCache;
import edu.zjnu.lab.q22.spi.UserCache;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Q22 · Spring Boot 自动装配实验imports 扫描 + 条件装配 @ConditionalOnMissingBean
 *
 * 运行: mvn -q compile exec:java -Dexec.mainClass=edu.zjnu.lab.q22.Q22AutoConfigureLab
 *
 * 验证面试点:
 * 1. 自动装配 = 读所有 jar 的 imports 文件 -> 逐个过条件过滤 -> 注册 Bean
 * 2. @ConditionalOnMissingBean: 用户自己配了就用用户的 (starter 的精髓)
 * 3. Spring Boot 2.7+: META-INF/spring/xxx.AutoConfiguration.imports 替代 spring.factories
 */
public class Q22AutoConfigureLab {

    /** 一个自动配置类: 类比 RedisAutoConfiguration */
    static class CacheAutoConfiguration {
        final Map<String, Object> container;
        final boolean redisOnClasspath; // @ConditionalOnClass(RedisOperations.class)
        final boolean isWebApp;         // @ConditionalOnWebApplication

        CacheAutoConfiguration(Map<String, Object> container, boolean redisOnClasspath, boolean isWebApp) {
            this.container = container;
            this.redisOnClasspath = redisOnClasspath;
            this.isWebApp = isWebApp;
        }

        void register() {
            if (!redisOnClasspath) {                 // @ConditionalOnClass: 依赖没引, 整个配置类跳过
                System.out.println("  [CacheAutoConfiguration] @ConditionalOnClass 不满足 -> 跳过");
                return;
            }
            if (container.containsKey("cache")) {    // @ConditionalOnMissingBean: 用户已自定义 -> 让位
                System.out.println("  [CacheAutoConfiguration] @ConditionalOnMissingBean 不满足(用户已配) -> 默认 Bean 不注册");
                return;
            }
            container.put("cache", new DefaultCache());
            System.out.println("  [CacheAutoConfiguration] 条件全过 -> 注册默认 DefaultCache");
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 实验1: 自动装配流程模拟 ===");
        // 模拟: 扫描所有 jar 的 META-INF/spring/...AutoConfiguration.imports 拿到配置类清单
        String[] importsFile = {"CacheAutoConfiguration", "DataSourceAutoConfiguration", "WebMvcAutoConfiguration"};
        System.out.println("imports 文件内容: " + Arrays.toString(importsFile));
        System.out.println("(真实: 每个 jar 一份, AutoConfigurationImportSelector 全读出+去重+按条件过滤)\n");

        Map<String, Object> container = new LinkedHashMap<>();
        System.out.println("-- 场景A: classpath 有 Redis 依赖, 用户没自定义 --");
        new CacheAutoConfiguration(container, true, true).register();
        System.out.println("  容器里的 cache = " + ((Cache) container.get("cache")).name());

        container.clear();
        System.out.println("\n-- 场景B: 用户自己配了 cache (自定义优先!) --");
        container.put("cache", new UserCache());
        new CacheAutoConfiguration(container, true, true).register();
        System.out.println("  容器里的 cache = " + ((Cache) container.get("cache")).name() + "  <- starter 默认值让位");

        container.clear();
        System.out.println("\n-- 场景C: classpath 没有 Redis 依赖 --");
        new CacheAutoConfiguration(container, false, true).register();
        System.out.println("  容器里的 cache = " + container.get("cache"));

        System.out.println("\n=== 实验2: JDK SPI (imports 机制的同族思路) ===");
        // src/main/resources/META-INF/services/edu.zjnu.lab.q22.spi.Cache 里声明了两个实现
        ServiceLoader<Cache> loader = ServiceLoader.load(Cache.class);
        for (Cache c : loader) System.out.println("  ServiceLoader 加载到: " + c.name());
        System.out.println("  >> JDK SPI 缺点: 遍历即全量实例化、无法按名取、没有条件装配 -> Dubbo 才造增强版(见 Q23 实验)\n");

        System.out.println("=== 速记 ===");
        System.out.println("  @SpringBootApplication = @SpringBootConfiguration + @EnableAutoConfiguration + @ComponentScan");
        System.out.println("  看装配了啥: 启动加 --debug 看 CONDITIONS EVALUATION REPORT / actuator/conditions");
        System.out.println("  排除: @SpringBootApplication(exclude=Xxx.class) 或 spring.autoconfigure.exclude");
        System.out.println("  2.7 改 imports 原因: spring.factories 是全能SPI太杂, 拆出单一职责文件");
    }
}

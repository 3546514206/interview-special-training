package edu.zjnu.interview.springlabs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * spring-labs 启动类。
 *
 * <p>运行方式：
 * <pre>
 *   java -jar spring-labs/target/spring-labs.jar
 *   # 或远程调试
 *   java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
 *        -jar spring-labs/target/spring-labs.jar
 * </pre>
 */
@SpringBootApplication
public class SpringLabsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringLabsApplication.class, args);
    }
}

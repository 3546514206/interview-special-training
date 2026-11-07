# spring-labs

面试专项训练 · Spring Boot 3.3.5 Web 实验模块（**JDK 17**）。

本模块是 `interview-special-training` 聚合工程下的一个独立子模块，用于练习 Spring Boot Web
的接口开发、参数校验、统一异常处理、健康检查、容器化与远程调试。

- 包名：`edu.zjnu.interview.springlabs`
- 启动类：`edu.zjnu.interview.springlabs.SpringLabsApplication`
- 依赖管理：父 pom 仍然是 `../pom.xml`（`edu.zjnu:interview-special-training:1.0-SNAPSHOT`），
  Spring Boot 版本通过 `spring-boot-dependencies:3.3.5` BOM **import** 方式引入；
- 编译级别在本模块内覆盖为 **17**（父 pom 的 1.8 配置不影响本模块）。

---

## 1. 环境要求

| 项 | 版本 |
|---|---|
| JDK | 17（`/Library/Java/JavaVirtualMachines/JDK17/Contents/Home`） |
| Maven | 3.6.3+ |
| Spring Boot | 3.3.5 |

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
```

## 2. 构建

```bash
# 在仓库根目录执行（parent 通过 ../pom.xml 解析，不依赖父 pom 登记 <module>）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -f spring-labs/pom.xml -B clean package
```

产物（文件名固定，部署脚本依赖）：

```
spring-labs/target/spring-labs.jar          # 可执行 Spring Boot jar（内嵌依赖）
spring-labs/target/spring-labs.jar.original # 原始 jar（repackage 前的备份）
```

> 若本机 `~/.m2/repository` 不可写（沙箱/只读家目录），追加
> `-Dmaven.repo.local=/tmp/spring-labs-m2` 指定一个可写的本地仓库。

只跑测试：

```bash
mvn -f spring-labs/pom.xml -B test
```

## 3. 本地运行

```bash
# 方式一：直接运行 jar
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
java -jar spring-labs/target/spring-labs.jar

# 方式二：IDEA 直接运行 SpringLabsApplication（模块 SDK 选 JDK 17）

# 方式三：Maven spring-boot:run
mvn -f spring-labs/pom.xml spring-boot:run
```

默认监听 `0.0.0.0:8080`，端口可用环境变量覆盖：`SERVER_PORT=18080 java -jar ...`。

## 4. API 清单

| # | 方法 | 路径 | 请求 | 成功响应 |
|---|---|---|---|---|
| 1 | GET | `/api/ping` | 无 | `{"status":"UP","service":"spring-labs","timestamp":"2026-11-07T21:11:23.337"}` |
| 2 | GET | `/api/hello?name=香农` | `name` 可选，缺省 `world` | `{"message":"你好, 香农!","name":"香农"}`；缺省时 `{"message":"你好, world!","name":"world"}` |
| 3 | POST | `/api/echo` | `{"message":"hello"}` | `{"echo":"hello","length":5}`；message 为空 → `400 {"error":"message 不能为空"}` |
| 4 | GET | `/api/items` | 无 | `{"items":[{"id":1,"name":"alpha"},{"id":2,"name":"beta"},{"id":3,"name":"gamma"}]}` |
| 5 | GET | `/api/slow?ms=1200` | `ms` 缺省 1000，上限 10000 | `{"sleptMs":1200}`（先睡眠 ms 毫秒） |
| 6 | GET | `/api/boom` | 无 | `500 {"error":"boom"}` |
| 7 | GET | `/actuator/health` | 无 | `{"status":"UP"}` |

`/api/ping` 的 `timestamp` 格式固定为 `yyyy-MM-dd'T'HH:mm:ss.SSS`（容器本地时间，毫秒**恒为 3 位**）：
用 `DateTimeFormatter.ofPattern` 而非 `ISO_LOCAL_DATE_TIME` 序列化，否则毫秒尾零会被吞掉
（`.530` → `.53`，`.000` → 没有小数部分），客户端写死 `\.\d{3}` 的正则会随机失配。

错误响应体统一为 `{"error":"<msg>"}`：

| 场景 | 状态码 | error |
|---|---|---|
| `@Valid` 校验失败（message 为空/纯空白/null） | 400 | `message 不能为空` |
| 请求体缺失或非法 JSON | 400 | `message 不能为空` |
| 查询参数类型错误（如 `?ms=abc`） | 400 | `参数格式不正确: ms` |
| 缺少必填查询参数 | 400 | `缺少必要参数: xxx` |
| 路径未匹配 | 404 | `资源不存在` |
| 请求方法不支持（如 `GET /api/echo`） | 405 | `不支持的请求方法: GET` |
| Content-Type 不支持（如 `text/plain`） | 415 | `不支持的 Content-Type: text/plain;charset=UTF-8` |
| 未预期异常（如 `/api/boom`） | 500 | 异常 message（如 `boom`） |

Actuator 只暴露 `health,info`（**不暴露 env**），且 `show-details=never`，所以
`/actuator/health` 只返回 `{"status":"UP"}`。

## 5. 目录结构

```
spring-labs/
├── pom.xml
├── Dockerfile              # 运行时阶段镜像（需要上下文内有 app.jar）
├── Dockerfile.build        # 多阶段自包含构建（CI 用）
├── .dockerignore
├── README.md
├── src/main/java/edu/zjnu/interview/springlabs/
│   ├── SpringLabsApplication.java
│   └── web/
│       ├── ApiController.java          # 每个 handler 独立方法，便于打断点
│       ├── GlobalExceptionHandler.java # @RestControllerAdvice 统一 {"error":"..."}
│       └── dto/                        # PingResponse / HelloResponse / EchoRequest
│                                        # EchoResponse / ItemDto / ErrorResponse
├── src/main/resources/application.yml
└── src/test/java/edu/zjnu/interview/springlabs/ApiControllerTest.java
```

## 6. 远程调试（JDWP）

服务端口 `8080`，调试端口 `5005`。

```bash
# 本地 / 服务器上以 JDWP 方式启动
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
     -jar spring-labs/target/spring-labs.jar
```

IDEA 配置：`Run > Edit Configurations > + > Remote JVM Debug`

- Host：服务器地址（如 `119.x.x.x`）
- Port：`5005`
- 断点位置建议：`ApiController` 的 `slow()`（线程处于 `TIMED_WAITING`，便于观察线程栈）、
  `boom()`、`GlobalExceptionHandler.handleUnexpected()`。

调试时用 `?name=香农` 之类的请求触发断点；`/api/slow` 适合观察 Tomcat 工作线程的栈。

## 7. 容器化

### 7.1 运行时镜像（Dockerfile）

构建上下文为 `spring-labs/`，上下文内必须有 `app.jar`：

```bash
mvn -f spring-labs/pom.xml -B clean package
cp spring-labs/target/spring-labs.jar spring-labs/app.jar
docker build -f spring-labs/Dockerfile -t spring-labs:latest spring-labs
docker run --rm -p 8080:8080 -p 5005:5005 spring-labs:latest
```

> `.dockerignore` 排除了 `src/`、`*.md` 等，但**不会**排除 `app.jar` / `target/app.jar`，
> 运行时阶段的 `COPY app.jar /app/app.jar` 始终可用。

### 7.2 多阶段自包含构建（Dockerfile.build，CI 用）

因为子模块 pom 通过 `relativePath ../pom.xml` 继承父 pom，构建上下文必须是**仓库根目录**：

```bash
docker build -f spring-labs/Dockerfile.build -t spring-labs:latest .
```

镜像内先 `maven:3.9-eclipse-temurin-17` 执行 `mvn -B -f spring-labs/pom.xml -DskipTests package`，
再把 `spring-labs/target/spring-labs.jar` 复制进 `eclipse-temurin:17-jre` 运行时镜像。
需要镜像内也跑测试时去掉 `-DskipTests`。

## 8. 测试

```bash
mvn -f spring-labs/pom.xml -B test
```

`ApiControllerTest` 基于 `@SpringBootTest` + `MockMvc`，覆盖上面 7 个接口的关键断言
（共 15 个用例，包含空 message、未匹配路径、请求方法/参数类型错误、slow 上限等边界）。

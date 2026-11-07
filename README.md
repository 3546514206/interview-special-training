# 面试专项训练

> 构建与实验环境：JDK 8（`maven.compiler.source/target=1.8`）+ Maven。
> 例外：`spring-labs` 子模块单独使用 **JDK 17 + Spring Boot 3.3.5**，需要单独指定 JDK 构建（见下方命令）。

本仓库是一个 **Maven 聚合工程（多模块）**：根目录只放聚合 `pom.xml` 与仓库级说明，
每个专题/子项目是根目录下的一个独立子模块，自带 `pom.xml`、源码、文档与本地配置。

## 目录结构

```
interview-special-training/          # 聚合工程根（packaging=pom，不产出 jar）
├── pom.xml                           # 聚合 pom：模块清单 + 公共属性/依赖/插件版本
├── README.md                         # 本文件
├── .gitignore                        # 统一忽略 .env / target / IDE 文件
├── java-labs/                        # 子模块：Java 面试实验代码
│   ├── pom.xml                       # 子模块 pom（继承根 pom）
│   ├── src/main/java/edu/zjnu/interview/
│   ├── src/main/resources/
│   ├── doc/                          # 各专题答卷 / 实验教程 / 原理图
│   ├── .env                          # 本机私有配置（gitignore，勿提交）
│   └── .env.example                  # 配置模板（提交版）
├── spring-labs/                      # 子模块：Spring Boot Web 实验（独立 JDK 17）
│   ├── pom.xml                       # 子模块 pom（继承根 pom，编译级别覆盖为 17）
│   ├── src/main/java/edu/zjnu/interview/springlabs/
│   ├── src/main/resources/application.yml
│   ├── src/test/java/edu/zjnu/interview/springlabs/
│   ├── Dockerfile                    # 仅运行时阶段（需先备好 app.jar）
│   └── Dockerfile.build              # 多阶段自包含构建（CI 用）
└── deploy/                           # 部署编排：Docker Compose / 部署脚本（不含任何凭据）
```

## 常用命令（均在仓库根目录执行）

```bash
# 构建全部子模块
mvn clean compile

# 只构建 java-labs
mvn -pl java-labs clean compile

# 跑某个实验（工作目录 = java-labs/，.env 能被自动读到）
mvn -q -pl java-labs compile exec:java -Dexec.mainClass=edu.zjnu.interview.lock.q01.L01VisibilityLab

# 构建 spring-labs（该模块要求 JDK 17，不能用默认的 JDK 8）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/JDK17/Contents/Home
mvn -f spring-labs/pom.xml -B clean package      # 产物：spring-labs/target/spring-labs.jar

# 本地起服务
java -jar spring-labs/target/spring-labs.jar      # 默认监听 8080
```

## 子模块

| 子模块 | 目录 | 内容 |
|---|---|---|
| `java-labs` | [java-labs/](java-labs/) | Java 实验代码：JVM / 并发 / 锁 / 数据库 / Kafka，及配套答卷与实验教程 |
| `spring-labs` | [spring-labs/](spring-labs/) | Spring Boot 3.3.5 Web 实验（JDK 17）：7 个 HTTP 契约接口、异常映射、MockMvc 测试、容器化 |

后续新增子项目（如 `rag-labs`）的步骤：

1. 在根目录建同名目录，写一份带 `<parent>` 指向根 `pom.xml` 的子 `pom.xml`；
2. 在根 `pom.xml` 的 `<modules>` 里登记一行 `<module>xxx</module>`；
3. 公共依赖版本放根 `pom.xml` 的 `<dependencyManagement>`，公共插件版本放 `<pluginManagement>`；
4. 子模块自带的私有配置统一命名为 `.env`（根 `.gitignore` 已用 `**/.env` 全局忽略），模板命名 `.env.example`。

## 文档路径约定

- 文档正文里的代码/资源路径以 **仓库根** 为基准，统一带 `java-labs/` 前缀，例如
  `java-labs/src/main/java/edu/zjnu/interview/lock/q01/L01VisibilityLab.java`；
- Markdown 链接一律用 **相对当前文件** 的路径，保证 GitHub / IDE 预览都能正常渲染。

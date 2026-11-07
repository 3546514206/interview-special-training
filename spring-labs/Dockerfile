# spring-labs 运行时镜像（仅运行时阶段）
#
# 构建上下文 = spring-labs/ 目录，且上下文内必须存在 app.jar：
#   mvn -f spring-labs/pom.xml -B clean package
#   cp spring-labs/target/spring-labs.jar spring-labs/app.jar
#   docker build -f spring-labs/Dockerfile -t spring-labs:latest spring-labs
#
# 注意：.dockerignore 不会排除 app.jar / target/app.jar。

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY app.jar /app/app.jar

# 8080: HTTP 服务端口；5005: JDWP 远程调试端口
EXPOSE 8080 5005

ENTRYPOINT ["java","-jar","/app/app.jar"]

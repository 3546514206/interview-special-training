# ==========================================================================
#  spring-labs · 仅运行时镜像（不含编译工具链）
#
#  构建上下文里只应有两个文件：app.jar + 本 Dockerfile。
#  本地产物 spring-labs/target/spring-labs.jar 上传后重命名为 app.jar。
#
#  构建：
#    docker build -t spring-labs:jdwp .
# ==========================================================================
FROM eclipse-temurin:17-jre

# 时区设为 UTC，容器内时间与宿主机/腾讯云控制台一致
ENV TZ=UTC

WORKDIR /app

# 宿主机只有 ~1.9G 物理内存，堆不宜过大；SerialGC 省内存省线程。
# compose 里的 JAVA_OPTS 会覆盖这一行（以 compose 注入的为准）。
ENV JAVA_OPTS="-Xms128m -Xmx640m -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -Dfile.encoding=UTF-8"

# 非 root 运行
RUN groupadd -r -g 10001 spring \
    && useradd -r -u 10001 -g spring -d /app -s /sbin/nologin spring \
    && mkdir -p /app/logs \
    && chown -R spring:spring /app

COPY --chown=spring:spring app.jar /app/app.jar

USER spring

# 8080 = 业务 HTTP；5005 = JDWP
EXPOSE 8080 5005

# 用 sh -c 以便展开 $JAVA_OPTS；exec 让 java 成为 1 号进程，
# 这样 docker stop / compose down 的 SIGTERM 能直达 JVM，触发优雅停机。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

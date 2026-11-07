# spring-labs · 腾讯云 119 Docker 部署手册

把 `spring-labs`（Spring Boot 3.3.5 / JDK 17）以「仅运行时」镜像部署到腾讯云 119 服务器，
并开启 JDWP 远程调试，供本地 IDEA 直接附加调试。

> **关于主机地址**：真实主机 / 端口 / 用户名 / 私钥路径见 external-server skill 的服务器清单
> `references/servers/tencent-cloud-119.txt`，**不写入本仓库**。
> 本文所有连接信息一律用占位符表示：`<服务器IP>`、`<端口>`、`<用户名>`、`<私钥路径>`；
> 部署时通过 `deploy.sh --host/--port/--user/--key` 参数传入。
> （`<服务器IP>` 之外出现的 `/home/ubuntu/app/` 是部署目录，不是凭据。）

---

## 1. 架构与端口

```
本地开发机                        腾讯云 119 (<服务器IP>)
┌──────────────┐                 ┌─────────────────────────────────────────┐
│ IDEA         │  ──JDWP 5005──▶ │ 宿主机 0.0.0.0:5005 ─▶ 容器 5005        │
│  远程调试     │  或 SSH 隧道     │   （JDWP，等价 RCE 入口，需限源 IP）      │
│              │                 │                                         │
│ .http 客户端 │  ──HTTP 8080──▶ │ 宿主机 0.0.0.0:8080 ─▶ 容器 8080        │
│              │  或 SSH 隧道     │   （业务 HTTP）                          │
└──────────────┘                 │                                         │
                                 │ Docker 29.1.3 / Compose v2.40.3         │
                                 │ 远端目录 /home/ubuntu/app/              │
                                 │ 内存 ~1.9G，无 java/maven（不在远端编译）  │
                                 └─────────────────────────────────────────┘
```

| 端口 | 容器内 | 宿主机 | 说明 |
|------|--------|--------|------|
| 8080 | 8080 | `${APP_PORT:-8080}` | 业务 HTTP（`/actuator/health` 健康检查） |
| 5005 | 5005 | `${JDWP_BIND:-0.0.0.0}:${JDWP_PORT:-5005}` | JDWP 远程调试 |

**JDWP 监听地址说明**：容器内用 `address=*:5005`（JDK 9+ 语法，`*` = 监听所有网卡），
这样 Docker 无论把端口映射到宿主机 `127.0.0.1` 还是 `0.0.0.0` 都能连通；
宿主机侧绑定哪个地址由 `.env` 的 `JDWP_BIND` 决定。

---

## 2. 前置条件

| 位置 | 要求 |
|------|------|
| 本地 | JDK 17、Maven、`ssh` / `scp` 可用 |
| 本地 | 已构建出 `spring-labs/target/spring-labs.jar`（**不要在服务器上编译**，服务器没装 java/maven） |
| 远端 | Docker 29.1.3 + `docker compose` v2（注意：**没有** `docker-compose` 命令） |
| 腾讯云 | 安全组放通 TCP 8080 / 5005 入方向（见第 5 节；服务器改不了，只能在控制台操作） |

本地构建：

```bash
mvn -f spring-labs/pom.xml -B clean package
ls -l spring-labs/target/spring-labs.jar
```

---

## 3. 目录结构

```
deploy/
├── docker-compose.yml   # 运行时编排（服务、端口、JDWP、日志轮转、健康检查）
├── .env.example         # 配置样例：端口、JDWP_BIND、镜像标签（不含凭据）
├── Dockerfile           # 仅运行时镜像：eclipse-temurin:17-jre + app.jar
├── deploy.sh            # 幂等部署脚本（上传→构建→compose→健康检查→摘要）
└── README.md
```

> **关于两个 Dockerfile**
>
> | 文件 | 用途 |
> |------|------|
> | `spring-labs/Dockerfile` | 应用模块自带的运行时镜像，`ENTRYPOINT ["java","-jar","/app/app.jar"]`，供本地 `docker build` 用 |
> | `deploy/Dockerfile` | **本部署流程实际使用**的运行时镜像：同样 `FROM eclipse-temurin:17-jre`，但入口用 `sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`，因此 compose 注入的 `JAVA_OPTS`（堆大小 / GC / 堆转储路径）能真正生效；并以非 root 用户（uid 10001 `spring`）运行 |
>
> 两者都不含编译工具链，构建上下文里只有 `app.jar` + `Dockerfile`。

远端目录（`--remote-dir`，默认 `/home/ubuntu/app/`）：

```
/home/ubuntu/app/
├── build/                 # 构建上下文：只有 app.jar + Dockerfile 两个文件
│   ├── app.jar            # 由 spring-labs.jar 上传重命名而来
│   └── Dockerfile
├── docker-compose.yml
├── .env
└── logs/                  # 堆转储等（属主 uid 10001，容器内可写）
```

---

## 4. 部署

### 4.1 一键脚本（推荐）

```bash
cd deploy
./deploy.sh \
  --host <服务器IP> \
  --port <端口> \
  --user <用户名> \
  --key <私钥路径> \
  --local-jar ../spring-labs/target/spring-labs.jar \
  --remote-dir /home/ubuntu/app
```

脚本行为（**幂等**，可重复执行）：

1. 校验本地 jar / Dockerfile / compose 文件存在；
2. 远端创建 `<remote-dir>/build` 与 `<remote-dir>/logs`（logs 属主设为 10001，保证容器内可写）；
3. 上传 `app.jar` → `build/`、`Dockerfile` → `build/`、`docker-compose.yml` 与渲染后的 `.env` → 根目录；
4. 校验构建上下文**只有** `app.jar` + `Dockerfile`；
5. `docker build -t spring-labs:jdwp .`，并额外打 `spring-labs:jdwp-<时间戳>` 作为回滚点；
6. `docker compose -p spring-labs up -d --remove-orphans`；
7. 轮询 `http://127.0.0.1:8080/actuator/health` 直到 `{"status":"UP"}`（超时默认 240s）；
8. 打印 `docker ps`、容器详情、生效的环境变量、宿主机端口监听、镜像列表。

常用可选参数：

| 参数 | 作用 |
|------|------|
| `--jdwp-bind 127.0.0.1` | 切回「仅本机可达 + SSH 隧道」模式 |
| `--image-tag <tag>` | 换镜像标签（每次部署建议换，便于回滚） |
| `--health-timeout <秒>` | 健康检查最长等待时间 |
| `--skip-build` | 不上传不构建，只执行 `compose up` |
| `--rollback <tag>` | 回滚到指定镜像标签 |
| `--dry-run` | 只打印动作，不真正连接远端 |

### 4.2 手动部署

```bash
# 1) 上传
scp -i <私钥路径> -P <端口> ../spring-labs/target/spring-labs.jar \
    <用户名>@<服务器IP>:/home/ubuntu/app/build/app.jar
scp -i <私钥路径> -P <端口> Dockerfile \
    <用户名>@<服务器IP>:/home/ubuntu/app/build/Dockerfile
scp -i <私钥路径> -P <端口> docker-compose.yml .env \
    <用户名>@<服务器IP>:/home/ubuntu/app/

# 2) 构建（构建上下文里只有 app.jar + Dockerfile）
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'cd /home/ubuntu/app/build && docker build -t spring-labs:jdwp .'

# 3) 启动
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'cd /home/ubuntu/app && docker compose -p spring-labs up -d'

# 4) 健康检查
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'curl -s http://127.0.0.1:8080/actuator/health'
```

---

## 5. 腾讯云安全组

**服务器本身不拦端口**（`ufw status` = inactive，iptables INPUT = ACCEPT），
能不能从公网访问，**完全取决于腾讯云控制台的安全组**。服务器上改不了。

### 5.1 当前状态：8080 / 5005 已放通 ✅

部署完成后实测（2026-11-07）：

| 探测目标 | 结果 |
|----------|------|
| `http://<服务器IP>:8080/actuator/health` | **通**，HTTP 200，连续 5/5 次成功 |
| `<服务器IP>:5005` JDWP 握手 | **通**，回显 `JDWP-Handshake`，连续 5/5 次成功 |
| 宿主机 `127.0.0.1:8080` / `127.0.0.1:5005` | 通 |
| 任意其他端口（如临时监听的 19099） | **不通**（连接被立即丢弃） |

结论：安全组是**按端口放通**，8080 与 5005 已有放通规则；**当前公网直连可用**。

> ⚠️ **仍需在控制台确认 5005 的「来源」**。
> 服务器侧读不到安全组规则，因此无法判断这两条规则的来源是否已限制为你的固定 IP。
> 如果 5005 的来源是 `0.0.0.0/0`，那么 JDWP 这个**远程代码执行入口此刻对整个互联网开放**
> —— 请到控制台把来源收紧为你的固定出口 IP。

### 5.2 建议的规则清单

在 **腾讯云控制台 → 云服务器 → 安全组 → 入方向规则**：

| 协议 | 端口 | 来源 | 用途 | 状态 |
|------|------|------|------|------|
| TCP | 8080 | `0.0.0.0/0`（或你的固定 IP） | 业务 HTTP | 已放通 |
| TCP | 5005 | **你的固定出口 IP**（如 `x.x.x.x/32`） | JDWP 远程调试 | 已放通，**请确认来源是否已收紧** |
| TCP | 22 | 你的固定 IP | SSH 管理 | 已放通 |

> ⚠️ **不要把 5005 的来源设成 `0.0.0.0/0`。**
> JDWP 协议**不加密**，且连接上去就能加载任意代码 —— 它等价于一个
> **远程代码执行（RCE）入口**。限制来源 IP 是唯一的有效防护
> （服务器侧的 `ufw` / `iptables` 对 Docker 发布端口不生效，必须靠云安全组）。

---

## 6. 两条访问路径

### 6.1 路径 A —— 公网直连（✅ 当前默认可用路径）

安全组已放通 8080 / 5005，直接连，**无需 SSH 隧道**：

* `.http` 请求文件：`http://<服务器IP>:8080`
* IDEA 远程调试：`host = <服务器IP>`，`port = 5005`

### 6.2 路径 B —— SSH 隧道（兜底 / 收紧方案）

主机上只需要 22 端口可达，8080 / 5005 全部走私钥加密的隧道：

```bash
# 保持隧道常驻（前台运行，Ctrl-C 断开）
ssh -N \
  -L 8080:127.0.0.1:8080 \
  -L 5005:127.0.0.1:5005 \
  -i <私钥路径> <用户名>@<服务器IP>
```

隧道建立后：

* IDEA 远程调试：`host = localhost`，`port = 5005`
* `.http` 请求文件：`http://localhost:8080`

> **路径 B 的前置条件**：JDWP 在**宿主机回环地址**上可达，
> 即 `.env` 里 `JDWP_BIND=127.0.0.1`。
> 若当前是 `JDWP_BIND=0.0.0.0`，宿主机回环同样能连上（`0.0.0.0` 包含 `127.0.0.1`），
> 路径 B 也能工作（已实测 `127.0.0.1:5005` 握手成功）；
> 但**收紧方案**就是把 `JDWP_BIND` 改回 `127.0.0.1`，
> 这样即使安全组误放通了 5005，公网也连不上调试端口。

一行切回收紧模式：

```bash
sed -i 's/^JDWP_BIND=.*/JDWP_BIND=127.0.0.1/' /home/ubuntu/app/.env
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'cd /home/ubuntu/app && docker compose -p spring-labs up -d --force-recreate'
```

---

## 7. IDEA 远程调试配置

1. `Run/Debug Configurations` → `+` → **Remote JVM Debug**
2. Host / Port：
   * 路径 A：`<服务器IP>` / `5005`
   * 路径 B：`localhost` / `5005`
3. **不要**改挂起选项（本项目用的是 `server=y,suspend=n`，
   JVM 正常启动后才接受调试连接）
4. 断点可直接命中 Java 源码

容器内 JDWP 已由镜像的环境变量开启：

```
JAVA_TOOL_OPTIONS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

### 7.1 JDWP 已生效的三个标志（均已实测通过）

**① 容器启动日志出现这一行** —— 调试代理已加载并开始监听：

```bash
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'docker logs spring-labs | grep "Listening for transport"'
# 期望输出：Listening for transport dt_socket at address: 5005
```

**② JDWP 握手成功** —— 协议层确认端口后面是真正的 JDWP agent：

```bash
# 本机执行（nc）
printf 'JDWP-Handshake' | nc <服务器IP> 5005
# 期望输出：JDWP-Handshake

# 没有 nc 时用 python3
python3 -c "
import socket
s = socket.create_connection(('<服务器IP>', 5005), 5)
s.sendall(b'JDWP-Handshake')
print(s.recv(14))
s.close()"
# 期望输出：b'JDWP-Handshake'
```

**③ 宿主机端口已监听**：

```bash
ssh -i <私钥路径> <用户名>@<服务器IP> 'ss -tln | grep 5005'
# 期望输出：LISTEN 0 4096 0.0.0.0:5005 0.0.0.0:*
```

容器内核对（`eclipse-temurin:17-jre` 镜像里**没有** `ss` / `netstat`，读 `/proc/net/tcp6`；
5005 = 十六进制 `138D`，状态 `0A` = LISTEN）：

```bash
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'docker exec spring-labs sh -c "cat /proc/net/tcp6 | grep -i 138D"'
```

---

## 8. 日志与排查

```bash
# 实时日志
ssh -i <私钥路径> <用户名>@<服务器IP> 'docker logs -f --tail 200 spring-labs'

# 健康检查 + 容器状态
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'curl -s http://127.0.0.1:8080/actuator/health; docker ps'

# 容器内环境变量确认（JDWP 参数是否生效）
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'docker inspect -f "{{range .Config.Env}}{{println .}}{{end}}" spring-labs | grep JAVA_TOOL_OPTIONS'

# 容器内身份 / logs 目录可写性
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'docker exec spring-labs sh -c "id; touch /app/logs/.w && echo WRITABLE"'

# 堆转储（OOM 时自动生成）
ssh -i <私钥路径> <用户名>@<服务器IP> 'ls -lh /home/ubuntu/app/logs'
```

日志轮转由 compose 的 `logging` 段控制：`json-file` 驱动，`max-size=10m`，`max-file=3`
（单个容器最多约 30MB 日志）。

---

## 9. 回滚

每次部署都会额外打一个带时间戳的标签（`spring-labs:jdwp-<YYYYmmdd-HHMMSS>`），
compose 只使用稳定标签 `spring-labs:jdwp`。回滚步骤：

```bash
# 1) 查看可用回滚点
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'docker images spring-labs --format "{{.Repository}}:{{.Tag}}  {{.CreatedSince}}"'

# 2) 把旧标签重新打成稳定标签并重建容器
ssh -i <私钥路径> <用户名>@<服务器IP> 'cd /home/ubuntu/app &&
  docker tag spring-labs:jdwp-<旧时间戳> spring-labs:jdwp &&
  docker compose -p spring-labs up -d --force-recreate'

# 3) 验证
ssh -i <私钥路径> <用户名>@<服务器IP> \
  'curl -s http://127.0.0.1:8080/actuator/health'
```

或者用脚本一步到位：

```bash
./deploy.sh --host <服务器IP> --port <端口> --user <用户名> --key <私钥路径> \
            --rollback jdwp-<旧时间戳>
```

回滚只换镜像，**远端 `.env` 里的端口 / JDWP_BIND 配置保持不变**。
部署脚本会自动清理旧回滚标签，只保留最近 3 个。

---

## 10. 排障清单

> 当前公网 8080 / 5005 **均已放通**，下表前几行是「将来改端口或收紧安全组后」才会遇到的故障。

| 现象 | 可能原因 | 处置 |
|------|----------|------|
| **改了端口 / 收紧了安全组后**，`curl http://<服务器IP>:8080` 超时 | 安全组没放通新端口 | 控制台加对应端口入方向；或改走 SSH 隧道（路径 B） |
| **改了端口 / 收紧了安全组后**，IDEA attach 转圈直到超时 | 安全组没放通 5005 / `JDWP_BIND` 不是 `0.0.0.0` 且没建隧道 / 隧道断了 | 放通 5005（限源 IP）；或建 SSH 隧道并用 `localhost:5005` |
| IDEA 报 `Connection refused` | 容器没起来，或 5005 没监听 | `docker ps` 看容器状态；`docker logs spring-labs`；按第 7.1 节三个标志逐项确认 |
| 容器反复重启 | 堆内存不足 / 应用启动异常 | `docker logs --tail 200 spring-labs`；调 `JAVA_OPTS` 的 `-Xmx`（宿主机仅 ~1.9G 内存） |
| `docker compose` 报 `no such file: docker-compose` | 用了旧版命令 | 本机是 Compose v2，命令是 `docker compose`（无连字符） |
| 健康检查一直 `starting` / 超时 | Spring Boot 冷启动慢 | `start_period` 已是 45s；可用 `--health-timeout` 调大；看日志确认卡在哪 |
| `docker build` 报找不到 `app.jar` | 上传路径不对 | 确认 jar 上传到了 `<remote-dir>/build/app.jar` |
| 构建上下文有多余文件 | 脚本会主动中止 | 清理 `<remote-dir>/build/`，只保留 `app.jar` + `Dockerfile` |
| `bind: address already in use` | 宿主机端口被占 | `ss -tln | grep -E ':(8080\|5005)'`；改 `.env` 里的端口后 `up -d --force-recreate` |
| 8080 通但 5005 不通 | 只放通了 8080 | 安全组还需放通 5005（记得限制来源 IP） |
| `curl` 返回 `{"status":"DOWN"}` | 应用内部依赖异常 | `docker logs --tail 100 spring-labs` 看具体组件 |
| 容器内 `/app/logs` 写不进去 | 宿主机 logs 目录属主不对 | `sudo chown 10001:10001 /home/ubuntu/app/logs` 后重启容器（`deploy.sh` 已自动处理） |

---

## 11. 安全须知

1. **JDWP = 未加密的 RCE 入口**。任何能连上 5005 的人都可以在你的 JVM 里执行任意代码。
   必须在腾讯云安全组把 5005 入方向来源限制为你的固定出口 IP。
2. **调试端口只在需要时开放**。调完把 `JDWP_BIND` 改回 `127.0.0.1`（隧道模式），
   或者把安全组的 5005 规则删掉。
3. **仓库里不含任何凭据，也不含服务器 IP**：SSH 用户名、私钥路径、私钥内容都不在 `deploy/` 下；
   部署脚本通过 `--host` / `--port` / `--user` / `--key` 传入。
4. **不要在服务器上装 java / maven**。构建在本地完成，服务器只跑运行时镜像。

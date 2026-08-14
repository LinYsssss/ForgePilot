# TLS 部署叠加层

给单机演示栈加 HTTPS 终止。设计原则:**叠加不侵入**——全部 TLS 改动由本目录三个文件承载,基础栈(`deploy/docker-compose.yml` / `deploy/nginx.conf`)零改动;不叠加时行为与原来完全一致。

| 文件 | 作用 |
| --- | --- |
| `nginx-tls.conf` | 443 终止 + 80→301 跳转;逐条继承基础 nginx.conf 的安全头组、SSE 专段、actuator 拦截语义 |
| `docker-compose.tls.yml` | compose 叠加文件:替换 nginx 配置挂载、挂证书目录、发布 443 |
| `gen-self-signed.sh` | 自签证书生成(幂等,`--force` 重签) |

## 一、自签开箱(演示/内网)

```bash
cd deploy

# 1. 生成自签证书。参数为额外要写进 SAN 的主机(服务器公网 IP / 域名),
#    不传则只含 localhost+127.0.0.1 —— 远程访问必须把实际访问地址传进去。
./tls/gen-self-signed.sh 203.0.113.7 demo.example.com

# 2. 叠加启动(顺序固定:基础文件在前,tls 在后)
docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml up -d

# 3. 验收
curl -kI https://localhost/                    # 200,且响应带 Strict-Transport-Security
curl -sI http://localhost/ | head -n 1         # 301(80 只做跳转)
```

自签证书浏览器会告警,属预期(演示时点「继续访问」);要消除告警只能换真实证书。

注意:防火墙/云安全组需放行 443/tcp(80 保留,用于明文入口跳转)。

## 二、换真实证书

拿到 CA 签发的证书后,同名放进 `tls/certs/`,无需改任何配置:

```text
tls/certs/server.crt   # 证书。若 CA 提供中间链,这里放 fullchain(站点证书在前、中间证书拼接在后)
tls/certs/server.key   # 私钥(保持 600 权限)
```

然后重载 nginx:`docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml exec nginx nginx -s reload`。

`tls/certs/` 已被 gitignore,证书与私钥永不入库;私钥与 `.env` 同一纪律,走安全渠道单独保管。

## 三、回滚

启动时不带 tls 叠加文件即回到纯 HTTP 原状,基础栈零 diff:

```bash
docker compose -f docker-compose.yml -f tls/docker-compose.tls.yml down
docker compose up -d
```

**HSTS 坑**:`nginx-tls.conf` 里的 HSTS(max-age 180 天)在**受信证书**下会被浏览器记住——之后该主机名的纯 HTTP 访问会被浏览器强制升级 https 而打不开,需等 max-age 过期或手动清除浏览器 HSTS 缓存(Chrome:`chrome://net-internals/#hsts`)。自签证书未被信任,浏览器不会记 HSTS,回滚无此问题。

## 四、维护契约

- `nginx-tls.conf` 的 443 server 块是基础 `nginx.conf` 的语义副本:**基础配置改动必须同步到 TLS 副本**(安全头、location、CSP 全部在内);两份不一致时以基础配置语义为准修正副本。
- 栈级验收(https 走通登录会话、SSE 不断流、80 跳转)在有 Docker 的服务器侧执行,清单见 `.trellis/tasks/08-13-production-hardening/research/server-acceptance-checklist.md`。

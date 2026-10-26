#!/usr/bin/env python3
"""本地 HTTP 代理 -> 上游 SOCKS5（带认证）转换器。

## 为什么需要它

JVM 的 SOCKS5 认证无法经系统属性传递：`-Djava.net.socks.username` / `-DsocksProxyHost`
组合实测无效，Gradle 直接报 `SOCKS : authentication failed`（JDK 需要自定义
`Authenticator` 才会读取凭据）。而上游代理只提供 SOCKS5 + 认证。

做法：本地起一个**免认证的 HTTP 代理**，Gradle 指向它；本进程把 CONNECT 隧道转发到
上游 SOCKS5（带用户名 / 密码）。JVM 侧零配置，只认本地 HTTP 代理。

## 凭据来源（不硬编码）

全部经环境变量传入，脚本本身不含任何密钥：

| 变量 | 必填 | 说明 |
|---|---|---|
| `MPMT_PROXY_SOCKS_HOST` | 是 | 上游 SOCKS5 主机 |
| `MPMT_PROXY_SOCKS_PORT` | 是 | 上游 SOCKS5 端口 |
| `MPMT_PROXY_USER` | 是 | SOCKS5 用户名 |
| `MPMT_PROXY_PASSWORD` | 是 | SOCKS5 密码 |
| `MPMT_PROXY_LISTEN_PORT` | 否 | 本地监听端口，默认 24081 |

## 用法

```bash
export MPMT_PROXY_SOCKS_HOST=203.0.113.10
export MPMT_PROXY_SOCKS_PORT=1080
export MPMT_PROXY_USER=your-user
export MPMT_PROXY_PASSWORD=your-password
nohup python3 tools/socks2http.py > .tmp/socks2http.log 2>&1 &
```

随后把 Gradle 的 `http(s).proxyHost/Port` 指向 `127.0.0.1:$MPMT_PROXY_LISTEN_PORT`
（见 `~/.gradle/gradle.properties`）。

仅监听 127.0.0.1，不暴露到局域网。
"""

import os
import selectors
import socket
import struct
import sys
import threading

LISTEN_HOST = "127.0.0.1"
BUFFER = 65536


def _require(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        sys.stderr.write(
            "缺少环境变量 %s。请先 export 上游 SOCKS5 连接参数，见本文件头部说明。\n" % name
        )
        sys.exit(2)
    return value


def _socks5_auth(host: str, port: int) -> socket.socket:
    """连上游并完成用户名 / 密码认证，返回已认证的 socket。"""
    user = _require("MPMT_PROXY_USER").encode()
    password = _require("MPMT_PROXY_PASSWORD").encode()

    sock = socket.create_connection((host, port), timeout=20)
    # 只声明支持「用户名 / 密码」认证
    sock.sendall(b"\x05\x01\x02")
    if sock.recv(2) != b"\x05\x02":
        sock.close()
        raise RuntimeError("上游未选择用户名密码认证")

    sock.sendall(b"\x01" + bytes([len(user)]) + user + bytes([len(password)]) + password)
    if sock.recv(2)[1:] != b"\x00":
        sock.close()
        raise RuntimeError("上游 SOCKS5 认证失败（检查 MPMT_PROXY_USER / MPMT_PROXY_PASSWORD）")
    return sock


def connect_upstream(target_host: str, target_port: int) -> socket.socket:
    """与上游 SOCKS5 建立到目标的隧道，返回已连通的 socket。"""
    host = _require("MPMT_PROXY_SOCKS_HOST")
    port = int(_require("MPMT_PROXY_SOCKS_PORT"))

    # 优先用域名（让上游做远程 DNS），失败再退回 IPv4 字面量
    sock = _socks5_auth(host, port)
    try:
        host_bytes = target_host.encode()
        sock.sendall(
            b"\x05\x01\x00\x03" + bytes([len(host_bytes)]) + host_bytes + struct.pack("!H", target_port)
        )
        reply = sock.recv(10)
        if len(reply) >= 2 and reply[1] == 0x00:
            return sock
        sock.close()
    except OSError:
        sock.close()

    sock = _socks5_auth(host, port)
    ip = socket.gethostbyname(target_host)
    sock.sendall(b"\x05\x01\x00\x01" + socket.inet_aton(ip) + struct.pack("!H", target_port))
    reply = sock.recv(10)
    if len(reply) < 2 or reply[1] != 0x00:
        sock.close()
        raise RuntimeError("上游 CONNECT 失败：%r" % (reply,))
    return sock


def pump(a: socket.socket, b: socket.socket) -> None:
    """双向转发直到任一端关闭。"""
    sel = selectors.DefaultSelector()
    sel.register(a, selectors.EVENT_READ, b)
    sel.register(b, selectors.EVENT_READ, a)
    try:
        while True:
            for key, _ in sel.select(timeout=300):
                src = key.fileobj
                dst = key.data
                data = src.recv(BUFFER)
                if not data:
                    return
                dst.sendall(data)
    finally:
        sel.close()


def handle(client: socket.socket) -> None:
    """处理一个客户端连接（只支持 CONNECT 隧道）。"""
    try:
        client.settimeout(20)
        header = b""
        while b"\r\n\r\n" not in header:
            chunk = client.recv(BUFFER)
            if not chunk:
                return
            header += chunk

        line = header.split(b"\r\n", 1)[0].decode("latin-1")
        parts = line.split()
        if len(parts) < 2 or parts[0].upper() != "CONNECT":
            client.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return

        host, _, port_text = parts[1].rpartition(":")
        port = int(port_text) if port_text.isdigit() else 443
        upstream = connect_upstream(host, port)
        client.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        client.settimeout(None)
        pump(client, upstream)
        upstream.close()
    except Exception as exc:  # 代理进程不能因单连接失败退出
        sys.stderr.write("handle error: %r\n" % (exc,))
        sys.stderr.flush()
    finally:
        try:
            client.close()
        except OSError:
            pass


def main() -> None:
    listen_port = int(os.environ.get("MPMT_PROXY_LISTEN_PORT", "24081"))
    _require("MPMT_PROXY_SOCKS_HOST")
    _require("MPMT_PROXY_SOCKS_PORT")
    _require("MPMT_PROXY_USER")
    _require("MPMT_PROXY_PASSWORD")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, listen_port))
    server.listen(128)
    print(
        "本地 HTTP 代理已就绪：http://%s:%d -> socks5://%s:%s"
        % (LISTEN_HOST, listen_port, os.environ["MPMT_PROXY_SOCKS_HOST"], os.environ["MPMT_PROXY_SOCKS_PORT"]),
        flush=True,
    )
    while True:
        client, _ = server.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()

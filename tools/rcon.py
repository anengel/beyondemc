"""极简 Minecraft RCON 客户端 —— 供无头验证在专用服务器上发送控制台指令。

用法:
    python rcon.py <host> <port> <password> <command> [command...]

存在理由：Gradle 的 runServer 任务**不把 stdin 转发**给服务端 JVM，
因此 `runServer | echo reload` 这类管道收不到指令（实测：日志停在自检，reload 无任何输出）。
无头环境下要在服务器上跑 `/reload`（这是让 ProjectE 构建 EMC 表、进而触发
EMCRemapEvent → 物化重算的唯一无玩家途径），只能走 RCON。
"""
import socket
import struct
import sys


def _pack(req_id, req_type, body):
    payload = struct.pack("<ii", req_id, req_type) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _recv(sock):
    raw_len = _read_exactly(sock, 4)
    if raw_len is None:
        return None, None, None
    (length,) = struct.unpack("<i", raw_len)
    data = _read_exactly(sock, length)
    if data is None:
        return None, None, None
    req_id, req_type = struct.unpack("<ii", data[:8])
    body = data[8:-2].decode("utf-8", "replace")
    return req_id, req_type, body


def _read_exactly(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def main(argv):
    host, port, password = argv[0], int(argv[1]), argv[2]
    commands = argv[3:]

    sock = socket.create_connection((host, port), timeout=15)
    sock.settimeout(15)

    sock.sendall(_pack(1, 3, password))  # type 3 = auth
    req_id, _, _ = _recv(sock)
    if req_id == -1:
        print("[rcon] 认证失败")
        return 2
    print("[rcon] 认证成功")

    rc = 0
    for cmd in commands:
        sock.sendall(_pack(2, 2, cmd))  # type 2 = command
        _, _, body = _recv(sock)
        print("[rcon] > %s" % cmd)
        if body:
            for line in body.splitlines():
                print("[rcon]   %s" % line)
    sock.close()
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

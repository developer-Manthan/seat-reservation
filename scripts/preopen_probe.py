"""
Can the target hold N open connections, and how close together can N requests be sent?

Opens N connections first (a few hundred at a time), holds them, then sends GET /healthz on all of them in the
same instant and counts the answers. It creates no data. Standard library only.

    python3 scripts/preopen_probe.py https://seat-booking.up.railway.app 20000
    python3 scripts/preopen_probe.py http://localhost:8080 2000 500      (the last number: how many to open at a time)

Every held connection needs memory on this machine (a few hundred KB each over HTTPS), so 20,000 needs several GB.
"""
import asyncio
import socket
import ssl
import sys
import time
from collections import Counter
from urllib.parse import urlsplit

target = urlsplit(sys.argv[1])
total = int(sys.argv[2]) if len(sys.argv) > 2 else 1000
parallel = int(sys.argv[3]) if len(sys.argv) > 3 else 500
https = target.scheme == "https"
host = target.hostname
port = target.port or (443 if https else 80)

try:
    import resource
    soft, hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    wanted = total + 500
    resource.setrlimit(resource.RLIMIT_NOFILE, (wanted if hard == resource.RLIM_INFINITY else min(hard, wanted), hard))
    now, _ = resource.getrlimit(resource.RLIMIT_NOFILE)
    print(f"open files this process may have: {now} (the system's ceiling is "
          f"{'unlimited' if hard == resource.RLIM_INFINITY else hard}, this run needs about {wanted})", flush=True)
except (ImportError, ValueError, OSError) as problem:
    print(f"could not read or raise the open-file limit: {problem}", flush=True)

context = ssl.create_default_context() if https else None
family, _, _, _, found = sorted(socket.getaddrinfo(host, port, type=socket.SOCK_STREAM), key=lambda i: i[0] != socket.AF_INET)[0]
address = found[0]
# Every connection needs its own local port. The system's own range can be small (about 6,000 on Railway), so each
# connection is given a port from this larger range. Set LOCAL_PORTS=system to let the system choose again.
import itertools
import os
own_ports = os.environ.get("LOCAL_PORTS") != "system"
local_ports = itertools.cycle(range(10000, 65000))
any_local_address = "::" if family == socket.AF_INET6 else "0.0.0.0"
try:
    with open("/proc/sys/net/ipv4/ip_local_port_range") as setting:
        print(f"the system's own local ports: {' to '.join(setting.read().split())}; "
              f"this run uses {'10000 to 64999' if own_ports else 'those'}", flush=True)
except OSError:
    pass
request = f"GET /healthz HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n".encode()


def clock(since_epoch):
    return time.strftime("%H:%M:%S", time.gmtime(since_epoch)) + f".{int(since_epoch % 1 * 1000):03d}"


async def main():
    gate = asyncio.Semaphore(parallel)
    opened, gave_up, failed_attempts = [], Counter(), Counter()
    began = time.perf_counter()

    async def open_one():
        last = "?"
        for _ in range(5):
            async with gate:
                try:
                    local = (any_local_address, next(local_ports)) if own_ports else None
                    reader, writer = await asyncio.wait_for(asyncio.open_connection(
                        address, port, ssl=context, server_hostname=host if https else None, local_addr=local), 60)
                    opened.append((reader, writer, time.perf_counter()))
                    if len(opened) % 1000 == 0:
                        print(f"   {len(opened)} open after {time.perf_counter() - began:.0f}s, "
                              f"failed attempts so far: {dict(failed_attempts)}", flush=True)
                    return
                except Exception as error:
                    # The reason in words, for example "OSError 24: Too many open files".
                    number = getattr(error, "errno", None)
                    text = getattr(error, "strerror", None) or str(error)
                    last = f"{type(error).__name__}{' ' + str(number) if number else ''}: {text}"[:90]
                    failed_attempts[last] += 1
        gave_up[last] += 1

    await asyncio.gather(*[open_one() for _ in range(total)])
    print(f"opened {len(opened)} of {total} connections in {time.perf_counter() - began:.1f}s ({parallel} at a time), "
          f"could not open: {dict(gave_up)}", flush=True)
    if not opened:
        return

    fire = time.perf_counter()
    idle = fire - min(at for _, _, at in opened)
    first = time.time()
    for _, writer, _ in opened:
        writer.write(request)
    last = time.time()
    print(f"sent on all {len(opened)} connections: first at {clock(first)} UTC, last at {clock(last)} UTC, "
          f"{1000 * (last - first):.0f} ms apart (the oldest connection had been idle for {idle:.0f}s)", flush=True)

    outcomes, answered = Counter(), []

    async def read_one(reader, writer):
        try:
            await writer.drain()
            raw = await asyncio.wait_for(reader.read(), 180)
            if not raw:
                outcomes["closed with no answer"] += 1
            else:
                outcomes["status " + raw.split(b" ", 2)[1].decode()] += 1
                answered.append(time.perf_counter() - fire)
        except Exception as error:
            outcomes[type(error).__name__] += 1
        finally:
            writer.close()

    await asyncio.gather(*[read_one(reader, writer) for reader, writer, _ in opened])
    print(f"answers: {dict(outcomes)}")
    if answered:
        answered.sort()
        print(f"answers arrived between {answered[0]:.2f}s and {answered[-1]:.2f}s after sending "
              f"(half of them within {answered[len(answered) // 2]:.2f}s)")


asyncio.run(main())

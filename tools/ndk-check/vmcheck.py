#!/usr/bin/env python3
"""Run NDK-built aarch64 binaries in a real aarch64 kernel under QEMU.

qemu-user cannot run nft: its netlink emulation lets through NETLINK_ROUTE,
NETLINK_KOBJECT_UEVENT and NETLINK_AUDIT only, and nft opens
NETLINK_NETFILTER in nft_ctx_new(), before it even parses --version
("Unable to initialize Netlink socket: Protocol not supported"). So the check
boots a stock OpenWrt armsr/armv8 initramfs image (its kernel has nf_tables,
nft_* expressions and the fw4 firewall loaded) in qemu-system-aarch64, attaches
the payload directory as an ext4 disk, and runs payload/run.sh on the serial
console. Static bionic binaries run on any Linux kernel, so this is the same
code that will run on the phone, minus Android's SELinux and netd.

Usage: vmcheck.py --kernel IMAGE --payload DIR [--qemu qemu-system-aarch64]
The payload must contain run.sh; its output is printed, and the exit status is
0 only if run.sh printed the line VMCHECK-OK.
"""

import argparse
import os
import select
import subprocess
import sys
import tempfile
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--kernel", required=True)
    ap.add_argument("--payload", required=True)
    ap.add_argument("--qemu", default="qemu-system-aarch64")
    ap.add_argument("--timeout", type=int, default=600)
    a = ap.parse_args()

    img = tempfile.NamedTemporaryFile(suffix=".ext4", delete=False).name
    subprocess.run(["mkfs.ext4", "-q", "-F", "-d", a.payload, img, "128M"], check=True)

    cmd = [a.qemu, "-M", "virt", "-cpu", "cortex-a53", "-smp", "2", "-m", "512",
           "-nographic", "-no-reboot", "-kernel", a.kernel,
           "-append", "console=ttyAMA0",
           "-drive", f"file={img},if=virtio,format=raw",
           "-netdev", "user,id=n0", "-device", "virtio-net-pci,netdev=n0"]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, bufsize=0)
    buf = b""
    deadline = time.time() + a.timeout
    stage = 0
    ok = False

    def send(s):
        p.stdin.write(s.encode())
        p.stdin.flush()

    try:
        while time.time() < deadline:
            r, _, _ = select.select([p.stdout], [], [], 1.0)
            if r:
                chunk = os.read(p.stdout.fileno(), 65536)
                if not chunk:
                    break
                buf += chunk
                if stage >= 2:
                    sys.stdout.write(chunk.decode("utf-8", "replace"))
                    sys.stdout.flush()
            if stage == 0 and b"Please press Enter to activate this console" in buf:
                time.sleep(8)          # let procd finish loading modules and fw4
                send("\n")
                stage = 1
            elif stage == 1 and b"root@" in buf.split(b"Please press Enter")[-1]:
                send("mkdir -p /mnt/p && mount -t ext4 /dev/vda /mnt/p && "
                     "sh /mnt/p/run.sh /mnt/p; echo VMCHECK-END; poweroff -f\n")
                stage = 2
            if stage == 2 and b"\nVMCHECK-END" in buf:
                ok = b"\nVMCHECK-OK" in buf.replace(b"\r", b"")
                time.sleep(2)
                break
    finally:
        p.kill()
        os.unlink(img)
    if stage < 2:
        sys.stdout.write(buf.decode("utf-8", "replace")[-4000:])
        print("\nvmcheck: console never came up")
    print("\nvmcheck:", "OK" if ok else "FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()

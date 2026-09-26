#!/bin/sh
# /init проверки ядра GKI в QEMU: движок собирает правила на этом ядре, nft грузит их и ручной
# набор выражений. Как запускать — README.md рядом. Итог — строка VMCHECK-OK или VMCHECK-FAIL.
export PATH=/bin
mount -t proc proc /proc; mount -t sysfs sys /sys; mount -t devtmpfs dev /dev
echo "== kernel: $(cat /proc/version | head -c 60)"
ip link set lo up
mkdir -p /tmp/state
printf '203.0.113.0/24\n' > /tmp/a.lst; printf 'example.com\n' > /tmp/d.lst
cat > /tmp/spec.json <<E2
{ "schema": 2, "lan_devices": ["rndis0"],
  "outputs": { "vpn": { "kind": "interface", "device": "wg0" } },
  "channels": [
    { "name": "s", "from": ["self"], "match": { "any": true, "allow_all": true, "proto": "udp", "ports": ["50000-65535"] }, "out": "vpn" },
    { "name": "u", "from": ["uid:10123"], "match": { "prefixes_file": "/tmp/a.lst" }, "out": "vpn" },
    { "name": "d", "from": ["uid:10124"], "match": { "domains_files": ["/tmp/d.lst"] }, "out": "vpn" },
    { "name": "l", "match": { "prefixes_file": "/tmp/a.lst" }, "out": "vpn" } ] }
E2
steer apply --dry-run --spec /tmp/spec.json --state-dir /tmp/state > /tmp/r.nft 2>/tmp/r.err; echo "== steer dry-run rc=$?"; cat /tmp/r.err
echo "== раскладка: $(grep -c '^table' /tmp/r.nft) таблиц: $(grep '^table' /tmp/r.nft | tr '\n' ' ')"
nft -f /tmp/r.nft; rc1=$?; echo "== nft -f (правила движка) rc=$rc1"
cat > /tmp/t.nft <<E3
table inet dertest {
  set s4 { type ipv4_addr; flags timeout; timeout 1h; }
  set p4 { type ipv4_addr; flags interval; elements = { 198.18.0.0/15 } }
  chain out { type route hook output priority mangle; policy accept;
    meta skuid 10123 ip daddr @p4 meta mark set mark and 0xf03fffff or 0x00400000 ct mark set mark counter
    ip daddr @s4 counter
  }
  chain nat_out { type nat hook output priority -100; policy accept;
    meta mark and 0x0fc00000 == 0x00400000 udp dport 53 redirect to :5300
  }
  chain post { type nat hook postrouting priority 100; policy accept; ct mark 0x00400000 masquerade
  }
}
E3
nft -f /tmp/t.nft; rc2=$?; echo "== nft -f (ручная проверка inet nat, set timeout, ct mark, skuid, route) rc=$rc2"
nft add element inet dertest s4 { 192.0.2.1 timeout 30s }; rc3=$?; echo "== элемент с таймаутом rc=$rc3"
echo "== правил в ядре: $(nft list ruleset | wc -l) строк"
[ $rc1 = 0 ] && [ $rc2 = 0 ] && [ $rc3 = 0 ] && echo VMCHECK-OK || echo VMCHECK-FAIL
poweroff -f

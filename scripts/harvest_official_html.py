#!/usr/bin/env python3
"""Harvest extra OpenVPN profiles from VPN Gate's official partial HTML list.

This intentionally uses only vpngate.net. The official list itself is partial, so this
adds whatever profiles are visible but absent from the CSV seed. It is not a promise
of the full VPN Gate population.
"""
import base64
import html
import os
import re
import sys
import time
import urllib.parse
import urllib.request

if len(sys.argv) < 3:
    print("usage: harvest_official_html.py EXISTING_CSV OUT_CSV [LIMIT]", file=sys.stderr)
    sys.exit(2)

existing_path, out_path = sys.argv[1], sys.argv[2]
limit = int(sys.argv[3]) if len(sys.argv) > 3 else 48
limit = max(0, min(limit, 80))
UA = "Yinglong-seed-builder/0.3.3"


def fetch(url: str, max_bytes: int) -> bytes:
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,text/plain,application/x-openvpn-profile,*/*;q=0.1",
        "Cache-Control": "no-cache",
    })
    with urllib.request.urlopen(req, timeout=25) as r:
        data = r.read(max_bytes + 1)
        if len(data) > max_bytes:
            raise RuntimeError("response too large")
        return data


def existing_ips(path: str):
    out = set()
    if not os.path.exists(path):
        return out
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            if not line or line.startswith(("#", "*")):
                continue
            parts = line.rstrip("\n").split(",")
            if len(parts) >= 2 and parts[1]:
                out.add(parts[1].strip())
    return out


def clean(s: str) -> str:
    return (s or "").replace(",", " ").replace("\r", " ").replace("\n", " ").strip()

have = existing_ips(existing_path)
rows = []
seen_links = set()
# These are the same official site with harmless URL variations. VPN Gate's returned
# partial list can vary over time/cache, so merging them may expose a few more relays.
pages = [
    "https://www.vpngate.net/en/",
    "https://www.vpngate.net/EN/",
    "https://www.vpngate.net/en/?lang=en&_yinglong=%d" % int(time.time()),
]
link_re = re.compile(r'''href=["']([^"']*do_openvpn\.aspx\?[^"']+)["']''', re.I)

for page in pages:
    if len(rows) >= limit:
        break
    try:
        raw = fetch(page, 5 * 1024 * 1024).decode("utf-8", "ignore")
    except Exception as e:
        print(f"[Yinglong] HTML source failed: {page}: {type(e).__name__}: {e}", file=sys.stderr)
        continue

    for href in link_re.findall(raw):
        if len(rows) >= limit:
            break
        href = html.unescape(href)
        full = urllib.parse.urljoin(page, href)
        if full in seen_links:
            continue
        seen_links.add(full)
        q = urllib.parse.parse_qs(urllib.parse.urlparse(full).query)
        ip = (q.get("ip") or [""])[0]
        fqdn = (q.get("fqdn") or [ip])[0]
        hid = (q.get("hid") or [""])[0]
        sid = (q.get("sid") or [""])[0]
        tcp = (q.get("tcp") or [""])[0]
        udp = (q.get("udp") or [""])[0]
        if not ip or ip in have or not hid or not sid:
            continue

        if tcp:
            proto, port, flag = "tcp", tcp, "tcp=1"
        elif udp:
            proto, port, flag = "udp", udp, "udp=1"
        else:
            continue

        key = urllib.parse.quote(f"/vpngate_{ip}_{proto}_{port}.ovpn", safe="")
        query = (
            f"{key}=&hid={urllib.parse.quote(hid)}&host={urllib.parse.quote(ip)}"
            f"&port={urllib.parse.quote(port)}&sid={urllib.parse.quote(sid)}&{flag}"
        )
        cfg_url = urllib.parse.urljoin(page, "/common/openvpn_download.aspx?") + query
        try:
            cfg = fetch(cfg_url, 2 * 1024 * 1024).decode("utf-8", "ignore")
            low = cfg.lower()
            if "client" not in low or "remote " not in low or ("<ca>" not in low and "ca " not in low):
                continue
            b64 = base64.b64encode(cfg.encode("utf-8")).decode("ascii")
            row = [
                clean(fqdn), clean(ip), "1", "0", "0", "", "", "0", "0", "0", "0",
                "", "VPN Gate official HTML", f"seed harvest {proto}:{port}", b64,
            ]
            rows.append(",".join(row))
            have.add(ip)
            print(f"[Yinglong] HTML harvested {ip} {proto}:{port}", file=sys.stderr)
        except Exception as e:
            print(f"[Yinglong] HTML config failed {ip}: {type(e).__name__}: {e}", file=sys.stderr)

with open(out_path, "w", encoding="utf-8") as out:
    for row in rows:
        out.write(row + "\n")

print(f"[Yinglong] Official HTML harvest added {len(rows)} relay profiles", file=sys.stderr)

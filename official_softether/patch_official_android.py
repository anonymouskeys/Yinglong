#!/usr/bin/env python3
from pathlib import Path
import subprocess, sys

root = Path(sys.argv[1])
if not (root / ".git").exists():
    raise SystemExit(f"official SoftEther git tree not found: {root}")

expected = "ed17437af9719ac66acab30faa29e375d613c35f"
head = subprocess.check_output(["git","-C",str(root),"rev-parse","HEAD"], text=True).strip()
if head != expected:
    raise SystemExit(f"wrong official SoftEther revision: {head} != {expected}")

protected = [
    "src/Cedar/Protocol.c",
    "src/Cedar/Connection.c",
    "src/Cedar/Session.c",
    "src/Mayaqua/Network.c",
    "src/Mayaqua/Encrypt.c",
    "src/Mayaqua/Pack.c",
]
before = {
    p: subprocess.check_output(["git","-C",str(root),"hash-object",p], text=True).strip()
    for p in protected
}

# Android app processes cannot rely on writable /tmp. In Mayaqua minimal mode
# this self-test is unnecessary. No protocol/session/network code is changed.
p = root / "src/Mayaqua/Mayaqua.c"
s = p.read_text(errors="surrogateescape")
old = "\tCheckUnixTempDir();"
new = '''#ifdef __ANDROID__
\tif (MayaquaIsMinimalMode() == false)
\t{
\t\tCheckUnixTempDir();
\t}
#else
\tCheckUnixTempDir();
#endif'''
if new not in s:
    if old not in s:
        raise SystemExit("Mayaqua CheckUnixTempDir anchor not found")
    s = s.replace(old, new, 1)
    p.write_text(s, errors="surrogateescape")

for rel, old_hash in before.items():
    new_hash = subprocess.check_output(["git","-C",str(root),"hash-object",rel], text=True).strip()
    if new_hash != old_hash:
        raise SystemExit(f"protected official core file changed unexpectedly: {rel}")

changed = subprocess.check_output(["git","-C",str(root),"diff","--name-only"], text=True).splitlines()
unexpected = [x for x in changed if x != "src/Mayaqua/Mayaqua.c"]
if unexpected:
    raise SystemExit("unexpected official source modifications: " + ", ".join(unexpected))

print("official SoftEther v4.44-9807 source verified")
print("Protocol.c / Connection.c / Session.c / Network.c are byte-for-byte official")
print("only Android Mayaqua temp-dir adaptation is applied")

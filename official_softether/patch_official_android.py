#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
if not (root / ".git").exists():
    raise SystemExit(f"official SoftEther git tree not found: {root}")

expected = "ed17437af9719ac66acab30faa29e375d613c35f"
head = subprocess.check_output(
    ["git", "-C", str(root), "rev-parse", "HEAD"], text=True
).strip()
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
    p: subprocess.check_output(
        ["git", "-C", str(root), "hash-object", p], text=True
    ).strip()
    for p in protected
}

# Android app processes cannot rely on writable /tmp. In Mayaqua minimal mode
# this self-test is unnecessary. This is an OS-layer adaptation only.
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

# Stable 4.44 declares this POSIX sigaction callback as returning void*.
# Android NDK 27 / Clang requires the POSIX type:
#     void (*)(int, siginfo_t *, void *)
# Current upstream SoftEtherVPN already fixed this exact function. Backport
# only that OS-portability fix; protocol/session/transport files stay pinned.
p = root / "src/Mayaqua/Unix.c"
s = p.read_text(errors="surrogateescape")

old1 = '''static void *signal_received_for_ignore(int sig, siginfo_t *info, void *ucontext)
{
\treturn NULL;
}'''
old2 = '''static void *signal_received_for_ignore(int sig, siginfo_t *info, void *ucontext)
{
\treturn NULL;
}'''
new2 = '''static void signal_received_for_ignore(int sig, siginfo_t *info, void *ucontext)
{
\t(void)sig;
\t(void)info;
\t(void)ucontext;
}'''

if new2 not in s:
    if old1 in s:
        s = s.replace(old1, new2, 1)
    elif old2 in s:
        s = s.replace(old2, new2, 1)
    else:
        raise SystemExit("Unix sigaction callback anchor not found")

s = s.replace(
    "sa.sa_sigaction = signal_received_for_ignore;",
    "sa.sa_sigaction = &signal_received_for_ignore;",
    1,
)
p.write_text(s, errors="surrogateescape")

for rel, old_hash in before.items():
    new_hash = subprocess.check_output(
        ["git", "-C", str(root), "hash-object", rel], text=True
    ).strip()
    if new_hash != old_hash:
        raise SystemExit(f"protected official core file changed unexpectedly: {rel}")

changed = subprocess.check_output(
    ["git", "-C", str(root), "diff", "--name-only"], text=True
).splitlines()
allowed = {
    "src/Mayaqua/Mayaqua.c",
    "src/Mayaqua/Unix.c",
}
unexpected = [x for x in changed if x not in allowed]
if unexpected:
    raise SystemExit("unexpected official source modifications: " + ", ".join(unexpected))

u = (root / "src/Mayaqua/Unix.c").read_text(errors="surrogateescape")
if "static void *signal_received_for_ignore" in u:
    raise SystemExit("bad Stable sigaction callback signature still present")
if "static void signal_received_for_ignore" not in u:
    raise SystemExit("fixed sigaction callback signature missing")
if "sa.sa_sigaction = &signal_received_for_ignore;" not in u:
    raise SystemExit("fixed sigaction assignment missing")

print("official SoftEther v4.44-9807 source verified")
print("Protocol/Connection/Session/Network/Encrypt/Pack remain byte-for-byte official")
print("Android-only Mayaqua adaptations: /tmp check + upstream sigaction fix")

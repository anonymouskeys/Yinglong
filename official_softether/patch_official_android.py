#!/usr/bin/env python3
from pathlib import Path
import re
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
    rel: subprocess.check_output(
        ["git", "-C", str(root), "hash-object", rel], text=True
    ).strip()
    for rel in protected
}

# ----------------------------------------------------------------------
# Android Mayaqua adaptation 1: no /tmp self-test in minimal mode.
# ----------------------------------------------------------------------
p = root / "src/Mayaqua/Mayaqua.c"
s = p.read_text(errors="surrogateescape")
old = "\tCheckUnixTempDir();"
new = """#ifdef __ANDROID__
\tif (MayaquaIsMinimalMode() == false)
\t{
\t\tCheckUnixTempDir();
\t}
#else
\tCheckUnixTempDir();
#endif"""
if new not in s:
    count = s.count(old)
    if count != 1:
        raise SystemExit(f"Mayaqua CheckUnixTempDir: expected 1 anchor, got {count}")
    s = s.replace(old, new, 1)
    p.write_text(s, errors="surrogateescape")

# ----------------------------------------------------------------------
# Android Mayaqua adaptation 2:
# Stable 4.44 uses the wrong POSIX sigaction callback return type:
#
#   static void *signal_received_for_ignore(...)
#
# NDK 27 correctly requires:
#
#   void (*)(int, siginfo_t *, void *)
#
# Current upstream SoftEtherVPN has already fixed exactly this function.
# Use a whitespace-tolerant regex because Stable keeps a trailing space
# after the function signature and the repository historically uses CRLF.
# ----------------------------------------------------------------------
p = root / "src/Mayaqua/Unix.c"
s = p.read_text(errors="surrogateescape")

fixed_signature = re.compile(
    r"static\s+void\s+signal_received_for_ignore\s*"
    r"\(\s*int\s+sig\s*,\s*siginfo_t\s*\*\s*info\s*,\s*void\s*\*\s*ucontext\s*\)",
    re.MULTILINE,
)

if not fixed_signature.search(s):
    buggy_function = re.compile(
        r"static\s+void\s*\*\s*signal_received_for_ignore\s*"
        r"\(\s*int\s+sig\s*,\s*siginfo_t\s*\*\s*info\s*,\s*void\s*\*\s*ucontext\s*\)"
        r"\s*\{\s*return\s+NULL\s*;\s*\}",
        re.MULTILINE | re.DOTALL,
    )

    replacement = """static void signal_received_for_ignore(int sig, siginfo_t *info, void *ucontext)
{
\t(void)sig;
\t(void)info;
\t(void)ucontext;
}"""

    s, n = buggy_function.subn(replacement, s, count=1)
    if n != 1:
        pos = s.find("signal_received_for_ignore")
        if pos >= 0:
            excerpt = repr(s[max(0, pos - 160): pos + 420])
        else:
            excerpt = "<symbol not present>"
        raise SystemExit(
            "Unix sigaction callback: expected exactly 1 buggy function; "
            f"matched {n}; nearby={excerpt}"
        )

assign_old = re.compile(
    r"sa\.sa_sigaction\s*=\s*&?\s*signal_received_for_ignore\s*;"
)
s, n_assign = assign_old.subn(
    "sa.sa_sigaction = &signal_received_for_ignore;", s, count=1
)
if n_assign != 1:
    raise SystemExit(
        f"Unix sigaction assignment: expected exactly 1 match, got {n_assign}"
    )

p.write_text(s, errors="surrogateescape")

# ----------------------------------------------------------------------
# Safety proof: protocol/session/network/auth implementation stays pinned.
# ----------------------------------------------------------------------
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
    raise SystemExit(
        "unexpected official source modifications: " + ", ".join(unexpected)
    )

u = (root / "src/Mayaqua/Unix.c").read_text(errors="surrogateescape")
if re.search(r"static\s+void\s*\*\s*signal_received_for_ignore", u):
    raise SystemExit("bad Stable sigaction callback signature still present")
if not fixed_signature.search(u):
    raise SystemExit("fixed void sigaction callback signature missing")
if "sa.sa_sigaction = &signal_received_for_ignore;" not in u:
    raise SystemExit("fixed sigaction assignment missing")

print("official SoftEther v4.44-9807 source verified")
print("Protocol.c / Connection.c / Session.c / Network.c / Encrypt.c / Pack.c unchanged")
print("Android Mayaqua patches: minimal-mode temp dir + upstream sigaction portability fix")

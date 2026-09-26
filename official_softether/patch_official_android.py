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
    ["git", "-C", str(root), "rev-parse", "HEAD"],
    text=True,
).strip()

if head != expected:
    raise SystemExit(f"wrong official SoftEther revision: {head} != {expected}")

# Protocol/session/transport/auth files must stay byte-for-byte pinned.
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
        ["git", "-C", str(root), "hash-object", rel],
        text=True,
    ).strip()
    for rel in protected
}

# ---------------------------------------------------------------------
# Android adaptation 1: minimal-mode app process must not require /tmp.
# ---------------------------------------------------------------------
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
        raise SystemExit(
            f"Mayaqua CheckUnixTempDir: expected 1 anchor, got {count}"
        )
    s = s.replace(old, new, 1)
    p.write_text(s, errors="surrogateescape")

# ---------------------------------------------------------------------
# Android adaptation 2: backport current-upstream POSIX sigaction fix.
# Stable 4.44 incorrectly returns void* from sa_sigaction callback.
# ---------------------------------------------------------------------
p = root / "src/Mayaqua/Unix.c"
s = p.read_text(errors="surrogateescape")

fixed_signature = re.compile(
    r"static\s+void\s+signal_received_for_ignore\s*"
    r"\(\s*int\s+sig\s*,\s*siginfo_t\s*\*\s*info\s*,"
    r"\s*void\s*\*\s*ucontext\s*\)",
    re.MULTILINE,
)

if not fixed_signature.search(s):
    buggy_function = re.compile(
        r"static\s+void\s*\*\s*signal_received_for_ignore\s*"
        r"\(\s*int\s+sig\s*,\s*siginfo_t\s*\*\s*info\s*,"
        r"\s*void\s*\*\s*ucontext\s*\)"
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
        raise SystemExit(
            f"Unix sigaction callback: expected 1 match, got {n}"
        )

assignment = re.compile(
    r"sa\.sa_sigaction\s*=\s*&?\s*signal_received_for_ignore\s*;"
)
s, n = assignment.subn(
    "sa.sa_sigaction = &signal_received_for_ignore;",
    s,
    count=1,
)

if n != 1:
    raise SystemExit(
        f"Unix sigaction assignment: expected 1 match, got {n}"
    )

p.write_text(s, errors="surrogateescape")

# ---------------------------------------------------------------------
# Android adaptation 3: iconv ABI.
#
# Stable UNIX_LINUX assumes glibc and manually declares:
#   iconv_open / iconv / iconv_close
#
# Android Bionic does not provide those symbols. The bundled GNU libiconv
# exports libiconv_open/libiconv/libiconv_close because its iconv.h maps
# the POSIX names to libiconv_*.
#
# The historical SoftEther Android port already uses this exact platform
# split: on __ANDROID__, include <iconv.h>; on ordinary Linux, retain the
# manual glibc declarations.
# ---------------------------------------------------------------------
p = root / "src/Mayaqua/Mayaqua.h"
s = p.read_text(errors="surrogateescape")

android_iconv_marker = """#ifdef\tUNIX_LINUX
#ifdef __ANDROID__
#include <iconv.h>
#else"""

if android_iconv_marker not in s:
    linux_iconv_block = re.compile(
        r"#ifdef\s+UNIX_LINUX\s*"
        r"typedef\s+void\s*\*\s*iconv_t\s*;\s*"
        r"iconv_t\s+iconv_open\s*\([^;]+;\s*"
        r"size_t\s+iconv\s*\([^;]+;\s*"
        r"int\s+iconv_close\s*\([^;]+;\s*"
        r"#else\s*//\s*UNIX_LINUX\s*"
        r"#include\s+<iconv\.h>\s*"
        r"#endif\s*//\s*UNIX_LINUX",
        re.MULTILINE | re.DOTALL,
    )

    replacement = """#ifdef\tUNIX_LINUX
#ifdef __ANDROID__
#include <iconv.h>
#else
typedef void *iconv_t;
iconv_t iconv_open (__const char *__tocode, __const char *__fromcode);
size_t iconv (iconv_t __cd, char **__restrict __inbuf,
                     size_t *__restrict __inbytesleft,
                     char **__restrict __outbuf,
                     size_t *__restrict __outbytesleft);
int iconv_close (iconv_t __cd);
#endif // __ANDROID__
#else\t// UNIX_LINUX
#include <iconv.h>
#endif\t// UNIX_LINUX"""

    s, n = linux_iconv_block.subn(replacement, s, count=1)

    if n != 1:
        pos = s.find("iconv_open")
        excerpt = (
            repr(s[max(0, pos - 300):pos + 800])
            if pos >= 0
            else "<iconv_open not found>"
        )
        raise SystemExit(
            "Mayaqua.h Linux iconv block: expected 1 match, "
            f"got {n}; nearby={excerpt}"
        )

    p.write_text(s, errors="surrogateescape")

# ---------------------------------------------------------------------
# Verification: no protocol/session/transport/auth source changes.
# ---------------------------------------------------------------------
for rel, old_hash in before.items():
    new_hash = subprocess.check_output(
        ["git", "-C", str(root), "hash-object", rel],
        text=True,
    ).strip()

    if new_hash != old_hash:
        raise SystemExit(
            f"protected official core file changed unexpectedly: {rel}"
        )

changed = subprocess.check_output(
    ["git", "-C", str(root), "diff", "--name-only"],
    text=True,
).splitlines()

allowed = {
    "src/Mayaqua/Mayaqua.c",
    "src/Mayaqua/Unix.c",
    "src/Mayaqua/Mayaqua.h",
}

unexpected = [x for x in changed if x not in allowed]

if unexpected:
    raise SystemExit(
        "unexpected official source modifications: "
        + ", ".join(unexpected)
    )

u = (root / "src/Mayaqua/Unix.c").read_text(
    errors="surrogateescape"
)
h = (root / "src/Mayaqua/Mayaqua.h").read_text(
    errors="surrogateescape"
)

if re.search(
    r"static\s+void\s*\*\s*signal_received_for_ignore",
    u,
):
    raise SystemExit(
        "bad Stable sigaction callback signature still present"
    )

if not fixed_signature.search(u):
    raise SystemExit(
        "fixed sigaction callback signature missing"
    )

if "sa.sa_sigaction = &signal_received_for_ignore;" not in u:
    raise SystemExit(
        "fixed sigaction assignment missing"
    )

if "#ifdef __ANDROID__" not in h:
    raise SystemExit(
        "Android platform branch missing from Mayaqua.h"
    )

android_pos = h.find("#ifdef __ANDROID__", h.find("#ifdef\tUNIX_LINUX"))
include_pos = h.find("#include <iconv.h>", android_pos)
else_pos = h.find("#else", android_pos)

if android_pos < 0 or include_pos < 0 or else_pos < 0:
    raise SystemExit(
        "Android iconv branch is incomplete"
    )

if not (android_pos < include_pos < else_pos):
    raise SystemExit(
        "Android iconv.h is not selected before Linux glibc declarations"
    )

print("official SoftEther v4.44-9807 source verified")
print("Protocol/Connection/Session/Network/Encrypt/Pack remain byte-for-byte official")
print("Android OS adaptations: temp-dir, sigaction, GNU libiconv ABI")

"""Exercise the actual seed script with a partial API snapshot and existing rows."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as temporary:
    work = Path(temporary)
    (work / "scripts").mkdir()
    assets = work / "app/src/main/assets"
    assets.mkdir(parents=True)
    shutil.copy(root / "scripts/update_seed.sh", work / "scripts/update_seed.sh")
    header = "#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64\n"

    def row(ip, score):
        return f"vpn,{ip},{score},10,1000,Japan,JP,1,100,1,1,none,test,,cHJvZmlsZQ==\n"

    seed = assets / "relays_seed.csv"
    seed.write_text("*vpn_servers\n" + header + row("198.51.100.1", 1) + row("198.51.100.2", 2) + "*\n")
    fresh = work / "fresh.csv"
    fresh.write_text("*vpn_servers\n" + header + row("198.51.100.1", 99)
                     + "".join(row(f"203.0.113.{n}", n) for n in range(1, 12)) + "*\n")
    tools = work / "bin"
    tools.mkdir()
    mocks = {
        "curl": '#!/bin/bash\nwhile [ "$#" -gt 0 ]; do if [ "$1" = -o ]; then cp "$TEST_FEED" "$2"; exit; fi; shift; done\nexit 1\n',
        "sleep": "#!/bin/sh\nexit 0\n",
        "python": "#!/bin/sh\nexit 1\n",  # optional HTML source unavailable
    }
    for name, source in mocks.items():
        p = tools / name
        p.write_text(source)
        p.chmod(0o755)
    env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"],
               TMPDIR=str(work), TEST_FEED=str(fresh))
    subprocess.run(["bash", str(work / "scripts/update_seed.sh")], env=env, check=True, capture_output=True)
    entries = {line.split(",")[1]: line.split(",") for line in seed.read_text().splitlines() if line.startswith("vpn,")}
    assert len(entries) == 13, entries.keys()
    assert entries["198.51.100.1"][2] == "99", "fresh profile must replace a duplicate"
    assert "198.51.100.2" in entries, "old relay absent from the API slice must survive"
    print("Seed preservation passed: partial feed merged, saved relay retained, duplicate refreshed.")

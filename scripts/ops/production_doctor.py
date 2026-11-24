"""Read-only host checks. Run on the production server; never print runtime secrets."""
import argparse
import json
from pathlib import Path
import shutil
import socket
import ssl
import subprocess
import time
from urllib.parse import urlparse

from smoke_web import inspect as inspect_web


def container_state(name):
    result = subprocess.run(
        ["docker", "inspect", '--format={"state":{{json .State}},"restarts":{{.RestartCount}}}', name],
        capture_output=True, text=True, timeout=10, check=True)
    data = json.loads(result.stdout)
    state = data["state"]
    return {"name": name, "running": state.get("Running") is True,
            "health": state.get("Health", {}).get("Status", "missing"),
            "restarts": data["restarts"]}


def certificate_days(host):
    context = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=10) as connection:
        with context.wrap_socket(connection, server_hostname=host) as secured:
            expires = ssl.cert_time_to_seconds(secured.getpeercert()["notAfter"])
    return int((expires - time.time()) // 86400)


def assess(*, disk, certificate, containers, private_files, timers):
    checks = [
        {"name": "disk", "ok": disk.free >= 1024 ** 3 and disk.used / disk.total < 0.90,
         "freeMiB": disk.free // 1024 ** 2, "usedPercent": round(disk.used * 100 / disk.total)},
        {"name": "tls", "ok": certificate >= 14, "daysRemaining": certificate},
    ]
    checks.extend({"name": item["name"], "ok": item["running"] and item["health"] == "healthy" and item["restarts"] < 3,
                   "health": item["health"], "restarts": item["restarts"]} for item in containers)
    checks.extend({"name": name, "ok": mode is not None and mode & 0o077 == 0}
                  for name, mode in private_files.items())
    checks.extend({"name": name, "ok": active} for name, active in timers.items())
    return checks


def inspect(root, origin):
    uri = urlparse(origin)
    if uri.scheme != "https" or not uri.hostname or uri.username or uri.port not in (None, 443) or uri.path not in ("", "/") or uri.query or uri.fragment:
        raise ValueError("Use one public HTTPS origin")
    root = root.resolve(strict=True)
    current = (root / "current").resolve(strict=True)
    if not current.is_relative_to(root) or current == root:
        raise ValueError("Current release must stay inside the deployment directory")
    files = {"runtimePermissions": current / "deploy/runtime.env", "deploymentPermissions": current / "deploy/.env",
             "tlsKeyPermissions": root / "tls/privkey.pem"}
    modes = {name: path.stat().st_mode if path.is_file() else None for name, path in files.items()}
    timers = {name: subprocess.run(["systemctl", "is-active", "--quiet", name], timeout=10).returncode == 0
              for name in ("certbot.timer", "unattended-upgrades.service")}
    checks = assess(disk=shutil.disk_usage(root), certificate=certificate_days(uri.hostname),
                    containers=[container_state("jibro-production-" + name + "-1") for name in ("backend", "web")],
                    private_files=modes, timers=timers)
    checks.append({"name": "publicRoutesAndSecurity", "ok": bool(inspect_web(origin, ssl.create_default_context()))})
    return {"ok": all(check["ok"] for check in checks), "checks": checks,
            "readOnly": True, "externalAlertsConfigured": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/opt/jibro"))
    parser.add_argument("--origin", required=True)
    arguments = parser.parse_args()
    try:
        report = inspect(arguments.root, arguments.origin)
    except Exception as error:
        # Command output, request URLs and environment values never reach reports.
        report = {"ok": False, "error": type(error).__name__, "readOnly": True}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    raise SystemExit(0 if report["ok"] else 1)

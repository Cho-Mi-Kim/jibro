"""Package already-verified artifacts; never reads .env or deploys a service."""
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def package(destination):
    frontend = ROOT / "frontend/dist"
    jars = [p for p in (ROOT / "backend/build/libs").glob("*.jar") if not p.name.endswith("-plain.jar") and "worker" not in p.name]
    if not (frontend / "index.html").is_file() or len(jars) != 1:
        raise ValueError("Build frontend and exactly one backend bootJar before packaging")
    destination = destination.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    shutil.copytree(frontend, destination / "frontend")
    (destination / "backend").mkdir()
    shutil.copy2(jars[0], destination / "backend/app.jar")
    for relative in ("backend.Dockerfile", "web.Dockerfile", "compose.production.yaml", ".env.example", "runtime.env.example", "nginx/default.conf.template", "certbot-install-certificate.sh", "README.md"):
        source = ROOT / "deploy" / relative
        target = destination / "deploy" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    for name in ("check_configuration.py", "database_backup.py", "smoke_web.py", "production_doctor.py"):
        target = destination / "scripts/ops" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "scripts/ops" / name, target)
    manifest = {
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)),
        "validation": "See the matching work record; packaging is not a test or deployment",
        "sha256": {str(p.relative_to(destination)).replace("\\", "/"): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(destination.rglob("*")) if p.is_file()},
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    archive = destination.with_suffix(".zip")
    with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as output:
        for path in destination.rglob("*"):
            if path.is_file():
                output.write(path, path.relative_to(destination))
    print(json.dumps({"directory": str(destination), "archive": str(archive), "files": len(manifest["sha256"]), "includesRuntimeEnvFile": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    package(parser.parse_args().output)

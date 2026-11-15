"""PostgreSQL custom backup and restore rehearsal into a NEW, temporary database.

Connection uses standard PGHOST/PGPORT/PGUSER/PGDATABASE/PGPASSFILE variables.
No password is accepted on the command line. Never restores over an existing DB.
"""
import argparse
import hashlib
import ipaddress
import json
import os
import re
import shutil
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path


def connection_environment(allow_local=False):
    """Validate the effective connection policy before running any PostgreSQL tool."""
    environment = dict(os.environ)
    host = environment.get("PGHOST", "")
    if not host:
        raise RuntimeError("Set PGHOST explicitly; implicit socket or service connections are not allowed")
    if environment.get("PGSERVICE") or environment.get("PGSERVICEFILE"):
        raise RuntimeError("PGSERVICE and PGSERVICEFILE are not supported; specify the connection explicitly")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host):
            raise RuntimeError("PGHOST must be one explicit DNS hostname or IP address, not a socket or host list")
    local = host in {"localhost", "127.0.0.1", "::1"}
    if allow_local:
        if not local:
            raise RuntimeError("--allow-local is limited to an explicit loopback PGHOST")
        address = environment.get("PGHOSTADDR", "")
        if address:
            try:
                if not ipaddress.ip_address(address).is_loopback:
                    raise ValueError()
            except ValueError:
                raise RuntimeError("Local PGHOSTADDR must be a literal loopback IP address") from None
    elif local:
        raise RuntimeError("Local database operations require the explicit --allow-local option")
    else:
        if environment.get("PGSSLMODE") != "verify-full":
            raise RuntimeError("Remote database operations require PGSSLMODE=verify-full")
        try:
            certificate = Path(environment.get("PGSSLROOTCERT", "")).resolve(strict=True)
            if not certificate.is_file():
                raise OSError()
            with certificate.open("rb") as source:
                if not source.read(1):
                    raise OSError()
        except (OSError, ValueError, RuntimeError):
            raise RuntimeError("Set PGSSLROOTCERT to a readable, nonempty trusted CA file") from None
        environment["PGSSLROOTCERT"] = str(certificate)
    return environment


def run(binary, arguments, environment=None):
    folder = os.environ.get("PG_BIN", "")
    command = str(Path(folder) / (binary + (".exe" if os.name == "nt" else ""))) if folder else shutil.which(binary)
    if not command:
        raise RuntimeError("PostgreSQL 17 client tools are required (set PG_BIN)")
    result = subprocess.run([command, *arguments], env=environment, text=True, capture_output=True, timeout=600)
    if result.returncode:
        # PostgreSQL diagnostics may contain identifiers; keep the public report minimal.
        raise RuntimeError(binary + " failed; check connection, permissions and server logs")
    return result.stdout.strip()


def checksum(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def backup(destination, *, allow_local=False):
    environment = connection_environment(allow_local)
    if not environment.get("PGDATABASE"):
        raise RuntimeError("Set PGDATABASE explicitly before backup")
    destination = destination.resolve()
    if destination.exists() or destination.with_suffix(destination.suffix + ".json").exists():
        raise RuntimeError("Backup target already exists; choose a new path")
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    partial = destination.with_suffix(destination.suffix + ".partial")
    partial.touch(mode=0o600, exist_ok=False)
    try:
        run("pg_dump", ["--format=custom", "--no-owner", "--no-acl", "--file=" + str(partial)], environment)
        run("pg_restore", ["--list", str(partial)], environment)
        partial.replace(destination)
        manifest = {"createdAt": datetime.now(timezone.utc).isoformat(), "sha256": checksum(destination), "format": "postgresql-custom", "bytes": destination.stat().st_size}
        metadata = destination.with_suffix(destination.suffix + ".json")
        with metadata.open("x", encoding="utf-8") as output:
            json.dump(manifest, output, indent=2)
        metadata.chmod(0o600)
        return {"backup": str(destination), "sha256": manifest["sha256"], "restoreVerified": False}
    finally:
        partial.unlink(missing_ok=True)


def restore_check(archive, *, allow_local=False):
    environment = connection_environment(allow_local)
    archive = archive.resolve()
    metadata = archive.with_suffix(archive.suffix + ".json")
    manifest = json.loads(metadata.read_text(encoding="utf-8"))
    if manifest.get("sha256") != checksum(archive):
        raise RuntimeError("Backup checksum mismatch; nothing was restored")
    target = "jibro_restore_check_" + uuid.uuid4().hex[:12]
    assert re.fullmatch(r"jibro_restore_check_[a-f0-9]{12}", target)
    if target == environment.get("PGDATABASE"):
        raise RuntimeError("Restore target cannot be the source database")
    env = dict(environment)
    env["PGDATABASE"] = target
    created = False
    try:
        run("createdb", ["--maintenance-db=postgres", "--template=template0", target], environment)
        created = True
        run("pg_restore", ["--exit-on-error", "--single-transaction", "--no-owner", "--no-acl", "--dbname=" + target, str(archive)], env)
        names = run("psql", ["-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"], env).splitlines()
        counts = {}
        for name in names:
            identifier = '"' + name.replace('"', '""') + '"'
            counts[name] = int(run("psql", ["-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c", "SELECT count(*) FROM public." + identifier], env))
        return {"restoreVerified": True, "temporaryDatabase": target, "tableCounts": counts, "sourceDatabaseModified": False, "temporaryDatabaseRemoved": True}
    finally:
        if created:
            # Only this invocation's uniquely named DB can reach the cleanup call.
            run("dropdb", ["--maintenance-db=postgres", target], environment)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    backup_parser = sub.add_parser("backup")
    backup_parser.add_argument("--output", type=Path, required=True)
    restore_parser = sub.add_parser("restore-check")
    restore_parser.add_argument("--archive", type=Path, required=True)
    for command_parser in (backup_parser, restore_parser):
        command_parser.add_argument("--allow-local", action="store_true", help="Explicitly allow only literal loopback hosts for local rehearsal")
    args = parser.parse_args()
    try:
        result = backup(args.output, allow_local=args.allow_local) if args.command == "backup" else restore_check(args.archive, allow_local=args.allow_local)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (RuntimeError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        if isinstance(error, RuntimeError):
            raise SystemExit(str(error))
        raise SystemExit("Backup or restore check failed; no existing database was selected for restore")

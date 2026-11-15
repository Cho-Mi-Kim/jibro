"""Report readiness without printing credentials or making external requests."""
import argparse
import base64
import ipaddress
import json
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlparse


def load_env(path):
    values = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError("Invalid environment file syntax (values are not displayed)")
        values[key] = value.strip()
    return values


def present(value):
    return bool(value) and "REPLACE" not in value.upper()


def collection_errors(values, today=None):
    errors = []
    categories = [item.strip() for item in values.get("LH_COLLECTION_CATEGORY_CODES", "06,13").split(",")]
    if not categories or not set(categories) <= {"06", "13"} or len(set(categories)) != len(categories):
        errors.append("LH_COLLECTION_CATEGORY_CODES must contain unique values from 06,13")
    # Use Korean calendar days without depending on an operating-system timezone database.
    today = today or datetime.now(timezone(timedelta(hours=9))).date()
    raw = values.get("LH_COLLECTION_HISTORY_START_DATE", "2000-01-01")
    try:
        if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", raw):
            raise ValueError()
        start = date.fromisoformat(raw)
        if not date(1900, 1, 1) <= start <= today:
            raise ValueError()
    except ValueError:
        errors.append("LH_COLLECTION_HISTORY_START_DATE must be an ISO date from 1900-01-01 through today in Korea")
    return errors


def valid_uri_host(host):
    if not host:
        return False
    if ":" in host:
        try:
            ipaddress.IPv6Address(host)
            return "%" not in host
        except ValueError:
            return False
    return bool(re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\.?", host))


def oauth_origin(value, production):
    """Mirror OAuthEndpoints' configured-origin contract; never resolve a hostname."""
    try:
        origin = urlparse(value)
        loopback = any(origin.netloc == host or origin.netloc.startswith(host + ":")
                       for host in ("localhost", "127.0.0.1", "[::1]"))
        if (not valid_uri_host(origin.hostname) or re.search(r"[\s\\?#]", value)
                or origin.username is not None or origin.path not in ("", "/")
                or not value.startswith(origin.scheme + "://")
                or not (origin.scheme == "https" or not production and loopback and origin.scheme == "http")
                or production and origin.port not in (None, 443)):
            return None
        # Access the port even locally so malformed authorities produce a named validation error.
        _ = origin.port
        return origin.scheme + "://" + origin.netloc
    except ValueError:
        return None


def oauth_errors(values, production=False):
    errors = []
    frontend = oauth_origin(values.get("FRONTEND_URL", "http://127.0.0.1:5173"), production)
    configured = values.get("OAUTH_BASE_URL", "").strip()
    callback = oauth_origin(configured, production) if configured else frontend if production else "http://127.0.0.1:8080"
    if frontend is None:
        errors.append("FRONTEND_URL must be an HTTPS origin or local loopback HTTP origin")
    if callback is None and configured:
        errors.append("OAUTH_BASE_URL must be an HTTPS origin or local loopback HTTP origin")
    elif production and callback is not None and frontend is not None and callback != frontend:
        errors.append("OAUTH_BASE_URL must match FRONTEND_URL in production")
    return errors


def valid_push_configuration(values):
    try:
        key = json.loads(values["JIBRO_VAPID_JWK"])
        if not isinstance(key, dict) or key.get("kty") != "EC" or key.get("crv") != "P-256":
            return False
        for name in ("x", "y", "d"):
            component = key.get(name)
            if not isinstance(component, str) or not re.fullmatch(r"[A-Za-z0-9_-]{43}", component):
                return False
            if len(base64.urlsafe_b64decode(component + "=")) != 32:
                return False
        subject = values["JIBRO_PUSH_SUBJECT"]
        uri = urlparse(subject)
        if re.search(r"[\s\\]", subject) or "#" in subject or not subject.startswith(uri.scheme + ":"):
            return False
        if uri.scheme == "mailto":
            return bool(re.fullmatch(r"[^\s?@]+@[^\s?@]+", subject.removeprefix("mailto:")))
        _ = uri.port
        return uri.scheme == "https" and valid_uri_host(uri.hostname) and uri.username is None
    except (ValueError, TypeError):
        return False


def connection_status(values):
    groups = {
        "LH": ["LH_SERVICE_KEY"],
        "SMTP": ["SPRING_MAIL_HOST", "SPRING_MAIL_PORT", "SPRING_MAIL_USERNAME", "SPRING_MAIL_PASSWORD", "JIBRO_MAIL_FROM"],
        "Web Push": ["JIBRO_VAPID_JWK", "JIBRO_PUSH_SUBJECT"],
        "Google": ["GOOGLE_CLIENT_ID", "GOOGLE_CLIENT_SECRET"],
        "Kakao": ["KAKAO_CLIENT_ID", "KAKAO_CLIENT_SECRET"],
    }
    result = {}
    for name, keys in groups.items():
        # A default port is not an attempted SMTP connection.
        actual = [k for k in keys if k != "SPRING_MAIL_PORT"]
        configured = [present(values.get(k, "")) for k in actual]
        result[name] = "configured-not-verified" if all(configured) else "incomplete" if any(configured) else "disabled"
    if result["SMTP"] == "configured-not-verified":
        port = values.get("SPRING_MAIL_PORT", "587")
        if not re.fullmatch(r"[0-9]{1,5}", port) or not 1 <= int(port) <= 65535:
            result["SMTP"] = "invalid"
    if result["Web Push"] == "configured-not-verified":
        if not valid_push_configuration(values):
            result["Web Push"] = "invalid"
    return result


def configuration_errors(values, production=False):
    errors = collection_errors(values) + oauth_errors(values, production)
    for name, state in connection_status(values).items():
        if state in ("incomplete", "invalid"):
            errors.append(name + " configuration " + state)
    return errors


def production_errors(values, deployment):
    errors = configuration_errors(values, production=True)
    for key in ("DB_URL", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET", "FRONTEND_URL"):
        if not present(values.get(key, "")):
            errors.append(key + " missing or placeholder")
    origin = oauth_origin(values.get("FRONTEND_URL", ""), production=True)
    host = deployment.get("PUBLIC_HOST", "")
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?", host) or "." not in host or host.endswith("example.com"):
        errors.append("PUBLIC_HOST must be the actual DNS hostname")
    if origin is None or urlparse(origin).hostname != host:
        errors.append("FRONTEND_URL must match the HTTPS public origin")
    if len(values.get("JWT_SECRET", "").encode()) < 32:
        errors.append("JWT_SECRET must contain at least 32 bytes")
    db = values.get("DB_URL", "")
    if not db.startswith("jdbc:postgresql://") or not re.search(r"[?&]sslmode=verify-full(?:&|$)", db) or "sslrootcert=/run/rds-ca/global-bundle.pem" not in db:
        errors.append("DB_URL must use verified TLS with the mounted RDS CA")
    for name in ("BACKEND_IMAGE", "WEB_IMAGE"):
        if not re.fullmatch(r"[^\s]+@sha256:[0-9a-f]{64}", deployment.get(name, "")):
            errors.append(name + " must use an immutable image digest")
    for name in ("TLS_DIRECTORY", "RDS_CA_FILE"):
        if not deployment.get(name, "").startswith("/"):
            errors.append(name + " must be an absolute server path")
    if values.get("AUTH_SECURE_COOKIE", "true").lower() != "true":
        errors.append("AUTH_SECURE_COOKIE cannot be false in production")
    if "local-sample" in values.get("SPRING_PROFILES_ACTIVE", ""):
        errors.append("Do not enable local-sample in production")
    for name in ("JIBRO_SMTP_AUTH", "JIBRO_SMTP_STARTTLS"):
        if values.get(name, "true").lower() != "true":
            errors.append(name + " must stay enabled for external SMTP")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env", required=True, type=Path)
    parser.add_argument("--deployment", type=Path, help="Production deployment env file; validates but does not connect")
    args = parser.parse_args()
    values = load_env(args.env)
    output = {"connections": connection_status(values), "externalRequestsMade": False}
    if args.deployment:
        errors = production_errors(values, load_env(args.deployment))
        output["productionErrors"] = errors
    else:
        production = "production" in [item.strip() for item in values.get("SPRING_PROFILES_ACTIVE", "").split(",")]
        errors = configuration_errors(values, production)
        output["configurationErrors"] = errors
        output["productionErrors"] = []
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, OSError):
        raise SystemExit("Configuration file could not be read or parsed; values were not printed")

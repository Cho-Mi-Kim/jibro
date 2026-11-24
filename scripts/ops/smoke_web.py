"""Read-only HTTPS, SPA routing and API checks for a prepared deployment."""
import argparse
import json
import ssl
from urllib.error import HTTPError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


def inspect(base, context):
    if urlparse(base).scheme != "https":
        raise ValueError("Use the HTTPS origin; this check never disables certificate validation")
    result = {}
    denied = ("/.env", "/.git/config", "/runtime.env", "/backup.sql", "/development", "/review-public18-20261108.html", "/jibro-code-model.html", "/app-routes.conf", "/security-headers.conf")
    for path in ("/", "/notices", "/privacy", "/contact", "/robots.txt", "/sitemap.xml", "/pdf-viewer.html", "/evidence-viewer.html", "/evidence-dynamic.html", "/missing-deployment-page", "/api/v1/health", "/assets/missing-deployment-probe.js", *denied):
        try:
            response = urlopen(Request(base.rstrip("/") + path), context=context, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            body = response.read(1024 * 1024)
            policy = response.headers.get("Content-Security-Policy", "")
            script_policy = next((part.strip() for part in policy.split(";") if part.strip().startswith("script-src ")), "")
            result[path] = {"status": response.status, "contentType": response.headers.get("Content-Type", ""),
                "noSniff": response.headers.get("X-Content-Type-Options") == "nosniff",
                "referrerProtected": response.headers.get("Referrer-Policy") == "no-referrer",
                "scriptProtected": bool(script_policy) and "'unsafe-inline'" not in script_policy and "'unsafe-eval'" not in script_policy and "frame-ancestors 'none'" in policy,
                "permissionsRestricted": "camera=()" in response.headers.get("Permissions-Policy", ""),
                "resourceProtected": response.headers.get("Cross-Origin-Resource-Policy") == "same-origin"}
            if path == "/api/v1/health":
                payload = json.loads(body)
                result[path]["healthy"] = payload.get("status") == "UP" and payload.get("database") == "UP"
    assert result["/"]["status"] == 200 and "text/html" in result["/"]["contentType"]
    assert result["/notices"]["status"] == 200 and "text/html" in result["/notices"]["contentType"]
    assert result["/api/v1/health"].get("healthy")
    assert result["/assets/missing-deployment-probe.js"]["status"] == 404
    assert result["/missing-deployment-page"]["status"] == 404
    assert all(result[path]["status"] == 404 for path in denied)
    assert all(result[path]["status"] == 200 for path in ("/pdf-viewer.html", "/evidence-viewer.html", "/evidence-dynamic.html"))
    for path in ("/privacy", "/contact"):
        assert result[path]["status"] == 200 and "text/html" in result[path]["contentType"]
    assert result["/robots.txt"]["status"] == 200 and "text/plain" in result["/robots.txt"]["contentType"]
    assert result["/sitemap.xml"]["status"] == 200 and "xml" in result["/sitemap.xml"]["contentType"]
    assert all(value["noSniff"] and value["referrerProtected"] and value["scriptProtected"] and value["permissionsRestricted"] and value["resourceProtected"] for value in result.values())
    try:
        response = urlopen(Request(base.rstrip("/") + "/", headers={"Host": "unrecognized.invalid"}), context=context, timeout=10)
    except HTTPError as error:
        response = error
    with response:
        assert response.status == 421
        result["unrecognizedHost"] = {"status": response.status}
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--ca", help="Optional trusted test CA; public deployment uses system trust")
    args = parser.parse_args()
    context = ssl.create_default_context(cafile=args.ca)
    print(json.dumps(inspect(args.origin, context), ensure_ascii=False, indent=2))

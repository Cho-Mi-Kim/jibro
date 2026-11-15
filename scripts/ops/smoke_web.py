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
    for path in ("/", "/notices", "/api/v1/health", "/assets/missing-deployment-probe.js"):
        try:
            response = urlopen(Request(base.rstrip("/") + path), context=context, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            body = response.read(1024 * 1024)
            result[path] = {"status": response.status, "contentType": response.headers.get("Content-Type", ""),
                "noSniff": response.headers.get("X-Content-Type-Options") == "nosniff",
                "referrerProtected": response.headers.get("Referrer-Policy") == "no-referrer"}
            if path == "/api/v1/health":
                payload = json.loads(body)
                result[path]["healthy"] = payload.get("status") == "UP" and payload.get("database") == "UP"
    assert result["/"]["status"] == 200 and "text/html" in result["/"]["contentType"]
    assert result["/notices"]["status"] == 200 and "text/html" in result["/notices"]["contentType"]
    assert result["/api/v1/health"].get("healthy")
    assert result["/assets/missing-deployment-probe.js"]["status"] == 404
    assert all(value["noSniff"] and value["referrerProtected"] for value in result.values())
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--ca", help="Optional trusted test CA; public deployment uses system trust")
    args = parser.parse_args()
    context = ssl.create_default_context(cafile=args.ca)
    print(json.dumps(inspect(args.origin, context), ensure_ascii=False, indent=2))

"""Exercise the actual hardened web image in an isolated, loopback-only Docker project.

Uses a local fake API, a temporary trusted certificate and no production credentials.
Requires Docker Compose, OpenSSL and a built frontend. Does not contact application APIs.
"""
import argparse
import base64
import hashlib
import http.client
import json
from pathlib import Path
import re
import shutil
import socket
import ssl
import subprocess
import tempfile
import time
import uuid


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, timeout=120, **kwargs)
    if result.returncode:
        raise RuntimeError("Isolated edge command failed: " + command[0])
    return result.stdout.strip()


def verify(frontend, deploy):
    token = uuid.uuid4().hex[:12]
    image = "jibro-edge-check:" + token
    results = []
    with tempfile.TemporaryDirectory(prefix="jibro-edge-") as temporary:
        root = Path(temporary)
        shutil.copytree(frontend, root / "frontend")
        shutil.copytree(deploy, root / "deploy", ignore=shutil.ignore_patterns(".env", "runtime.env"))
        template = root / "deploy/nginx/default.conf.template"
        config = template.read_text()
        assert config.count("server backend:8080 resolve;") == 1
        config = config.replace("server backend:8080 resolve;", "server 127.0.0.1:9000;")
        config += '\nserver { listen 127.0.0.1:9000; access_log off; location / { add_header X-Observed-Peer $http_x_forwarded_for always; return 204; } }\n'
        template.write_text(config)
        compose_file = root / "deploy/compose.production.yaml"
        compose_text = compose_file.read_text()
        for port in (80, 443):
            binding = "'" + str(port) + ":" + str(port) + "'"
            assert compose_text.count(binding) == 1
            compose_text = compose_text.replace(binding, "'127.0.0.1::" + str(port) + "'")
        compose_file.write_text(compose_text)
        (root / "deploy/runtime.env").write_text("")
        tls = root / "tls"
        tls.mkdir()
        run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=edge.test", "-addext", "subjectAltName=DNS:edge.test", "-keyout", str(tls / "privkey.pem"), "-out", str(tls / "fullchain.pem")])
        (root / "acme/.well-known/acme-challenge").mkdir(parents=True)
        (root / "acme/.well-known/acme-challenge/probe").write_text("verified challenge")
        run(["docker", "build", "-f", "deploy/web.Dockerfile", "-t", image, "."], cwd=root)
        (root / "deploy/.env").write_text(f"PUBLIC_HOST=edge.test\nWEB_IMAGE={image}\nBACKEND_IMAGE={image}\nTLS_DIRECTORY={tls}\nACME_DIRECTORY={root / 'acme'}\nRDS_CA_FILE={tls / 'fullchain.pem'}\n")
        compose = ["docker", "compose", "-p", "jibro-edge-" + token, "--env-file", str(root / "deploy/.env"), "-f", str(compose_file)]
        try:
            run(compose + ["up", "-d", "--no-deps", "web"])
            container = run(compose + ["ps", "-q", "web"])
            ports = json.loads(run(["docker", "inspect", "--format={{json .NetworkSettings.Ports}}", container]))
            https_port = int(ports["443/tcp"][0]["HostPort"])
            http_port = int(ports["80/tcp"][0]["HostPort"])
            context = ssl.create_default_context(cafile=str(tls / "fullchain.pem"))

            class EdgeConnection(http.client.HTTPSConnection):
                def connect(self):
                    connection = socket.create_connection(("127.0.0.1", https_port), timeout=5)
                    self.sock = context.wrap_socket(connection, server_hostname="edge.test")

            connection = EdgeConnection("edge.test", context=context, timeout=5)

            def request(path, method="GET", headers=None):
                connection.request(method, path, headers=headers or {})
                response = connection.getresponse()
                return response.status, dict(response.getheaders()), response.read()

            for attempt in range(20):
                try:
                    assert request("/")[0] == 200
                    break
                except (OSError, http.client.HTTPException):
                    connection.close()
                    time.sleep(0.2)
            else:
                raise RuntimeError("Isolated web did not start")
            for page in ("/", "/pdf-viewer.html", "/evidence-viewer.html", "/evidence-dynamic.html"):
                status, headers, body = request(page)
                assert status == 200, page
                policy = headers["Content-Security-Policy"]
                assert "'unsafe-inline'" not in policy.split("script-src ")[1].split(";")[0]
                for attributes, script in re.findall(rb"<script\b([^>]*)>([\s\S]*?)</script\s*>", body, re.I):
                    if b"src=" not in attributes and script.strip():
                        digest = base64.b64encode(hashlib.sha256(script).digest()).decode()
                        assert "'sha256-" + digest + "'" in policy, page
            results.append("trusted HTTPS and exact viewer script hashes")
            for path in ("/.env", "/.git/config", "/backup.sql", "/app-routes.conf", "/security-headers.conf", "/development", "/review-public18-20261108.html", "/jibro-code-model.html"):
                assert request(path)[0] == 404, path
            assert request("/", headers={"Host": "unrecognized.invalid"})[0] == 421
            results.append("internal files and unrecognized Host blocked")
            http_connection = http.client.HTTPConnection("127.0.0.1", http_port, timeout=5)
            http_connection.request("GET", "/.well-known/acme-challenge/probe", headers={"Host": "edge.test"})
            response = http_connection.getresponse()
            assert response.status == 200 and response.read() == b"verified challenge"
            http_connection.request("GET", "/notices", headers={"Host": "edge.test"})
            response = http_connection.getresponse()
            assert response.status == 308 and response.getheader("Location") == "https://edge.test/notices"
            response.read()
            http_connection.close()
            results.append("HTTP challenge served without redirect or web stop")
            status, headers, _ = request("/api/v1/health", headers={"X-Forwarded-For": "198.51.100.99"})
            assert status == 204 and headers["X-Observed-Peer"] != "198.51.100.99"
            results.append("client-supplied forwarded IP replaced")
            for endpoint, allowed in (("security/visitor", 5), ("auth/signup", 5), ("auth/login", 11)):
                statuses = [request("/api/v1/" + endpoint, "POST")[0] for _ in range(allowed)]
                assert statuses == [204] * allowed, endpoint
                status, headers, body = request("/api/v1/" + endpoint, "POST")
                assert status == 429 and headers.get("Retry-After") == "30", endpoint
                assert json.loads(body)["code"] == "TOO_MANY_REQUESTS", endpoint
                assert "Content-Security-Policy" in headers
            results.append("visitor, mail and login bursts limited with JSON 429 and Retry-After")
            assert request("/api/v1/security/visitor")[0] == 204
            statuses = [request("/api/v1/notices")[0] for _ in range(120)]
            assert 429 in statuses and set(statuses) <= {204, 429}
            assert request("/")[0] == 200
            connection.close()
            results.append("API burst limited while static pages remain available")
            security = json.loads(run(["docker", "inspect", "--format={{json .HostConfig}}", container]))
            assert security["ReadonlyRootfs"] and "ALL" in security["CapDrop"]
            assert "no-new-privileges:true" in security["SecurityOpt"]
            results.append("actual production container hardening applied")
        finally:
            subprocess.run(compose + ["down", "--volumes"], capture_output=True, timeout=30)
            subprocess.run(["docker", "image", "rm", image], capture_output=True, timeout=30)
    return {"ok": True, "isolated": True, "productionRequestsMade": False, "checks": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frontend", type=Path, default=Path("frontend/dist"))
    parser.add_argument("--deploy", type=Path, default=Path("deploy"))
    args = parser.parse_args()
    print(json.dumps(verify(args.frontend.resolve(), args.deploy.resolve()), indent=2))

"""Run the real POSIX hook with temporary certificates and an isolated Docker stub."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix" and shutil.which("openssl"), "POSIX/OpenSSL required")
class CertificateHookTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = tempfile.TemporaryDirectory(prefix="jibro-cert-fixture-")
        cls.fixtures = Path(cls.fixture.name)
        for name in ("old", "new"):
            folder = cls.fixtures / name
            folder.mkdir()
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "60",
                            "-subj", "/CN=edge.test", "-addext", "subjectAltName=DNS:edge.test",
                            "-keyout", str(folder / "privkey.pem"), "-out", str(folder / "fullchain.pem")],
                           check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.fixture.cleanup()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="jibro-cert-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.tls = self.root / "tls"
        shutil.copytree(self.fixtures / "old", self.tls)
        self.lineage = self.root / "lineage"
        shutil.copytree(self.fixtures / "new", self.lineage)
        self.before = {name: (self.tls / name).read_bytes() for name in ("privkey.pem", "fullchain.pem")}
        binary = self.root / "bin"
        binary.mkdir()
        docker = binary / "docker"
        docker.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$DOCKER_TEST_LOG"\ncase "$*" in\n*" nginx -t") [ "${FAIL_VALIDATION:-0}" != 1 ] ;;\n*" nginx -s reload") [ "${FAIL_RELOAD:-0}" != 1 ] ;;\n*) exit 2 ;;\nesac\n')
        docker.chmod(0o755)
        self.environment = os.environ | {"PATH": str(binary) + os.pathsep + os.environ["PATH"],
            "DOCKER_TEST_LOG": str(self.root / "docker.log"), "JIBRO_CERT_HOST": "edge.test",
            "JIBRO_TLS_DIRECTORY": str(self.tls), "RENEWED_LINEAGE": str(self.lineage), "RENEWED_DOMAINS": "edge.test"}
        self.hook = Path(__file__).resolve().parents[2] / "deploy/certbot-install-certificate.sh"

    def invoke(self, **environment):
        return subprocess.run(["sh", str(self.hook)], env=self.environment | environment, capture_output=True).returncode

    def test_installs_matching_certificate_and_reloads_without_stop(self):
        self.assertEqual(self.invoke(), 0)
        for name in self.before:
            self.assertEqual((self.tls / name).read_bytes(), (self.lineage / name).read_bytes())
        self.assertEqual((self.tls / "privkey.pem").stat().st_mode & 0o777, 0o600)
        log = (self.root / "docker.log").read_text()
        self.assertIn("nginx -t", log)
        self.assertIn("nginx -s reload", log)
        self.assertNotIn("stop", log)

    def test_nginx_validation_or_reload_failure_restores_both_previous_files(self):
        for setting in ("FAIL_VALIDATION", "FAIL_RELOAD"):
            self.assertNotEqual(self.invoke(**{setting: "1"}), 0)
            for name, original in self.before.items():
                self.assertEqual((self.tls / name).read_bytes(), original)
            self.assertEqual(sorted(p.name for p in self.tls.iterdir()), sorted(self.before))

    def test_mismatched_key_does_not_install_or_call_docker(self):
        shutil.copy2(self.tls / "privkey.pem", self.lineage / "privkey.pem")
        self.assertNotEqual(self.invoke(), 0)
        for name, original in self.before.items():
            self.assertEqual((self.tls / name).read_bytes(), original)
        self.assertFalse((self.root / "docker.log").exists())

    def test_other_domain_is_ignored(self):
        self.assertEqual(self.invoke(RENEWED_DOMAINS="different.invalid"), 0)
        self.assertFalse((self.root / "docker.log").exists())
        for name, original in self.before.items():
            self.assertEqual((self.tls / name).read_bytes(), original)


if __name__ == "__main__":
    unittest.main()

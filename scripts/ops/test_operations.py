import contextlib
import io
import json
import os
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch
import check_configuration as config
import database_backup as backup


class ConfigurationTests(unittest.TestCase):
    def test_missing_external_connections_are_disabled_not_verified(self):
        self.assertEqual(set(config.connection_status({}).values()), {"disabled"})

    def test_incomplete_and_invalid_connections_do_not_print_secrets(self):
        result = config.connection_status({"SPRING_MAIL_HOST": "secret-host", "JIBRO_VAPID_JWK": "not-a-key", "JIBRO_PUSH_SUBJECT": "mailto:private@example.com"})
        self.assertEqual(result["SMTP"], "incomplete")
        self.assertEqual(result["Web Push"], "invalid")
        self.assertNotIn("secret-host", json.dumps(result))

    def test_placeholder_production_configuration_is_rejected(self):
        errors = config.production_errors({}, {})
        self.assertGreater(len(errors), 5)

    def test_different_https_port_is_rejected(self):
        errors = config.production_errors({"FRONTEND_URL": "https://housing.example.org:444"}, {"PUBLIC_HOST": "housing.example.org"})
        self.assertIn("FRONTEND_URL must match the HTTPS public origin", errors)

    def test_raw_values_preserve_dollar_and_json_without_expansion(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.env"
            path.write_text('DB_PASSWORD=abc$test#xyz\nJIBRO_VAPID_JWK={"kty":"EC"}\n', encoding="utf-8")
            self.assertEqual(config.load_env(path)["DB_PASSWORD"], "abc$test#xyz")

    def test_collection_defaults_and_explicit_supported_scopes(self):
        self.assertEqual(config.collection_errors({}, date(2026, 11, 7)), [])
        for categories in ("06", "13", "06,13", "13, 06"):
            with self.subTest(categories=categories):
                self.assertEqual(config.collection_errors({"LH_COLLECTION_CATEGORY_CODES": categories}), [])

    def test_collection_rejects_unsupported_duplicate_and_empty_categories(self):
        for categories in ("", "06,", "06,06", "13, 13", "6", "05", "06,13,06", "secret-scope"):
            with self.subTest(categories=categories):
                errors = config.collection_errors({"LH_COLLECTION_CATEGORY_CODES": categories})
                self.assertEqual(len(errors), 1)
                self.assertTrue(errors[0].startswith("LH_COLLECTION_CATEGORY_CODES"))
                self.assertNotIn("secret-scope", str(errors))

    def test_history_date_bounds_and_calendar_validation(self):
        today = date(2026, 11, 7)
        for start in ("1900-01-01", "2024-02-29", "2026-11-07"):
            self.assertEqual(config.collection_errors({"LH_COLLECTION_HISTORY_START_DATE": start}, today), [])
        for start in ("", "1899-12-31", "2026-11-08", "2026-02-29", "20261107", "2026-1-07", "private-value"):
            with self.subTest(start=start):
                errors = config.collection_errors({"LH_COLLECTION_HISTORY_START_DATE": start}, today)
                self.assertEqual(len(errors), 1)
                self.assertTrue(errors[0].startswith("LH_COLLECTION_HISTORY_START_DATE"))
                self.assertNotIn("private-value", str(errors))

    def test_oauth_local_defaults_and_configured_origins(self):
        self.assertEqual(config.oauth_errors({}), [])
        for origin in ("http://127.0.0.1:8080", "http://localhost:8080/", "http://[::1]:8080", "https://housing.example.org"):
            with self.subTest(origin=origin):
                self.assertEqual(config.oauth_errors({"OAUTH_BASE_URL": origin}), [])

    def test_oauth_rejects_non_origins_and_untrusted_plain_http(self):
        for origin in ("http://housing.example.org", "https://private@housing.example.org",
                       "https://housing.example.org/callback", "https://housing.example.org?",
                       "https://housing.example.org#", "https://housing.example.org:invalid",
                       "https://housing.example.org:65536", "https://[invalid", "https://bad host",
                       "https://housing.example.org\\private", " HTTPS://housing.example.org",
                       "https://bad_host.example.org", "https://%65xample.org", "http://LOCALHOST:8080"):
            with self.subTest(origin=origin):
                errors = config.oauth_errors({"OAUTH_BASE_URL": origin})
                self.assertEqual(errors, ["OAUTH_BASE_URL must be an HTTPS origin or local loopback HTTP origin"])
                self.assertNotIn("private", str(errors))

    def test_production_oauth_uses_frontend_fallback_and_exact_configured_origin(self):
        values = {"FRONTEND_URL": "https://housing.example.org/"}
        self.assertEqual(config.oauth_errors(values, True), [])
        self.assertEqual(config.oauth_errors({**values, "OAUTH_BASE_URL": "https://housing.example.org"}, True), [])
        for callback in ("https://other.example.org", "https://housing.example.org:443"):
            self.assertIn("OAUTH_BASE_URL must match FRONTEND_URL in production",
                          config.oauth_errors({**values, "OAUTH_BASE_URL": callback}, True))
        for callback in ("http://localhost:8080", "https://housing.example.org:444"):
            self.assertIn("OAUTH_BASE_URL must be an HTTPS origin or local loopback HTTP origin",
                          config.oauth_errors({**values, "OAUTH_BASE_URL": callback}, True))

    def test_production_validator_includes_collection_and_oauth_without_duplicate_errors(self):
        values = {"DB_URL": "jdbc:postgresql://database.internal/jibro?sslmode=verify-full&sslrootcert=/run/rds-ca/global-bundle.pem",
                  "DB_USERNAME": "fixture", "DB_PASSWORD": "fixture-password", "JWT_SECRET": "a" * 32,
                  "FRONTEND_URL": "https://housing.example.org"}
        deployment = {"PUBLIC_HOST": "housing.example.org", "BACKEND_IMAGE": "app@sha256:" + "a" * 64,
                      "WEB_IMAGE": "web@sha256:" + "b" * 64, "TLS_DIRECTORY": "/certs", "ACME_DIRECTORY": "/acme", "RDS_CA_FILE": "/rds-ca.pem"}
        self.assertEqual(config.production_errors(values, deployment), [])
        errors = config.production_errors({**values, "LH_COLLECTION_CATEGORY_CODES": "06,06",
                                           "OAUTH_BASE_URL": "https://other.example.org",
                                           "SPRING_MAIL_HOST": "private-host"}, deployment)
        self.assertEqual(len(errors), 3)
        self.assertEqual(errors.count("SMTP configuration incomplete"), 1)
        self.assertNotIn("private-host", str(errors))

    def test_malformed_frontend_port_is_reported_without_parse_exception(self):
        errors = config.production_errors({"FRONTEND_URL": "https://housing.example.org:private"},
                                          {"PUBLIC_HOST": "housing.example.org"})
        self.assertIn("FRONTEND_URL must match the HTTPS public origin", errors)
        self.assertNotIn("private", str(errors))

    def test_local_image_ids_are_immutable_but_tags_and_short_ids_are_rejected(self):
        for image in ("sha256:" + "a" * 64, "registry/app@sha256:" + "b" * 64):
            errors = config.production_errors({}, {"BACKEND_IMAGE": image, "WEB_IMAGE": image})
            self.assertFalse(any("immutable image" in error for error in errors))
        for image in ("app:latest", "app:release", "sha256:abc123", "sha256:" + "g" * 64):
            errors = config.production_errors({}, {"BACKEND_IMAGE": image, "WEB_IMAGE": image})
            self.assertEqual(sum("immutable image" in error for error in errors), 2)

    def test_smtp_default_port_is_not_a_connection_and_invalid_ports_are_rejected(self):
        self.assertEqual(config.connection_status({"SPRING_MAIL_PORT": "587"})["SMTP"], "disabled")
        values = {"SPRING_MAIL_HOST": "smtp.example.org", "SPRING_MAIL_USERNAME": "fixture",
                  "SPRING_MAIL_PASSWORD": "fixture-password", "JIBRO_MAIL_FROM": "mail@example.org"}
        self.assertEqual(config.connection_status(values)["SMTP"], "configured-not-verified")
        for port in ("", "0", "65536", "-1", "secret"):
            with self.subTest(port=port):
                self.assertEqual(config.connection_status({**values, "SPRING_MAIL_PORT": port})["SMTP"], "invalid")

    def test_push_structural_checks_do_not_claim_delivery_verification(self):
        # Coordinate-shaped fixtures check offline syntax only, not an actual signing operation.
        key = {"kty": "EC", "crv": "P-256", "x": "A" * 43, "y": "B" * 43, "d": "C" * 43}
        values = {"JIBRO_VAPID_JWK": json.dumps(key), "JIBRO_PUSH_SUBJECT": "mailto:ops@example.org"}
        self.assertEqual(config.connection_status(values)["Web Push"], "configured-not-verified")
        for raw in ("[]", "null", "42", json.dumps({**key, "d": "short"}), json.dumps({**key, "x": 42})):
            with self.subTest(raw=raw):
                self.assertEqual(config.connection_status({**values, "JIBRO_VAPID_JWK": raw})["Web Push"], "invalid")
        for subject in ("mailto:invalid", "http://ops.example.org", "https://private@ops.example.org", "https://ops.example.org#secret",
                        "MAILTO:ops@example.org", "https://ops.example.org:invalid", "https://bad_host.example.org"):
            with self.subTest(subject=subject):
                self.assertEqual(config.connection_status({**values, "JIBRO_PUSH_SUBJECT": subject})["Web Push"], "invalid")

    def test_cli_without_deployment_rejects_new_invalid_settings_and_redacts_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.env"
            path.write_text("LH_COLLECTION_CATEGORY_CODES=private-scope\nOAUTH_BASE_URL=https://private@housing.example.org\n", encoding="utf-8")
            output = io.StringIO()
            with patch("sys.argv", ["check_configuration.py", "--env", str(path)]), contextlib.redirect_stdout(output), patch("socket.create_connection") as network:
                self.assertEqual(config.main(), 1)
                network.assert_not_called()
            self.assertNotIn("private", output.getvalue())
            result = json.loads(output.getvalue())
            self.assertEqual(len(result["configurationErrors"]), 2)
            self.assertFalse(result["externalRequestsMade"])
            self.assertEqual(result["productionErrors"], [])

    def test_runtime_production_profile_enforces_oauth_policy_without_deployment_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runtime.env"
            path.write_text("SPRING_PROFILES_ACTIVE=production\nFRONTEND_URL=https://housing.example.org\nOAUTH_BASE_URL=http://localhost:8080\n", encoding="utf-8")
            output = io.StringIO()
            with patch("sys.argv", ["check_configuration.py", "--env", str(path)]), contextlib.redirect_stdout(output):
                self.assertEqual(config.main(), 1)
            self.assertEqual(len(json.loads(output.getvalue())["configurationErrors"]), 1)


class RestoreSafetyTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"PGHOST": "127.0.0.1", "PGDATABASE": "source"}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def test_bad_checksum_does_not_create_database(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "db.dump"
            archive.write_bytes(b"broken")
            archive.with_suffix(".dump.json").write_text('{"sha256":"wrong"}', encoding="utf-8")
            with patch.object(backup, "run") as runner:
                with self.assertRaisesRegex(RuntimeError, "checksum"):
                    backup.restore_check(archive, allow_local=True)
                runner.assert_not_called()

    def test_failed_creation_never_drops_existing_database(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "db.dump"
            archive.write_bytes(b"test")
            archive.with_suffix(".dump.json").write_text(json.dumps({"sha256": backup.checksum(archive)}), encoding="utf-8")
            with patch.object(backup, "run", side_effect=RuntimeError("createdb failed")) as runner:
                with self.assertRaises(RuntimeError):
                    backup.restore_check(archive, allow_local=True)
                self.assertEqual(runner.call_count, 1)
                self.assertEqual(runner.call_args.args[0], "createdb")

    def test_restore_failure_cleans_only_generated_database(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "db.dump"
            archive.write_bytes(b"test")
            archive.with_suffix(".dump.json").write_text(json.dumps({"sha256": backup.checksum(archive)}), encoding="utf-8")
            with patch.object(backup, "run", side_effect=["", RuntimeError("restore failed"), ""]) as runner:
                with self.assertRaises(RuntimeError):
                    backup.restore_check(archive, allow_local=True)
                created_name = runner.call_args_list[0].args[1][-1]
                self.assertRegex(created_name, r"^jibro_restore_check_[a-f0-9]{12}$")
                self.assertEqual(runner.call_args_list[-1].args[:2], ("dropdb", ["--maintenance-db=postgres", created_name]))
                self.assertEqual(runner.call_args_list[-1].args[2]["PGHOST"], "127.0.0.1")


class ConnectionSafetyTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)

    def test_missing_host_blocks_before_any_database_command_or_archive_read(self):
        with patch.object(backup, "run") as runner:
            with self.assertRaisesRegex(RuntimeError, "PGHOST"):
                backup.restore_check(Path("does-not-exist.dump"))
            runner.assert_not_called()

    def test_remote_requires_verified_tls_and_a_readable_ca(self):
        os.environ["PGHOST"] = "private-database.example.org"
        with self.assertRaisesRegex(RuntimeError, "verify-full"):
            backup.connection_environment()
        os.environ["PGSSLMODE"] = "verify-full"
        with tempfile.TemporaryDirectory() as directory:
            certificate = Path(directory) / "ca.pem"
            for path in (Path(directory), certificate):
                os.environ["PGSSLROOTCERT"] = str(path)
                with self.assertRaisesRegex(RuntimeError, "PGSSLROOTCERT"):
                    backup.connection_environment()
            certificate.write_bytes(b"")
            with self.assertRaisesRegex(RuntimeError, "PGSSLROOTCERT"):
                backup.connection_environment()
            certificate.write_bytes(b"trusted test CA fixture")
            env = backup.connection_environment()
            self.assertEqual(env["PGSSLMODE"], "verify-full")
            self.assertEqual(env["PGSSLROOTCERT"], str(certificate.resolve()))

    def test_local_requires_explicit_flag_and_only_literal_hosts(self):
        for host in ("localhost", "127.0.0.1", "::1"):
            os.environ["PGHOST"] = host
            with self.assertRaisesRegex(RuntimeError, "--allow-local"):
                backup.connection_environment()
            self.assertEqual(backup.connection_environment(True)["PGHOST"], host)
        os.environ["PGHOST"] = "remote.example.org"
        with self.assertRaisesRegex(RuntimeError, "loopback PGHOST"):
            backup.connection_environment(True)

    def test_local_hostaddr_cannot_redirect_to_remote_or_multiple_addresses(self):
        os.environ["PGHOST"] = "localhost"
        for address in ("192.0.2.1", "127.0.0.1,192.0.2.1", "localhost"):
            os.environ["PGHOSTADDR"] = address
            with self.assertRaisesRegex(RuntimeError, "PGHOSTADDR"):
                backup.connection_environment(True)
        for address in ("127.0.0.1", "127.0.0.2", "::1"):
            os.environ["PGHOSTADDR"] = address
            self.assertEqual(backup.connection_environment(True)["PGHOSTADDR"], address)

    def test_services_and_implicit_socket_connections_cannot_override_policy(self):
        for host in ("localhost", "private.example.org"):
            os.environ["PGHOST"] = host
            for name in ("PGSERVICE", "PGSERVICEFILE"):
                with patch.dict(os.environ, {name: "private-value"}):
                    with self.assertRaisesRegex(RuntimeError, "PGSERVICE") as error:
                        backup.connection_environment(host == "localhost")
                    self.assertNotIn("private-value", str(error.exception))
        for host in ("/tmp", "@socket", "localhost,remote.example.org"):
            os.environ["PGHOST"] = host
            with self.assertRaisesRegex(RuntimeError, "one explicit"):
                backup.connection_environment()

    def test_rejected_remote_backup_creates_no_files_and_runs_no_tools(self):
        os.environ.update(PGHOST="private.example.org", PGDATABASE="source", PGPASSWORD="must-not-be-printed")
        with tempfile.TemporaryDirectory() as directory, patch.object(backup, "run") as runner:
            target = Path(directory) / "new-folder" / "backup.dump"
            with self.assertRaises(RuntimeError) as error:
                backup.backup(target)
            self.assertNotIn("must-not-be-printed", str(error.exception))
            self.assertFalse(target.parent.exists())
            runner.assert_not_called()


if __name__ == "__main__":
    unittest.main()

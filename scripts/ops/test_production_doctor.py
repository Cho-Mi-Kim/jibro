from collections import namedtuple
import unittest

from production_doctor import assess

Disk = namedtuple("Disk", "total used free")


class ProductionDoctorTests(unittest.TestCase):
    def checks(self, **changes):
        values = {"disk": Disk(20 * 1024 ** 3, 8 * 1024 ** 3, 12 * 1024 ** 3), "certificate": 60,
                  "containers": [{"name": "backend", "running": True, "health": "healthy", "restarts": 0}],
                  "private_files": {"runtime": 0o100600}, "timers": {"certbot.timer": True}}
        values.update(changes)
        return {item["name"]: item["ok"] for item in assess(**values)}

    def test_healthy_baseline(self):
        self.assertTrue(all(self.checks().values()))

    def test_resource_pressure_and_expiry_are_failures(self):
        self.assertFalse(self.checks(certificate=13)["tls"])
        self.assertFalse(self.checks(disk=Disk(20 * 1024 ** 3, 19 * 1024 ** 3, 1024 ** 3))["disk"])

    def test_stopped_unhealthy_or_restarting_container_is_not_ready(self):
        for changes in ({"running": False}, {"health": "unhealthy"}, {"health": "missing"}, {"restarts": 3}):
            container = {"name": "web", "running": True, "health": "healthy", "restarts": 0} | changes
            self.assertFalse(self.checks(containers=[container])["web"])

    def test_missing_or_exposed_secrets_and_disabled_renewal_are_failures(self):
        for mode in (None, 0o100644, 0o100640):
            self.assertFalse(self.checks(private_files={"runtime": mode})["runtime"])
        self.assertFalse(self.checks(timers={"certbot.timer": False})["certbot.timer"])


if __name__ == "__main__":
    unittest.main()

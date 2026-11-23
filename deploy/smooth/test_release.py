import json
from pathlib import Path
import tempfile
import os
import re
import unittest
from unittest.mock import patch
import release
import compatibility


class ReleaseFailureTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        root = Path(self.directory.name) / "smooth-release"
        config = Path(self.directory.name) / "config.json"
        config.write_text(json.dumps(dict(root=str(root), gateway="quotation-test-gateway", worker="worker",
            backend="old", frontend="front", edge="edge")))
        self.manager = release.Release(config)
        self.resume_patch = patch.object(self.manager, "resume_slot")
        self.resume_patch.start()
        self.addCleanup(self.resume_patch.stop)
        self.old = dict(release="old", backend="old", frontend="front", backend_id="1", frontend_id="2")
        self.new = dict(release="new", backend="new", frontend="front", backend_id="3", frontend_id="2")
        self.manager.state = dict(installed=True, active=self.old, previous=None)
        self.path = self.manager.gateway_dir / "default.conf"
        self.path.write_text(release.configuration(self.old, "worker"))
        self.addCleanup(self.directory.cleanup)

    def test_failed_readiness_never_reloads_gateway(self):
        with patch.object(self.manager, "smoke", side_effect=RuntimeError("unhealthy")), patch("release.run") as command:
            with self.assertRaisesRegex(RuntimeError, "unhealthy"):
                self.manager.switch(self.new)
            command.assert_not_called()
        self.assertEqual(self.manager.state["active"], self.old)

    def test_public_failure_restores_old_route_without_stopping_any_container(self):
        with patch.object(self.manager, "smoke"), patch.object(self.manager, "marker", return_value="old"), \
             patch.object(self.manager, "worker_pids", return_value=["10"]), patch.object(self.manager, "wait_marker"), \
             patch.object(self.manager, "public_smoke", side_effect=RuntimeError("business smoke failed")), \
             patch("release.run") as command:
            with self.assertRaisesRegex(RuntimeError, "business smoke failed"):
                self.manager.switch(self.new)
            calls = command.call_args_list
            self.assertEqual(sum(c.args[-1] == "reload" for c in calls), 2)
            self.assertFalse(any("stop" in c.args or "rm" in c.args for c in calls))
        self.assertEqual(self.path.read_text(), release.configuration(self.old, "worker"))
        self.assertNotIn("pending", self.manager.state)
        self.assertEqual(self.manager.state["active"], self.old)

    def test_invalid_nginx_config_restores_file_before_reload(self):
        commands = []
        def command(*args):
            commands.append(args)
            if len(commands) == 1:
                raise RuntimeError("nginx syntax error")
            return ""
        with patch.object(self.manager, "smoke"), patch.object(self.manager, "marker", return_value="old"), \
             patch.object(self.manager, "worker_pids", return_value=[]), patch.object(self.manager, "wait_marker"), \
             patch("release.run", side_effect=command):
            with self.assertRaisesRegex(RuntimeError, "syntax"):
                self.manager.switch(self.new)
        self.assertEqual(sum(c[-1] == "reload" for c in commands), 1)
        self.assertEqual(self.path.read_text(), release.configuration(self.old, "worker"))

    def test_interrupted_switch_can_be_recovered_from_durable_intent(self):
        self.manager.state["pending"] = dict(old=self.old, target=self.new, workers=["10"])
        self.path.write_text(release.configuration(self.new, "worker"))
        with patch.object(self.manager, "smoke"), patch.object(self.manager, "wait_marker"), \
             patch.object(self.manager, "public_smoke"), patch("release.run"):
            self.manager.recover()
        self.assertNotIn("pending", self.manager.state)
        self.assertEqual(self.path.read_text(), release.configuration(self.old, "worker"))

    def test_inflight_upload_prevents_retiring_unused_slot(self):
        self.manager.state["slots"] = {"unused": self.new}
        with patch.object(self.manager, "smoke"), patch("release.run", return_value="10 nginx: worker process is shutting down") as command:
            with self.assertRaisesRegex(RuntimeError, "in-flight"):
                self.manager.retire("unused")
            self.assertEqual(command.call_count, 1)

    def test_gateway_disables_request_replay_and_preserves_worker_route(self):
        config = release.configuration(self.new, "worker")
        self.assertIn("proxy_next_upstream off", release.PROXY)
        self.assertIn("proxy_request_buffering off", release.PROXY)
        self.assertIn("purchase-shimo-sync", config)
        self.assertIn("http://worker:8088", config)
        self.assertIn("http://new:8088", config)

    def test_import_restore_excludes_review_publish_and_upload_chunks(self):
        base = "/api/v1/logistics/"
        for suffix in ["imports", "channels/abc-123/imports", "providers/abc-123/imports",
                       "rebuild/datasets/abc-123/imports", "rebuild/uploads/abc-123/complete",
                       "rebuild/imports/abc-123/retry"]:
            self.assertIsNotNone(re.fullmatch(release.IMPORT_PATH, base + suffix))
        for suffix in ["imports/preview", "rebuild/imports/abc-123/publish-ready",
                       "rebuild/imports/abc-123/publish-progress", "versions/abc-123/review",
                       "rebuild/uploads/abc-123/files/0/chunks/0"]:
            self.assertIsNone(re.fullmatch(release.IMPORT_PATH, base + suffix))
        self.assertNotIn(release.IMPORT_PATH, release.configuration(self.new, "worker"))
        self.assertIn(release.IMPORT_PATH, release.configuration(dict(self.new, imports_on_worker=True), "worker"))

    def test_restore_profile_preserves_application_identity(self):
        self.manager.state["slots"] = {"old": self.old}
        with patch.object(self.manager, "check_identity"), patch.object(self.manager, "switch") as switch, \
             patch("release.environment", return_value={"APP_LOGISTICS_RESUME_ON_START":"false"}):
            self.manager.restore_imports("restore")
        target = switch.call_args.args[0]
        self.assertEqual(target["backend_id"], self.old["backend_id"])
        self.assertEqual(target["frontend_id"], self.old["frontend_id"])
        self.assertTrue(target["imports_on_worker"])

    @unittest.skipIf(os.name == "nt", "Linux flock integration")
    def test_lock_reloads_state_written_since_construction(self):
        self.manager.save()
        newer=dict(self.manager.state,active=self.new)
        release.atomic(self.manager.state_path,json.dumps(newer))
        with self.manager.lock():
            self.assertEqual(self.manager.state["active"],self.new)


class CompatibilityTests(unittest.TestCase):
    def test_changed_migration_is_rejected_even_if_adapter_attempts_to_allow_it(self):
        name = compatibility.PREFIX + "db/migration/V1.sql"
        runtime = compatibility.PREFIX + "com/milano/quotation/common/WebReleaseConfiguration.class"
        before, after = {name:b"old", runtime:b"runtime"}, {name:b"new", runtime:b"runtime"}
        with patch("compatibility.entries", side_effect=[before, after]), patch("compatibility.closure", return_value=set()):
            with self.assertRaisesRegex(ValueError, "Worker/schema compatibility"):
                compatibility.verify("old.jar", "new.jar", [dict(path=name, worker_sha256=compatibility.sha(b"old"), web_sha256=compatibility.sha(b"new"))])


if __name__ == "__main__":
    unittest.main()

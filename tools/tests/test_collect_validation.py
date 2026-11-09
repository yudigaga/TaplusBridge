import json
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import collect_validation


ROOT = "io.github.yudigaga.taplusbridge."
CONTROLLER = ROOT + "runtime.ModuleProcessLifecycle"
SERVER = ROOT + "runtime.RuntimeServer"
TRANSPORT = ROOT + "runtime.BridgeTransport"
REMOVED = [ROOT + "runtime.RuntimeLaunchEntry", ROOT + "runtime.SystemPackageOps"]


class CollectValidationTest(unittest.TestCase):
    def collect(self, *, missing_controller=False, retained=None, inline=False, missing_inline_method=False):
        # Only the external DEX decoder is replaced; all artifact reads, mapping validation,
        # release gates and the produced manifest use the real collector.
        classes = {"L" + name.replace(".", "/") + ";": {} for name in (
            ROOT + "hook.TaplusModule", SERVER, TRANSPORT,
            "io.github.libxposed.service.XposedService", "top.yukonga.miuix.kmp.basic.ScaffoldKt")}
        mapping = []
        if not missing_controller:
            if inline:
                mapping = ["example.InlinedOwner -> a:", "    1:1:void " + CONTROLLER + ".scheduleIdle():1:1 -> b"]
                classes["La;"] = {} if missing_inline_method else {"b()V": 0}
            else:
                mapping = [CONTROLLER + " -> a:"]
                classes["La;"] = {}
        if retained:
            mapping += [retained + " -> bad:"]
            classes["Lbad;"] = {}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); build = root / "build"; artifacts = root / "artifacts"
            artifacts.mkdir()
            for directory in ("outputs/mapping/release", "test-results/testDebugUnitTest", "reports"):
                (build / directory).mkdir(parents=True)
            (build / "outputs/mapping/release/mapping.txt").write_text("\n".join(mapping) + "\n", encoding="utf-8")
            (build / "test-results/testDebugUnitTest/TEST-example.xml").write_text(
                '<testsuite name="example" tests="1" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
            (build / "reports/lint-results-release.txt").write_text("0 errors, 0 warnings", encoding="utf-8")
            version = "0.15.41"
            (artifacts / ("apksigner-" + version + ".txt")).write_text(
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Signer #1 certificate SHA-256 digest: " + "1" * 64 + "\n", encoding="utf-8")
            (artifacts / ("zipalign-" + version + ".txt")).write_text("Verification successful", encoding="utf-8")
            manifest = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="io.github.yudigaga.taplusbridge" android:versionName="0.15.41" android:versionCode="73">
                <permission android:name="io.github.yudigaga.taplusbridge.CANCEL_CAPTURE" android:protectionLevel="signature"/>
                <application><activity android:name="io.github.yudigaga.taplusbridge.ui.TransitActivity" android:theme="@style/TransitTheme"/>
                <provider android:name="rikka.shizuku.ShizukuProvider" android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"/></application>
                </manifest>'''
            (artifacts / ("module-manifest-" + version + ".xml")).write_text(manifest, encoding="utf-8")
            (artifacts / ("host-contract-" + version + ".json")).write_text("{}", encoding="utf-8")
            seed = Path(__file__).resolve().parents[2] / "app/src/main/assets/icebridge-rules/revisions/rules-000001.json"
            with zipfile.ZipFile(artifacts / ("TaplusBridge-" + version + "-release.apk"), "w") as apk:
                apk.writestr("META-INF/xposed/java_init.list", ROOT + "hook.TaplusModule")
                apk.writestr("META-INF/xposed/scope.list", "com.miui.contentextension\nsystem\n")
                apk.writestr("META-INF/xposed/module.prop", "minApiVersion=102\ntargetApiVersion=102\n")
                apk.writestr("classes.dex", b"Synthetic DEX decoder input")
                apk.writestr("assets/icebridge-rules/revisions/rules-000001.json", seed.read_bytes())
            with patch.object(collect_validation, "definitions", return_value=classes), redirect_stdout(StringIO()):
                collect_validation.collect(build, artifacts, version, 73, 0, "release", "1" * 64)
            return json.loads((artifacts / "validation.json").read_text(encoding="utf-8"))

    def test_current_release_requires_ordinary_process_controller_in_dex(self):
        with self.assertRaisesRegex(AssertionError, "ModuleProcessLifecycle"):
            self.collect(missing_controller=True)

    def test_current_release_records_only_supported_routing_and_lifetime_claims(self):
        result = self.collect()
        self.assertFalse(result["frozen_module_runtime"])
        self.assertEqual("module ContentProvider", result["ordinary_provider_route"])
        self.assertEqual(3000, result["module_idle_retirement_ms"])
        self.assertIn(CONTROLLER, result["r8"]["code_presence"])
        self.assertIn(TRANSPORT, result["r8"]["code_presence"])
        self.assertNotIn("runtime_launch_request_code_zero_supported", result)
        self.assertNotIn("probe_frozen_launch_tickets", result)
        self.assertFalse(result["runtime_hook_verified"])

    def test_inlined_controller_requires_its_mapped_method_to_survive_in_dex(self):
        result = self.collect(inline=True)
        self.assertEqual({"kind": "inlined", "owner": "a", "method": "b"}, result["r8"]["code_presence"][CONTROLLER])
        with self.assertRaisesRegex(AssertionError, "ModuleProcessLifecycle"):
            self.collect(inline=True, missing_inline_method=True)

    def test_release_rejects_retained_frozen_launch_code_even_when_obfuscated(self):
        for removed in REMOVED:
            with self.subTest(removed=removed), self.assertRaisesRegex(AssertionError, "Removed frozen-runtime code"):
                self.collect(retained=removed)


if __name__ == "__main__":
    unittest.main()

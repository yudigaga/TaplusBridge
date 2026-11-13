"""Collect local test/signing/static evidence; never implies device validation."""
import argparse
import hashlib
import json
import shutil
import re
import struct
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime
from pathlib import Path
from verify_host import definitions


def native_alignment(archive):
    libraries = []
    for name in archive.namelist():
        if not name.startswith("lib/") or not name.endswith(".so"):
            continue
        data = archive.read(name)
        assert data[:4] == b"\x7fELF", name
        endian = "<" if data[5] == 1 else ">"
        wide = data[4] == 2
        phoff = struct.unpack_from(endian + ("Q" if wide else "I"), data, 32 if wide else 28)[0]
        size, count = struct.unpack_from(endian + "HH", data, 54 if wide else 42)
        alignments = []
        for i in range(count):
            offset = phoff + i * size
            if struct.unpack_from(endian + "I", data, offset)[0] != 1:
                continue
            alignment = struct.unpack_from(endian + ("Q" if wide else "I"), data, offset + (48 if wide else 28))[0]
            assert alignment >= 16384, (name, alignment)
            alignments.append(alignment)
        assert alignments, name
        libraries.append({"path": name, "load_segment_alignments": alignments})
    return libraries


def collect(build, artifacts, version, code, warnings, variant="debug", certificate_sha256=None):
    release = variant == "release"
    suffix = "-release" if release else "-dev"
    version_name = version if release else version + "-dev"
    mapping = build / "outputs/mapping/release/mapping.txt"
    renamed = {}
    mapping_lines = []
    code_presence = {}
    removed_frozen_code = {}
    if release:
        assert mapping.is_file(), "Release R8 mapping missing"
        mapping_lines = mapping.read_text(encoding="utf-8").splitlines()
        renamed = dict(match.groups() for line in mapping_lines if (match := re.fullmatch(r"(\S+) -> (\S+):", line)))
    apk = artifacts / ("TaplusBridge-" + version + suffix + ".apk")
    suites = []
    results = build / "test-results/testDebugUnitTest"
    for file in sorted(results.glob("TEST-*.xml")):
        suite = ET.parse(file).getroot()
        suites.append({"suite": suite.attrib["name"], **{name: int(suite.attrib.get(name, 0))
                       for name in ("tests", "failures", "errors", "skipped")}})
    assert suites, "No test results"
    totals = {name: sum(row[name] for row in suites) for name in ("tests", "failures", "errors", "skipped")}
    assert totals["failures"] == totals["errors"] == totals["skipped"] == 0, totals
    lint_path = build / ("reports/lint-results-" + variant + ".txt")
    lint = lint_path.read_text(encoding="utf-8")
    assert f"0 errors, {warnings} warnings" in lint, "Review changed lint result"
    shutil.copytree(results, artifacts / ("junit-" + version), dirs_exist_ok=True)
    shutil.copy2(lint_path, artifacts / ("lint-" + version + ".txt"))
    certificate = (artifacts / ("apksigner-" + version + ".txt")).read_text(encoding="utf-8-sig")
    assert "v2): true" in certificate
    if release:
        assert certificate_sha256 and re.fullmatch(r"[0-9a-fA-F]{64}", certificate_sha256), "Expected release certificate required"
        assert "CN=Android Debug" not in certificate, "Release must use the long-term key"
    else:
        certificate_sha256 = "4e06d06e242802a8b8e77f696079ddd78e3d615d0cf73b781f3a187abb51f980"
        assert "CN=Android Debug" in certificate
    actual_certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})", certificate)
    assert actual_certificate and actual_certificate.group(1).lower() == certificate_sha256.lower(), "Signing certificate changed"
    assert "Verification successful" in (artifacts / ("zipalign-" + version + ".txt")).read_text(encoding="utf-8-sig")
    with zipfile.ZipFile(apk) as archive:
        entry = archive.read("META-INF/xposed/java_init.list").decode().strip()
        scope = archive.read("META-INF/xposed/scope.list").decode().strip().splitlines()
        metadata = dict(line.split("=", 1) for line in
                        archive.read("META-INF/xposed/module.prop").decode().splitlines() if "=" in line)
        assert scope == ["com.miui.contentextension", "system"], (
            "Modern API system_server requires the virtual system scope; actual scope: " + repr(scope))
        assert metadata["minApiVersion"] == metadata["targetApiVersion"] == "102"
        classes = {}
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes.update(definitions(archive.read(name)))
        assert "L" + entry.replace(".", "/") + ";" in classes
        assert not any(name.startswith(("Lio/github/libxposed/api/", "Lde/robv/android/xposed/")) for name in classes)
        assert "Lio/github/yudigaga/taplusbridge/bridge/GenericLinkLauncher;" not in classes
        assert "Lio/github/yudigaga/taplusbridge/rules/FluidCloudRuleCatalog;" not in classes
        for original in ("io.github.yudigaga.taplusbridge.runtime.RuntimeLaunchEntry",
                         "io.github.yudigaga.taplusbridge.runtime.SystemPackageOps"):
            packaged = renamed.get(original, original)
            descriptor = "L" + packaged.replace(".", "/") + ";"
            frame = re.compile(r"\s" + re.escape(original) + r"\.[A-Za-z_$<>][A-Za-z0-9_$<>]*\(")
            assert descriptor not in classes and original not in renamed and not any(
                frame.search(line) for line in mapping_lines), "Removed frozen-runtime code retained: " + original
            removed_frozen_code[original] = {"dex_definition_absent": True, "r8_mapping_absent": True}
        required_classes = ("io.github.yudigaga.taplusbridge.runtime.RuntimeServer",
                         "io.github.yudigaga.taplusbridge.runtime.ModuleProcessLifecycle",
                         "io.github.yudigaga.taplusbridge.runtime.BridgeTransport",
                         "io.github.libxposed.service.XposedService",
                         "top.yukonga.miuix.kmp.basic.ScaffoldKt")
        if code >= 78:
            required_classes += ("io.github.yudigaga.taplusbridge.hook.SourceSelectionHooks",
                                 "io.github.yudigaga.taplusbridge.selection.SelectionActions",
                                 "io.github.yudigaga.taplusbridge.browser.BrowserStore",
                                 "io.github.yudigaga.taplusbridge.hook.HostActionTargets")
        if code >= 79:
            required_classes += ("io.github.yudigaga.taplusbridge.probe.RecognitionMenuController",
                                 "io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuHooks")
        if code >= 80:
            required_classes += ("io.github.yudigaga.taplusbridge.probe.RecognitionMenuRetention",
                                 "io.github.yudigaga.taplusbridge.probe.MenuKeyInputHooks",
                                 "io.github.yudigaga.taplusbridge.hook.HostRecognitionMenuRetention")
        for original in required_classes:
            packaged = renamed.get(original, original)
            descriptor = "L" + packaged.replace(".", "/") + ";"
            if descriptor in classes:
                code_presence[original] = {"kind": "class", "owner": packaged}
                continue
            # R8 can inline and remove helper classes; verify the surviving method named in its mapping.
            owner = None
            frame = re.compile(r"\s" + re.escape(original) + r"\.[A-Za-z_$<>][A-Za-z0-9_$<>]*\(")
            for line in mapping_lines:
                heading = re.fullmatch(r"(\S+) -> (\S+):", line)
                if heading:
                    owner = heading.group(2)
                elif owner is not None and frame.search(line) and " -> " in line:
                    method = line.rsplit(" -> ", 1)[1]
                    members = classes.get("L" + owner.replace(".", "/") + ";", {})
                    if any(member.startswith(method + "(") for member in members):
                        code_presence[original] = {"kind": "inlined", "owner": owner, "method": method}
                        break
            assert original in code_presence, "Packaged code missing: " + original
        assert hashlib.sha256(archive.read("assets/icebridge-rules/revisions/rules-000001.json")).hexdigest() == "e840f50919b022b085621c34394c7d09fde1373e22467e490bbb8c8633a05203"
        native_libraries = native_alignment(archive)
    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(artifacts / ("module-manifest-" + version + ".xml")).getroot()
    assert manifest.attrib["package"] == "io.github.yudigaga.taplusbridge"
    assert manifest.attrib[ns + "versionName"] == version_name
    assert manifest.attrib[ns + "versionCode"] == str(code)
    transit = manifest.find("application/activity[@" + ns + "name='io.github.yudigaga.taplusbridge.ui.TransitActivity']")
    assert transit is not None and ns + "theme" in transit.attrib, "No window theme on transit entry"
    cancel_permission = manifest.find("permission[@" + ns + "name='io.github.yudigaga.taplusbridge.CANCEL_CAPTURE']")
    assert cancel_permission is not None and cancel_permission.attrib[ns + "protectionLevel"] in ("signature", "0x2", "2")
    shizuku = manifest.find("application/provider[@" + ns + "name='rikka.shizuku.ShizukuProvider']")
    assert shizuku is not None and shizuku.attrib.get(ns + "permission") == "android.permission.INTERACT_ACROSS_USERS_FULL"
    assert not any("icebox" in value.lower() for node in manifest.iter() for value in node.attrib.values())
    validation = {
        "checked_at": datetime.now().astimezone().isoformat(),
        "apk": apk.name, "size": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest().upper(),
        "versionName": version_name, "versionCode": code,
        "r8": {"enabled": release, "mapping_sha256": hashlib.sha256(mapping.read_bytes()).hexdigest() if release else None,
               "code_presence": code_presence},
        "signing": {"type": "long-term release" if release else "Android Debug", "v2": True,
                    "v3": "v3): true" in certificate,
                    "certificate_sha256": actual_certificate.group(1).lower()},
        "zip_alignment_4_bytes": True,
        "zip_alignment_native_16k": True,
        "native_16k_alignment": "ELF PT_LOAD verified" if native_libraries else "no native libraries in module",
        "native_libraries": native_libraries,
        "ui": {"toolkit": "Compose Miuix", "version": "0.9.4", "renders": sorted(p.name for p in (artifacts / ("ui-" + version)).glob("*.png"))},
        "rules": {"engine": "IceBridge", "offline_seed_revision": 1, "offline_seed_rules": 32, "legacy_table_removed": True},
        "thaw_channels": ["ROOT", "SHIZUKU"], "icebox_sdk_removed": True,
        "recognition_diagnostics_ui_removed": True,
        "frozen_module_runtime": False,
        "ordinary_provider_route": "module ContentProvider",
        "module_idle_retirement_ms": 3000,
        "removed_frozen_runtime_code": removed_frozen_code,
        "system_runtime_channels": {"scope": "owner user 0, Android 13+",
                                    "storage": "framework preferences: versioned system configuration and opaque routing identity",
                                    "capabilities": ["source_scope", "source_content", "capture", "supplemental_link_probe", "drag_gesture"],
                                    "target_package_operations": False, "device_verified": False},
        "generic_user_target": False, "manual_link_open": False, "legacy_global_target_removed": True,
        "rule_forwarding": "module Provider/Activity selects configured domain or protocol and source; optional verified cloud-link adapters",
        "online_snapshot_storage": "versioned Base64 UTF-8 in atomic preference transaction; legacy plaintext compatible",
        "libxposed_metadata": metadata, "scope": scope,
        "api_classes_bundled": False, "legacy_xposed_classes_bundled": False,
        "modern_system_scope_verified_in_apk": scope == ["com.miui.contentextension", "system"],
        "tests": totals, "suites": suites, "lint": {"errors": 0, "warnings": warnings},
        "host_contract": json.loads((artifacts / ("host-contract-" + version + ".json")).read_text(encoding="utf-8")),
        "device_connected": False, "installed_on_device": False, "runtime_hook_verified": False,
        "root_shizuku_thaw_verified_on_device": False, "target_deep_links_verified_on_device": False,
        "release_signed_with_long_term_key": release, "remote_published": False,
        "wechat_or_source_app_hooked": False,
        "source_selection_toolbar": {"code_included": code >= 78,
                                     "requires_explicit_source_scope": True,
                                     "supported_ui": "native readonly TextView floating ActionMode",
                                     "device_verified": False},
        "recognition_popup_cleanup": {"code_included": code >= 79,
                                      "requires_source_app_scope": False,
                                      "preserves_existing_windows": True,
                                      "echo_retention": {"code_included": code >= 80,"max_age_ms": 250,
                                                         "one_use": True,"requires_real_key_input_guard": True,
                                                         "device_verified": False},
                                      "device_verified": False},
        "real_screenshot_card_payload_verified": False,
        "system_capture_mode": "owner-user source capture and supplemental link probes; source launch interception restricted to an authenticated gesture",
        "probe_protocol": 2,
        "probe_global_source_cooldown": False,
        "system_capture_verified_on_device": False,
        "system_capture_raw_urls_persisted": False,
        "probe_synthetic_input_verified_on_device": False,
        "probe_source_page_never_visible_verified_on_device": False,
        "recognition_limits": "Automatic probe requires fresh source geometry, physical-UP evidence, default portrait display and verified source-window targeting. Only verified source-launch entries are consumed; unknown asynchronous source launches cannot be attributed perfectly, other launch APIs and in-Activity navigation are not proven covered. Zero flash and real destination require device validation.",
    }
    python_tests = artifacts / ("python-tests-" + version + ".txt")
    if python_tests.exists():
        result = python_tests.read_text(encoding="utf-8-sig")
        count = re.search(r"Ran (\d+) tests?", result)
        assert count and result.strip().endswith("OK"), "Diagnostic tool tests did not pass"
        validation["diagnostics_tool_tests"] = {"tests": int(count.group(1)), "failures": 0, "errors": 0}
    (artifacts / "validation.json").write_text(json.dumps(validation, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (artifacts / ("validation-" + version + ".json")).write_text(json.dumps(validation, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    (artifacts / ("SHA256SUMS-" + version + ".txt")).write_text(validation["sha256"] + "  " + apk.name + "\n", encoding="utf-8")
    print(json.dumps({"tests": totals, "sha256": validation["sha256"], "size": validation["size"]}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", type=Path)
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("--version", default="0.8.1")
    parser.add_argument("--code", type=int, default=14)
    parser.add_argument("--lint-warnings", type=int, required=True)
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    parser.add_argument("--certificate-sha256", help="Required for release; compare with the prior signed APK")
    args = parser.parse_args()
    collect(args.build, args.artifacts, args.version, args.code, args.lint_warnings,
            args.variant, args.certificate_sha256)

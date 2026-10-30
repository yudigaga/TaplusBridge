"""Check the supplied system capture APK and saved DEX excerpts, without executing its code."""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from verify_host import definitions


def verify(apk, analysis):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        dex = archive.read("classes.dex")
        classes = definitions(dex)
        service = "Lcom/miui/contentcatcher/ContentCatcherService;"
        fetcher = "Lcom/miui/catcherpatch/ViewContentFetcher;"
        required = {
            service: ["getPageConfig(Lmiui/contentcatcher/sdk/Token;)Lmiui/contentcatcher/sdk/data/PageConfig;",
                "updateConfig([Ljava/lang/String;)V", "updateClientConfig(Ljava/lang/String;Ljava/lang/String;Z)V",
                "decorateContent(Lmiui/contentcatcher/sdk/ClientToken;Lmiui/contentcatcher/sdk/Token;Lmiui/contentcatcher/sdk/DecorateContentParam;)V"],
            fetcher: ["fetchNativeViewContent(Landroid/view/View;Z)Landroid/os/Bundle;",
                "fetchWechatDefaultContent(Landroid/view/View;I)Landroid/os/Bundle;",
                "fetchTargetViewContent(Landroid/view/View;Ljava/lang/String;IZ)Landroid/os/Bundle;",
                "reservedFunction(Ljava/util/Map;I)Ljava/util/Map;"],
            "Lcom/miui/contentcatcher/Interceptor;": ["updateContent(Lmiui/contentcatcher/sdk/DecorateContentParam;)V"],
        }
        checked = []
        for klass, members in required.items():
            for member in members:
                assert member in classes.get(klass, {}), klass + "->" + member
                checked.append(klass + "->" + member)
        config = json.loads(archive.read("assets/config/config_view.json"))
        reflections = []
        for package in config["packages"]:
            for rule in package["configs"]:
                for catcher in rule["catchers"]:
                    for view in catcher["views"]:
                        reflections.append({"package": package["package"], "version": rule["version"],
                            "activity": catcher["activity"], "type": view["type"], "fields": view["name"]})
        source = (analysis / "dex/fetchTargetViewContent.smali").read_text(encoding="utf-8-sig")
        assert re.search(r"and-int/lit8\s+\w+, p3, 0x1", source)
        branch = source[source.index("and-int/lit8", source.index('"catchType"')):]
        assert "fetchNativeViewContent(Landroid/view/View;Z)Landroid/os/Bundle;" in branch
        assert "return-object v0" in branch and "return-object v2" in branch
        update = (analysis / "dex/updateContent.smali").read_text(encoding="utf-8-sig")
        assert "if-nez p1" in update and "iput-boolean p1" in update and "return-void" in update
        manifest = ET.parse(analysis / "jadx/resources/AndroidManifest.xml").getroot()
        ns = "{http://schemas.android.com/apk/res/android}"
        return {"apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest().upper(),
            "size": apk.stat().st_size, "package": manifest.attrib["package"],
            "version_name": manifest.attrib[ns + "versionName"], "version_code": int(manifest.attrib[ns + "versionCode"]),
            "dex_files": [name for name in names if name.endswith(".dex")], "classes_dex_bytes": len(dex),
            "native_libraries": [name for name in names if name.startswith("lib/")],
            "method_definitions_checked": checked, "view_reflection_rules": reflections,
            "text_mode_returns_accessibility_over_reflected_bundle": True,
            "nonnull_decorate_request_refetches_content": False,
            "runtime_verified": False, "system_configuration_changed": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("analysis", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.apk, args.analysis)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("package", "version_name", "version_code", "size", "sha256",
        "classes_dex_bytes", "text_mode_returns_accessibility_over_reflected_bundle", "runtime_verified")}, indent=2))

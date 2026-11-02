"""Verify Lens host Hook member definitions in a supplied APK; this is not runtime validation."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

from verify_host import definitions


def verify(apk):
    root = "Lcom/miui/contentextension/"
    required = {
        root + "text/cardview/TaplusRecognitionExpandedImageCard;": {
            "mRecognizeObject:Landroid/widget/TextView;": False,
            "mImageGroup:Landroid/widget/RelativeLayout;": False,
            "refresh()V": False,
            "updateLayout()V": False,
            "onClick(Landroid/view/View;)V": False,
            "doSkipToXiaoMiScanner(ILjava/lang/String;)V": False,
        },
        root + "services/TextContentExtensionService;": {
            "getBitmap()Landroid/graphics/Bitmap;": True,
            "getInjectorPackage()Ljava/lang/String;": True,
            "setBitmap(Landroid/graphics/Bitmap;)V": True,
            "onStartPick()V": False,
            "cancelTask()V": False,
            "onDestroy()V": False,
            "quitByDefault(Ljava/lang/String;ZZ)V": False,
        },
        root + "services/TextContentExtensionService$AppStateWatcher;": {
            "onForegroundActivitiesChanged(IIZ)V": False,
        },
    }
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes.update(definitions(archive.read(name)))
    checked = []
    for klass, members in required.items():
        for name, static in members.items():
            flags = classes.get(klass, {}).get(name)
            if flags is None or bool(flags & 8) != static:
                raise ValueError("Lens host contract mismatch: " + klass + "->" + name)
            checked.append(klass + "->" + name)
    return {"apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "status": "static-contract-pass", "definitions_checked": len(checked), "members": checked,
            "runtime_hook_verified": False, "native_panel_verified": False, "google_search_verified": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = json.dumps(verify(args.apk), ensure_ascii=True, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(result + "\n", encoding="utf-8")
    print(result)

"""Check drag host members in an APK; external ContentCatcher SDK contracts stay explicitly unverified."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zipfile

from verify_host import definitions, uleb


def dex_tables(data):
    word = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings = []
    for index in range(word(56)):
        position = word(word(60) + index * 4)
        _, position = uleb(data, position)
        strings.append(data[position:data.index(0, position)].decode("utf-8", errors="replace"))
    types = [strings[word(word(68) + index * 4)] for index in range(word(64))]
    protos = []
    for index in range(word(72)):
        offset = word(76) + index * 12
        parameters = word(offset + 8)
        arguments = [] if not parameters else [
            types[struct.unpack_from("<H", data, parameters + 4 + item * 2)[0]]
            for item in range(word(parameters))]
        protos.append("(" + "".join(arguments) + ")" + types[word(offset + 4)])
    methods = set()
    for index in range(word(88)):
        owner, proto, name = struct.unpack_from("<HHI", data, word(92) + index * 8)
        methods.add(types[owner] + "->" + strings[name] + protos[proto])
    supers = {}
    for index in range(word(96)):
        offset = word(100) + index * 32
        parent = word(offset + 8)
        supers[types[word(offset)]] = None if parent == 0xffffffff else types[parent]
    return methods, supers


def verify(apk):
    root = "Lcom/miui/contentextension/"
    result = "Lmiui/contentcatcher/sdk/ClientCatcherResult;"
    token = "Lmiui/contentcatcher/sdk/Token;"
    listener = "Lmiui/contentcatcher/sdk/listener/IContentListenerCallback;"
    service = root + "services/TextContentExtensionService;"
    main = root + "text/floatview/TaplusMainFloatView;"
    splash = root + "text/floatview/TaplusSplashFloatView;"
    base = root + "text/floatview/BaseFloatView;"
    card = root + "text/cardview/TaplusRecognitionExpandedCard;"
    text = root + "text/cardview/TaplusRecognitionExpandedTextCard;"
    image = root + "text/cardview/TaplusRecognitionExpandedImageCard;"
    adapter = root + "text/adapter/TaplusRecognitionAdapter;"
    segments = root + "text/adapter/TaplusSegmentAdapter;"
    required = {
        service: {
            "mCallback:" + listener: False,
            "mCatcherResult:" + result: True,
            "sIsTaskFinished:Z": True,
            "mMainFloatView:" + main: False,
            "mSplashFloatView:" + splash: False,
            "onStartCommand(Landroid/content/Intent;II)I": False,
            "onStartPick()V": False,
            "cancelTask()V": False,
            "onDestroy()V": False,
            "onPickText(" + result + ")V": False,
            "onPickImage(" + result + ")V": False,
            "startPickTextTask()V": False,
            "startPickImageTask()V": False,
            "setBitmap(Landroid/graphics/Bitmap;)V": True,
            "parseInjectorToken(" + result + ")V": False,
            "showMainFloatView(Z)V": False,
            "destroySplashFloatView()V": False,
        },
        root + "services/TextContentExtensionService$1;": {
            "onContentReceived(" + result + ")V": False,
        },
        base: {"mMainView:Landroid/view/View;": False},
        main: {
            "mRecognitionCard:" + card: False,
            "showMainFloat(Z)V": False,
        },
        splash: {"hideSplashFloatView(Z)V": False},
        card: {
            "mRecognitionAdapter:" + adapter: False,
            "doSwitchRecognition(Z)V": False,
            "doRefreshImageRecognition()V": False,
            "getCurrentSelectedItem()I": False,
            "hasRefreshed()Z": False,
            "isLoading()Z": False,
            "mHasRefreshed:Z": False,
            "mNeedImageRecognition:Z": False,
        },
        adapter: {"getItem(I)" + root + "text/cardview/ITaplusCardView;": False},
        text: {
            "mTranslate:Landroid/widget/TextView;": False,
            "mSelectAll:Landroid/widget/TextView;": False,
            "mSegmentAdapter:" + segments: False,
            "isLoading()Z": False,
            "getSelectedNumber()I": False,
            "getSegmentCount()I": False,
            "getSelectedWords()Ljava/lang/String;": False,
            "getSelectedWordsWithBlank()Ljava/lang/String;": False,
            "updateSegments(Ljava/util/List;Z)V": False,
            "refresh()V": False,
            "onClick(Landroid/view/View;)V": False,
        },
        segments: {
            "mSegments:Ljava/util/List;": False,
            "mSelectors:Landroid/util/SparseBooleanArray;": False,
            "mSelectedCount:I": False,
            "updateSegments(Ljava/util/List;)V": False,
            "getSelectedWords()Ljava/lang/String;": False,
            "getSelectedCount()I": False,
            "getItemCount()I": False,
            "doSelectAll()V": False,
        },
        root + "data/recognition/ImageRecognition;": {
            "doImageRecognize(Landroid/graphics/Bitmap;)V": True,
        },
        image: {
            "mRecognizeObject:Landroid/widget/TextView;": False,
            "mImageGroup:Landroid/widget/RelativeLayout;": False,
            "onClick(Landroid/view/View;)V": False,
        },
    }
    required_supers = {
        main: base,
        base: "Ljava/lang/Object;",
        service: "Landroid/app/Service;",
        card: "Landroid/widget/LinearLayout;",
        text: "Landroid/widget/LinearLayout;",
        image: "Landroid/widget/LinearLayout;",
        adapter: "Landroidx/viewpager/widget/PagerAdapter;",
        root + "services/TextContentExtensionService$1;":
            "Lmiui/contentcatcher/sdk/listener/IContentListenerCallback$Stub;",
    }
    external_sdk = {
        result: ["<init>()V", "setInjectorToken(" + token + ")V", "getInjectorToken()" + token,
                 "getParams(Ljava/lang/String;)Ljava/util/Map;", "getPropertyMap()Ljava/util/Map;",
                 "addContent(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)V"],
        token: ["<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", "getPkgName()Ljava/lang/String;"],
        listener: ["onContentReceived(" + result + ")V"],
    }
    classes, references, supers = {}, set(), {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                data = archive.read(name)
                classes.update(definitions(data))
                referenced, parents = dex_tables(data)
                references.update(referenced)
                supers.update(parents)
    checked = []
    for owner, members in required.items():
        for member, static in members.items():
            flags = classes.get(owner, {}).get(member)
            if flags is None or bool(flags & 8) != static:
                raise ValueError("Drag host contract mismatch: " + owner + "->" + member)
            checked.append(owner + "->" + member)
    for owner, parent in required_supers.items():
        if supers.get(owner) != parent:
            raise ValueError("Drag host superclass mismatch: " + owner)
    sdk = []
    for owner, members in external_sdk.items():
        for member in members:
            defined = member in classes.get(owner, {})
            referenced = owner + "->" + member in references
            sdk.append({"member": owner + "->" + member,
                        "defined_in_apk": defined, "referenced_in_apk": referenced,
                        "evidence": "definition" if defined else "reference-only" if referenced else "n/a-not-defined-or-referenced"})
    return {
        "apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "status": "static-host-contract-pass", "definitions_checked": len(checked), "members": checked,
        "superclasses": required_supers, "external_sdk_members": sdk,
        "external_sdk_contract_verified": all(item["defined_in_apk"] for item in sdk),
        "external_sdk_note": "ContentCatcher SDK is supplied by the device framework; references do not prove constructors or runtime availability.",
        "runtime_hook_verified": False, "drag_drop_verified": False, "uri_grant_verified": False,
        "long_press_coordination_verified": False,
    }


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

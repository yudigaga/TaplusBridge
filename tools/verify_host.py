"""Check definitions in the supplied host DEX, without installing or executing the APK."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path


def uleb(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 127) << shift
        if byte < 128:
            return result, pos
        shift += 7
        if shift > 35:
            raise ValueError("invalid ULEB128")


def definitions(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("not a standard DEX")
    word = lambda offset: struct.unpack_from("<I", data, offset)[0]
    strings = []
    for i in range(word(56)):
        position = word(word(60) + i * 4)
        _, position = uleb(data, position)
        end = data.index(0, position)
        strings.append(data[position:end].decode("utf-8", errors="replace"))
    types = [strings[word(word(68) + i * 4)] for i in range(word(64))]
    protos = []
    for i in range(word(72)):
        offset = word(76) + i * 12
        params = word(offset + 8)
        arguments = [] if not params else [types[struct.unpack_from("<H", data, params + 4 + j * 2)[0]]
                                             for j in range(word(params))]
        protos.append("(" + "".join(arguments) + ")" + types[word(offset + 4)])
    methods, fields = [], []
    for i in range(word(88)):
        klass, proto, name = struct.unpack_from("<HHI", data, word(92) + i * 8)
        methods.append((types[klass], strings[name] + protos[proto]))
    for i in range(word(80)):
        klass, typ, name = struct.unpack_from("<HHI", data, word(84) + i * 8)
        fields.append((types[klass], strings[name] + ":" + types[typ]))
    result = {}
    for i in range(word(96)):
        offset = word(100) + i * 32
        klass = types[word(offset)]
        position = word(offset + 24)
        if not position:
            continue
        counts = []
        for _ in range(4):
            count, position = uleb(data, position)
            counts.append(count)
        members = {}
        for group, count in enumerate(counts):
            index = 0
            for _ in range(count):
                delta, position = uleb(data, position)
                access, position = uleb(data, position)
                index += delta
                if group >= 2:
                    _, position = uleb(data, position)
                    owner, name = methods[index]
                else:
                    owner, name = fields[index]
                if owner != klass:
                    raise ValueError("class/member ownership mismatch")
                members[name] = access
        result[klass] = members
    return result


def verify(apk):
    root = "Lcom/miui/contentextension/"
    context = "Landroid/content/Context;"
    intent = "Landroid/content/Intent;"
    catcher = "Lmiui/contentcatcher/sdk/ClientCatcherResult;"
    info = root + "data/recognition/TextRecommendationInfo;"
    required = {
        root + "utils/AppsUtils;": {
            "generateOpenIntent(" + context + info + ")" + intent: True,
            "startActivity(" + context + intent + ")V": True,
        },
        root + "clipboard/utils/ClipboardUtils;": {
            "generateNerIntent(" + context + "Ljava/lang/String;Ljava/lang/String;)" + intent: True,
        },
        root + "services/TextContentExtensionService;": {
            "getInjectorPackage()Ljava/lang/String;": True,
            "parseInjectorToken(" + catcher + ")V": False,
            "onStartPick()V": False,
            "onStartCommand(" + intent + "II)I": False,
            "cancelTask()V": False, "onDestroy()V": False,
            "sIsClipboardViewShowing:Z": True,
        },
        info: {name + "()Ljava/lang/String;": False for name in
               ("getDetailUrl", "getIntent", "getPackageName", "getCategory")},
        root + "App;": {"getInstance()" + root + "App;": True},
        root + "text/RecommendationMonitor;": {
            "onDetailReady(Ljava/util/ArrayList;)V": True,
            "onSegmentReady(Ljava/util/ArrayList;Z)V": True,
        },
        root + "data/recognition/TextRecommendationUtils;": {
            "getTextSegmentResponse(" + context + "Ljava/lang/String;Z)V": True,
            "tryToGetRecommendation(" + context + "Ljava/lang/String;Ljava/lang/String;)V": True,
            "sLastRecommendationTask:Landroid/os/AsyncTask;": True,
            "sLastSegmentTask:Landroid/os/AsyncTask;": True,
            "sIsSegmentingPassage:Z": True,
        },
        root + "data/recognition/ImageRecognition;": {
            "processOcrResult(" + context + "Ljava/lang/String;)V": True,
            "doImageRecognize(Landroid/graphics/Bitmap;)V": True,
            "mOcrDisposable:Lio/reactivex/disposables/Disposable;": True,
        },
        root + "data/recognition/TextRecommendationUtils$1;": {
            "onPostExecute(Ljava/util/ArrayList;)V": False,
        },
        root + "data/recognition/TextRecommendationUtils$2;": {
            "onPostExecute(Ljava/lang/String;)V": False,
        },
        root + "data/recognition/ImageRecognition$1;": {
            "onNext(Lkotlin/Pair;)V": False,
            "onError(Ljava/lang/Throwable;)V": False,
        },
        root + "text/cardview/TaplusRecommendBaseCard;": {
            "bindRecommendInfo(" + info + ")V": False,
            "bindNerInfo(" + root + "data/recognition/Entity;)V": False,
            "bindDeviceInfo(" + root + "clipboard/model/DeviceInfo;)V": False,
            "mRecommendTitle:Landroid/widget/TextView;": False,
            "mRecommendAbstract:Landroid/widget/TextView;": False,
            "mRecommendIcon:" + root + "view/SourceIcon;": False,
        },
        root + "view/SourceIcon;": {"setIcon(Landroid/graphics/drawable/Drawable;)V": False},
    }
    required[root + "services/TextContentExtensionService;"].update({
        "onPickText(" + catcher + ")V": False,
        "onPickImage(" + catcher + ")V": False,
        "startPickTextTask()V": False, "startPickImageTask()V": False,
    })
    required[info].update({name + ":Ljava/lang/String;": False for name in
                           ("title", "cat", "detailUrl", "packageName", "intent", "sourceIcon")})
    required[info]["<init>()V"] = False
    required[info]["setCanOpenWithApp(Z)V"] = False
    required[root + "services/TextContentExtensionService;"].update({
        "getInjectorActivity()Ljava/lang/String;": True,
        "getInjectorPoint()Landroid/graphics/Point;": True,
        "getContentRect()Landroid/graphics/Rect;": False,
        "getFirstTouchPoint()Landroid/graphics/PointF;": False,
        "mSplashFloatView:" + root + "text/floatview/TaplusSplashFloatView;": False,
        "mMainFloatView:" + root + "text/floatview/TaplusMainFloatView;": False,
    })
    required[root + "text/floatview/BaseFloatView;"] = {
        "mMainView:Landroid/view/View;": False,
        "mWindowManager:Landroid/view/WindowManager;": False,
        "wmParams:Landroid/view/WindowManager$LayoutParams;": False,
        "mIsShowing:Z": False,
    }
    classes = {}
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes.update(definitions(archive.read(name)))
    checked = []
    for klass, members in required.items():
        actual = classes.get(klass, {})
        for member, static in members.items():
            if member not in actual or bool(actual[member] & 8) != static:
                raise ValueError("host contract mismatch: " + klass + "->" + member)
            checked.append(klass + "->" + member)
    return {"apk": str(apk.resolve()), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "status": "static-contract-pass", "definitions_checked": len(checked), "members": checked,
            "runtime_hook_verified": False}


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

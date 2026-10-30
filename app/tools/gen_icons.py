#!/usr/bin/env python3
"""生成 olfact 的图标集（vector drawable）。

只生成「我们自己画的」轮廓图标；原版素材（home_*.xml / *_webp）按需从
_archive/olfact-repack/decode/res 拷贝，不在这里重画。

用法：python3 app/tools/gen_icons.py
输出：app/src/main/res/drawable/<name>.xml 与 core/src/main/res/drawable/uikit_<name>.xml
"""
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
APP = os.path.join(ROOT, "app/src/main/res/drawable")
CORE = os.path.join(ROOT, "core/src/main/res/drawable")

STROKE = "M12,6 L12,6"  # unused placeholder

# name -> list of (pathData, style, extra)
# style: "s" 描边（2dp, round cap/join） | "f" 填充
ICONS = {
    # ---- 导航 / 通用 ----
    "chevron_right": [("M9,5 L16,12 L9,19", "s")],
    "arrow_right": [("M4,12 H20", "s"), ("M14,6 L20,12 L14,18", "s")],
    "back": [("M15,4 L7,12 L15,20", "s")],
    "close": [("M6,6 L18,18", "s"), ("M18,6 L6,18", "s")],
    "search": [("M17,11 A6,6 0 1 1 5,11 A6,6 0 1 1 17,11", "s"), ("M15.4,15.4 L21,21", "s")],
    "scan": [("M4,9 V4 h5", "s"), ("M20,9 V4 h-5", "s"), ("M4,15 v5 h5", "s"), ("M20,15 v5 h-5", "s"), ("M3,12 H21", "s")],
    "more": [("M6,12 m-1.6,0 a1.6,1.6 0 1,1 3.2,0 a1.6,1.6 0 1,1 -3.2,0", "f"),
             ("M12,12 m-1.6,0 a1.6,1.6 0 1,1 3.2,0 a1.6,1.6 0 1,1 -3.2,0", "f"),
             ("M18,12 m-1.6,0 a1.6,1.6 0 1,1 3.2,0 a1.6,1.6 0 1,1 -3.2,0", "f")],
    "check": [("M5,13 L10,18 L20,7", "s")],
    "plus": [("M12,5 V19", "s"), ("M5,12 H19", "s")],
    "minus": [("M5,12 H19", "s")],
    "edit": [("M4,20 L5,16 L17,4 L20,7 L8,19 Z", "s"), ("M15,6 L18,9", "s")],
    "trash": [("M4,7 H20", "s"), ("M9,7 V4 h6 v3", "s"), ("M6,7 L7,21 h10 L18,7", "s"), ("M10,11 V17", "s"), ("M14,11 V17", "s")],
    "refresh": [("M20,12 A8,8 0 1 1 17.2,5.9", "s"), ("M20,3 V8 H15", "s")],
    "sort": [("M4,7 H14", "s"), ("M4,12 H11", "s"), ("M4,17 H8", "s"), ("M17,6 V18", "s"), ("M14,15 L17,18 L20,15", "s")],

    # ---- 主壳 ----
    "home": [("M3,11 L12,3 L21,11 V21 H14 V15 H10 V21 H3 Z", "s")],
    "tabs": [("M3,7 h11 v11 H3 Z", "s"), ("M9,3 h12 v12", "s")],
    "grid": [("M4,4 h6 v6 H4 Z", "s"), ("M14,4 h6 v6 h-6 Z", "s"),
             ("M4,14 h6 v6 H4 Z", "s"), ("M14,14 h6 v6 h-6 Z", "s")],
    "browser": [("M3,5 h18 v14 H3 Z", "s"), ("M3,9 H21", "s"), ("M6,7 h.01", "s")],

    # ---- 功能 ----
    "download": [("M12,3 V15", "s"), ("M7,10 L12,15 L17,10", "s"), ("M4,20 H20", "s")],
    "bookmark": [("M7,3 h10 v18 l-5,-4 -5,4 Z", "s")],
    "bookmark_add": [("M7,3 h10 v18 l-5,-4 -5,4 Z", "s"), ("M12,7 V13", "s"), ("M9,10 H15", "s")],
    "history": [("M12,3 A9,9 0 1 1 3,12", "s"), ("M3,12 L3,6", "s"), ("M3.2,7 L7,7", "s"), ("M12,7 V12 L16,14", "s")],
    "settings": [("M12,8.5 A3.5,3.5 0 1 1 12,15.5 A3.5,3.5 0 1 1 12,8.5", "s"),
                 ("M12,2 V5", "s"), ("M12,19 V22", "s"), ("M4.2,7 L6.8,8.5", "s"), ("M17.2,15.5 L19.8,17", "s"),
                 ("M4.2,17 L6.8,15.5", "s"), ("M17.2,8.5 L19.8,7", "s")],
    "star": [("M12,3.5 L14.6,9.2 L20.8,10 L16.3,14.2 L17.5,20.3 L12,17.2 L6.5,20.3 L7.7,14.2 L3.2,10 L9.4,9.2 Z", "s")],
    "clock": [("M12,3 A9,9 0 1 1 12,21 A9,9 0 1 1 12,3", "s"), ("M12,7 V12.5 L16,15", "s")],
    "share": [("M18,8 A2.5,2.5 0 1 1 18,3 A2.5,2.5 0 1 1 18,8", "s"),
              ("M6,14.5 A2.5,2.5 0 1 1 6,9.5 A2.5,2.5 0 1 1 6,14.5", "s"),
              ("M18,21 A2.5,2.5 0 1 1 18,16 A2.5,2.5 0 1 1 18,21", "s"),
              ("M8.2,10.8 L15.8,6.7", "s"), ("M8.2,13.2 L15.8,17.3", "s")],
    "copy": [("M9,9 H20 V20 H9 Z", "s"), ("M15,5 H4 V16", "s")],
    "info": [("M12,3 A9,9 0 1 1 12,21 A9,9 0 1 1 12,3", "s"), ("M12,11 V16.5", "s"), ("M12,7.6 V7.7", "s")],
    "shield": [("M12,3 L20,5.6 V11.5 C20,16.4 16,19.4 12,21 C8,19.4 4,16.4 4,11.5 V5.6 Z", "s"),
               ("M8.5,11.8 L11,14.3 L15.5,9.6", "s")],
    "code": [("M9,7 L3,12 L9,17", "s"), ("M15,7 L21,12 L15,17", "s")],
    "cloud": [("M7,18 h10 a4,4 0 0 0 0.4,-8 a6,6 0 0 0 -11.3,2 A3.6,3.6 0 0 0 7,18 Z", "s")],
    "ai": [("M12,3 L13.9,9.1 L20,11 L13.9,12.9 L12,19 L10.1,12.9 L4,11 L10.1,9.1 Z", "s"),
           ("M19,17 L19.8,19.2 L22,20 L19.8,20.8 L19,23 L18.2,20.8 L16,20 L18.2,19.2 Z", "f")],
    "moon": [("M20,14.5 A8.5,8.5 0 1 1 9.5,4 A6.8,6.8 0 0 0 20,14.5 Z", "s")],
    "eye": [("M2,12 C5,6.5 8.8,4.5 12,4.5 C15.2,4.5 19,6.5 22,12 C19,17.5 15.2,19.5 12,19.5 C8.8,19.5 5,17.5 2,12 Z", "s"),
            ("M12,9 A3,3 0 1 1 12,15 A3,3 0 1 1 12,9", "s")],
    "globe": [("M12,3 A9,9 0 1 1 12,21 A9,9 0 1 1 12,3", "s"), ("M3,12 H21", "s"),
              ("M12,3 C15.5,6.2 15.5,17.8 12,21 C8.5,17.8 8.5,6.2 12,3 Z", "s")],
    "lock": [("M7.5,10.5 V8 A4.5,4.5 0 0 1 16.5,8 V10.5", "s"), ("M5.8,10.5 H18.2 V20.5 H5.8 Z", "s")],
    "unlock": [("M8,10.5 V8 A4,4 0 0 1 16,8", "s"), ("M5.8,10.5 H18.2 V20.5 H5.8 Z", "s")],
    "play": [("M8,5 L19,12 L8,19 Z", "f")],
    "pause": [("M7,5 H10.5 V19 H7 Z", "f"), ("M13.5,5 H17 V19 H13.5 Z", "f")],
    "skip_next": [("M6,5 L15,12 L6,19 Z", "f"), ("M17.5,5 H20 V19 H17.5 Z", "f")],
    "speed": [("M4,17.5 A9,9 0 1 1 20,17.5", "s"), ("M12,17 L16,11", "s"), ("M12,17 m-1,0 a1,1 0 1,1 2,0 a1,1 0 1,1 -2,0", "f")],
    "fullscreen": [("M4,9 V4 H9", "s"), ("M20,9 V4 H15", "s"), ("M4,15 V20 H9", "s"), ("M20,15 V20 H15", "s")],
    "pip": [("M3,5 H21 V19 H3 Z", "s"), ("M12.5,11.5 H18 V16.5 H12.5 Z", "s")],
    "list": [("M8,6 H20", "s"), ("M8,12 H20", "s"), ("M8,18 H20", "s"), ("M4,6 h.01", "s"), ("M4,12 h.01", "s"), ("M4,18 h.01", "s")],
    "qr": [("M3,3 H9 V9 H3 Z", "s"), ("M15,3 H21 V9 H15 Z", "s"), ("M3,15 H9 V21 H3 Z", "s"),
           ("M15,15 h2 v2 h-2 Z", "s"), ("M19,15 h2 v2 h-2 Z", "s"), ("M15,19 h2 v2 h-2 Z", "s"), ("M19,19 h2 v2 h-2 Z", "s")],
    "file": [("M6,3 H15 L19,7 V21 H6 Z", "s"), ("M15,3 V7 H19", "s")],
    "folder": [("M3,6 h6 l2,2.5 h10 V20 H3 Z", "s")],
    "incognito": [("M5,10 H19", "s"), ("M8,10 L10,4 H14 L16,10", "s"),
                  ("M9,14 A1.6,1.6 0 1 1 9,17.2 A1.6,1.6 0 1 1 9,14", "s"),
                  ("M15,14 A1.6,1.6 0 1 1 15,17.2 A1.6,1.6 0 1 1 15,14", "s")],
    "reader": [("M4,5 H11 V19 H4 Z", "s"), ("M11,5 H20 V19 H11 Z", "s"), ("M6,9 H9", "s"), ("M6,13 H9", "s")],
    "pc": [("M3,5 H21 V16 H3 Z", "s"), ("M9,20 H15", "s"), ("M12,16 V20", "s")],
    "phone": [("M7,3 H17 V21 H7 Z", "s"), ("M10.5,18 H13.5", "s")],
    "user": [("M12,4 A4,4 0 1 1 12,12 A4,4 0 1 1 12,4", "s"), ("M4,21 C4.8,16.8 8,15 12,15 C16,15 19.2,16.8 20,21", "s")],
    "translate": [("M4,6 H12", "s"), ("M8,4 V6", "s"), ("M10.5,6 C10,10 7.5,13 4,14.5", "s"),
                  ("M6,9 C7.2,11.6 9.4,13.6 12,14.6", "s"), ("M13,20 L16.5,11 L20,20", "s"), ("M14.3,17.2 H18.7", "s")],
    "cloud_up": [("M7,17 h10 a3.6,3.6 0 0 0 0.3,-7.2 a5.6,5.6 0 0 0 -10.6,1.8 A3.2,3.2 0 0 0 7,17 Z", "s"),
                 ("M12,20 V12", "s"), ("M9.5,14.5 L12,12 L14.5,14.5", "s")],
    "key": [("M14,10 A4,4 0 1 0 6,10 A4,4 0 1 0 14,10", "s"), ("M13.2,12.8 L20,19.6", "s"), ("M17,16.5 L18.8,14.7", "s")],
    "bell": [("M6,17 V11 A6,6 0 0 1 18,11 V17 L20,19 H4 Z", "s"), ("M10,22 h4", "s")],
    "language": [("M12,3 A9,9 0 1 1 12,21 A9,9 0 1 1 12,3", "s"), ("M3.5,9 H20.5", "s"), ("M3.5,15 H20.5", "s")],
}

STROKE_COLOR = "#FF212121"


def path_xml(d, style):
    if style == "s":
        return ('        <path android:pathData="%s" android:strokeColor="%s" '
                'android:strokeWidth="1.8" android:strokeLineCap="round" '
                'android:strokeLineJoin="round" android:fillColor="#00000000" />' % (d, STROKE_COLOR))
    return '        <path android:pathData="%s" android:fillColor="%s" />' % (d, STROKE_COLOR)


def vector(name, parts):
    body = "\n".join(path_xml(d, s) for d, s in parts)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp" android:height="24dp"\n'
            '    android:viewportWidth="24" android:viewportHeight="24">\n'
            '%s\n</vector>\n' % body)


def write(dirpath, name, parts):
    os.makedirs(dirpath, exist_ok=True)
    with open(os.path.join(dirpath, name + ".xml"), "w", encoding="utf-8") as f:
        f.write(vector(name, parts))


def main():
    for name, parts in ICONS.items():
        write(APP, "ic_" + name, parts)
        write(CORE, "uikit_ic_" + name, parts)
    print("generated %d icons -> %s / %s" % (len(ICONS), APP, CORE))


if __name__ == "__main__":
    main()

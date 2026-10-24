#!/usr/bin/env python3
"""把中文韵律模型打成手机可下载、可导入的 zip。

正式应用的 release 里和安装包、角色包放在一起，文件名固定是 prosody.zip。
根目录只有：

    manifest.json
    model_fp16.onnx
"""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path

FORMAT = 1
FILE_NAME = "model_fp16.onnx"
MODEL_BYTES = 599_034_176
PACK_ID = "prosody"
PACK_NAME = "韵律模型"


def main() -> None:
    parser = argparse.ArgumentParser(description="打包或检查安卓韵律包")
    sub = parser.add_subparsers(dest="command", required=True)

    pack = sub.add_parser("pack", help="把 model_fp16.onnx 打成 prosody.zip")
    pack.add_argument("--model", type=Path, required=True)
    pack.add_argument("--output", type=Path, required=True)

    check = sub.add_parser("check", help="检查 prosody.zip")
    check.add_argument("zip", type=Path)

    args = parser.parse_args()
    if args.command == "pack":
        pack_model(args.model, args.output)
    else:
        check_zip(args.zip)


def pack_model(model: Path, output: Path) -> None:
    if model.stat().st_size != MODEL_BYTES:
        raise SystemExit(f"{model} 大小是 {model.stat().st_size}，应为 {MODEL_BYTES}")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "format": FORMAT,
        "id": PACK_ID,
        "name": PACK_NAME,
        "file": FILE_NAME,
        "size": MODEL_BYTES,
    }
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        archive.writestr("manifest.json", payload.encode("utf-8"))
        archive.write(model, FILE_NAME)
    check_zip(output)


def check_zip(path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if names != {"manifest.json", FILE_NAME}:
            raise SystemExit(f"韵律包里的文件不对：{sorted(names)}")
        manifest = json.loads(archive.read("manifest.json"))
        info = archive.getinfo(FILE_NAME)
    expected = {
        "format": FORMAT,
        "id": PACK_ID,
        "name": PACK_NAME,
        "file": FILE_NAME,
        "size": MODEL_BYTES,
    }
    if manifest != expected:
        raise SystemExit(f"manifest 不对：{manifest}")
    if info.file_size != MODEL_BYTES:
        raise SystemExit(f"{FILE_NAME} 大小是 {info.file_size}，应为 {MODEL_BYTES}")
    print(f"ok {path} {path.stat().st_size} bytes")


if __name__ == "__main__":
    main()

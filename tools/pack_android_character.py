#!/usr/bin/env python3
"""把一个角色打成手机可导入的 zip。

安装包里不带角色。每个 zip 的根目录是：

    manifest.json
    models/t2s_encoder_fp32.onnx
    models/t2s_first_stage_decoder_fp32.onnx
    models/t2s_stage_decoder_fp32.onnx
    models/vits_fp32.onnx
    tensors/ref_seq.bin
    tensors/ref_bert.bin
    tensors/ssl_content.bin
    tensors/ge.bin
    tensors/ge_advanced.bin

四个 ONNX 必须是灌好权重的自包含文件。
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import zipfile
from pathlib import Path

FORMAT = 1
MODEL_NAMES = (
    "t2s_encoder_fp32.onnx",
    "t2s_first_stage_decoder_fp32.onnx",
    "t2s_stage_decoder_fp32.onnx",
    "vits_fp32.onnx",
)
REQUIRED_TENSORS = ("ref_seq", "ref_bert", "ssl_content", "ge", "ge_advanced")
DTYPE_WIDTH = {"float32": 4, "int64": 8}
DISPLAY_NAMES = {"feibi": "菲比", "sanyueqi": "三月七"}


def main() -> None:
    parser = argparse.ArgumentParser(description="打包或检查安卓角色包")
    sub = parser.add_subparsers(dest="command", required=True)

    pack = sub.add_parser("pack", help="把已经导出的 android_case 目录打成 zip")
    pack.add_argument("--source", type=Path, required=True)
    pack.add_argument("--id", required=True)
    pack.add_argument("--output", type=Path, required=True)
    pack.add_argument("--name", default="")

    bake = sub.add_parser("bake", help="把带外部权重的 V2ProPlus 目录灌成角色包")
    bake.add_argument("--model-dir", type=Path, required=True)
    bake.add_argument("--wav", type=Path, required=True)
    bake.add_argument("--ref-text", required=True)
    bake.add_argument("--ref-ids", type=Path, required=True, help="空格分隔的音素 id，与手机中文前端一致")
    bake.add_argument("--id", required=True)
    bake.add_argument("--output", type=Path, required=True)
    bake.add_argument("--name", default="")
    bake.add_argument("--work", type=Path, required=True)

    check = sub.add_parser("check", help="检查 zip，并尝试加载四个 ONNX")
    check.add_argument("zip", type=Path)
    check.add_argument("--run-encoder", action="store_true")

    args = parser.parse_args()
    if args.command == "pack":
        pack_case(args.source, args.output, args.id, args.name)
    elif args.command == "bake":
        bake_character(args)
    else:
        check_zip(args.zip, args.run_encoder)


def pack_case(source: Path, output: Path, character_id: str, display_name: str) -> None:
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    work = output.parent / f".work-{character_id}"
    if work.exists():
        shutil.rmtree(work)
    models = work / "models"
    models.mkdir(parents=True)
    assemble_models(source / "models", models)
    tensors = work / "tensors"
    shutil.copytree(source / "tensors", tensors)
    write_manifest(work, manifest, character_id, display_name)
    write_zip(work, output)
    shutil.rmtree(work)
    print(f"wrote {output} ({output.stat().st_size} bytes)")


def assemble_models(source: Path, dest: Path) -> None:
    parts_path = source / "parts.json"
    if parts_path.is_file():
        parts = json.loads(parts_path.read_text(encoding="utf-8"))
    else:
        parts = {}
    for name in MODEL_NAMES:
        target = dest / name
        spec = parts.get(name)
        ready = source / name
        if spec is None:
            if not ready.is_file():
                raise SystemExit(f"缺少 {name}")
            shutil.copy2(ready, target)
            continue
        with target.open("wb") as out:
            for relative in spec["parts"]:
                piece = source / relative
                if not piece.is_file():
                    raise SystemExit(f"缺少分片 {relative}")
                with piece.open("rb") as incoming:
                    shutil.copyfileobj(incoming, out, 1024 * 1024)
        if target.stat().st_size != spec["size"]:
            raise SystemExit(f"{name} 大小是 {target.stat().st_size}，应为 {spec['size']}")


def write_manifest(root: Path, manifest: dict, character_id: str, display_name: str) -> None:
    character_id = character_id.strip().lower()
    manifest = dict(manifest)
    manifest["format"] = FORMAT
    manifest["id"] = character_id
    manifest["name"] = display_name or manifest.get("name") or DISPLAY_NAMES.get(character_id, character_id)
    manifest["model_sizes"] = {
        name: (root / "models" / name).stat().st_size for name in MODEL_NAMES
    }
    (root / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def write_zip(root: Path, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
        for path in sorted(root.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(root).as_posix())


def bake_character(args: argparse.Namespace) -> None:
    import numpy as np
    import onnx
    import soundfile as sf
    import soxr
    from onnxruntime import InferenceSession

    work = args.work
    if work.exists():
        shutil.rmtree(work)
    models = work / "models"
    tensors = work / "tensors"
    models.mkdir(parents=True)
    tensors.mkdir()

    model_dir = args.model_dir
    embed_float_bin(
        onnx,
        model_dir / "t2s_encoder_fp32.onnx",
        model_dir / "t2s_encoder_fp32.bin",
        models / "t2s_encoder_fp32.onnx",
        expand_fp16=False,
    )
    shared = np.fromfile(model_dir / "t2s_shared_fp16.bin", dtype=np.float16).astype(np.float32).tobytes()
    embed_bytes(onnx, model_dir / "t2s_first_stage_decoder_fp32.onnx", shared, models / "t2s_first_stage_decoder_fp32.onnx")
    embed_bytes(onnx, model_dir / "t2s_stage_decoder_fp32.onnx", shared, models / "t2s_stage_decoder_fp32.onnx")
    del shared
    vits = np.fromfile(model_dir / "vits_fp16.bin", dtype=np.float16).astype(np.float32).tobytes()
    embed_bytes(onnx, model_dir / "vits_fp32.onnx", vits, models / "vits_fp32.onnx")
    del vits

    ids = [int(part) for part in args.ref_ids.read_text(encoding="utf-8").split()]
    if not ids:
        raise SystemExit("参考音素是空的")
    ref_seq = np.asarray([ids], dtype=np.int64)
    ref_bert = np.zeros((len(ids), 1024), dtype=np.float32)
    audio_32k, audio_16k = load_prompt(sf, soxr, np, args.wav)
    ssl_content, ge, ge_advanced = reference_features(
        np, onnx, InferenceSession, model_dir, audio_32k, audio_16k
    )
    write_array(tensors / "ref_seq.bin", ref_seq)
    write_array(tensors / "ref_bert.bin", ref_bert)
    write_array(tensors / "ssl_content.bin", ssl_content)
    write_array(tensors / "ge.bin", ge)
    write_array(tensors / "ge_advanced.bin", ge_advanced)
    manifest = {
        "format": FORMAT,
        "id": args.id.strip().lower(),
        "name": args.name or DISPLAY_NAMES.get(args.id.strip().lower(), args.id),
        "language": "Chinese",
        "sample_rate": 32000,
        "model_type": "v2proplus",
        "ref_text": args.ref_text,
        "roberta": False,
        "tensors": {
            "ref_seq": tensor_spec("tensors/ref_seq.bin", "int64", ref_seq.shape),
            "ref_bert": tensor_spec("tensors/ref_bert.bin", "float32", ref_bert.shape),
            "ssl_content": tensor_spec("tensors/ssl_content.bin", "float32", ssl_content.shape),
            "ge": tensor_spec("tensors/ge.bin", "float32", ge.shape),
            "ge_advanced": tensor_spec("tensors/ge_advanced.bin", "float32", ge_advanced.shape),
        },
        "models": list(MODEL_NAMES),
    }
    write_manifest(work, manifest, args.id, args.name)
    write_zip(work, args.output)
    shutil.rmtree(work)
    print(f"wrote {args.output} ({args.output.stat().st_size} bytes)")


def embed_float_bin(onnx, onnx_path: Path, bin_path: Path, dest: Path, expand_fp16: bool) -> None:
    import numpy as np

    if expand_fp16:
        payload = np.fromfile(bin_path, dtype=np.float16).astype(np.float32).tobytes()
    else:
        payload = bin_path.read_bytes()
    embed_bytes(onnx, onnx_path, payload, dest)


def embed_bytes(onnx, onnx_path: Path, payload: bytes, dest: Path) -> None:
    model = onnx.load(onnx_path, load_external_data=False)
    patched = 0
    for tensor in model.graph.initializer:
        if tensor.data_location != onnx.TensorProto.EXTERNAL:
            continue
        offset, length = 0, 0
        for entry in tensor.external_data:
            if entry.key == "offset":
                offset = int(entry.value)
            elif entry.key == "length":
                length = int(entry.value)
        if length <= 0:
            length = len(payload) - offset
        chunk = payload[offset:offset + length]
        if len(chunk) != length:
            raise SystemExit(f"{onnx_path.name} 的 {tensor.name} 超出权重文件")
        tensor.raw_data = chunk
        del tensor.external_data[:]
        tensor.data_location = onnx.TensorProto.DEFAULT
        patched += 1
    if patched == 0:
        raise SystemExit(f"{onnx_path.name} 没有外部权重")
    dest.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, dest)
    print(f"embedded {patched} tensors into {dest.name} ({dest.stat().st_size} bytes)")


def load_prompt(sf, soxr, np, wav_path: Path):
    wav, sample_rate = sf.read(wav_path, dtype="float32")
    if wav.ndim > 1:
        wav = np.mean(wav, axis=1)
    if sample_rate != 32000:
        wav = soxr.resample(wav, sample_rate, 32000, quality="hq")
    wav = np.concatenate([wav, np.zeros(int(0.3 * 32000), dtype=np.float32)])
    audio_16k = soxr.resample(wav, 32000, 16000, quality="hq")
    return wav.reshape(1, -1), audio_16k.reshape(1, -1)


def reference_features(np, onnx, inference_session, model_dir: Path, audio_32k, audio_16k):
    data_dir = ensure_genie_data()
    hubert_onnx = data_dir / "chinese-hubert-base" / "chinese-hubert-base.onnx"
    hubert_bin = data_dir / "chinese-hubert-base" / "chinese-hubert-base_weights_fp16.bin"
    speaker = data_dir / "speaker_encoder.onnx"
    hubert_path = bake_temp(onnx, hubert_onnx, hubert_bin, data_dir / "chinese-hubert-base" / "baked.onnx")
    hubert = inference_session(str(hubert_path), providers=["CPUExecutionProvider"])
    ssl_content = hubert.run(None, {"input_values": audio_16k})[0].astype(np.float32)
    del hubert
    sv = inference_session(str(speaker), providers=["CPUExecutionProvider"])
    sv_emb = sv.run(None, {"waveform": audio_16k})[0]
    del sv
    prompt_bin = np.fromfile(model_dir / "prompt_encoder_fp16.bin", dtype=np.float16).astype(np.float32).tobytes()
    prompt_path = model_dir / ".prompt_encoder_baked.onnx"
    embed_bytes(onnx, model_dir / "prompt_encoder_fp32.onnx", prompt_bin, prompt_path)
    prompt = inference_session(str(prompt_path), providers=["CPUExecutionProvider"])
    ge, ge_advanced = prompt.run(None, {"ref_audio": audio_32k, "sv_emb": sv_emb})
    del prompt
    prompt_path.unlink(missing_ok=True)
    return ssl_content.astype(np.float32), ge.astype(np.float32), ge_advanced.astype(np.float32)


def bake_temp(onnx, onnx_path: Path, bin_path: Path, dest: Path) -> Path:
    if dest.is_file() and dest.stat().st_size > 1024:
        return dest
    if bin_path.is_file():
        embed_float_bin(onnx, onnx_path, bin_path, dest, expand_fp16=True)
    else:
        shutil.copy2(onnx_path, dest)
    return dest


def ensure_genie_data() -> Path:
    from huggingface_hub import hf_hub_download

    root = Path.cwd() / "GenieData"
    root.mkdir(exist_ok=True)
    files = (
        "GenieData/speaker_encoder.onnx",
        "GenieData/chinese-hubert-base/chinese-hubert-base.onnx",
        "GenieData/chinese-hubert-base/chinese-hubert-base_weights_fp16.bin",
    )
    for filename in files:
        local = hf_hub_download(
            repo_id="High-Logic/Genie",
            filename=filename,
            local_dir=".",
        )
        print(f"resource {local}")
    return root


def write_array(path: Path, array) -> None:
    path.write_bytes(np_tobytes(array))


def np_tobytes(array) -> bytes:
    return array.astype(array.dtype, copy=False).tobytes()


def tensor_spec(file: str, dtype: str, shape) -> dict:
    return {"file": file, "dtype": dtype, "shape": [int(dim) for dim in shape]}


def check_zip(path: Path, run_encoder: bool) -> None:
    import numpy as np
    import onnxruntime

    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        if "manifest.json" not in names:
            raise SystemExit("缺少 manifest.json")
        manifest = json.loads(archive.read("manifest.json"))
    if manifest.get("format") != FORMAT:
        raise SystemExit("format 不是 1")
    character_id = manifest.get("id", "")
    if not character_id:
        raise SystemExit("缺少 id")
    if not manifest.get("ref_text"):
        raise SystemExit("缺少 ref_text")
    for name in MODEL_NAMES:
        if f"models/{name}" not in names:
            raise SystemExit(f"缺少 {name}")
    tensors = manifest["tensors"]
    for name in REQUIRED_TENSORS:
        spec = tensors[name]
        if spec["file"] not in names:
            raise SystemExit(f"缺少 {spec['file']}")
    work = path.parent / f".check-{path.stem}"
    if work.exists():
        shutil.rmtree(work)
    with zipfile.ZipFile(path) as archive:
        archive.extractall(work)
    for name in MODEL_NAMES:
        model = work / "models" / name
        expected = manifest.get("model_sizes", {}).get(name)
        if expected is not None and model.stat().st_size != expected:
            raise SystemExit(f"{name} 大小不符")
        session = onnxruntime.InferenceSession(str(model), providers=["CPUExecutionProvider"])
        print(f"loaded {name} inputs={[item.name for item in session.get_inputs()]}")
        del session
    for name in REQUIRED_TENSORS:
        spec = tensors[name]
        blob = (work / spec["file"]).read_bytes()
        width = DTYPE_WIDTH[spec["dtype"]]
        count = 1
        for dim in spec["shape"]:
            count *= dim
        if len(blob) != count * width:
            raise SystemExit(f"{name} 字节数不符")
    if run_encoder:
        session = onnxruntime.InferenceSession(
            str(work / "models" / "t2s_encoder_fp32.onnx"),
            providers=["CPUExecutionProvider"],
        )
        feeds = {}
        for item in session.get_inputs():
            spec = tensors[item.name] if item.name in tensors else None
            if item.name == "text_seq":
                spec = tensors["ref_seq"]
            elif item.name == "text_bert":
                spec = tensors["ref_bert"]
            if spec is None:
                raise SystemExit(f"编码器需要 {item.name}，包里没有")
            array = np.frombuffer((work / spec["file"]).read_bytes(), dtype=spec["dtype"])
            feeds[item.name] = array.reshape(spec["shape"])
        outputs = session.run(None, feeds)
        print(f"encoder ran, outputs={[tuple(out.shape) for out in outputs]}")
    shutil.rmtree(work)
    print(f"ok {character_id} {path}")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.exit(0)

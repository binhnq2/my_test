#!/usr/bin/env python3
"""Assembles a `voice-models/vi-VN/<version>/` pack from an extracted Vosk model.

Run with `python -I` (isolated mode: the interpreter loads nothing from the
model directory, which is untrusted data copied off the internet).

    python -I tools/voicepack/build_voice_pack.py \
        --model  .work/voskmodel/x/vosk-model-small-vn-0.4 \
        --out    .work/voicepack/vi-VN/vosk-model-small-vn-0.4 \
        --grammar tools/voicepack/command_grammar.json

The output is the layout `VoiceModelStore` expects:

    metadata.json
    model/...                the Kaldi directory, copied byte for byte
    command_grammar.json
    checksums.sha256         sha256sum format: "<hex>  <path>", sorted
    LICENSES.md

`metadata.json`'s `size` is the pack *payload*: every regular file except
`metadata.json` and `checksums.sha256`, which cannot be part of the number they
describe. That matches `File.payloadBytes()` in `VoiceModelValidator.kt`.

The script writes nothing outside `--out`, copies the model without
transforming it, and refuses to overwrite a non-empty directory: a pack built
over a half-written one would checksum the mixture.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

SCHEMA_VERSION = 1
ENGINE = "vosk"
LOCALE = "vi-VN"
MIN_SDK = 23
ABIS = ["armeabi-v7a", "arm64-v8a"]
VOCABULARY = "GRAMMAR"
LICENSE = "Apache-2.0"
LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0"
SOURCE_URL = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"

# Descriptor files, excluded from the declared payload size.
DESCRIPTORS = {"metadata.json", "checksums.sha256"}

# Files that must exist before a pack is worth building at all; the same three
# `VoiceModelValidator` refuses a pack for.
REQUIRED = ["am/final.mdl", "graph/HCLr.fst", "graph/Gr.fst"]


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def payload_bytes(root: Path) -> int:
    return sum(
        entry.stat().st_size
        for entry in root.rglob("*")
        if entry.is_file() and entry.name not in DESCRIPTORS
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--grammar", required=True, type=Path)
    parser.add_argument(
        "--version",
        default=None,
        help="modelVersion; defaults to the model directory's own name",
    )
    args = parser.parse_args()

    model_dir: Path = args.model.resolve()
    out_dir: Path = args.out.resolve()
    grammar_file: Path = args.grammar.resolve()
    version = args.version or model_dir.name

    if not model_dir.is_dir():
        print(f"FAIL: model directory not found: {model_dir}", file=sys.stderr)
        return 1
    for relative in REQUIRED:
        if not (model_dir / relative).is_file():
            print(f"FAIL: model is missing {relative}", file=sys.stderr)
            return 1
    if not grammar_file.is_file():
        print(f"FAIL: grammar not found: {grammar_file}", file=sys.stderr)
        return 1

    # The grammar has to be the array Vosk takes, and non-empty: an empty one
    # makes every command decode to nothing while the model reports healthy.
    phrases = json.loads(grammar_file.read_text(encoding="utf-8"))
    if not isinstance(phrases, list) or not phrases:
        print("FAIL: command_grammar.json must be a non-empty array", file=sys.stderr)
        return 1
    if any(not isinstance(item, str) or not item.strip() for item in phrases):
        print("FAIL: command_grammar.json contains a non-string entry", file=sys.stderr)
        return 1

    if out_dir.exists() and any(out_dir.iterdir()):
        print(f"FAIL: refusing to overwrite non-empty {out_dir}", file=sys.stderr)
        return 1

    out_dir.mkdir(parents=True, exist_ok=True)
    model_out = out_dir / "model"
    shutil.copytree(model_dir, model_out)

    # The grammar Vosk will actually be handed at run time is built from the
    # command table (`CommandGrammar.build`); this file is the pack's own
    # declaration of what it may recognise, checked for shape and non-emptiness
    # by the validator.
    shutil.copyfile(grammar_file, out_dir / "command_grammar.json")

    licenses = out_dir / "LICENSES.md"
    licenses.write_text(LICENSES_TEXT, encoding="utf-8")

    # Metadata is written before the checksums so the payload size can be
    # measured with it in place; it is excluded from that measure anyway, but
    # building the sum from the finished directory is what makes the number
    # checkable afterwards with `du`-style tools.
    metadata = {
        "schemaVersion": SCHEMA_VERSION,
        "engine": ENGINE,
        "locale": LOCALE,
        "modelVersion": version,
        "minSdk": MIN_SDK,
        "supportedAbis": ABIS,
        "size": payload_bytes(out_dir),
        "sha256": "",
        "license": LICENSE,
        "licenseUrl": LICENSE_URL,
        "sourceUrl": SOURCE_URL,
        "vocabularyMode": VOCABULARY,
        "description": "Mô hình tiếng Việt nhỏ cho Vosk (Kaldi), chạy ngoại tuyến.",
    }
    metadata_file = out_dir / "metadata.json"
    metadata_file.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # checksums.sha256 covers every payload file, in a stable order, in the
    # `sha256sum` format the validator parses and a human can re-check.
    lines = []
    for entry in sorted(
        (p for p in out_dir.rglob("*") if p.is_file() and p.name not in DESCRIPTORS),
        key=lambda p: p.relative_to(out_dir).as_posix(),
    ):
        relative = entry.relative_to(out_dir).as_posix()
        lines.append(f"{sha256_of(entry)}  {relative}")
    (out_dir / "checksums.sha256").write_text("\n".join(lines) + "\n", encoding="utf-8")

    # The manifest's own sha256 is the digest of the payload as a whole: the
    # checksum file, byte for byte. That makes "does this directory still match
    # the metadata it ships" answerable with one `sha256sum -c` and one digest.
    metadata["sha256"] = sha256_of(out_dir / "checksums.sha256")
    metadata_file.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    files = [p for p in out_dir.rglob("*") if p.is_file()]
    print(f"pack      {out_dir}")
    print(f"version   {version}")
    print(f"files     {len(files)}")
    print(f"payload   {metadata['size']} bytes")
    print(f"total     {sum(p.stat().st_size for p in files)} bytes")
    print(f"sha256    {metadata['sha256']}")
    print(f"phrases   {len(phrases)}")
    return 0


LICENSES_TEXT = """# Giấy phép — gói mô hình giọng nói tiếng Việt

## Mô hình: vosk-model-small-vn-0.4

| Trường | Giá trị |
|---|---|
| Tên | `vosk-model-small-vn-0.4` |
| Nguồn tải | <https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip> |
| Trang danh mục | <https://alphacephei.com/vosk/models> |
| Kích thước tệp ZIP | 33.656.337 byte |
| SHA-256 tệp ZIP | `efe5c8494212110471a79befc48c79da679e5b1fc52a4ffb500222ff86d622e5` |
| Giấy phép | Apache License 2.0 |
| Văn bản giấy phép | <https://www.apache.org/licenses/LICENSE-2.0> |

### Căn cứ

Giấy phép được công bố ở **cột license của dòng "Vietnamese"** trên trang danh
mục mô hình Vosk (<https://alphacephei.com/vosk/models>), nơi dòng này ghi
`32M | 15.70 (Vivos test) | Lightweight Vietnamese model | Apache 2.0`.

**Gói ZIP không kèm tệp giấy phép nào.** Đây là lý do tệp `LICENSES.md` này tồn
tại trong pack: nó ghi lại nguồn của tuyên bố giấy phép để người kiểm tra không
phải suy đoán, và nó phải được giữ lại khi pack được chép sang thiết bị.

### Phân phối lại

Apache License 2.0 cho phép phân phối lại, kể cả trong sản phẩm thương mại, với
điều kiện giữ thông báo bản quyền và giấy phép, và nêu rõ các thay đổi nếu có.
Gói này **không sửa đổi** nội dung mô hình: các tệp dưới `model/` được sao chép
nguyên byte từ ZIP gốc, và `checksums.sha256` chứng minh điều đó.

Gói **có thêm** ba tệp không thuộc mô hình gốc — `metadata.json`,
`command_grammar.json` và `LICENSES.md` — do dự án launcher này tạo ra, không
phải của Alphacephei.

### Không phải lời tuyên bố về độ chính xác

WER 15,70 trong README của mô hình (`exp/chain/tdnn/decode_test`) là số đo của
nhà cung cấp trên bộ test VIVOS, không phải số đo trong cabin xe. Không có kết
quả đo nào trên TS8 trong tài liệu này.

## Thành phần phần mềm: Vosk Android 0.3.47

`com.alphacephei:vosk-android:0.3.47`, được đóng trong APK (không phải trong
pack này). Apache License 2.0. Bao gồm thư viện JNA 5.13.0 (Apache License 2.0 /
LGPL 2.1 dual).
"""


if __name__ == "__main__":
    raise SystemExit(main())

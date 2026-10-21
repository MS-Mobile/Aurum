#!/usr/bin/env python3
"""Generates the reference images used by the instrumented port test.

Runs the unmodified corrigir_foto.py with the iPhone preset and grain disabled
(--grao 0) so the comparison is deterministic. Everything is written as PNG so
JPEG compression does not eat into the PSNR budget, and the decoded input is
saved too, so the Kotlin test processes exactly the same pixels (no JPEG
decoder or EXIF orientation differences).

Usage (from the project root):
    python tools/generate_references.py
"""
import subprocess
import sys
from pathlib import Path

import cv2

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools/corrigir_foto.py"
PHOTO = ROOT / "tools/foto_teste.jpg"
OUT_DIR = ROOT / "app/src/androidTest/assets/reference"


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"OpenCV {cv2.__version__}")

    original = cv2.imread(str(PHOTO))
    if original is None:
        raise SystemExit(f"Could not open {PHOTO}")
    input_png = OUT_DIR / "input.png"
    cv2.imwrite(str(input_png), original)

    output_png = OUT_DIR / "expected_iphone_no_grain.png"
    subprocess.run(
        [sys.executable, str(SCRIPT), str(input_png), str(output_png),
         "--iphone", "--grao", "0", "--sem-comparacao"],
        check=True,
    )
    print(f"Input:    {input_png} {original.shape[1]}x{original.shape[0]}")
    print(f"Expected: {output_png}")


if __name__ == "__main__":
    main()

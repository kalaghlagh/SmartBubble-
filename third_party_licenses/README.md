# Third-party notices — Smart Bubble

Components of other projects distributed in the Smart Bubble app, their licenses and the files
that carry their license texts. Model-weight licenses are listed separately from code licenses.

## Runtime libraries

| Component | Version | License | Use | License text |
|---|---|---|---|---|
| ONNX Runtime for Android (`com.microsoft.onnxruntime:onnxruntime-android`) | 1.27.0 | MIT | Runs the OCR models on the CPU (native `libonnxruntime.so`, Java bindings) | `onnxruntime_LICENSE.txt` |

## Model weights (assets/paddleocr/v5)

| File | Bytes | SHA-256 | Origin | License |
|---|---|---|---|---|
| `det.ort` | 4 898 208 | `bfb226a460dee7e50b210e20e7c51becff55798150aea45cb9d047c81bfb9c9a` | PaddleOCR PP-OCRv5_mobile_det | Apache-2.0 |
| `arabic_PP-OCRv5_mobile_rec.ort` | 8 106 064 | `17d31ec78b3dd2168c97595031fdf7adeba145c4cfa5f33278f04e8363fdea9d` | PaddleOCR arabic_PP-OCRv5_mobile_rec | Apache-2.0 |
| `arabic_PP-OCRv5_mobile_rec_dict.txt` | 2 371 | `2a215ea5877f01b1f8c8803783cda73707222c39a84d4a6cfee9ef502c48248e` | PaddleOCR (inference.yml character list) | Apache-2.0 |
| `latin_PP-OCRv5_mobile_rec.ort` | 8 149 136 | `5bb93e0fef6fcde14ddadfec23ff9efbc331531ba1ae54baba85605d7794efda` | PaddleOCR latin_PP-OCRv5_mobile_rec | Apache-2.0 |
| `latin_PP-OCRv5_mobile_rec_dict.txt` | 2 618 | `b95923300a0656f8169feee90143cbfcdb62d82a37b54e6b12c224c3e584916f` | PaddleOCR (inference.yml character list) | Apache-2.0 |

- The weights are © the PaddleOCR / PaddlePaddle authors (https://github.com/PaddlePaddle/PaddleOCR,
  Apache License 2.0, `paddleocr/LICENSE-Apache-2.0.txt`).
- The ORT-format files were produced and published by MakeACopy
  (https://github.com/egdels/makeacopy, commit 13b4423, `app/src/main/assets/paddleocr/v5/`):
  PaddleX download → Paddle2ONNX (opset 11) → ONNX Runtime ORT conversion (basic optimization).
  The dictionary checksums equal MakeACopy's upstream pins; the `.ort` checksums were computed on
  the files taken from that commit. Conversion does not change the Apache-2.0 license
  (MakeACopy `third_party_licenses/paddleocr/README.md`).
- The individual model cards on Hugging Face could not be read from the build environment; the
  license statement relies on the PaddleOCR repository license and MakeACopy's provenance record.
- Not shipped: SLANet_plus, PP-DocLayout-S, UVDoc, PP-OCRv6 or any other model.
- "PaddleOCR", "PaddlePaddle" and "ONNX Runtime" are trademarks of their owners; no trademark
  license is implied. Smart Bubble is not affiliated with these projects.

## Source code adapted from other projects

| Smart Bubble file | Adapted from | License | Notices |
|---|---|---|---|
| `app/src/main/java/com/example/ocr/paddle/ScriptMerge.kt` | Scanly `core/ocr/paddle/ScriptMerge.kt` (https://github.com/Azyrn/Scanly, commit 8da3cf5) | Apache-2.0 | `scanly_LICENSE.txt`, `scanly_NOTICE.txt` |
| `app/src/main/java/com/example/ocr/paddle/RtlText.kt` | Scanly `core/ocr/paddle/RtlText.kt` | Apache-2.0 | `scanly_LICENSE.txt`, `scanly_NOTICE.txt` |
| `app/src/main/java/com/example/ocr/paddle/DbPostProcess.kt` | MakeACopy `utils/ocr/paddle/DbPostProcessor.java` (axis-aligned path) | Apache-2.0 | `makeacopy_LICENSE.txt`, `makeacopy_NOTICE.txt` |
| `app/src/main/java/com/example/ocr/paddle/PaddleOrtOcrEngine.kt`, `RecLine.kt` (CTC decoding) | pre/post-processing constants of MakeACopy `PaddleDetOrtRunner.java`, `PaddleRecOrtRunner.java`, `CtcDecoder.java`, `RecDictLoader.java` | Apache-2.0 | `makeacopy_LICENSE.txt`, `makeacopy_NOTICE.txt` |

Each adapted file states its origin and the changes made in its header.

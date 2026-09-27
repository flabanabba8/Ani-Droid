#!/usr/bin/env python3
"""Prepare experimental variants without modifying the production Kokoro model."""
import hashlib
import json
from pathlib import Path
import urllib.request
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / '.cache/kokoro-android/model.fp32.onnx'
OUTPUT = ROOT / '.cache/kokoro-android/benchmarks'
FP16_URL = 'https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/kokoro-v1.0.fp16.onnx'
FP16_SHA256 = 'f3a290d384fbb27966d462905c71a46cef9e5fd00516b40df32a0b4afe77ac96'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    expected = 'b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11'
    if digest(SOURCE) != expected:
        raise RuntimeError('Source model does not match the build manifest')
    download = OUTPUT / 'upstream-fp16.onnx'
    if not download.exists() or digest(download) != FP16_SHA256:
        temporary = download.with_suffix('.download')
        urllib.request.urlretrieve(FP16_URL, temporary)
        if digest(temporary) != FP16_SHA256:
            raise RuntimeError('FP16 download checksum mismatch')
        temporary.replace(download)
    baseline = onnx.load(SOURCE)
    fp16 = onnx.load(download)
    # Sherpa reads these model/frontend properties. Tensor names/order and graph are unchanged.
    del fp16.metadata_props[:]
    fp16.metadata_props.extend(baseline.metadata_props)
    onnx.save(fp16, OUTPUT / 'fp16.onnx')
    nodes = [node.name for node in baseline.graph.node
             if not node.name.startswith('/decoder/generator')
             and node.op_type in ['Conv', 'MatMul', 'LSTM', 'Gemm']]
    quantize_dynamic(str(SOURCE), str(OUTPUT / 'selective8.onnx'), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=['Conv', 'MatMul', 'LSTM', 'Gemm'], nodes_to_quantize=nodes,
                     extra_options={'EnableSubgraph': True})
    report = {'source_sha256': expected, 'fp16_url': FP16_URL, 'fp16_download_sha256': FP16_SHA256,
              'variants': {name: {'bytes': (OUTPUT / f'{name}.onnx').stat().st_size,
                                  'sha256': digest(OUTPUT / f'{name}.onnx')}
                           for name in ['fp16', 'selective8']}}
    (OUTPUT / 'provenance.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()

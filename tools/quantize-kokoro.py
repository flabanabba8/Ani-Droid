#!/usr/bin/env python3
"""Reproduce PageCast's benchmarked selective QUInt8 model using pinned ONNX tools."""
import hashlib
from pathlib import Path
import sys
import onnx
from onnxruntime.quantization import QuantType, quantize_dynamic

SOURCE_SHA256 = 'b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11'
OUTPUT_SHA256 = '84514ba144a99ac1e00d2ab60ebf9207ba7794b3b86c96ecb2ed9fb983f54f51'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    source, output = map(Path, sys.argv[1:])
    if digest(source) != SOURCE_SHA256:
        raise RuntimeError('Unexpected full-precision source model')
    model = onnx.load(source)
    nodes = [node.name for node in model.graph.node
             if not node.name.startswith('/decoder/generator')
             and node.op_type in ['Conv', 'MatMul', 'LSTM', 'Gemm']]
    quantize_dynamic(str(source), str(output), weight_type=QuantType.QUInt8,
                     op_types_to_quantize=['Conv', 'MatMul', 'LSTM', 'Gemm'], nodes_to_quantize=nodes,
                     extra_options={'EnableSubgraph': True})
    if digest(output) != OUTPUT_SHA256:
        output.unlink(missing_ok=True)
        raise RuntimeError('Selective Kokoro differs from the benchmarked model')
    print('Selective 8-bit model matches the benchmark SHA-256', flush=True)


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Join macOS binaries for different CPUs into one universal file (what `lipo -create` does).
Usage: fatmacho.py OUT arm64-binary amd64-binary"""
import struct
import sys

CPU = {0x0100000C: (0x0100000C, 0), 0x01000007: (0x01000007, 3)}  # arm64, x86_64
ALIGN = 14  # 16 KiB pages

out, *inputs = sys.argv[1:]
slices = []
for path in inputs:
    data = open(path, "rb").read()
    magic, cputype, cpusubtype = struct.unpack("<III", data[:12])
    assert magic == 0xFEEDFACF, f"{path}: not a 64-bit Mach-O"
    slices.append((cputype, cpusubtype & 0x00FFFFFF, data))

header = struct.pack(">II", 0xCAFEBABE, len(slices))
offset = 1 << ALIGN
entries, body = b"", b""
for cputype, cpusubtype, data in slices:
    entries += struct.pack(">iiIII", cputype, cpusubtype, offset, len(data), ALIGN)
    pad = b""
    body_start = offset
    body += data
    offset += len(data)
    offset = (offset + (1 << ALIGN) - 1) & ~((1 << ALIGN) - 1)
    body += b"\0" * (offset - body_start - len(data))
first = header + entries
with open(out, "wb") as f:
    f.write(first + b"\0" * ((1 << ALIGN) - len(first)) + body)

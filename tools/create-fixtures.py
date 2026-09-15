#!/usr/bin/env python3
"""Generate small reproducible importer fixtures (no third-party Python packages)."""
from pathlib import Path
from zipfile import ZipFile

root = Path(__file__).resolve().parent / "samples" / "generated"
root.mkdir(parents=True, exist_ok=True)
story = 'Alice opened the book.\n\n“Hello, reader!” said Alice.\n\nThe narrator turned the page.'
html = '<html><head><title>HTML sample</title></head><body><h1>A small adventure</h1><p>Alice opened the book.</p><p>“Hello, reader!” said Alice.</p></body></html>'
(root / "sample.html").write_text(html)
(root / "sample.txt").write_text(story)
(root / "sample.md").write_text('# Markdown sample\n\n' + story + '\n\nA [reading link](https://example.com).')
(root / "sample.fb2").write_text('<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0"><description><title-info><book-title>FB2 sample</book-title></title-info></description><body><section><title><p>A small adventure</p></title><p>Alice opened the book.</p><p>“Hello, reader!” said Alice.</p></section></body></FictionBook>')
with ZipFile(root / "sample.docx", "w") as archive:
    archive.writestr('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    archive.writestr('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    archive.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>DOCX sample</w:t></w:r></w:p><w:p><w:r><w:t>“Hello, reader!” said Alice.</w:t></w:r></w:p></w:body></w:document>')

stream = b'BT /F1 20 Tf 50 760 Td (PDF sample) Tj 0 -40 Td /F1 14 Tf (Alice opened the book.) Tj 0 -30 Td ("Hello, reader!" said Alice.) Tj ET'
objects = [b'<< /Type /Catalog /Pages 2 0 R >>', b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>', b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', f'<< /Length {len(stream)} >>\nstream\n'.encode() + stream + b'\nendstream']
pdf = bytearray(b'%PDF-1.4\n')
offsets = [0]
for index, obj in enumerate(objects, 1):
    offsets.append(len(pdf))
    pdf.extend(f'{index} 0 obj\n'.encode() + obj + b'\nendobj\n')
xref = len(pdf)
pdf.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
for offset in offsets[1:]:
    pdf.extend(f'{offset:010d} 00000 n \n'.encode())
pdf.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
(root / 'sample.pdf').write_bytes(pdf)
print(root)

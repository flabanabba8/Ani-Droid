#!/usr/bin/env python3
"""Generate PageCast's original screenshot samples and render its vector icon.

Requires Pillow for PNG export. Sample text and icon: GPL-3.0-or-later.
Screenshots themselves are captured from the running Android app, not mocked.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
samples = ROOT / 'tools/artifacts/listing'
samples.mkdir(parents=True, exist_ok=True)
books = {
    'The Lantern Road': [
        'The last train had left, but a light still shone in the station window. Mara tucked her map beneath her coat and crossed the empty platform.',
        '“You are just in time,” said the station keeper. “The road to the sea begins behind that door.”',
        'Beyond the door lay a garden full of silver leaves. Lanterns hung from every branch, swaying gently in a wind she could not feel.',
        'Mara opened her notebook. For years she had collected stories about this place. Now, at last, she had a story of her own to write.',
        'She chose the narrow path beside the stream. Somewhere ahead, someone was singing. The lanterns brightened as she followed the sound.',
        'At the bridge she stopped to listen. There was no hurry now. The stars were coming out, and the road was hers to discover.',
    ],
    'A Field Guide to Quiet Mornings': [
        'Begin with an open window. Notice the sounds that arrive before the city wakes: a bird on the roof, a bicycle on the street, a kettle in the next room.',
        'Choose one small thing to observe. The changing light on a wall can be enough. Write a sentence about it, then leave space for tomorrow.',
        'Carry a book when you go outside. A familiar page can turn an ordinary bench into a place worth returning to.',
    ],
}
for title, paragraphs in books.items():
    (samples / (title.replace(' ', '_') + '.html')).write_text(
        '<html><head><meta charset="utf-8"><title>' + title + '</title></head><body><h1>' + title +
        '</h1>' + ''.join('<p>' + p + '</p>' for p in paragraphs) + '</body></html>')

# Match ic_reader.xml's two quadratic paths; supersample for a crisp listing icon.
ns = '{http://schemas.android.com/apk/res/android}'
paths = list(ET.parse(ROOT / 'app/src/main/res/drawable/ic_reader.xml').getroot())
size = 2048
image = Image.new('RGB', (size, size), paths[0].get(ns + 'fillColor'))
draw = ImageDraw.Draw(image)
def curve(a, b, c):
    return [((1-t)**2*a[0]+2*(1-t)*t*b[0]+t*t*c[0],
             (1-t)**2*a[1]+2*(1-t)*t*b[1]+t*t*c[1]) for t in (i/64 for i in range(65))]
for points in [curve((8,10),(17,8),(23,13))+[(23,40)]+curve((23,40),(17,34),(8,36)),
               curve((25,13),(31,8),(40,10))+[(40,36)]+curve((40,36),(31,34),(25,40))]:
    draw.polygon([(x*size/48,y*size/48) for x,y in points], fill=paths[1].get(ns+'fillColor'))
out = ROOT / 'fastlane/metadata/android/en-US/images'
out.mkdir(parents=True, exist_ok=True)
image.resize((512,512),Image.Resampling.LANCZOS).save(out/'icon.png')
print('Generated listing icon and original sample documents.')

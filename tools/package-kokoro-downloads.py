"""Produce deterministic optional bundles and a wrapper-only AAR (no native code)."""
import hashlib,io,json,shutil,struct,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def patched_class(data):
 # OfflineTts has one System reference: loadLibrary(String). Route it to our
 # verified download loader, preserving every JNI method name/signature.
 old=b'java/lang/System';new=b'com/geminireader/tts/KokoroNativeLoader'
 needle=bytes([1])+struct.pack('>H',len(old))+old
 assert data.count(needle)==1
 return data.replace(needle,bytes([1])+struct.pack('>H',len(new))+new)
def zip_files(target,files):
 with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as z:
  for name,data in sorted(files):
   info=zipfile.ZipInfo(name,(2020,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
   z.writestr(info,data)
def main():
 out=ROOT/'app/build/generated/kokoro';assets=out/'bootstrap-assets'
 if assets.exists():shutil.rmtree(assets)
 assets.mkdir(parents=True)
 shutil.copytree(ROOT/'docs/kokoro-licenses',assets/'kokoro-licenses')
 model=out/'assets/kokoro';manifest=json.loads((model/'manifest.json').read_text())
 cache=ROOT/'.cache/kokoro-android'
 tar=cache/'kokoro-multi-lang-v1_0.tar.bz2';source=cache/'sherpa-onnx-1.13.8.aar'
 catalog={'model':{'url':'https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/'+tar.name,'size':tar.stat().st_size,'sha256':sha(tar),'sourceSize':(cache/'model.fp32.onnx').stat().st_size,'sourceSha256':sha(cache/'model.fp32.onnx'),'manifest':manifest},'runtimeUrl':'https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/'+source.name,'runtimes':{}}
 with zipfile.ZipFile(source) as aar:
  with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as jar:
   classes=[]
   for n in jar.namelist():
    d=jar.read(n)
    if n=='com/k2fsa/sherpa/onnx/OfflineTts.class':d=patched_class(d)
    classes.append((n,d))
   wrapper=out/'classes-download.jar';zip_files(wrapper,classes)
  zip_files(out/'sherpa-onnx.aar',[(n,wrapper.read_bytes() if n=='classes.jar' else aar.read(n)) for n in aar.namelist() if not n.startswith('jni/') and not n.endswith('/')])
  with source.open('rb') as raw:
   for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
    files=[];ranges=[]
    for n in ['libonnxruntime.so','libsherpa-onnx-jni.so']:
     info=aar.getinfo(f'jni/{abi}/{n}');data=aar.read(info)
     raw.seek(info.header_offset);header=raw.read(30);nameLen,extraLen=struct.unpack_from('<HH',header,26);offset=info.header_offset+30+nameLen+extraLen
     raw.seek(offset);compressed=raw.read(info.compress_size)
     files.append({'path':n,'size':len(data),'sha256':hashlib.sha256(data).hexdigest()})
     ranges.append({'path':n,'offset':offset,'size':len(compressed),'sha256':hashlib.sha256(compressed).hexdigest(),'method':info.compress_type})
    catalog['runtimes'][abi]={'manifest':{'version':f'runtime-1.13.8-{abi}-v1','files':files},'ranges':ranges}
 (assets/'kokoro-downloads.json').write_text(json.dumps(catalog,sort_keys=True))
 import subprocess
 subprocess.run(['uv','run','--isolated','--no-project','--python','3.11','--with','onnx==1.22.0','--with','numpy==2.4.6','python',str(ROOT/'tools/kokoro-recipe.py')],check=True)
 print('Download metadata and wrapper-only runtime prepared',flush=True)
if __name__=='__main__':main()

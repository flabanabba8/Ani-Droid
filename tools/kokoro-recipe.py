"""Create a tiny, deterministic recipe to reproduce our pinned selective8 model."""
import gzip,hashlib,struct
from pathlib import Path
import numpy as np
import onnx
from onnx import numpy_helper
ROOT=Path(__file__).resolve().parents[1]
def fields(b,start,end):
 def var(i):
  n=0;s=0
  while True:
   c=b[i];i+=1;n|=(c&127)<<s
   if c<128:return n,i
   s+=7
 i=start
 while i<end:
  key,i=var(i);w=key&7
  if w==2:n,i=var(i);yield key>>3,i,i+n;i+=n
  elif w==0:_,i=var(i)
  elif w==1:i+=8
  elif w==5:i+=4
  else:raise ValueError(w)
def tensors(b):
 graph=next((a,e) for f,a,e in fields(b,0,len(b)) if f==7);result={}
 for f,a,e in fields(b,*graph):
  if f!=5:continue
  fs=list(fields(b,a,e));name=next(b[x:y].decode() for n,x,y in fs if n==8)
  raw=next(((x,y) for n,x,y in fs if n==9),None)
  if raw:result[name]=raw
 return result
def main():
 base=(ROOT/'.cache/kokoro-android/model.fp32.onnx').read_bytes();target=(ROOT/'.cache/kokoro-android/model.selective8.onnx').read_bytes()
 src=tensors(base);dst=tensors(target)
 model=onnx.load_from_string(target);ts={t.name:t for t in model.graph.initializer}
 ops=[];corrections=0
 for name,(a,e) in dst.items():
  if name in src:
   x,y=src[name]
   if target[a:e]==base[x:y]:ops.append((a,e,struct.pack('>BQI',0,x,y-x)));continue
   dims=list(ts[name].dims)
   if len(dims)==2 and y-x==e-a and ts[name].data_type==1:
    transposed=np.frombuffer(base[x:y],dtype='<f4').reshape(dims[1],dims[0]).T.tobytes()
    if transposed==target[a:e]:
     ops.append((a,e,struct.pack('>BQII',3,x,dims[0],dims[1])));continue
  if not name.endswith('_quantized'):continue
  original=name.removesuffix('_quantized')
  if original not in src:continue
  x,y=src[original];vals=np.frombuffer(base[x:y],dtype='<f4');t=ts[name]
  scale=numpy_helper.to_array(ts[original+'_scale']).flatten();zp=numpy_helper.to_array(ts[original+'_zero_point']).flatten()
  signed=t.data_type==3;transpose=original.startswith('onnx::LSTM')
  groups,rows,cols=tuple(t.dims) if transpose else (1,1,len(vals))
  q=vals.reshape(len(scale),-1)/scale[:,None]
  q=np.rint(q)+zp[:,None];q=np.clip(q,-128 if signed else 0,127 if signed else 255).astype(np.int8 if signed else np.uint8).flatten()
  if transpose:q=q.reshape(groups,cols,rows).transpose(0,2,1).flatten()
  expected=np.frombuffer(target[a:e],dtype=np.uint8);q=q.view(np.uint8)
  diff=np.flatnonzero(q!=expected);corrections+=len(diff);q[diff]=expected[diff];assert q.tobytes()==target[a:e]
  op=struct.pack('>BQIIII?',1,x,len(vals),groups,rows,cols,transpose)+struct.pack('>?I',signed,len(scale))
  for sc,z in zip(scale,zp):op+=struct.pack('>fi',float(sc),int(z))
  op+=struct.pack('>I',len(diff))
  for i in diff:op+=struct.pack('>IB',int(i),int(expected[i]))
  ops.append((a,e,op))
 # Unchanged tensor-valued Constant attributes are not graph initializers.
 def constants(message):
  if message.DESCRIPTOR.name=='TensorProto' and len(message.raw_data)>=256:
   data=message.raw_data;a=target.find(data);x=base.find(data)
   if a>=0 and x>=0 and not any(a<e and a+len(data)>b for b,e,_ in ops):
    ops.append((a,a+len(data),struct.pack('>BQI',0,x,len(data))))
  for field,value in message.ListFields():
   if field.type==field.TYPE_MESSAGE:
    if field.is_repeated:
     for v in value:constants(v)
    else:constants(value)
 constants(model)
 cursor=0;recipe=b'KQR1';reconstructed=bytearray()
 for a,e,op in sorted(ops):
  assert a>=cursor
  if a>cursor:
   recipe+=struct.pack('>BI',2,a-cursor)+target[cursor:a];reconstructed+=target[cursor:a]
  recipe+=op;reconstructed+=target[a:e];cursor=e
 recipe+=struct.pack('>BI',2,len(target)-cursor)+target[cursor:]+bytes([255]);reconstructed+=target[cursor:]
 assert bytes(reconstructed)==target
 path=ROOT/'app/build/generated/kokoro/bootstrap-assets/kokoro-recipe.bin';path.parent.mkdir(parents=True,exist_ok=True)
 path.write_bytes(gzip.compress(recipe,mtime=0))
 print('Recipe bytes',path.stat().st_size,'corrections',corrections,'literal+recipe',len(recipe),flush=True)
if __name__=='__main__':main()

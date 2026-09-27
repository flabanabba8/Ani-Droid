import wave,json
from pathlib import Path
import numpy as np
from scipy.signal import welch
rows=[]
for p in sorted(Path('tools/artifacts/kokoro-benchmark-audio').glob('*.wav')):
 with wave.open(str(p)) as w: rate=w.getframerate();x=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(float)/32768
 f,power=welch(x,rate,nperseg=2400,noverlap=1200)
 r={'file':p.name,'seconds':len(x)/rate,'peak':float(abs(x).max()),'clippedFraction':float(np.mean(abs(x)>=.999)),'dc':float(x.mean())}
 for tone in [4800,9600]:
  floor=np.median(power[(abs(f-tone)>30)&(abs(f-tone)<200)])
  r[f'tone{tone}dB']=float(10*np.log10(power[np.argmin(abs(f-tone))]/floor))
 rows.append(r)
Path('tools/artifacts/kokoro-benchmark-spectra.json').write_text(json.dumps(rows,indent=2))
for kind in ['fp32','fp16','selective8']:
 rs=[r for r in rows if r['file'].startswith(kind+'-')]
 if rs: print(kind,'runs',len(rs),'max tone prominence dB',*[round(max(r[f'tone{t}dB'] for r in rs),2) for t in [4800,9600]],'max clipping',max(r['clippedFraction'] for r in rs))

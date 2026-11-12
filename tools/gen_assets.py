# Regenerates app/src/main/assets from public catalogues.
# Inputs in <data dir>/data/: hyg.csv (HYG v4.1), ngc.csv + addendum.csv (OpenNGC), modern.json (Stellarium modern sky culture).
# Usage: python3 tools/gen_assets.py <data dir> app/src/main/assets
import csv, json, sys
D=sys.argv[1]; OUT=sys.argv[2]
cons=json.load(open(f'{D}/data/modern.json'))
line_hips=set()
conrows=[]
for c in cons['constellations']:
    ab=c['id'].split()[-1]
    name=c.get('common_name',{}).get('native',ab)
    segs=[]
    for poly in c['lines']:
        poly=[h for h in poly if isinstance(h,int)]
        for a,b in zip(poly,poly[1:]):
            segs.append((a,b)); line_hips.update((a,b))
    conrows.append((ab,name,segs))
greek={'Alp':'α','Bet':'β','Gam':'γ','Del':'δ','Eps':'ε','Zet':'ζ','Eta':'η','The':'θ','Iot':'ι','Kap':'κ','Lam':'λ','Mu':'μ','Nu':'ν','Xi':'ξ','Omi':'ο','Pi':'π','Rho':'ρ','Sig':'σ','Tau':'τ','Ups':'υ','Phi':'φ','Chi':'χ','Psi':'ψ','Ome':'ω'}
stars=[]; have=set()
for r in csv.DictReader(open(f'{D}/data/hyg.csv')):
    if r['id']=='0': continue
    try: mag=float(r['mag'])
    except: continue
    hip=int(r['hip']) if r['hip'] else 0
    if mag>6.0 and hip not in line_hips: continue
    name=r['proper']; isp=1 if name else 0
    if not name and r['bayer']:
        b=r['bayer']; base=b.rstrip('0123456789-'); suf=b[len(base):]
        name=greek.get(base,base)+suf+' '+r['con']
    elif not name and r['flam']:
        name=r['flam']+' '+r['con']
    ci=r['ci'] or '0.6'
    stars.append((hip,float(r['ra']),float(r['dec']),mag,float(ci),isp,name.replace(',',' ')))
    if hip: have.add(hip)
stars.sort(key=lambda s:s[3])
with open(f'{OUT}/stars.csv','w') as f:
    for s in stars: f.write('%d,%.5f,%.4f,%.2f,%.2f,%d,%s\n'%s)
miss=0
with open(f'{OUT}/constellations.csv','w') as f:
    for ab,name,segs in conrows:
        segs=[(a,b) for a,b in segs if a in have and b in have]
        f.write(ab+','+name+','+' '.join('%d-%d'%s for s in segs)+'\n')
types={'G':'Galaxy','GPair':'Galaxy pair','GTrpl':'Galaxy triplet','GGroup':'Galaxy group','OCl':'Open cluster','GCl':'Globular cluster','PN':'Planetary nebula','HII':'Nebula','EmN':'Emission nebula','RfN':'Reflection nebula','Neb':'Nebula','Cl+N':'Cluster + nebula','SNR':'Supernova remnant','*Ass':'Stellar association','DrkN':'Dark nebula','Nova':'Nova'}
def hms(s,deg=False):
    sign=-1 if s.startswith('-') else 1; p=[float(x) for x in s.lstrip('+-').split(':')]
    v=p[0]+p[1]/60+p[2]/3600; return sign*v*(1 if deg else 15)
dso=[]
for fn in ('ngc.csv','addendum.csv'):
    for r in csv.DictReader(open(f'{D}/data/{fn}'),delimiter=';'):
        t=r['Type']
        if t not in types or not r['RA']: continue
        m=r['M']; common=r['Common names'].split(',')[0]
        try: mag=float(r['V-Mag'] or r['B-Mag'])
        except: mag=99
        if not (m or common or mag<=9.5): continue
        nm=r['Name']
        if nm.startswith('NGC') or nm.startswith('IC'): nm=nm.replace('NGC0','NGC').replace('NGC0','NGC').replace('IC0','IC').replace('IC0','IC')
        label=('M'+str(int(m))) if m else nm
        dso.append((label,nm,types[t],hms(r['RA']),hms(r['Dec'],True),mag if mag<99 else 0,float(r['MajAx'] or 0),common.replace(',',' ')))
# Messier objects that are not in NGC (M40, M45 Pleiades etc.) handled by addendum where present
with open(f'{OUT}/dso.csv','w') as f:
    for d in dso: f.write('%s,%s,%s,%.4f,%.4f,%.1f,%.1f,%s\n'%d)
print(len(stars),'stars',len(dso),'dso', sum(1 for d in dso if d[0].startswith('M')),'messier')

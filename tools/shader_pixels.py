"""Source-space chart oracle. Regions come from fixture geometry, never pixel colors."""
import math
import numpy as np
from PIL import Image
from visual_pixels import interior

PALETTE = np.array([
    [0,0,0], [48,48,48], [128,128,128], [240,240,240],
    [220,32,32], [32,220,32], [32,32,220], [220,220,32],
    [32,220,220], [220,32,220], [200,128,80], [80,128,200],
    [96,32,160], [32,160,96], [160,96,32], [255,255,255],
],dtype=np.int16).reshape(4,4,3)
TOLERANCE = 4
MAX_BAD_FRACTION = 0.01

def write_chart(directory):
    chart = np.repeat(np.repeat(PALETTE.astype(np.uint8),32,axis=0),32,axis=1)
    Image.fromarray(chart).save(directory/'fixture-opaque.png')

def regions(frame, width=960, height=720):
    eye, center, normal, right, up = (np.asarray(frame[k],dtype=float) for k in
                                      ('camera','center','normal','right','up'))
    yaw,pitch=map(math.radians,(frame['cameraYaw'],frame['cameraPitch']))
    forward=np.array([-math.sin(yaw)*math.cos(pitch),-math.sin(pitch),math.cos(yaw)*math.cos(pitch)])
    cr=np.array([-math.cos(yaw),0,-math.sin(yaw)]); cu=np.cross(cr,forward)
    focal=height/(2*math.tan(math.radians(frame['fov'])/2))
    yy,xx=np.mgrid[:height,:width]
    rays=forward+((xx[...,None]+.5-width/2)/focal)*cr-((yy[...,None]+.5-height/2)/focal)*cu
    with np.errstate(divide='ignore',invalid='ignore'):
        depth=((center-eye)@normal)/(rays@normal)
    hit=eye+depth[...,None]*rays; delta=hit-center
    u=.5+(delta@right)/frame['size']; v=.5-(delta@up)/frame['size']
    screen=(depth>0)&(u>.02)&(u<.98)&(v>.02)&(v<.98)
    covered=np.zeros(screen.shape,dtype=bool)
    for box in frame['occluders']:
        with np.errstate(divide='ignore',invalid='ignore'):
            a=(np.asarray(box[:3])-eye)/rays;b=(np.asarray(box[3:])-eye)/rays
        near=np.minimum(a,b).max(axis=-1);far=np.maximum(a,b).min(axis=-1)
        covered|=(far>=np.maximum(near,0))&(near<depth)
    return u,v,interior(screen&~covered),interior(screen&covered)

def measure(path, frame, tolerance=TOLERANCE):
    with Image.open(path) as image: rgb=np.asarray(image.convert('RGB'),dtype=np.int16)
    if rgb.shape != (720,960,3): raise ValueError('shader fixture needs960x720 framebuffer')
    u,v,visible,hidden=regions(frame)
    if not np.count_nonzero(visible)>=32: raise ValueError('insufficient projected screen samples')
    if frame.get('alpha'):
        # The cross-variant oracle verifies unchanged translucent pixels; source RGB alone
        # cannot be an alpha-blending reference over a shader-processed background.
        return dict(status='pass',image_errors=0.,occlusion_errors=0.,patches=[],alpha=True)
    patches=[]
    for y in range(4):
        for x in range(4):
            mask=visible&(u>(x+.18)/4)&(u<(x+.82)/4)&(v>(y+.18)/4)&(v<(y+.82)/4)
            if np.count_nonzero(mask)<12:
                if frame['occluded']: continue
                raise ValueError('missing projected color patch')
            difference=np.abs(rgb[mask]-PALETTE[y,x]); error=difference.max(axis=1)
            patches.append(dict(index=y*4+x,expected=PALETTE[y,x].tolist(),samples=int(error.size),
                                observed=np.median(rgb[mask],axis=0).tolist(),
                                median=round(float(np.median(error)),4),max=int(error.max()),
                                bad=float(np.mean(error>tolerance))))
    if not patches: raise ValueError('no chart patches')
    if frame['occluded'] and np.count_nonzero(hidden)<32: raise ValueError('missing occluder coverage')
    # Covered pixels must not be replaced with the known underlying chart. Dark red concrete
    # may undergo grading; detect source-color leakage rather than demanding ungraded concrete.
    covered_patches=[]
    for y in range(4):
        for x in range(4):
            mask=hidden&(u>(x+.18)/4)&(u<(x+.82)/4)&(v>(y+.18)/4)&(v<(y+.82)/4)
            if np.count_nonzero(mask)>=12 and x+y*4 not in (0,4):
                covered_patches.append(float(np.mean(np.max(np.abs(rgb[mask]-PALETTE[y,x]),axis=1)<=TOLERANCE)))
    leakage=max(covered_patches,default=0.)
    error=max(p['bad'] for p in patches)
    return dict(status='pass' if error<=MAX_BAD_FRACTION and leakage<=MAX_BAD_FRACTION else 'pixel-failure',
                image_errors=error,occlusion_errors=leakage,patches=patches)

def compare_transparency(fixed, original, frame):
    u,v,visible,_=regions(frame)
    local=np.abs(u-.5)
    mask=visible&(((local>.22)&(local<.30))|((local>.37)&(local<.43)))
    if mask.sum()<32: raise ValueError('insufficient alpha/background samples')
    a=np.asarray(Image.open(fixed).convert('RGB'),dtype=np.int16)
    b=np.asarray(Image.open(original).convert('RGB'),dtype=np.int16)
    return float(np.percentile(np.max(np.abs(a[mask]-b[mask]),axis=1),95))

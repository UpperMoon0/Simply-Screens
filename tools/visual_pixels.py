"""Geometric regions + tolerant color assertions, not screenshot golden matching.

The oracle uses the *model face*, independent of the renderer's image transform.
Never derive the expected region by finding the image's actual colored pixels.
"""
import math
import numpy as np
from PIL import Image


def interior(mask):
    # Drop raster boundary pixels, including foreground silhouettes.
    out = mask.copy()
    for axis in (0, 1):
        out &= np.roll(mask, 1, axis) & np.roll(mask, -1, axis)
    out[0, :] = out[-1, :] = False
    out[:, 0] = out[:, -1] = False
    return out


def measure(path, frame):
    with Image.open(path) as image:
        rgb = np.asarray(image.convert("RGB"), dtype=np.int16)
    h, w, _ = rgb.shape
    if (w, h) != (960, 720):
        raise ValueError(f"unexpected viewport {w}x{h}; expected fixed 960x720")
    eye, center, normal, right, up = (np.array(frame[k], dtype=float) for k in
                                      ("camera", "center", "normal", "right", "up"))
    yaw, pitch = map(math.radians, (frame["cameraYaw"], frame["cameraPitch"]))
    forward = np.array([-math.sin(yaw)*math.cos(pitch), -math.sin(pitch), math.cos(yaw)*math.cos(pitch)])
    camera_right = np.array([-math.cos(yaw), 0, -math.sin(yaw)])
    camera_up = np.cross(camera_right, forward)
    focal = h / (2 * math.tan(math.radians(frame["fov"]) / 2))
    # Bound work to the projected rectangle, never the entire large framebuffer.
    corners = [center + sx*right*frame["size"]/2 + sy*up*frame["size"]/2 for sx in (-1,1) for sy in (-1,1)]
    projected = []
    for corner in corners:
        delta = corner - eye
        depth = delta @ forward
        if depth <= 0:
            raise ValueError("screen behind camera")
        projected.append((w/2 + focal*(delta @ camera_right)/depth, h/2 - focal*(delta @ camera_up)/depth))
    margin = max(4, math.ceil(max(max(x for x,y in projected)-min(x for x,y in projected),
                                  max(y for x,y in projected)-min(y for x,y in projected))*0.15))
    x0 = max(0, math.floor(min(x for x,y in projected))-margin)
    x1 = min(w, math.ceil(max(x for x,y in projected))+margin)
    y0 = max(0, math.floor(min(y for x,y in projected))-margin)
    y1 = min(h, math.ceil(max(y for x,y in projected))+margin)
    if x1 <= x0 or y1 <= y0:
        raise ValueError("screen outside viewport")
    yy, xx = np.mgrid[y0:y1, x0:x1]
    rays = forward + ((xx[...,None]+0.5-w/2)/focal)*camera_right - ((yy[...,None]+0.5-h/2)/focal)*camera_up
    denom = rays @ normal
    with np.errstate(divide="ignore", invalid="ignore"):
        depth = ((center-eye) @ normal) / denom
    hit = eye + depth[...,None]*rays
    delta = hit-center
    mask = (depth > 0) & (np.abs(delta @ right) < frame["size"]*0.46) & (np.abs(delta @ up) < frame["size"]*0.46)
    outside = (np.abs(delta @ right) > frame["size"]*0.55) | (np.abs(delta @ up) > frame["size"]*0.55)
    covered = np.zeros(mask.shape, dtype=bool)
    for box in frame["occluders"]:
        with np.errstate(divide="ignore", invalid="ignore"):
            t1 = (np.array(box[:3])-eye)/rays
            t2 = (np.array(box[3:])-eye)/rays
        near = np.max(np.minimum(t1,t2), axis=-1)
        far = np.min(np.maximum(t1,t2), axis=-1)
        covered |= (far >= np.maximum(near,0)) & (near < depth)
    exposed = interior(mask & ~covered)
    hidden = interior(mask & covered)
    # Too few independent samples is an invalid fixture, never a visual pass.
    if np.count_nonzero(exposed) < 4 or (frame["occluded"] and np.count_nonzero(hidden) < 4):
        raise ValueError("insufficient visible/occluded screen pixels")
    crop = rgb[y0:y1, x0:x1]
    r,g,b = crop[...,0],crop[...,1],crop[...,2]
    magenta = (r > 80) & (b > 80) & (r > g*2) & (b > g*2)
    if np.count_nonzero(outside) >= 4 and np.mean(magenta[outside]) > 0.1:
        raise ValueError("image outside expected silhouette; invalid camera/fixture or misplaced geometry")
    if frame.get("alpha"):
        local_u = np.abs(delta @ right) / frame["size"]
        opaque = interior(exposed & (local_u < 0.12))
        semi = interior(exposed & (local_u >= 0.22) & (local_u < 0.29))
        transparent = interior(exposed & (local_u >= 0.39) & (local_u < 0.44))
        if min(np.count_nonzero(opaque), np.count_nonzero(semi), np.count_nonzero(transparent)) < 4:
            raise ValueError("insufficient alpha fixture samples")
        opaque_error = float(np.mean(~magenta[opaque]))
        semi_error = float(np.mean(~magenta[semi]))
        transparent_leak = float(np.mean(magenta[transparent]))
        rb = (r.astype(float) + b.astype(float)) * 0.5
        opaque_level = float(np.mean(rb[opaque]))
        semi_level = float(np.mean(rb[semi]))
        transparent_level = float(np.mean(rb[transparent]))
        span = opaque_level - transparent_level
        if span <= 20.0:
            raise ValueError("alpha fixture lacks opaque/transparent contrast")
        blend_fraction = (semi_level - transparent_level) / span
        # The displayed midpoint is backend/color-space dependent: 50% source alpha
        # can land near 0.5 in linear blending or ~0.75 in an sRGB framebuffer.
        # What must remain invariant is that it is materially between transparent
        # backing and the opaque reference; ignored/discarded alpha stays rejected.
        alpha_pass = (opaque_error <= 0.005 and semi_error <= 0.005
                      and transparent_leak <= 0.005 and 0.20 <= blend_fraction <= 0.85)
        alpha_error = max(opaque_error, semi_error, transparent_leak,
                          max(0.0, 0.20-blend_fraction, blend_fraction-0.85))
        return dict(image_errors=opaque_error, occlusion_errors=0.0,
                    alpha_errors=alpha_error, alpha_blend_fraction=blend_fraction,
                    alpha_levels=dict(opaque=opaque_level, semi=semi_level, transparent=transparent_level),
                    exposed_pixels=int(exposed.sum()), occluded_pixels=0,
                    status="pass" if alpha_pass else "pixel-failure")

    image_error = float(np.mean(~magenta[exposed]))
    # Occlusion is a depth contract: fail only when the screen image leaks into a
    # geometrically hidden region. Do not require the covering world pixels to be
    # a particular color; lighting, face shading and neighboring geometry can
    # legitimately make a red-concrete fixture non-red at some hidden pixels.
    occlusion_error = float(np.mean(magenta[hidden])) if frame["occluded"] else 0.0
    return dict(image_errors=image_error, occlusion_errors=occlusion_error,
                exposed_pixels=int(exposed.sum()), occluded_pixels=int(hidden.sum()),
                status="pass" if image_error <= 0.005 and occlusion_error <= 0.005 else "pixel-failure")

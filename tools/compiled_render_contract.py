"""Inspect real compiled renderer call sites; comments cannot satisfy this gate."""
import argparse
from pathlib import Path
import re
import subprocess

MODULES = ("common-1.20.1", "common-1.21.1", "neoforge-26.1.2")
CLASS = "com.nstut.simplyscreens.client.renderers.ScreenBlockEntityRenderer"


def verify_dump(text, modern=False):
    # javap -constants prints the inlined static final value even without class initialization.
    if not re.search(r"\bBASE_OFFSET\s*=\s*0\.501f;", text):
        raise ValueError("compiled BASE_OFFSET must be 0.501f")
    name = "submit" if modern else "renderTextureQuad"
    methods = re.split(r"(?m)^  (?=\S)", text)
    bodies = [m for m in methods if re.search(r"\b"+name+r"\(", m.split("\n",1)[0])]
    if modern and len(bodies) > 1:
        # javac also emits a generic bridge submit(BlockEntityRenderState,...).
        bodies = [m for m in bodies if "ScreenBlockEntityRenderState" in m.split("\n",1)[0]]
    if len(bodies) != 1:
        raise ValueError("missing or ambiguous compiled render method")
    body = bodies[0]
    calls = re.findall(r"(?m)^\s*\d+:\s+invoke\w+\s+.*// (?:InterfaceMethod|Method) ([^\s]+)", body)
    polygon = [i for i,c in enumerate(calls) if re.search(r"RenderTypes?\.textPolygonOffset:", c)]
    if len(polygon) != 1:
        raise ValueError("actual render method must call polygon offset exactly once")
    if any(re.search(r"RenderTypes?\.text(?:SeeThrough)?:", c) for c in calls):
        raise ValueError("plain or see-through compiled render call")
    consumer = "submitCustomGeometry:" if modern else "getBuffer:"
    if not any(consumer in c for c in calls[polygon[0]+1:]):
        raise ValueError("polygon offset is not followed by render submission")
    # Old APIs pass the produced RenderType straight into getBuffer. Reject a
    # discarded call followed by an unrelated render type, even if both names exist.
    if not modern:
        lines = [line for line in body.splitlines() if re.match(r"\s*\d+:", line)]
        i = next(i for i,line in enumerate(lines) if ".textPolygonOffset:" in line)
        if i+1 >= len(lines) or ".getBuffer:" not in lines[i+1]:
            raise ValueError("polygon offset result is not consumed by getBuffer")
    else:
        instructions = body[body.index(".textPolygonOffset:"):]
        instructions = instructions[:instructions.index(".submitCustomGeometry:")]
        if re.search(r"\d+:\s+(?:pop|pop2|astore)\b", instructions):
            raise ValueError("polygon offset result discarded before submission")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    for module in MODULES:
        classes = args.root / module / "build/classes/java/main"
        result = subprocess.run(["javap", "-p", "-c", "-constants", "-classpath", str(classes), CLASS], capture_output=True, text=True, check=True)
        verify_dump(result.stdout, module == "neoforge-26.1.2")
        print(module+": compiled render contract PASS")


if __name__ == "__main__": main()

#!/usr/bin/env python3
"""Run the existing version-neutral contract suite on the actual Java 8 runtime."""
import argparse,re
from pathlib import Path
from legacy_screen_sources import java8,ROOT,COMPAT

def generate(output, native116=False, forge=False):
    generated=set()
    sources=[ROOT/'common/src/test/java',ROOT/'common-legacy/src/test/java']
    if native116:sources.append(ROOT/'common-1.16.5/src/test/java')
    for source in sources:
        for path in source.rglob('*.java'):
            text=java8(path.read_text()).replace('org.slf4j.LoggerFactory', 'org.apache.logging.log4j.LogManager').replace('org.slf4j.Logger', 'org.apache.logging.log4j.Logger').replace('LoggerFactory.getLogger(', 'LogManager.getLogger(').replace(".getPackageName()", ".getPackage().getName()")
            text=re.sub(r'("(?:[^"\\]|\\.)*"|[a-zA-Z_]\w*)\.repeat\(([^()]*)\)',COMPAT+r'.repeat(\1, \2)',text)
            if native116 and not forge and path.name=='LegacyBlockEntitySyncTest.java':
                text=text.replace('extends LegacyBlockEntity','extends FabricBlockEntity')
            if forge:
                import forge_1165_sources
                text=forge_1165_sources.convert(text)
            target=output/path.relative_to(source);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text);generated.add(target)
    for path in output.rglob('*.java'):
        if path not in generated:path.unlink()
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--native-116',action='store_true');p.add_argument('--forge',action='store_true');args=p.parse_args();generate(args.output,args.native_116,args.forge)

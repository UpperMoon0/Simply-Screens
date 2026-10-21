#!/usr/bin/env python3
"""Run the existing version-neutral contract suite on the actual Java 8 runtime."""
import argparse,re
from pathlib import Path
from legacy_screen_sources import java8,ROOT,COMPAT

def generate(output):
    for source in [ROOT/'common/src/test/java',ROOT/'common-legacy/src/test/java']:
        for path in source.rglob('*.java'):
            text=java8(path.read_text()).replace('org.slf4j.LoggerFactory', 'org.apache.logging.log4j.LogManager').replace('org.slf4j.Logger', 'org.apache.logging.log4j.Logger').replace('LoggerFactory.getLogger(', 'LogManager.getLogger(').replace(".getPackageName()", ".getPackage().getName()")
            text=re.sub(r'("(?:[^"\\]|\\.)*"|[a-zA-Z_]\w*)\.repeat\(([^()]*)\)',COMPAT+r'.repeat(\1, \2)',text)
            target=output/path.relative_to(source);target.parent.mkdir(parents=True,exist_ok=True);target.write_text(text)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);generate(p.parse_args().output)

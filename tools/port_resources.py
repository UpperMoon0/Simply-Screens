#!/usr/bin/env python3
"""Adapt owned recipes, loot and language resources to each native pack format."""
import argparse,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def generate(version,loader,output):
    output.mkdir(parents=True,exist_ok=True)
    legacy=version=='1.12.2'
    for root in [ROOT/'common/src/main/resources',ROOT/f'{loader}-{version}/src/main/resources']:
        for source in root.rglob('*'):
            if not source.is_file() or source.suffix=='.bbmodel':continue
            relative=source.relative_to(root);name=str(relative)
            if legacy:
                if name.startswith('data/') and '/recipe/' not in name:continue
                name=name.replace('data/simply_screens/recipe/', 'assets/simply_screens/recipes/')
            else:
                name=name.replace('/recipe/', '/recipes/').replace('/loot_table/', '/loot_tables/').replace('/tags/block/', '/tags/blocks/')
            target=output/name;target.parent.mkdir(parents=True,exist_ok=True)
            if source.suffix!='.json':shutil.copyfile(source,target);continue
            data=json.loads(source.read_text())
            if version=='1.16.5' and '/patchouli_books/' in name:
                if name.endswith('/book.json'):
                    data['model']='simply_screens:item/guide_book'
                    data['creative_tab']='simply_screens.tab'
                data=json.loads(json.dumps(data).replace('amethyst shard','quartz'))
            if version=='1.16.5' and '/lang/' in name:
                data.setdefault('itemGroup.simply_screens.tab', data.get('itemGroup.simply_screens', 'Simply Screens'))
                data.setdefault('itemGroup.simply_screens:tab', data.get('itemGroup.simply_screens', 'Simply Screens'))
            if '/recipes/' in name:
                data.pop('category',None);data.pop('show_notification',None)
                if 'id' in data.get('result',{}):data['result']['item']=data['result'].pop('id')
                # Amethyst does not exist before 1.17. Quartz is the native optical substitute.
                for value in data.get('key',{}).values():
                    if isinstance(value,dict) and value.get('item')=='minecraft:amethyst_shard':value['item']='minecraft:quartz'
            if legacy and '/lang/' in name:
                target=target.with_suffix('.lang');target.write_text(''.join(str(k)+'='+str(v).replace('\n','\\n')+'\n' for k,v in data.items()));continue
            target.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    if not legacy:(output/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':6,'description':'Simply Screens resources'}})+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--version',choices=['1.12.2','1.16.5'],required=True);p.add_argument('--loader',choices=['fabric','forge'],required=True);p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();generate(args.version,args.loader,args.output)

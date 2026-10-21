#!/usr/bin/env python3
"""Check native port artifact identity, complete gameplay payload and dependency ownership."""
import argparse,json,struct,zipfile
from pathlib import Path
REQUIRED_CLASSES=[
 'SimplyScreens','Config','ScreenNetworkService','ScreenRegistry','ScreenRegistryHelper',
 'blocks/ScreenBlock','blocks/entities/ScreenBlockEntity','api/ScreenApi','api/ScreenCommands','api/ScreenControlService',
 'helpers/ServerImageManager','helpers/ClientImageManager','helpers/ScreenPacketSecurity','helpers/ScreenAccessService',
 'helpers/MediaDownloadReceiver','network/PacketRegistries','network/ScreenAccessC2SPacket','network/ScreenPlaylistC2SPacket',
 'network/UploadImageChunkC2SPacket','network/DownloadImageFromUrlC2SPacket','playlist/ScreenPlaylistService',
 'client/screens/ImageLoadScreen','client/renderers/ScreenBlockEntityRenderer']

def verify(path,target):
    loader,version=target.split('-',1);expected_major=69 if version=='26.2' else 52
    with zipfile.ZipFile(path) as jar:
        names=set(jar.namelist())
        for cls in REQUIRED_CLASSES:
            name='com/nstut/simplyscreens/'+cls+'.class'
            if name not in names:raise ValueError('Missing complete runtime class: '+name)
        for name in names:
            if name.startswith(('com/nstut/packetweave/','com/nstut/openui/','net/minecraft/')):
                raise ValueError('External dependency/game classes must not be bundled: '+name)
            if name.startswith('com/nstut/simplyscreens/') and name.endswith('.class'):
                data=jar.read(name)
                if data[:4]!=b'\xca\xfe\xba\xbe' or struct.unpack('>H',data[6:8])[0]!=expected_major:
                    raise ValueError('Wrong Java bytecode level: '+name)
        if version=='1.16.5':
            if 'pack.mcmeta' not in names or json.loads(jar.read('pack.mcmeta')).get('pack',{}).get('pack_format') != 6:
                raise ValueError('Missing or incompatible native 1.16 resource-pack metadata')
            config=json.loads(jar.read('simplyscreens-color.mixins.json'))
            refmap=config.get('refmap')
            if not refmap or refmap not in names:raise ValueError('Missing native legacy mixin refmap')
        if 'assets/simply_screens/textures/block/screen_front.png' not in names:raise ValueError('Missing screen texture')
        if loader=='fabric':
            meta=json.loads(jar.read('fabric.mod.json'))
            if meta['id']!='simply_screens' or meta['depends']['minecraft']!=version:raise ValueError('Wrong Fabric identity/version')
            if '${' in str(meta):raise ValueError('Unexpanded Fabric metadata')
            if not meta['entrypoints'].get('client') or not meta['entrypoints'].get('main'):raise ValueError('Missing loader entrypoints')
            if meta['depends'].get('packetweave')!='>=0.1.4':raise ValueError('Wrong native PacketWeave dependency')
        elif loader=='forge' and version=='1.12.2':
            meta=jar.read('mcmod.info').decode()
            if 'simply_screens' not in meta or '${' in meta:raise ValueError('Wrong Forge 1.12 identity')
        else:
            name='META-INF/neoforge.mods.toml' if loader=='neoforge' else 'META-INF/mods.toml'
            meta=jar.read(name).decode()
            if 'simply_screens' not in meta or '${' in meta or version not in meta:raise ValueError('Wrong native mod metadata')
    return len(REQUIRED_CLASSES)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('jar',type=Path);p.add_argument('--target',choices=['forge-1.12.2','fabric-1.16.5','forge-1.16.5','fabric-26.2','neoforge-26.2'],required=True)
    args=p.parse_args();count=verify(args.jar,args.target);print(f'Verified {args.target}: native identity, Java bytecode, {count} gameplay classes and external dependency ownership.')

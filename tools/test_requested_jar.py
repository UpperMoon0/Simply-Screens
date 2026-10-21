"""Artifact-contract fixtures only; headers do not claim runnable mod bytecode."""
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import verify_requested_jar as contract


class LegacyJarVariantContractTest(unittest.TestCase):
    def verify_variants(self,variants):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'contract-fixture.jar'
            with zipfile.ZipFile(path,'w') as jar:
                for cls in contract.REQUIRED_CLASSES:
                    jar.writestr('com/nstut/simplyscreens/'+cls+'.class',b'\xca\xfe\xba\xbe\x00\x00\x00\x34')
                jar.writestr('mcmod.info','[{"modid":"simply_screens","version":"fixture"}]')
                jar.writestr('assets/simply_screens/textures/block/screen_front.png',b'fixture')
                jar.writestr('assets/simply_screens/blockstates/screen.json',json.dumps({'variants':variants}))
            return contract.verify(path,'forge-1.12.2')

    def variants(self):
        return {f'facing={facing},state={state}':{'model':'simply_screens:block/screen'}
                for facing in ('north','south','west','east','up','down') for state in range(3)}

    def test_all_exact_order_native_variants_satisfy_artifact_contract(self):
        self.assertEqual(len(contract.REQUIRED_CLASSES),self.verify_variants(self.variants()))

    def test_previous_selector_order_is_rejected_in_artifact(self):
        old={','.join(reversed(key.split(','))):value for key,value in self.variants().items()}
        with self.assertRaisesRegex(ValueError,'exact-order blockstate variants'):self.verify_variants(old)

    def test_missing_native_state_is_rejected_in_artifact(self):
        missing=self.variants();missing.pop('facing=up,state=2')
        with self.assertRaisesRegex(ValueError,'exact-order blockstate variants'):self.verify_variants(missing)


if __name__=='__main__':unittest.main()

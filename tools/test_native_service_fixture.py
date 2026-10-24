import json
import os
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from unittest.mock import patch

import native_service_fixture as fixture
import requested_live_join as live


def request(url, token=fixture.TOKEN):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token})
    with opener.open(req, timeout=3) as response:
        return json.load(response)


class NativeServiceFixtureTest(unittest.TestCase):
    def test_service_permission_response_and_unknown_credentials_fail_closed(self):
        for allowed in (False, True):
            with fixture.service_fixture(allowed) as (url, requests):
                self.assertTrue(url.startswith('http://127.0.0.1:'))
                data = request(url + '/privileges')['privileges']
                self.assertIs(allowed, data['multiplayerServer']['enabled'])
                self.assertIs(False, data['multiplayerRealms']['enabled'])
                self.assertEqual({'blockedProfiles': []}, request(url + '/privacy/blocklist'))
                for endpoint, token, status in [('/privileges', 'real-token-must-be-rejected', 401),
                                                ('/unknown', fixture.TOKEN, 404)]:
                    with self.assertRaises(urllib.error.HTTPError) as error:
                        request(url + endpoint, token)
                    self.assertEqual(status, error.exception.code)
                self.assertEqual(['/privileges', '/privacy/blocklist'], requests)
            with self.assertRaises(urllib.error.URLError):
                request(url + '/privileges')

    def test_real_authlib_environment_and_native_permission_parser(self):
        # Optional locally before Gradle resolves dependencies; hosted native jobs
        # already have all these exact jars from their preceding production build.
        cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
        coordinates = [('com.mojang', 'authlib', '2.1.28'),
                       ('com.google.code.gson', 'gson', '2.8.0'),
                       ('org.apache.commons', 'commons-lang3', '3.5'),
                       ('com.google.guava', 'guava', '21.0'),
                       ('commons-io', 'commons-io', '2.5'),
                       ('org.apache.logging.log4j', 'log4j-api', None),
                       ('org.apache.logging.log4j', 'log4j-core', None)]
        jars = []
        for group, name, version in coordinates:
            paths = sorted((cache/group/name).glob((version or '*') + '/*/' + name + '-*.jar'))
            paths = [p for p in paths if not p.name.endswith(('-sources.jar', '-javadoc.jar'))]
            if not paths or not shutil.which('javac') or not shutil.which('java'):
                self.skipTest('Native Authlib dependency cache and JDK are required')
            jars.append(str(paths[-1]))
        source = '''
import java.net.Proxy;
import com.mojang.authlib.EnvironmentParser;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.YggdrasilSocialInteractionsService;
public class FixtureProbe {
  public static void main(String[] args) throws Exception {
    for (String host : new String[]{"auth", "account", "session", "services"})
      System.setProperty("minecraft.api." + host + ".host", args[0]);
    System.setProperty("minecraft.api.env", "SIMPLYSCREENS_CI_FIXTURE");
    YggdrasilAuthenticationService auth = new YggdrasilAuthenticationService(
      Proxy.NO_PROXY, "fixture", EnvironmentParser.getEnvironmentFromProperties().get());
    YggdrasilSocialInteractionsService service = auth.createSocialInteractionsService("SimplyScreensCiFixture");
    if (service.serversAllowed() != Boolean.parseBoolean(args[1]) || service.realmsAllowed())
      throw new AssertionError("native permission parser disagrees");
    if (service.isBlockedPlayer(java.util.UUID.randomUUID())) throw new AssertionError("blocklist");
    System.out.println("NATIVE_AUTHLIB_FIXTURE_PASS allowed=" + service.serversAllowed());
  }
}
'''
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)/'FixtureProbe.java';path.write_text(source)
            cp = os.pathsep.join(jars)
            subprocess.run(['javac', '-cp', cp, str(path)], check=True, capture_output=True, timeout=30)
            for allowed in (False, True):
                with fixture.service_fixture(allowed) as (url, requests):
                    result = subprocess.run(['java', '-cp', cp+os.pathsep+tmp, 'FixtureProbe',
                                             url, str(allowed).lower()], check=True,
                                            capture_output=True, text=True, timeout=30)
                    self.assertIn('NATIVE_AUTHLIB_FIXTURE_PASS allowed='+str(allowed).lower(), result.stdout)
                    self.assertEqual(['/privileges', '/privacy/blocklist'], requests)

    def run_fixture(self, root, *, denied=True, consume=True, unexpected_join=False, cleanup_failure=False):
        calls=[]
        def native(root, target, timeout, head, url, evidence_dir=None):
            calls.append(evidence_dir)
            data=request(url+'/privileges') if consume else None
            evidence=evidence_dir or root/'build/requested-runtime'/target
            evidence.mkdir(parents=True,exist_ok=True)
            (evidence/'client.log').write_text(live.PASS_MARKER if unexpected_join else 'fixture client log')
            (evidence/'server.log').write_text('fixture server log')
            if evidence_dir and denied:
                if cleanup_failure:raise RuntimeError('cleanup failure')
                raise live.NativeEligibilityUnavailable('fixture denial')
            (evidence/'receipt.json').write_text(json.dumps({'notEstablished':[]}))
        with patch.object(live,'run_native',side_effect=native),patch.object(live.subprocess,'check_output',return_value='exact-head\n'):
            live.run_with_service_fixture(root,'fabric-1.16.5',1)
        self.assertEqual(2,len(calls))
        return json.loads((root/'build/requested-runtime/fabric-1.16.5/receipt.json').read_text())

    def test_receipt_distinguishes_fixture_join_and_native_denial_from_real_accounts(self):
        with tempfile.TemporaryDirectory() as tmp:
            data=self.run_fixture(Path(tmp))
            self.assertEqual({'mode':'loopback-service-fixture','realAccountQualified':False,
                'nativeDenialControlPassed':True,'nativeAllowedJoinPassed':True},data['authentication'])
            self.assertIn('real-account authentication and service eligibility',data['notEstablished'])

    def test_denial_bypass_missing_service_unexpected_join_and_cleanup_failure_never_pass(self):
        for options in ({'denied':False},{'consume':False},{'unexpected_join':True},{'cleanup_failure':True}):
            with tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                with self.assertRaises(RuntimeError):self.run_fixture(root,**options)
                self.assertFalse((root/'build/requested-runtime/fabric-1.16.5/receipt.json').exists())


if __name__ == '__main__':
    unittest.main()

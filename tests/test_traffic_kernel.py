"""Verify accounting with real kernel counters and TCP transfers in isolated namespaces."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
ENGINE = (ROOT / 'backend/start-ap').read_text()
HELPERS = ENGINE[ENGINE.index('# Keep accounting in mangle'):ENGINE.index('\nteardown() {')]

@unittest.skipUnless(os.environ.get('RUN_NETFILTER_TESTS') == '1', 'requires root and network namespaces; CI runs this separately')
class KernelAccountingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if os.geteuid() != 0:
            raise RuntimeError('Run this integration test as root')
        cls.directory = tempfile.TemporaryDirectory()
        cls.path = Path(cls.directory.name)
        cls.router, cls.client, cls.server = [f'vap-{os.getpid()}-{x}' for x in ('r', 'c', 's')]
        cls.server_process = None
        cls.addClassCleanup(cls.cleanup)
        for ns in (cls.router, cls.client, cls.server):
            cls.cmd('ip', 'netns', 'add', ns)
            cls.cmd('ip', '-n', ns, 'link', 'set', 'lo', 'up')
        cls.cmd('ip', 'link', 'add', 'vap-ap', 'type', 'veth', 'peer', 'name', 'vap-client')
        cls.cmd('ip', 'link', 'set', 'vap-ap', 'netns', cls.router)
        cls.cmd('ip', 'link', 'set', 'vap-client', 'netns', cls.client)
        cls.cmd('ip', '-n', cls.router, 'link', 'set', 'vap-ap', 'name', 'ap0')
        cls.cmd('ip', 'link', 'add', 'vap-up', 'type', 'veth', 'peer', 'name', 'vap-server')
        cls.cmd('ip', 'link', 'set', 'vap-up', 'netns', cls.router)
        cls.cmd('ip', 'link', 'set', 'vap-server', 'netns', cls.server)
        for ns, iface, ip in (
            (cls.router, 'ap0', '192.168.42.1/24'),
            (cls.client, 'vap-client', '192.168.42.2/24'),
            (cls.client, 'vap-client', '192.168.42.3/24'),
            (cls.router, 'vap-up', '198.18.0.1/24'),
            (cls.server, 'vap-server', '198.18.0.2/24')
        ):
            cls.cmd('ip', '-n', ns, 'addr', 'add', ip, 'dev', iface)
            cls.cmd('ip', '-n', ns, 'link', 'set', iface, 'up')
        cls.cmd('ip', '-n', cls.client, 'route', 'add', 'default', 'via', '192.168.42.1')
        cls.cmd('ip', '-n', cls.server, 'route', 'add', '192.168.42.0/24', 'via', '198.18.0.1')
        cls.cmd('ip', 'netns', 'exec', cls.router, 'sysctl', '-qw', 'net.ipv4.ip_forward=1')
        # The helper can touch only the router's isolated mangle table.
        wrapper = cls.path / 'iptables'
        wrapper.write_text(f'#!/bin/sh\nexec ip netns exec {cls.router} iptables "$@"\n')
        wrapper.chmod(0o755)
        (cls.path / 'run').mkdir()
        harness = cls.path / 'meter.sh'
        harness.write_text('''#!/bin/sh
RUN_DIR="$TEST_DIR/run"
IPT="$TEST_DIR/iptables"
AP_IFACE=ap0
AWK=awk
FLOCK="${TEST_FLOCK:-flock}"
PRINTF=printf
TR=tr
CAT=cat
MKDIR=mkdir
RM=rm
''' + HELPERS + '\n"$@"\n')
        cls.harness = str(harness)
        (cls.path / 'payload').write_bytes(os.urandom(1024 * 1024))
        cls.server_process = subprocess.Popen(
            ['ip', 'netns', 'exec', cls.server, 'python3', '-m', 'http.server', '8765',
             '--bind', '198.18.0.2', '--directory', str(cls.path)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(60):
            probe = subprocess.run(['ip', 'netns', 'exec', cls.router, 'python3', '-c',
                'import socket; socket.create_connection(("198.18.0.2",8765),timeout=.1).close()'],
                capture_output=True)
            if probe.returncode == 0:
                break
            time.sleep(.05)
        else:
            raise RuntimeError('HTTP fixture did not start')

    @classmethod
    def cmd(cls, *args):
        result = subprocess.run(args, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(f"{args!r} failed: {result.stderr}\n{result.stdout}")
        return result.stdout

    @classmethod
    def cleanup(cls):
        if cls.server_process:
            cls.server_process.terminate()
            cls.server_process.wait(timeout=5)
        for ns in (cls.client, cls.server, cls.router):
            subprocess.run(['ip', 'netns', 'del', ns], capture_output=True)
        cls.directory.cleanup()

    def meter(self, *args, check=True):
        shell = shlex.split(os.environ.get('TEST_SHELL', 'sh'))
        result = subprocess.run([*shell, self.harness, *args], env=dict(os.environ, TEST_DIR=str(self.path)),
            capture_output=True, text=True)
        if check and result.returncode:
            self.fail(f"Meter {args!r} failed: {result.stderr}\n{result.stdout}")
        return result

    def read_counters(self):
        return {(direction, mac): int(value) for direction, mac, value in
                (line.split() for line in self.meter('traffic_dump').stdout.splitlines())}

    def saved_rules(self):
        output = self.cmd('ip', 'netns', 'exec', self.router, 'iptables-save')
        return '\n'.join(line for line in output.splitlines() if not line.startswith('#'))

    def transfer(self, source):
        script = '''import socket
s=socket.create_connection(("198.18.0.2",8765),timeout=5,source_address=(SOURCE,0))
s.sendall(b"GET /payload HTTP/1.0\\r\\nHost: test\\r\\n\\r\\n")
n=0
while True:
 b=s.recv(65536)
 if not b: break
 n+=len(b)
s.close()
assert n>=1048576,n
'''.replace('SOURCE', repr(source))
        self.cmd('ip', 'netns', 'exec', self.client, 'python3', '-c', script)
        time.sleep(.1)

    def test_real_transfers_identity_reuse_and_scoped_cleanup(self):
        first, second, replacement = 'aa:bb:cc:dd:ee:01', 'aa:bb:cc:dd:ee:02', 'aa:bb:cc:dd:ee:03'
        self.meter('traffic_init')
        for _ in range(2):
            self.meter('traffic_bind', first, '192.168.42.2')
            self.meter('traffic_bind', second, '192.168.42.3')
        initial = self.read_counters()
        self.assertTrue(all(value == 0 for value in initial.values()))
        self.transfer('192.168.42.2')
        one = self.read_counters()
        self.assertGreater(one['rx', 'aabbccddee01'], 1048576)
        self.assertLess(one['rx', 'aabbccddee01'], 1258291)  # detects duplicate accounting rules
        self.assertGreater(one['tx', 'aabbccddee01'], 0)
        self.assertEqual(one['rx', 'aabbccddee02'], 0)
        self.transfer('192.168.42.3')
        two = self.read_counters()
        self.assertGreater(two['rx', 'aabbccddee02'], 1048576)
        self.assertLess(two['rx', 'aabbccddee01'] - one['rx', 'aabbccddee01'], 10000)
        self.meter('traffic_bind', replacement, '192.168.42.2')
        self.meter('traffic_unbind', first, '192.168.42.2')  # stale DHCP deletion must not unbind the new owner
        self.transfer('192.168.42.2')
        three = self.read_counters()
        self.assertGreater(three['rx', 'aabbccddee03'], 1048576)
        self.assertEqual(three['rx', 'aabbccddee01'], two['rx', 'aabbccddee01'])
        before = self.saved_rules()
        for mac, ip in [('00:00:00:00:00:00', '192.168.42.4'), ('ff:ff:ff:ff:ff:ff', '192.168.42.4'),
                        (first, '192.168.42.4;touch /tmp/injected'), (first, '../../not-an-ip')]:
            self.assertNotEqual(self.meter('traffic_bind', mac, ip, check=False).returncode, 0)
        self.assertEqual(before, self.saved_rules())
        self.cmd('ip', 'netns', 'exec', self.router, 'iptables', '-t', 'mangle', '-N', 'keep_other_app')
        self.meter('traffic_clear')
        remaining = self.cmd('ip', 'netns', 'exec', self.router, 'iptables-save')
        self.assertNotIn('vap_traffic_', remaining)
        self.assertNotIn('vap_tx_', remaining)
        self.assertNotIn('vap_rx_', remaining)
        self.assertIn('keep_other_app', remaining)
        self.assertFalse((self.path / 'run/traffic').exists())

if __name__ == '__main__':
    unittest.main()

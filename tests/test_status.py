"""Exercise the real status command with radio and root commands replaced by fixtures."""
from pathlib import Path
import os
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
STATUS = (ROOT / 'backend/start-ap').read_text().split('cmd_status() {', 1)[1].split('\ncmd_leases()', 1)[0]

class StatusTests(unittest.TestCase):
    def run_status(self, *, running=True, bridged=False, empty=False):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            if running:
                (path / 'state').write_text('mode=bridged\ncontainer=openwrt\n' if bridged else 'ssid=Test\n')
                (path / 'hostapd.pid').write_text('42')
                (path / 'dnsmasq.pid').write_text('43')
                (path / 'dnsmasq.leases').write_text('0 aa:bb:cc:dd:ee:01 192.168.42.2 samsung *\n')
            (path / 'stations').write_text('' if empty else 'aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=1024\ntx_bytes=2048\n')
            iw = path / 'iw'
            iw.write_text('#!/bin/sh\ncase "$3" in\n info) echo "width: 20 MHz" ;;\n station) printf "Station aa:bb:cc:dd:ee:01 (on ap0)\\nrx bytes: 1024\\ntx bytes: 2048\\n" ;;\nesac\n')
            iw.chmod(0o755)
            harness = '''RUN_DIR="$TEST_DIR"
STATE_FILE="$TEST_DIR/state"
AP_IFACE=ap0
IP_GW=192.168.42.1
AWK=awk
TR=tr
GREP=grep
CAT=cat
KILL=true
ECHO=echo
PRINTF=printf
SED=sed
HEAD=head
IW="$TEST_DIR/iw"
hostapd_cli() { cat "$TEST_DIR/stations"; }
traffic_init() { return 1; }
traffic_dump() { :; }

ds_run() { printf '0 aa:bb:cc:dd:ee:01 192.168.42.2 iPhone *\\n'; }
cmd_status() {''' + STATUS + '\ncmd_status\n'
            env = dict(os.environ, TEST_DIR=directory)
            return subprocess.run(['sh'], input=harness, text=True, env=env, capture_output=True, check=True).stdout

    def test_routed_contains_counters_and_local_hostname(self):
        output = self.run_status()
        self.assertIn('clients=1\n', output)
        self.assertIn('[stations]\naa:bb:cc:dd:ee:01', output)
        self.assertIn('[connected]\naa:bb:cc:dd:ee:01\n', output)
        self.assertIn('rx_bytes=1024', output)
        self.assertIn('Station aa:bb:cc:dd:ee:01', output)
        self.assertIn('samsung *', output)
        self.assertTrue(output.endswith('[end_clients]\n'))

    def test_bridged_reads_container_leases(self):
        output = self.run_status(bridged=True)
        self.assertIn('mode=bridged', output)
        self.assertIn('iPhone *', output)
        self.assertNotIn('samsung', output)

    def test_stopped_does_not_emit_stale_clients(self):
        output = self.run_status(running=False)
        self.assertIn('running=0\n', output)
        self.assertNotIn('[stations]', output)

    def test_empty_hostapd_list_does_not_count_driver_ghost(self):
        output = self.run_status(empty=True)
        self.assertIn('clients=0\n', output)
        self.assertIn('[connected]\n\n[driver]', output)
        self.assertIn('Station aa:bb:cc:dd:ee:01', output)

if __name__ == '__main__':
    unittest.main()

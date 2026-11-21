"""Run unchanged Android lint with bounded diagnostics on GitHub Linux runners."""
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

module = sys.argv[1]
if module not in ('app', 'core', 'web', 'download', 'player', 'ai'):
    raise ValueError('Unknown module')
out = Path('build/lint-diagnostics') / module
out.mkdir(parents=True, exist_ok=True)
command = ['./gradlew', f':{module}:lintDebug', '--stacktrace']
process = subprocess.Popen(command, start_new_session=True)
started = time.monotonic()
next_dump = 180
while process.poll() is None:
    elapsed = time.monotonic() - started
    if elapsed >= next_dump:
        stamp = int(elapsed)
        listing = subprocess.run(['jcmd', '-l'], capture_output=True, text=True, timeout=20)
        (out / f'{stamp}-jvms.txt').write_text(listing.stdout, encoding='utf-8')
        for line in listing.stdout.splitlines():
            if 'GradleDaemon' not in line and 'GradleWorkerMain' not in line:
                continue
            pid = line.split()[0]
            for diagnostic in ('Thread.print', 'GC.heap_info'):
                try:
                    result = subprocess.run(['jcmd', pid, diagnostic], capture_output=True, text=True, timeout=30)
                    (out / f'{stamp}-{pid}-{diagnostic}.txt').write_text(result.stdout + result.stderr, encoding='utf-8')
                except subprocess.TimeoutExpired:
                    print(f'JVM diagnostic timed out: {pid} {diagnostic}', flush=True)
        subprocess.run(['ps', '-eo', 'pid,pcpu,pmem,etime,args'], stdout=(out / f'{stamp}-processes.txt').open('w'), check=False)
        print(f'Lint {module} still running after {stamp}s; JVM snapshots captured.', flush=True)
        next_dump += 180
    if elapsed > 900:
        print('Lint exceeded the diagnostic 15-minute budget; preserving thread stacks, no checks bypassed.', flush=True)
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
        sys.exit(124)
    time.sleep(5)
sys.exit(process.returncode)

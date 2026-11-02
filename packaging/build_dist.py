# This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
# This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
"""Builds dist/wse-overlay-studio-<version>/: the Windows setup program, the Windows zip, the Linux .deb and tarball,
the docs, and SHA256SUMS over all of them.

    python packaging/build_dist.py [--skip-tests] [--skip-exe] [--maintainer NAME]

Needs JDK 21 (jpackage) on the PATH and WiX 3.14.1 in tools/wix/3.14.1 for the setup program. The .deb is assembled
here (ar, tar), so dpkg-deb is not needed. Archive entries carry a fixed time, so the zip, tarball and .deb come out
byte for byte the same from the same inputs; the setup program does not.
"""
import argparse
import glob
import gzip
import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, 'packaging')
FIXED_TIME = 1767225600  # 2026-01-01 00:00 UTC
APP_NAME = 'Wowza Overlay Studio'
# java.net.http fetches Chrome for Testing over TLS, whose handshakes need jdk.crypto.ec on Java 21; without it
# every download fails with handshake_failure. jdeps cannot see that, so it is named here with the rest of what the
# setup window needs; every other module the installer uses comes from jdeps on its jar. 1.15.0's first
# build missed java.management this way, and the setup window and overlaystudio-cli failed at start.
RUNTIME_MODULES = 'java.base,java.desktop,java.net.http,jdk.crypto.ec'
# The same runtime is copied next to the capture service on Windows, so it also carries every module the service and
# any libraries it has need, as jdeps lists them from the payload the installer jar was built with.
CAPTURE_PAYLOAD = os.path.join(ROOT, 'installer', 'build', 'payload', 'payload', 'capture')
# Stays the same across versions, so a new setup program upgrades. New in 2.0: 1.x's setup program is a different
# product, detected by main.wxs and refused rather than upgraded.
UPGRADE_UUID = 'e0ebc59b-04ad-4a61-a559-7b8ac9f444a8'
DEB_NAME = 'wse-overlay-studio'
DEB_PREFIX = 'opt/wse-overlay-studio'
# Maintainer field of the .deb (--maintainer). The package is assembled here, not by dpkg-deb, so nothing requires an
# address in it; Debian policy and lintian expect "Name <address>".
DEB_MAINTAINER = 'Wowza Media Systems'
# Chrome stable's Depends (Google's apt index, 156.0.8078.73-1) without the ones a server has or Chrome for Testing
# does not use; libasound2 was renamed libasound2t64 on newer Debian and Ubuntu
DEB_RECOMMENDS = ('ca-certificates, fontconfig, fonts-liberation, libasound2 | libasound2t64, libatk-bridge2.0-0, '
                  'libatk1.0-0, libatspi2.0-0, libcairo2, libcups2, libdbus-1-3, libexpat1, libgbm1, libglib2.0-0, '
                  'libgtk-3-0 | libgtk-4-1, libnspr4, libnss3, libpango-1.0-0, libudev1, libvulkan1, libx11-6, '
                  'libxcb1, libxcomposite1, libxdamage1, libxext6, libxfixes3, libxkbcommon0, libxrandr2')


def run(cmd, **kw):
    print('>', ' '.join(cmd), flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True, **kw)


def remove(path):
    """Deletes a tree, including the read-only files a linked Java runtime has."""
    def retry(func, p, _exc):
        os.chmod(p, 0o700)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(path, onexc=retry)


def version():
    for line in open(os.path.join(ROOT, 'gradle.properties'), encoding='utf-8'):
        if line.strip().startswith('version'):
            return line.split('=', 1)[1].strip()
    raise SystemExit('No version in gradle.properties')


def crlf(data):
    return data.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n')


def lf(data):
    return data.replace(b'\r\n', b'\n')


def read(path):
    with open(path, 'rb') as f:
        return f.read()


# The documents README.md links to, which every package carries at the same paths
DOCS = ['docs/BUILD.md', 'docs/USAGE.md', 'docs/DESIGN.md']


def docs():
    """Files every package carries, as (archive path, bytes). Fails when a document README.md links to is missing."""
    missing = [d for d in DOCS + ['docs/images'] if not os.path.exists(os.path.join(ROOT, d))]
    if missing:
        raise SystemExit('Missing release documents (README.md links to them): ' + ', '.join(missing))
    images = os.path.join(ROOT, 'docs', 'images')
    out = [(d, read(os.path.join(ROOT, d))) for d in DOCS]
    for name in sorted(os.listdir(images)):
        if os.path.isfile(os.path.join(images, name)):
            out.append(('docs/images/' + name, read(os.path.join(images, name))))
    out += [('README.md', read(os.path.join(ROOT, 'README.md'))),
           ('THIRD-PARTY-NOTICES.md', read(os.path.join(ROOT, 'THIRD-PARTY-NOTICES.md'))),
           ('LICENSE.txt', read(os.path.join(ROOT, 'LICENSE.txt'))),
           ('licenses/apache-2.0.txt', read(os.path.join(PKG, 'licenses', 'apache-2.0.txt')))]
    return out


def write_zip(path, entries):
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data, _mode in entries:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)


def tar_bytes(entries, dirs=()):
    """A gzipped tar of (path, bytes, mode), with the given directories first; root:root, fixed time."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', mtime=FIXED_TIME) as gz:
        with tarfile.open(fileobj=gz, mode='w', format=tarfile.GNU_FORMAT) as t:
            for d in dirs:
                ti = tarfile.TarInfo(d)
                ti.type, ti.mode, ti.mtime, ti.uname, ti.gname = tarfile.DIRTYPE, 0o755, FIXED_TIME, 'root', 'root'
                t.addfile(ti)
            for name, data, mode in entries:
                ti = tarfile.TarInfo(name)
                ti.size, ti.mode, ti.mtime, ti.uname, ti.gname = len(data), mode, FIXED_TIME, 'root', 'root'
                t.addfile(ti, io.BytesIO(data))
    return buf.getvalue()


def parents(paths):
    out = []
    for p in paths:
        parts = p.strip('./').split('/')[:-1]
        for k in range(1, len(parts) + 1):
            d = './' + '/'.join(parts[:k]) + '/'
            if d not in out:
                out.append(d)
    return sorted(out)


def ar(path, members):
    """A Debian package is an ar archive: debian-binary, control.tar.gz, data.tar.gz, in that order."""
    with open(path, 'wb') as f:
        f.write(b'!<arch>\n')
        for name, data in members:
            header = (name.ljust(16) + str(FIXED_TIME).ljust(12) + '0'.ljust(6) + '0'.ljust(6) + '100644'.ljust(8)
                      + str(len(data)).ljust(10) + '`\n')
            f.write(header.encode('ascii'))
            f.write(data)
            if len(data) % 2:
                f.write(b'\n')


def linux_files(jar_name, jar):
    lx = os.path.join(PKG, 'linux')
    return [(jar_name, jar, 0o644),
            ('install.sh', lf(read(os.path.join(lx, 'install.sh'))), 0o755),
            ('uninstall.sh', lf(read(os.path.join(lx, 'uninstall.sh'))), 0o755),
            ('update-chrome.sh', lf(read(os.path.join(lx, 'update-chrome.sh'))), 0o755),
            ('find-java.sh', lf(read(os.path.join(lx, 'find-java.sh'))), 0o644)] + \
           [(n, lf(d) if n.endswith(('.md', '.txt')) else d, 0o644) for n, d in docs()]


def deb_control(template, package, ver, maintainer, size_kb):
    """The control file from packaging/linux/deb/control, without the rename check's 1x-compat marker lines."""
    lines = [l for l in template.replace('\r\n', '\n').split('\n') if not l.startswith('# 1x-compat:')]
    return '\n'.join(lines).format(package=package, version=ver, maintainer=maintainer, size_kb=size_kb,
                                   recommends=DEB_RECOMMENDS).encode('utf-8')


def build_deb(path, ver, jar_name, jar, maintainer):
    data_entries = [('./' + DEB_PREFIX + '/' + n, d, m) for n, d, m in linux_files(jar_name, jar)]
    data_entries.append(('./usr/share/doc/' + DEB_NAME + '/copyright',
                         lf(read(os.path.join(ROOT, 'THIRD-PARTY-NOTICES.md'))), 0o644))
    data = tar_bytes(data_entries, parents([e[0] for e in data_entries]))
    size_kb = sum(len(d) for _n, d, _m in data_entries) // 1024 + 1
    md5 = ''.join('%s  %s\n' % (hashlib.md5(d).hexdigest(), n[2:]) for n, d, _m in data_entries).encode('utf-8')
    deb = os.path.join(PKG, 'linux', 'deb')
    control = deb_control(read(os.path.join(deb, 'control')).decode('utf-8'), DEB_NAME, ver, maintainer, size_kb)
    ctrl = tar_bytes([('./control', control, 0o644), ('./md5sums', md5, 0o644),
                      ('./preinst', lf(read(os.path.join(deb, 'preinst'))), 0o755),
                      ('./postinst', lf(read(os.path.join(deb, 'postinst'))), 0o755),
                      ('./prerm', lf(read(os.path.join(deb, 'prerm'))), 0o755)], ['./'])
    ar(path, [('debian-binary', b'2.0\n'), ('control.tar.gz', ctrl), ('data.tar.gz', data)])


def jdeps_modules(what, jars, libs=()):
    """The JDK modules these jars and their libraries use, from jdeps."""
    # jdeps refuses an empty --class-path, and the capture service has had no libraries since 1.15.0
    cp = ['--class-path', os.pathsep.join(libs)] if libs else []
    r = subprocess.run(['jdeps', '--print-module-deps', '--ignore-missing-deps', '--multi-release', '21'] + cp + list(jars) + list(libs),
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit('jdeps could not list the modules ' + what + ' needs:\n' + r.stdout + r.stderr)
    # jdeps prints split-package warnings first; the module list is its last line
    lines = [l for l in r.stdout.splitlines() if l.strip() and not l.startswith('Warning:')]
    if not lines:
        raise SystemExit('jdeps printed no module list for ' + what)
    return {m for m in lines[-1].strip().split(',') if m}


def capture_modules():
    jars = sorted(glob.glob(os.path.join(CAPTURE_PAYLOAD, '*.jar')))
    if not jars:
        raise SystemExit('The installer payload has no capture service in ' + CAPTURE_PAYLOAD)
    return jdeps_modules('the capture service', jars, sorted(glob.glob(os.path.join(CAPTURE_PAYLOAD, 'lib', '*.jar'))))


def runtime_modules(jar_path):
    return ','.join(sorted(set(RUNTIME_MODULES.split(',')) | capture_modules() | jdeps_modules('the installer', [jar_path])))


def check_runtime(runtime, modules):
    """Fails the build when the linked runtime lacks a module the installer needs."""
    java = os.path.join(runtime, 'bin', 'java.exe')
    listed = subprocess.run([java, '--list-modules'], capture_output=True, text=True, check=True).stdout
    have = {line.split('@')[0] for line in listed.split()}
    missing = set(modules.split(',')) - have
    if missing:
        raise SystemExit('The setup program runtime lacks ' + ', '.join(sorted(missing)))
    print('runtime modules:', ', '.join(sorted(have)))


def build_exe(out_dir, ver, jar_path):
    stage = os.path.join(ROOT, 'build', 'pkg')
    modules = runtime_modules(jar_path)
    for d in ('input', 'temp', 'out'):
        remove(os.path.join(stage, d))
    os.makedirs(os.path.join(stage, 'input', 'licenses'))
    shutil.copy2(jar_path, os.path.join(stage, 'input'))
    # the license and the notices, in the program folder's app\ next to the installer jar, as in every other package
    for n in ('README.md', 'THIRD-PARTY-NOTICES.md', 'LICENSE.txt'):
        shutil.copy2(os.path.join(ROOT, n), os.path.join(stage, 'input', n))
    shutil.copy2(os.path.join(PKG, 'licenses', 'apache-2.0.txt'), os.path.join(stage, 'input', 'licenses'))
    # three launchers: the setup window (the Start menu entry, and what the setup program opens when it finishes), the
    # removal window, and a console one for scripts and for the setup program's own removal step (main.wxs)
    launchers = {'Uninstall Overlay Studio': 'arguments=Uninstall --gui\nwin-console=false\nwin-menu=true\n',
                 'overlaystudio-cli': 'win-console=true\nwin-menu=false\n'}
    extra = []
    for name, text in launchers.items():
        props = os.path.join(stage, name.replace(' ', '-') + '.properties')
        with open(props, 'w', encoding='utf-8') as f:
            f.write(text)
        extra += ['--add-launcher', name + '=' + props]
    wix = os.path.join(ROOT, 'tools', 'wix', '3.14.1')
    if not os.path.isfile(os.path.join(wix, 'candle.exe')):
        raise SystemExit('WiX 3.14.1 is missing from ' + wix + ' (wix314-binaries.zip from the wix3141rtm release)')
    env = dict(os.environ, PATH=wix + os.pathsep + os.environ.get('PATH', ''))
    run(['jpackage', '--type', 'exe', '--name', APP_NAME, '--app-version', ver,
         '--vendor', 'Wowza Media Systems, LLC', '--description', 'Overlay Studio for Wowza Streaming Engine',
         '--input', os.path.join(stage, 'input'), '--main-jar', os.path.basename(jar_path),
         '--main-class', 'com.wowza.wms.plugin.overlaystudio.install.Main',
         '--add-modules', modules, '--jlink-options', '--strip-debug --no-man-pages --no-header-files',
         '--arguments', '--gui', '--win-menu', '--win-menu-group', APP_NAME] + extra + ['--win-upgrade-uuid', UPGRADE_UUID,
         '--resource-dir', os.path.join(PKG, 'windows', 'resources'),
         '--temp', os.path.join(stage, 'temp'), '--dest', os.path.join(stage, 'out')], env=env)
    check_runtime(os.path.join(stage, 'temp', 'images', 'win-msi.image', APP_NAME, 'runtime'), modules)
    # no spaces: GitHub turns them into dots in a release file's name, and SHA256SUMS would no longer match it
    exe = '%s-%s.exe' % (APP_NAME.replace(' ', '-'), ver)
    shutil.copy2(os.path.join(stage, 'out', '%s-%s.exe' % (APP_NAME, ver)), os.path.join(out_dir, exe))
    return exe


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--skip-tests', action='store_true', help='build without running the installer and module tests')
    ap.add_argument('--skip-exe', action='store_true', help='no Windows setup program (no jpackage or WiX needed)')
    ap.add_argument('--maintainer', default=DEB_MAINTAINER, help='Maintainer field of the .deb (default: %(default)s)')
    a = ap.parse_args()
    ver = version()
    gradlew = os.path.join(ROOT, 'gradlew.bat' if os.name == 'nt' else 'gradlew')
    run([gradlew, '--no-daemon', '-q', ':installer:jar'] + ([] if a.skip_tests else [':module:test', ':installer:test', ':capture:test', ':bridge:test']))
    jar_name = 'wse-plugin-overlay-studio-installer-%s.jar' % ver
    jar_path = os.path.join(ROOT, 'installer', 'build', 'libs', jar_name)
    jar = read(jar_path)

    out = os.path.join(ROOT, 'dist', 'wse-overlay-studio-' + ver)
    remove(out)
    os.makedirs(out)
    made = []
    if not a.skip_exe:
        made.append(build_exe(out, ver, jar_path))

    win = os.path.join(PKG, 'windows')
    win_entries = [(jar_name, jar, 0o644)] + \
                  [(n, crlf(read(os.path.join(win, n))), 0o644) for n in ('install.cmd', 'uninstall.cmd', 'update-chrome.cmd', 'find-java.cmd')] + \
                  [(n, d, 0o644) for n, d in docs()]
    top = 'wse-overlay-studio-%s/' % ver
    name = 'wse-overlay-studio-%s-windows.zip' % ver
    write_zip(os.path.join(out, name), [(top + n, d, m) for n, d, m in win_entries])
    made.append(name)

    name = 'wse-overlay-studio-%s-linux.tar.gz' % ver
    files = [(top + n, d, m) for n, d, m in linux_files(jar_name, jar)]
    with open(os.path.join(out, name), 'wb') as f:
        f.write(tar_bytes(files, [top] + sorted({top + 'licenses/', top + 'docs/', top + 'docs/images/'})))
    made.append(name)

    name = '%s_%s_all.deb' % (DEB_NAME, ver)
    build_deb(os.path.join(out, name), ver, jar_name, jar, a.maintainer)
    made.append(name)

    # the packages a release carries; README.md and THIRD-PARTY-NOTICES.md sit beside them but are in every package
    for n in ('README.md', 'THIRD-PARTY-NOTICES.md'):
        shutil.copy2(os.path.join(ROOT, n), os.path.join(out, n))

    sums = ''.join('%s *%s\n' % (hashlib.sha256(read(os.path.join(out, n))).hexdigest(), n) for n in sorted(made))
    with open(os.path.join(out, 'SHA256SUMS'), 'w', encoding='utf-8', newline='\n') as f:
        f.write(sums)
    print(sums, end='')
    print('Built', out)


if __name__ == '__main__':
    sys.exit(main())

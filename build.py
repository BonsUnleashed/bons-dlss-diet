"""Build Bons DLSS Diet without Gradle: javac against the jars of an installed Forge 1.20.1 client, then a plain jar.

  py build.py [--minecraft DIR] [--forge 1.20.1-47.4.16] [--jdk DIR]

--minecraft  the launcher's game directory (default %APPDATA%\\.minecraft); its libraries/ folder supplies Forge,
             Minecraft (SRG names, which only the Forge event signatures need) and the libraries on the classpath
--jdk        a JDK's bin folder (default: javac on the PATH); JDK 17 or newer
Output: dist/bons_dlss_diet-<version>.jar and build/manifest.json (SHA-256 of the jar, list of entries).
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION = '1.0.0'
MOD_ID = 'bons_dlss_diet'
DISPLAY = 'Bons DLSS Diet'
EPOCH = (2026, 10, 15, 0, 0, 0)
MC_SRG = '1.20.1-20230612.114412'


def mods_toml():
    return f'''modLoader="javafml"
loaderVersion="[47,)"
license="MIT"
issueTrackerURL="https://github.com/BonsUnleashed/bons-dlss-diet/issues"
[[mods]]
modId="{MOD_ID}"
version="{VERSION}"
displayName="{DISPLAY}"
authors="BonsUnleashed"
displayURL="https://github.com/BonsUnleashed/bons-dlss-diet"
displayTest="IGNORE_ALL_VERSION"
side="CLIENT"
clientSideOnly=true
description=\'\'\'NVIDIA DLSS for Complementary Unbound shaders (with Euphoria Patches) through the Super Resolution mod. Writes "<your pack> + DLSS" next to the pack you already have; pick it in the shader menu. About a third more frames per second at the same detail. No mixins and nothing changed in other mods: it only writes a shader pack folder. Client only.\'\'\'
[[dependencies.{MOD_ID}]]
modId="minecraft"
mandatory=true
versionRange="[1.20.1,1.20.2)"
ordering="NONE"
side="CLIENT"
[[dependencies.{MOD_ID}]]
modId="forge"
mandatory=true
versionRange="[47.4,)"
ordering="NONE"
side="CLIENT"
# the Euphoria patcher writes the pack this mod builds on: construct after it
[[dependencies.{MOD_ID}]]
modId="euphoria_patcher"
mandatory=false
versionRange="*"
ordering="AFTER"
side="CLIENT"
[[dependencies.{MOD_ID}]]
modId="oculus"
mandatory=false
versionRange="[1.8.0,)"
ordering="AFTER"
side="CLIENT"
[[dependencies.{MOD_ID}]]
modId="super_resolution"
mandatory=false
versionRange="[0.9.2,)"
ordering="NONE"
side="CLIENT"
'''


def classpath(libraries: Path, forge: str):
    first = [libraries / f'net/minecraftforge/forge/{forge}/forge-{forge}-client.jar',
             libraries / f'net/minecraft/client/{MC_SRG}/client-{MC_SRG}-srg.jar',
             libraries / f'net/minecraftforge/forge/{forge}/forge-{forge}-universal.jar']
    for p in first:
        if not p.is_file():
            raise SystemExit(f'missing {p}: install Forge {forge} in that launcher first')
    skip = ('net/minecraft/server/', f'net/minecraftforge/forge/{forge}/forge-{forge}-server.jar')
    rest = [p for p in sorted(libraries.rglob('*.jar'))
            if not p.relative_to(libraries).as_posix().startswith(skip) and p not in first]
    return first + rest


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--minecraft', default=os.path.join(os.environ.get('APPDATA', ''), '.minecraft'))
    ap.add_argument('--forge', default='1.20.1-47.4.16')
    ap.add_argument('--jdk', default='')
    a = ap.parse_args()
    javac = str(Path(a.jdk) / 'javac') if a.jdk else shutil.which('javac')
    if not javac:
        raise SystemExit('javac not found: install a JDK 17+ or pass --jdk')
    classes = HERE / 'build/classes'
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = sorted((HERE / 'src').rglob('*.java'))
    cp = classpath(Path(a.minecraft) / 'libraries', a.forge)
    args = ['--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all,-processing,-classfile,-options',
            '-cp', os.pathsep.join(map(str, cp)), '-d', str(classes), *map(str, sources)]
    argfile = HERE / 'build/javac.args'
    argfile.write_text('\n'.join('"' + x.replace('\\', '/') + '"' for x in args), encoding='utf-8')
    r = subprocess.run([javac, '@' + str(argfile)], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stdout + r.stderr)
    if r.stderr.strip():
        print(r.stderr.strip())
    payload = {p.relative_to(classes).as_posix(): p.read_bytes() for p in sorted(classes.rglob('*.class'))}
    payload['META-INF/mods.toml'] = mods_toml().encode('utf-8')
    payload['META-INF/MANIFEST.MF'] = b'Manifest-Version: 1.0\r\n\r\n'
    payload['pack.mcmeta'] = (json.dumps({'pack': {'pack_format': 15, 'description': DISPLAY}}) + '\n').encode()
    payload['LICENSE'] = (HERE / 'LICENSE').read_bytes()
    dest = HERE / f'dist/{MOD_ID}-{VERSION}.jar'
    dest.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(payload.items()):
            info = zipfile.ZipInfo(name, EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    manifest = {'artifact': dest.name, 'version': VERSION, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(),
                'entries': sorted(payload)}
    (HERE / 'build/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(dest, manifest['sha256'], len(payload), 'entries')


if __name__ == '__main__':
    main()

"""Build Bons DLSS Diet from source without Gradle.

  py build.py deps                       fetch NVIDIA's DLSS SDK files and the Khronos Vulkan headers at pinned commits
                                         into third_party/ (every file checked against its Git blob id or SHA-256)
  py build.py native [--jdk HOME]        compile native/bons_dlss.cpp with MSVC into build/native/bons_dlss_diet.dll
  py build.py jar [--minecraft DIR] [--forge 1.20.1-47.4.16] [--oculus JAR] [--jdk HOME]
                                         compile src/ against an installed Forge client and package the jar
  py build.py all [same options]         the three steps in order

--minecraft  the launcher's game directory (default %APPDATA%\\.minecraft): its libraries/ folder supplies Forge,
             Minecraft (SRG names), LWJGL and the other libraries on the classpath
--oculus     the Oculus 1.8.0 jar the mixins are compiled against (default <minecraft>/mods/oculus-mc1.20.1-1.8.0.jar)
--jdk        a JDK 17+ home folder (default: javac on the PATH; the native step needs its include/ folder for jni.h)
MSVC: run from an "x64 Native Tools Command Prompt for VS", or the script finds vcvars64.bat through vswhere.
Output: dist/bons_dlss_diet-<version>.jar and build/manifest.json (SHA-256 of the jar and of each part).
NVIDIA's files are fetched at build time and are not part of this repository; see NOTICE.md.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERSION = '1.1.0'
MOD_ID = 'bons_dlss_diet'
DISPLAY = 'Bons DLSS Diet'
EPOCH = (2026, 10, 16, 0, 0, 0)
MC_SRG = '1.20.1-20230612.114412'
LWJGL = '3.3.1'
THIRD = HERE / 'third_party'

NVIDIA_REPO = 'NVIDIA/DLSS'
NVIDIA_COMMIT = '374959484e79a640feaba44c93ac8cfb0a03f5b5'
NVIDIA_FILES = {  # path in the SDK -> SHA-256 (headers are checked against their Git blob ids from the tree instead)
    'lib/Windows_x86_64/x64/nvsdk_ngx_s.lib': '4e5d355086d2bc11e1a0842457d2519ea528ee1f3e112c45679a84960c07dff3',
    'lib/Windows_x86_64/rel/nvngx_dlss.dll': '3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983',
    'LICENSE.txt': None,
}
VULKAN_REPO = 'KhronosGroup/Vulkan-Headers'
VULKAN_COMMIT = 'b5c8f996196ba4aa6d8f97e52b5d3b6e70f7e4e2'  # tag vulkan-sdk-1.4.341.0
VULKAN_SKIP = ('vulkan_android', 'vulkan_beta', 'vulkan_directfb', 'vulkan_fuchsia', 'vulkan_ggp', 'vulkan_ios',
               'vulkan_macos', 'vulkan_metal', 'vulkan_ohos', 'vulkan_screen', 'vulkan_vi', 'vulkan_wayland', 'vulkan_xcb',
               'vulkan_xlib', 'vulkan_xlib_xrandr')


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'bons-dlss-diet-build'})
    with urllib.request.urlopen(req, timeout=180) as r:
        return r.read()


def git_blob(data):
    return hashlib.sha1(b'blob %d\0' % len(data) + data).hexdigest()


def tree(repo, commit):
    t = json.loads(fetch(f'https://api.github.com/repos/{repo}/git/trees/{commit}?recursive=1'))
    if t.get('truncated'):
        raise SystemExit(f'{repo}: tree listing truncated')
    return {i['path']: i['sha'] for i in t['tree'] if i['type'] == 'blob'}


def get(repo, commit, path, blob, dest):
    data = fetch(f'https://raw.githubusercontent.com/{repo}/{commit}/{path}')
    if git_blob(data) != blob:
        raise SystemExit(f'{repo}/{path}: content differs from the pinned commit')
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return data


def deps(_args):
    nv = tree(NVIDIA_REPO, NVIDIA_COMMIT)
    wanted = [p for p in nv if p.startswith('include/') and p.endswith('.h')] + list(NVIDIA_FILES)
    for path in wanted:
        data = get(NVIDIA_REPO, NVIDIA_COMMIT, path, nv[path], THIRD / 'nvidia' / path)
        want = NVIDIA_FILES.get(path)
        if want and hashlib.sha256(data).hexdigest() != want:
            raise SystemExit(f'NVIDIA {path}: SHA-256 differs from the pinned file')
    vk = tree(VULKAN_REPO, VULKAN_COMMIT)
    n = 0
    for path, blob in vk.items():
        if path.startswith(('include/vulkan/', 'include/vk_video/')) and path.endswith('.h') and Path(path).stem not in VULKAN_SKIP:
            get(VULKAN_REPO, VULKAN_COMMIT, path, blob, THIRD / 'vulkan' / path)
            n += 1
    print(f'third_party/: {len(wanted)} NVIDIA files ({NVIDIA_COMMIT[:10]}), {n} Vulkan headers ({VULKAN_COMMIT[:10]})')


def msvc(cmd):
    if shutil.which('cl'):
        return subprocess.run(cmd, cwd=HERE / 'build/native')
    vswhere = Path(os.environ.get('ProgramFiles(x86)', r'C:\Program Files (x86)')) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    if not vswhere.is_file():
        raise SystemExit('MSVC not found: run from an x64 Native Tools Command Prompt or install the VS Build Tools (C++)')
    vs = subprocess.run([str(vswhere), '-latest', '-products', '*', '-requires',
                         'Microsoft.VisualStudio.Component.VC.Tools.x86.x64', '-property', 'installationPath'],
                        capture_output=True, text=True).stdout.strip()
    vcvars = Path(vs) / 'VC/Auxiliary/Build/vcvars64.bat'
    if not vcvars.is_file():
        raise SystemExit('vcvars64.bat not found: install the C++ build tools of Visual Studio')
    line = subprocess.list2cmdline(cmd)
    env = dict(os.environ, PATH=str(vswhere.parent) + os.pathsep + os.environ.get('PATH', ''))  # vcvars calls vswhere
    return subprocess.run(f'call "{vcvars}" >nul && {line}', shell=True, cwd=HERE / 'build/native', env=env)


def jdk_home(a):
    if a.jdk:
        return Path(a.jdk)
    javac = shutil.which('javac')
    if not javac:
        raise SystemExit('javac not found: install a JDK 17+ or pass --jdk')
    home = Path(javac).resolve().parent.parent
    if not (home / 'include/jni.h').is_file():
        # a launcher stub outside the JDK (Oracle's javapath folder): ask javac's own runtime where its JDK is
        r = subprocess.run([javac, '-J-XshowSettings:properties', '-version'], capture_output=True, text=True)
        for line in (r.stderr + r.stdout).splitlines():
            if line.strip().startswith('java.home ='):
                home = Path(line.split('=', 1)[1].strip())
    if not (home / 'include/jni.h').is_file():
        raise SystemExit(f'no include/jni.h under {home}: pass --jdk with the home folder of a JDK 17+')
    return home


def native(a):
    jdk = jdk_home(a)
    out = HERE / 'build/native'
    out.mkdir(parents=True, exist_ok=True)
    lib = THIRD / 'nvidia/lib/Windows_x86_64/x64/nvsdk_ngx_s.lib'
    if not lib.is_file():
        raise SystemExit('third_party/ is empty: run py build.py deps first')
    dll = out / 'bons_dlss_diet.dll'
    cmd = ['cl', '/nologo', '/Brepro', '/LD', '/MT', '/O2', '/EHsc', '/std:c++17', '/W4', '/permissive-', '/DUNICODE',
           '/D_UNICODE', '/wd4100', '/I', str(THIRD / 'vulkan/include'), '/I', str(THIRD / 'nvidia/include'),
           '/I', str(jdk / 'include'), '/I', str(jdk / 'include/win32'), str(HERE / 'native/bons_dlss.cpp'),
           '/Fe' + str(dll), '/link', '/Brepro', '/OPT:REF', '/OPT:ICF', str(lib), 'advapi32.lib', 'user32.lib',
           'gdi32.lib', 'opengl32.lib']
    if msvc(cmd).returncode or not dll.is_file():
        raise SystemExit('native build failed')
    print(dll, hashlib.sha256(dll.read_bytes()).hexdigest())


def mods_toml():
    return f'''modLoader="javafml"
loaderVersion="[47,)"
license="MIT (NVIDIA DLSS components: NVIDIA RTX SDKs license)"
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
description=\'\'\'NVIDIA DLSS for Complementary Unbound shaders (with Euphoria Patches). Writes "<your pack> + DLSS" next to the pack you already have; pick it in the shader menu. A third more frames per second at the same detail. Needs Oculus with Embeddium and an NVIDIA RTX graphics card. Client only.

Uses NVIDIA DLSS (NVIDIA DLSS SDK). NVIDIA and DLSS are trademarks of NVIDIA Corporation.\'\'\'
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
# optional dependencies carry no version floor: Forge refuses to boot when an installed optional dependency falls
# outside the range (pre-release versions sort below their release)
[[dependencies.{MOD_ID}]]
modId="oculus"
mandatory=false
versionRange="*"
ordering="AFTER"
side="CLIENT"
'''


def classpath(libraries: Path, forge: str, oculus: Path):
    first = [libraries / f'net/minecraftforge/forge/{forge}/forge-{forge}-client.jar',
             libraries / f'net/minecraft/client/{MC_SRG}/client-{MC_SRG}-srg.jar',
             libraries / f'net/minecraftforge/forge/{forge}/forge-{forge}-universal.jar', oculus,
             libraries / f'org/lwjgl/lwjgl/{LWJGL}/lwjgl-{LWJGL}.jar', libraries / f'org/lwjgl/lwjgl-glfw/{LWJGL}/lwjgl-glfw-{LWJGL}.jar']
    for p in first:
        if not p.is_file():
            raise SystemExit(f'missing {p}: install Forge {forge} (and Oculus 1.8.0) in that launcher first')
    skip = ('net/minecraft/server/', f'net/minecraftforge/forge/{forge}/forge-{forge}-server.jar', 'org/lwjgl/')
    rest = [p for p in sorted(libraries.rglob('*.jar'))
            if not p.relative_to(libraries).as_posix().startswith(skip) and p not in first]
    return first + rest


def jar(a):
    javac = str(Path(a.jdk) / 'bin/javac') if a.jdk else shutil.which('javac')
    if not javac:
        raise SystemExit('javac not found: install a JDK 17+ or pass --jdk')
    dll = HERE / 'build/native/bons_dlss_diet.dll'
    dlss = THIRD / 'nvidia/lib/Windows_x86_64/rel/nvngx_dlss.dll'
    for p in (dll, dlss):
        if not p.is_file():
            raise SystemExit(f'missing {p}: run py build.py deps and py build.py native first')
    classes = HERE / 'build/classes'
    shutil.rmtree(classes, ignore_errors=True)
    classes.mkdir(parents=True)
    sources = sorted((HERE / 'src').rglob('*.java'))
    oculus = Path(a.oculus) if a.oculus else Path(a.minecraft) / 'mods/oculus-mc1.20.1-1.8.0.jar'
    cp = classpath(Path(a.minecraft) / 'libraries', a.forge, oculus)
    args = ['--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all,-processing,-classfile,-options',
            '-cp', os.pathsep.join(map(str, cp)), '-d', str(classes), *map(str, sources)]
    argfile = HERE / 'build/javac.args'
    argfile.write_text('\n'.join('"' + x.replace('\\', '/') + '"' for x in args), encoding='utf-8')
    r = subprocess.run([javac, '@' + str(argfile)], capture_output=True, text=True)
    if r.returncode:
        raise SystemExit(r.stdout + r.stderr)
    payload = {p.relative_to(classes).as_posix(): p.read_bytes() for p in sorted(classes.rglob('*.class'))}
    payload['META-INF/mods.toml'] = mods_toml().encode('utf-8')
    payload['META-INF/MANIFEST.MF'] = (b'Manifest-Version: 1.0\r\nMixinConfigs: bons_dlss_diet.mixins.json\r\n'
                                       b'Implementation-Version: ' + VERSION.encode() + b'\r\n\r\n')
    payload['bons_dlss_diet.mixins.json'] = (HERE / 'resources/bons_dlss_diet.mixins.json').read_bytes()
    payload['pack.mcmeta'] = (json.dumps({'pack': {'pack_format': 15, 'description': DISPLAY}}) + '\n').encode()
    payload['LICENSE'] = (HERE / 'LICENSE').read_bytes()
    payload['NOTICE.txt'] = (HERE / 'NOTICE.md').read_bytes()
    payload['natives/windows-x64/bons_dlss_diet.dll'] = dll.read_bytes()
    payload['natives/windows-x64/nvngx_dlss.dll'] = dlss.read_bytes()
    payload['natives/windows-x64/NVIDIA_RTX_SDK_LICENSE.txt'] = (THIRD / 'nvidia/LICENSE.txt').read_bytes()
    dest = HERE / f'dist/{MOD_ID}-{VERSION}.jar'
    dest.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in sorted(payload.items()):
            info = zipfile.ZipInfo(name, EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    manifest = {'artifact': dest.name, 'version': VERSION, 'sha256': hashlib.sha256(dest.read_bytes()).hexdigest(),
                'native_sha256': hashlib.sha256(dll.read_bytes()).hexdigest(), 'entries': sorted(payload)}
    (HERE / 'build/manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(dest, manifest['sha256'], len(payload), 'entries')


def main():
    ap = argparse.ArgumentParser(description='Build Bons DLSS Diet (see the docstring).')
    ap.add_argument('step', choices=('deps', 'native', 'jar', 'all'))
    ap.add_argument('--minecraft', default=os.path.join(os.environ.get('APPDATA', ''), '.minecraft'))
    ap.add_argument('--forge', default='1.20.1-47.4.16')
    ap.add_argument('--oculus', default='')
    ap.add_argument('--jdk', default='')
    a = ap.parse_args()
    for step in (('deps', 'native', 'jar') if a.step == 'all' else (a.step,)):
        {'deps': deps, 'native': native, 'jar': jar}[step](a)


if __name__ == '__main__':
    sys.exit(main())

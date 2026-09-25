#!/usr/bin/env python3
"""Test the release APK's Android HUD on an ADB device, using only synthetic notification data.

Run scripts/build.py first. Does not install apps, alter permissions, or interact with the phone UI.
"""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, uuid, zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk',type=Path,required=True)
    parser.add_argument('--jdk',type=Path,required=True)
    parser.add_argument('--adb',type=Path,required=True)
    parser.add_argument('--serial',required=True)
    parser.add_argument('--render-only',action='store_true',help='Render a native music close-up using synthetic data, without running assertions')
    args=parser.parse_args()
    android=args.sdk/'platforms/android-36.1/android.jar';bt=args.sdk/'build-tools/37.0.0'
    java=args.jdk/'bin'/('java.exe' if os.name=='nt' else 'java')
    javac=args.jdk/'bin'/('javac.exe' if os.name=='nt' else 'javac')
    report=json.loads((ROOT/'dist/build-verification.json').read_text(encoding='utf-8'))
    program=next(Path(arg) for record in report['commands'] for arg in record['command'] if Path(arg).name=='program.jar')
    artifact=json.loads((ROOT/'dist/artifact-checks.json').read_text(encoding='utf-8'))
    apk=ROOT/'dist'/artifact['apk'];assert hashlib.sha256(apk.read_bytes()).hexdigest()==artifact['sha256']
    token=uuid.uuid4().hex[:10];work=ROOT/'build'/('hud-device-'+token);work.mkdir(parents=True)
    classes=work/'classes';classes.mkdir();dex=work/'dex';dex.mkdir();records=[]
    def run(command):
        result=subprocess.run(list(map(str,command)),cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,encoding='utf-8',errors='replace')
        records.append({'command':list(map(str,command)),'exit':result.returncode,'output':result.stdout})
        if result.returncode:raise RuntimeError(result.stdout)
        return result.stdout
    adb=[args.adb,'-s',args.serial]
    remote='/data/local/tmp/ninebot-hud-smoke-'+token
    run([javac,'-encoding','UTF-8','--release','17','-classpath',os.pathsep.join(map(str,[android,program])),'-d',classes]+sorted((ROOT/'tests/android').glob('*.java')))
    jar=work/'smoke.jar'
    with zipfile.ZipFile(jar,'w') as out:
        for path in classes.rglob('*.class'):out.write(path,path.relative_to(classes).as_posix())
    run([java,'-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--release','--min-api','33','--lib',android,'--classpath',program,'--output',dex,jar])
    run(adb+['shell','mkdir','-p',remote])
    try:
        run(adb+['push',apk,remote+'/module.apk']);run(adb+['push',dex/'classes.dex',remote+'/test.dex'])
        entry='MusicLayoutRender' if args.render_only else 'HudSmoke'
        result=run(adb+['shell',f'CLASSPATH={remote}/module.apk:{remote}/test.dex app_process /system/bin {entry} {remote}/hud.png'])
        if not args.render_only:
            hud_checks=re.search(r'PASS: (\d+) Android HUD checks',result)
            assert hud_checks and int(hud_checks.group(1))>=21,result
            assert 'PASS: 14 Android notification checks' in result,result
        run(adb+['pull',remote+'/hud.png',ROOT/('dist/music-layout-preview.png' if args.render_only else 'dist/hud-device-smoke.png')])
        print(result.strip())
    finally:
        run(adb+['shell','rm','-f',remote+'/module.apk',remote+'/test.dex',remote+'/hud.png'])
        run(adb+['shell','rmdir',remote])
        (ROOT/('dist/music-layout-render.json' if args.render_only else 'dist/hud-device-checks.json')).write_text(json.dumps({'apk_sha256':artifact['sha256'],'commands':records},ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':main()

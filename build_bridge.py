#!/usr/bin/env python3
"""Compile the AgentsMC layer against the unchanged, supplied Citizens engine."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT / 'source' / 'AgentsMC'
EXPECTED = 'ace5370aff7357842479a6908b9d421b7c450ff06c3a9e8261bd49773ea52351'
BASE = ROOT / 'AgentsMC-initial-build.jar'
if not BASE.is_file():
    fallback = Path('/tmp/citizens-compile.jar')
    if fallback.is_file():
        BASE = fallback
        print('AgentsMC-initial-build.jar missing; compiling against', BASE)
    else:
        raise SystemExit('Missing AgentsMC-initial-build.jar (and no /tmp/citizens-compile.jar fallback)')
elif hashlib.sha256(BASE.read_bytes()).hexdigest() != EXPECTED:
    raise SystemExit('The supplied base Citizens engine JAR does not match the expected build.')
java_home = Path(os.environ['JAVA_HOME'])
javac, java = java_home / 'bin/javac', java_home / 'bin/java'
version = subprocess.check_output([str(javac), '-version'], text=True)
if not version.startswith('javac 25.'):
    raise SystemExit('This build requires JDK 25; received ' + version.strip())
deps = PROJECT / 'build/deps'
deps.mkdir(parents=True, exist_ok=True)
CENTRAL = 'https://repo.maven.apache.org/maven2/'
PAPER = 'https://repo.papermc.io/repository/maven-public/'
artifacts = [
    ('adventure-text-minimessage.jar', PAPER+'net/kyori/adventure-text-minimessage/5.2.0/adventure-text-minimessage-5.2.0.jar', 'e1871aba1511dbe49cff211c10f85682886014161e5d629318aa69583c31a2c0'),
    ('paper-api.jar', PAPER+'io/papermc/paper/paper-api/26.3.build.211-stable/paper-api-26.3.build.211-stable.jar', '287cc9be8706902e1c87ec85f4335e1c07bdd25085ad17cb9e07e978ffb65705'),
    ('gson.jar', CENTRAL+'com/google/code/gson/gson/2.13.2/gson-2.13.2.jar', 'dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0'),
    ('adventure-api.jar', PAPER+'net/kyori/adventure-api/5.2.0/adventure-api-5.2.0.jar', '7e52fe7190be3e87b3b3f71712cfa12315fcd27109f302a7b440c12f01fae827'),
    ('adventure-key.jar', PAPER+'net/kyori/adventure-key/5.2.0/adventure-key-5.2.0.jar', '0184d173200e2eef8fbc791f622d1d58fd459f8930c616b5a4fe79e83eda6c55'),
    ('annotations.jar', CENTRAL+'org/jetbrains/annotations/26.0.2/annotations-26.0.2.jar', '2037be378980d3ba9333e97955f3b2cde392aa124d04ca73ce2eee6657199297'),
    ('bungeecord-chat.jar', PAPER+'net/md-5/bungeecord-chat/1.21-R0.2-deprecated+build.21/bungeecord-chat-1.21-R0.2-deprecated+build.21.jar', 'ce930b3ee048520a0ab538eb8d1627b301367fc1b300837235387aded6d89ba1'),
    ('guava.jar', CENTRAL+'com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar', 'dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3'),
]
for name, url, expected in artifacts:
    destination = deps / name
    if not destination.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'AgentsMC-Builder/0.2.0'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit('Downloaded dependency checksum mismatch: ' + name)
        destination.write_bytes(data)
    if hashlib.sha256(destination.read_bytes()).hexdigest() != expected:
        raise SystemExit('Cached dependency checksum mismatch: ' + name)
classes, tests = PROJECT/'build/classes', PROJECT/'build/tests'
for directory in [classes, tests]:
    if directory.exists(): shutil.rmtree(directory)
    directory.mkdir(parents=True)
classpath = os.pathsep.join([str(BASE), *(str(deps/name) for name, _, _ in artifacts)])
sources = sorted((PROJECT/'main/src/main/java/dev/agentsmc').glob('*.java'))
subprocess.run([str(javac), '--release', '25', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)
subprocess.run([str(javac), '--release', '25', '-cp', str(classes)+os.pathsep+classpath, '-d', str(tests), *map(str,(PROJECT/'tests').glob('*.java'))], check=True)
for name in ['AgentCredentialTest', 'BridgeTest', 'ActionCatalogTest', 'ViewerDeltaTest', 'NavigationTest', 'ActionProgramTest', 'PlacementTest', 'BuildPlanTest', 'SafeSpawnTest', 'BodyStateTest', 'ErrorReportingTest', 'SurvivalTest', 'BoatTest', 'PathfinderTest', 'SelfDefenseTest', 'RestartTest', 'RangedTest', 'ProtectTest', 'AgentEventsTest', 'AgentNamesTest', 'FollowTest', 'SleepTest', 'FeedbackTest', 'AgentUiTest', 'ChatLogTest', 'PerceptionTest', 'EquipmentTest', 'AutonomyTest', 'FinishActionsTest', 'SurveyTest', 'WorldTimeTest', 'SignTest', 'PlantTest', 'FurnaceLogTest', 'AgentOpTest']:
    subprocess.run([str(java), '-cp', str(classes)+os.pathsep+str(tests)+os.pathsep+classpath, 'dev.agentsmc.'+name], check=True)
replacement = {str(file.relative_to(classes)):file.read_bytes() for file in classes.rglob('*.class')}
for name in ['plugin.yml', 'bridge.yml', 'plugin-changelog.yml', 'agent-panel.html']:
    replacement[name] = (PROJECT/'main/src/main/resources'/name).read_bytes()
temporary = ROOT/'AgentsMC.jar.part'
with ZipFile(BASE) as original, ZipFile(temporary, 'w', ZIP_DEFLATED) as output:
    for entry in original.infolist():
        if entry.filename not in replacement: output.writestr(entry, original.read(entry.filename))
    for name, data in replacement.items(): output.writestr(name, data)
with ZipFile(temporary) as output:
    if output.testzip() is not None: raise SystemExit('Output JAR integrity check failed')
os.replace(temporary, ROOT/'AgentsMC.jar')
print('Built', ROOT/'AgentsMC.jar', '(no server credentials included)')

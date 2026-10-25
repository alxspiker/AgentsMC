# Building AgentsMC

You need a JDK 25 (for example Eclipse Temurin 25) and Python 3.

`build_bridge.py` compiles the AgentsMC layer (`dev.agentsmc`) against a pinned,
prebuilt base engine JAR (the embedded Citizens fork). Download
`AgentsMC-initial-build.jar` from the [v0.7.0 release](https://github.com/alxspiker/AgentsMC/releases/tag/v0.7.0)
and put it in the repository root. The script checks its SHA-256
(`ace5370aff7357842479a6908b9d421b7c450ff06c3a9e8261bd49773ea52351`).

```sh
JAVA_HOME=/path/to/jdk-25 python3 build_bridge.py
```

The script downloads and checksums the Paper API and library JARs, compiles the plugin
classes with `javac --release 25`, runs the tests in `source/AgentsMC/tests`,
and writes `AgentsMC.jar`. The output JAR contains a `bridge.yml` with no token.

`AgentsMC-initial-build.jar` is only the build input. Deploy `AgentsMC.jar`.
Never commit JARs or a filled-in `bridge.yml`.

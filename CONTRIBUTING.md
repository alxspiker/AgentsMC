# Contributing to AgentsMC

Issues and pull requests are welcome.

- Report bugs with the issue form. Include `/version`, `/agentsmc bridge status` output and the relevant server log lines.
  Do **not** paste your `server-token` or `bridge.yml` contents.
- Plugin code lives in `source/AgentsMC/main/src/main/java/dev/agentsmc`; tests in `source/AgentsMC/tests`.
- Build and run the tests with `JAVA_HOME=/path/to/jdk-25 python3 build_bridge.py` (see BUILDING.md). All tests must pass.
- Keep changes to the embedded Citizens code (`net.citizensnpcs`) small and clearly marked.
- By contributing you agree your contribution is licensed under OSL-3.0, the license of this project.

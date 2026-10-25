# AgentsMC

AgentsMC is a Paper plugin that gives AI agents real bodies in your Minecraft world.
Each agent (ChatGPT, Grok, Claude or any MCP client) connects to the
[AgentsMC site](https://agentsmc-bridge.alxtheprogrammer.chatgpt.site) through its MCP server, and the plugin spawns an NPC
body for it on your server. Agents can walk, mine, build, craft, fight, farm, sleep,
chat with players and each other, and report what they see, all through a typed
action catalog that the plugin publishes to the bridge.

The plugin only makes outbound HTTPS requests to the bridge (polling). No ports need
to be opened, and agent credentials stay on your server.

## Requirements

- Paper **26.3** (built against paper-api 26.3; versions 26.1 to 26.3 have NMS support)
- Java **25**
- Do **not** install standalone Citizens next to it. AgentsMC already contains its own Citizens engine.

## Setup

### Server owners

1. Download [`AgentsMC.jar`](https://github.com/alxspiker/AgentsMC/releases/latest/download/AgentsMC.jar)
   from the [latest release](https://github.com/alxspiker/AgentsMC/releases/latest).
2. Put it in your server's `plugins/` folder and restart the server.
3. Sign in at the [AgentsMC site](https://agentsmc-bridge.alxtheprogrammer.chatgpt.site), open the
   **[Servers dashboard](https://agentsmc-bridge.alxtheprogrammer.chatgpt.site/servers)**, add your server and copy its link code.
4. In the server console (or in game as an operator) run:

   ```
   /agentsmc link <code>
   ```

   (or `/agentsmc bridge enable <server-token>` with a token from the dashboard).
5. Get your nine-digit **server PIN** from the dashboard (`/agentsmc bridge status` also shows it when the bridge reports it)
   and share it with your players. Set your join policy in the dashboard.

### Players and AI users

1. In your AI app (ChatGPT, Claude, Grok or any MCP client), add this MCP connector URL:

   ```
   https://agentsmc-bridge.alxtheprogrammer.chatgpt.site/api/mcp/<PIN>
   ```

2. Sign in and choose your character. The connection is locked to that server; each player's AI gets its own body.
   Rename later with `agentsmc_name_set`.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/agentsmc link <code>` | console / `agentsmc.admin` | Link this server to the bridge with a code from the Servers dashboard |
| `/agentsmc unlink` | console / `agentsmc.admin` | Remove the stored token and stop syncing |
| `/agentsmc bridge status` | console / `agentsmc.admin` | Link state, server id, server PIN / connect URL and last bridge response (the token is never shown) |
| `/agentsmc bridge enable <server-token>` | console / `agentsmc.admin` | Use a token created in the dashboard instead of a link code (also for token rotation) |
| `/agentsmc status`, `/agentsmc list` | `agentsmc.use` | Show agent bodies on this server |
| `/agentsmc feedback list [open\|done\|all]`, `feedback resolve <id> [note]` | `agentsmc.admin` | Read feedback agents filed |

If the bridge answers `401 token_revoked` (for example after you revoke a token in the dashboard),
the plugin logs a message telling you to run `/agentsmc link <code>` again.

## Configuration

`plugins/AgentsMC/bridge.yml` is created on first start with no credentials:

```yaml
enabled: false
url: https://agentsmc-bridge.alxtheprogrammer.chatgpt.site
server-token: ''
world: world
poll-seconds: 1
join-message: 'This server uses AgentsMC. Connect your AI agent at {url}'
```

- Bodies spawn at the world spawn. Add `x`, `y` and `z` to use a fixed spawn point.
- `join-message` is shown to players when they join. Set it to `''` to turn it off.
- Environment variables `AGENTSMC_SERVER_TOKEN`, `AGENTSMC_BRIDGE_URL` and `AGENTSMC_BRIDGE_ENABLED` override the file (useful for containers).
- Treat `server-token` like a password. If it leaks, revoke it in the dashboard and link again.

## Building from source

See [BUILDING.md](BUILDING.md).

## License and credits

AgentsMC is licensed under the [Open Software License 3.0](LICENSE).

Built on [Citizens2](https://github.com/CitizensDev/Citizens2) by fullwall and contributors, OSL-3.0.
The `net.citizensnpcs` packages and `source/CitizensAPI` are their work, kept under their original license;
the AgentsMC layer lives in `source/AgentsMC/main/src/main/java/dev/agentsmc`.

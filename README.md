# SkyKings Guild Bridge (mod)

A Fabric mod that links your Hypixel guild chat with your guild's Discord channel, **without a
bridge bot account**. Every member who has the mod passes the guild chat they already see to
Discord, and Discord messages show up in their chat.

**Download, setup guide and the Discord bot:** <https://skykings.net/guild-bridge>

This repository is the mod (the client). The bridge server and Discord bot it connects to are run
by SkyKings and aren't part of this repository.

## What it does

- **Guild chat → Discord.** Reads the guild chat, officer chat (staff), logins and guild events
  your game already receives, and sends them to the bridge.
- **Discord → your screen.** Shows Discord messages as `[Discord] name: message` lines in your
  chat. These only exist on your own screen.
- **`/bc <message>`** talks to Discord and other mod users without going through Hypixel chat.
- **Staff tasks.** Promotions, demotions, kicks, mutes and invites asked for on Discord are shown
  to guild staff with a `[Run]` button. Clicking it puts a command in the chat box; nothing is
  sent until the staff member presses **Enter**.
- **`/gbridge`** opens the settings screen: toggles, who's online with the mod, and every command.
- **Name colours** that other mod users see in guild chat.

## It never sends commands or chat for you

The mod doesn't send anything to Hypixel by itself: no automatic commands, no automatic chat. The
only command it ever sends is a staff task (`/g promote <name>` and similar), and only after the
player clicked `[Run]` and pressed Enter, one press, one command. `StaffTasks.java` also checks
every staff command against a fixed allow-list (`/g promote|demote|invite|unmute|kick|mute`), so
even a misbehaving server couldn't make it run anything else.

Hypixel doesn't publish a list of approved mods. This one only reads chat and shows messages on
your own screen; use it at your own discretion.

## Privacy and security

- **Signing in:** the mod proves who you are the same way joining a Minecraft server does
  (`MojangJoin.java`, `BridgeConnection.serverIdFor`). Your login token is only ever sent to
  Mojang, by Minecraft's own code, never to the bridge. The one-time server id is a hash of a
  fixed prefix and random values from both sides, so it can't be reused to join a real server as you.
- **Encrypted only:** the mod refuses `ws://` addresses except `localhost` (`BridgeConfig.serverUri`).
- **What's sent:** guild chat lines (and officer chat for staff), guild event lines, your `/bc`
  messages, your mod settings that affect what you see, and staff task results (only Hypixel's
  reply to the command, never DMs or other chat). Nothing else from your chat is sent.
- **What's shown:** text from Discord and other players is shown as plain text; colour codes,
  links, clickable commands and control characters are stripped (`ChatDisplay.safe`).

## Minecraft versions

One jar runs on **Minecraft 26.1, 26.2 and 26.3** (Fabric Loader + Fabric API, Java 25). It's
compiled against the oldest version. The few things Minecraft changed between those versions are
looked up by name at runtime (26.x isn't obfuscated):

| File | Difference |
|---|---|
| `ChatAccess.java` | the chat moved from `Gui.getChat()` (26.1) to `Gui.hud.getChat()` (26.2+) |
| `ScreenAccess.java` | opening screens moved from `Minecraft.setScreen` (26.1) to `Gui.setScreen` (26.2+) |
| `MojangJoin.java` | Mojang's login interface was renamed in 26.3 |
| `MouseButtons.java` | 26.3 uses SDL instead of GLFW (used when a client, e.g. Lunar, doesn't pass clicks to mod screens) |

## Building

```
./gradlew build          # Linux/macOS (needs JDK 25)
.\build.ps1              # Windows: downloads a JDK 25, builds, and runs the version check
```

The jar ends up in `build/libs/` (or `release/` with `build.ps1`).

`build.ps1` also compiles against every Minecraft version in `versions.json` and fails if any
Minecraft, Fabric or Mojang method, field or callback the mod uses differs between them. That's
how version differences like the ones above are caught. To support a new Minecraft version, add it
to `versions.json` and to `"minecraft"` in `src/main/resources/fabric.mod.json`, run `build.ps1`,
and handle any difference it reports the same way.

## Settings file

`config/skykings-guild-bridge.json`. `serverUrl` is the bridge it connects to (default
`wss://skykings.net/bridge/ws`).

## Contributing

Issues and pull requests are welcome. Please keep it free of anything that sends commands or chat
on the player's behalf.

## License

[MIT](LICENSE)

# SkyKings Guild Bridge (mod)

A Fabric mod that links your Hypixel guild chat with your guild's Discord channel, **without a
bridge bot account**. Every member who has the mod passes the guild chat they already see to
Discord, and Discord messages show up in their chat.

**Download, setup guide and the Discord bot:** <https://skykings.net/guild-bridge> ·
**[Releases](https://github.com/Jacktheguys/SkyKings-Guild-Bridge-Mod/releases)**

Every release is built from this repository by GitHub Actions (not on anyone's PC), and its notes
list the jar's SHA-256 so you can check your download:
`certutil -hashfile skykings-guild-bridge-<version>.jar SHA256` on Windows, `sha256sum` on Linux/macOS.

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
- **`/gpv [player]`** shows a player's profile stats, laid out like skykings.net/profilestats.
- **Market tools:** `/gscanner` (auction snipes), `/gbazaar` (bazaar flips) and `/gmarket` (also forge,
  Kat, NPC and book flips), from skykings.net. Click a row to open the auction or the Bazaar.
- **Name colours** that other mod users see in guild chat.

## It never sends commands or chat for you

The mod doesn't send anything to Hypixel by itself: no automatic commands, no automatic chat.
Every command it sends is one the player asked for, one at a time:

- **Market tools** (`/gscanner`, `/gbazaar`, `/gmarket`): clicking a row sends `/viewauction <id>` or
  `/bz <item>` to open that auction or Bazaar search, nothing more. One click, one command, at most one
  a second, and only on Hypixel. The mod builds the command itself from a checked auction id or item name
  (`GuildBridgeMod.runMarketAction`); nothing from the server is run as a command. Buying is still done in
  Hypixel's own menu.
- **Staff tasks** (`/g promote <name>` and similar): only after the player clicked `[Run]` and pressed
  Enter, one press, one command. `StaffTasks.java` also checks
every staff command against a fixed allow-list (`/g promote|demote|invite|unmute|kick|mute`), so
even a misbehaving server couldn't make it run anything else. A command only runs when the
server confirms a task this player asked to run in the last few seconds; the server can't make
it run one on its own. Tasks are only shown to players the server says are guild staff.

Hypixel doesn't publish a list of approved mods. This one only reads chat and shows messages on
your own screen; use it at your own discretion.

## Privacy and security

- **Signing in:** the mod proves who you are the same way joining a Minecraft server does
  (`MojangJoin.java`, `BridgeConnection.serverIdFor`). Your login token is only ever sent to
  Mojang, by Minecraft's own code, never to the bridge. The one-time server id is a hash of a
  fixed prefix, the host name the mod connected to (whose TLS certificate it checked), and
  random values from both sides. So it can't be reused to join a real server as you, and another
  server can't pass the bridge's sign-in on to you and sign in to the real bridge as you. The
  mod answers one sign-in challenge per connection.
- **Encrypted only:** the mod refuses `ws://` addresses except `localhost` (`BridgeConfig.serverUri`).
  Only point it at a bridge server you trust: that server receives everything listed below.
- **What's sent to the bridge server:**
  - guild chat lines (and officer chat for staff) and guild event lines your game receives.
    That includes what guild members **without** the mod say: everything said in guild chat
    goes to Discord, the same as with a bridge bot. Guilds should tell their members.
  - your `/bc` messages, your Minecraft name and UUID, and your mod settings that affect what you
    see (e.g. Discord messages on/off, /g toggle state)
  - staff task results: only Hypixel's own reply to the `/g` command that was just run (its
    exact success or error line, matched in `StaffTasks.replyPatterns`), never other chat.
  - like any website, the server sees your IP address, and since the mod connects while you're
    on Hypixel, it knows when you're online there. Other mod users in your guild see that you're
    online with the mod (`/gbridge online`), and Discord shows it next to your messages.
- **Pictures:** Discord images, stickers and custom emojis are downloaded only from Discord's own
  image servers (`MediaStore.allowed`), checked for size before decoding, and never saved or run.
- **What's shown:** text from Discord and other players is shown as plain text; colour codes,
  links, clickable commands, control characters and invisible formatting characters
  (right-to-left overrides, zero-width spaces) are stripped (`ChatDisplay.safe`). The mod's own
  messages are tagged `[Bridge]`; other players' `/bc` messages are tagged `[Bridge Chat]`.

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
./gradlew build              # needs JDK 25
scripts/check-versions.sh    # the version check below (needs JDK 25 and jq)
.\build.ps1                  # Windows: downloads a pinned JDK 25 (checksum-verified), runs the check, builds
```

The jar ends up in `build/libs/` (or `release/` with `build.ps1`).

`build.ps1` and `scripts/check-versions.sh` (which CI runs) compile against every Minecraft version in `versions.json` and fails if any
Minecraft, Fabric or Mojang method, field or callback the mod uses differs between them. That's
how version differences like the ones above are caught. To support a new Minecraft version, add it
to `versions.json` and to `"minecraft"` in `src/main/resources/fabric.mod.json`, run `build.ps1`,
and handle any difference it reports the same way.

## Settings file

`config/skykings-guild-bridge.json`. `serverUrl` is the bridge it connects to (default
`wss://skykings.net/bridge/ws`).

## Releases

Releases are automatic ([release-please](https://github.com/googleapis/release-please)), driven by
[Conventional Commit](https://www.conventionalcommits.org/) titles on `main`:

- `feat: ...` bumps the minor version, `fix: ...` the patch, `feat!: ...` (breaking) the major.
  `docs:`, `ci:`, `chore:` and the like don't make a release.
- release-please keeps a "chore(main): release x.y.z" pull request open with the changelog and the
  version bump. Merging it tags the release; `.github/workflows/release.yml` then checks the mod
  against every Minecraft version, builds it, and attaches the jar and its SHA-256.
- skykings.net/guild-bridge picks up the new release within 10 minutes.

How the workflows stay safe: builds get a read-only token and no secrets, and release builds use no
cache; only the publish step can write to releases, and it never runs build code. Pull requests run
with plain `pull_request` (never `pull_request_target`). Every action is pinned to a commit SHA, and
Dependabot keeps them updated. The Gradle wrapper is checked against Gradle's published checksums.

## Contributing

Issues and pull requests are welcome. Open PRs against `main` with a Conventional Commit title
(e.g. `fix: ignore party chat`); they're squash-merged. Please keep the mod free of anything that
sends commands or chat on the player's behalf.

## License

[MIT](LICENSE)

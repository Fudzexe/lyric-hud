# Lyrics HUD (Fabric 1.21.11, client-only)

Shows time-synced lyrics for whatever song your PC is playing, in a draggable box with custom colours.

- **No Spotify login / connect URL / API key.** Song + position come from your OS media controls
  (the same info as the Windows volume flyout). Works with Spotify, Apple Music, YouTube in a browser, VLC...
- Lyrics come from [LRCLIB](https://lrclib.net) (free, no account).
- Windows: built in (uses a small hidden PowerShell helper). Linux: needs `playerctl`. macOS: not supported yet.

## Using it
1. Play a song.
2. In game press **J** (rebindable under Controls > Lyrics HUD).
3. Drag the box anywhere. Pick colours via the hex boxes (or the preset button), change scale / width /
   background, "Snap to grid", number of lines before/after, and Earlier/Later to fix lyric timing.
4. **Esc** saves. Settings live in `config/lyricshud.json` (colours are `"#RRGGBB"`).

There's also an unbound "Toggle lyrics HUD" key in Controls.

## Building
Easiest: generate a fresh project at https://fabricmc.net/develop (1.21.11, Yarn mappings), then copy
`src/` over its `src/` and `gradle.properties` / `build.gradle` values if they differ, or run:

    gradle wrapper --gradle-version 9.5.1
    ./gradlew build

The jar ends up in `build/libs/`. Needs Java 21 and Fabric API in your mods folder.

## If it doesn't compile
Minecraft's GUI/key APIs moved a lot in 1.21.6-1.21.11. The likeliest spots to need a one-line fix:
- `KeyBinding.Category.create(...)` in `LyricsHudClient`
- `client.getWindow().getHandle()` in `LyricsEditScreen`
- `HudElementRegistry` / `VanillaHudElements` imports

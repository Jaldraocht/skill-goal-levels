# Skill Goal Levels

A RuneLite plugin that shows the goal you set in the in-game XP tracker as the bottom-right number (the "denominator") on each skill in the skills tab, in its own color. Hover a skill and it switches back to your real level.

Example: you're 72 Fishing with a goal of 80. The tile normally shows `72 / 72`. With this plugin it shows `72 / 80`, with the 80 in green. Hover it and it shows `72 / 72` again.

## Setting a goal in-game
Right-click the XP orb next to the minimap → **Setup** → set a goal for a skill. Goals can be a level or an XP amount; XP goals are shown as the level that XP reaches.

## Settings
- **Goal level color** — color of the goal number (default: the same green as the `green:` chat effect, so goals stand out from real levels)
- **Hide completed goals** — go back to the real level once the goal is reached (default: on)
- **Show real level on hover** — swap back to the real level while hovering (default: on)

## Running it
```
./gradlew run
```
This launches a dev RuneLite client with the plugin loaded. For Jagex accounts, follow https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts to log in to the dev client.

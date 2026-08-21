# PillarsMC

A fast Paper minigame where players fight on pillars with randomized items, a shrinking arena, persistent stats, and global game events.

## Features

- Multiple configurable arenas with quick join and spectating
- Weighted common, rare, and legendary item pools
- Shrinking world border and the final `Last Breath` phase
- Automatic events with anti-repeat selection
- Smash, Cosmic Drift, Earthquake, Hunt Begins, and Hot Potato
- Manual-only Meteor Shower
- English and Russian messages
- In-game admin menus and asynchronous arena resets

## Requirements

- Paper 1.21.x
- Java 21
- Maven 3.x for building

## Install

```bash
mvn clean package
```

Copy `target/pillarsplugin-1.0-SNAPSHOT.jar` to the server's `plugins` directory, then restart the server.

Configuration is stored in `plugins/PillarsPlugin/`:

- `config.yml` — arenas, timing, events, language, and gameplay settings
- `item-pools.yml` — weighted item pools
- `messages_en.yml` / `messages_ru.yml` — player-facing text
- `stats.json` — player kills and wins

## Commands

`/pillars` and `/p` are interchangeable.

| Command | Purpose |
| --- | --- |
| `/p menu` | Open the arena menu |
| `/p quickjoin` | Join the best available arena |
| `/p join <arena>` | Join a specific arena |
| `/p leave` | Leave the current arena |
| `/p forcestart` | Force-start the current match |
| `/p admin` | Open admin controls |
| `/p event <name>` | Start an event manually |
| `/p event next` | Show the next automatic event and its timer |
| `/p itemadd <rarity> [weight]` | Add the held item to a pool |
| `/p itemremove <rarity> [material]` | Remove an item from a pool |

Manual event names: `smash`, `cosmic`, `meteor`, `earthquake`, `hunt`, `potato`, and `lastbreath`.

Admin commands and menus require `pillars.admin`. Force-starting requires `pillars.forcestart`.

## Media

> **Note:** These GIFs are slightly outdated. Menu layouts may have changed, and newer buttons may not be shown.

### Admin Menu

![Admin menu overview](docs/media/admin-menu-overview.gif)

### Arena Settings

![Arena settings menu](docs/media/arena-settings-menu.gif)

### Item Pool Editor

![Item pool editor](docs/media/item-pool-editor.gif)

### Arena Menu

![Player arena menu](docs/media/player-arena-menu.gif)

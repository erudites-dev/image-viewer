# Image Viewer

A multiloader Minecraft mod/plugin (Fabric / NeoForge / Paper) for 26.1+ that lets server operators display images to players via an in-game web viewer.

The server hosts a local HTTP server that serves images. When a player presses the keybind, their client opens a full-screen browser powered by [MCEF](https://modrinth.com/mod/mcef-keksuccino) to view the images.

---

## How It Works

1. Server starts → Image web server launches on the configured port
2. Player joins → Server sends the port and category list to the client
3. Player presses **I** → Browser screen opens, showing images from the server

Clients can connect to any of the supported server platforms — Fabric server, NeoForge server, or a Paper plugin server. The mod is client-optional on Fabric/NeoForge, so the client can also join vanilla/Paper servers without the channel handshake forcing a disconnect.

---

## Installation

### Server
Pick one of:
- **Fabric server** — install the Fabric jar
- **NeoForge server** — install the NeoForge jar
- **Paper server** — drop the Paper plugin jar into `plugins/`

### Client
- Install the Fabric or NeoForge jar

---

## Server Setup

### 1. Place Images

Put image files (`.png`, `.jpg`, `.jpeg`) in the server's `imageviewer/images/` directory.

```
<server root>/
└── imageviewer/
    └── images/
        ├── 1.png          ← shown as "Main" category
        ├── 2.png
        └── rules/         ← shown as "rules" category
            ├── 1.png
            └── 2.png
```

Images are displayed in numerical order (by the number extracted from the filename).

### 2. Configure the Port

On first launch, `imageviewer/config.json` is created automatically:

```json
{
  "webServerPort": 25580
}
```

| Value | Behavior |
|-------|----------|
| `25580` | Default fixed port |
| `0` | Random available port (not recommended — clients may be blocked by firewall) |
| Any other number | Use that port |

**Restart the server after changing the config.**

### 3. Open the Firewall

The HTTP port (default `25580`) must be reachable by clients. Open it in the server's firewall in addition to the Minecraft port (default `25565`).

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/imageviewer reload` | Op level 2 (gamemaster) | Re-detect categories and push the updated image list to every online player. Use this after adding/removing images or category subdirectories. |

Changing `webServerPort` still requires a server restart.

---

## Usage

| Action | Result |
|--------|--------|
| Press **I** (configurable) | Open image viewer |
| **Left click** | Next image |
| **Right click** | Previous image |
| **Left click** on last image | Close viewer |
| **Escape** | Close viewer |

If multiple image categories (subdirectories) exist, a selection screen appears first.

---

## Categories

Subdirectories inside `imageviewer/images/` become selectable categories.

- Images directly in `imageviewer/images/` → **Main** category
- `imageviewer/images/rules/` → **rules** category
- `imageviewer/images/event/` → **event** category

If only one category exists, the selection screen is skipped.

---

## Dependencies

| Dependency | Side | Required |
|------------|------|----------|
| [Fabric API](https://modrinth.com/mod/fabric-api) | Client (Fabric only) | Yes |
| [MCEF](https://modrinth.com/mod/mcef-keksuccino) | Client | Bundled (no separate install) |
# Image Viewer

A multiloader Minecraft mod/plugin (Fabric / NeoForge / Paper) for 1.21.1 that lets server operators display images to
players in a native in-game viewer.

Images are sent byte-for-byte over the Minecraft connection and rendered by the game itself at their original
resolution. No browser, no extra port, no external dependency.

---

## How It Works

1. Server starts → images in `imageviewer/images/` are indexed by SHA-256
2. Player joins → server sends the image catalog (categories and hashes)
3. Player opens a category → client loads images from its local cache and requests the missing ones in viewing order:
   the current image first, then the next and previous, then the rest of the category ahead
4. The server streams original files in chunks on a dedicated thread, as fast as the connection accepts them; each image
   appears as soon as it has arrived and been verified, while the following images keep downloading in the background
5. Images are uploaded to the GPU without downscaling

Clients can connect to any of the supported server platforms — Fabric server, NeoForge server, or a Paper plugin server.
The mod is client-optional on Fabric/NeoForge, so the client can also join vanilla/Paper servers without the channel
handshake forcing a disconnect.

---

## Installation

### Server

Pick one of:

- **Fabric server** — install the Fabric jar
- **NeoForge server** — install the NeoForge jar
- **Paper server** — drop the Paper plugin jar into `plugins/`

### Client

- Install the Fabric or NeoForge jar

Server and client must both run Image Viewer 0.2.0 or newer; the 0.1.x browser-based protocol is not compatible.

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

### 2. Configure

On first launch, `imageviewer/config.json` is created automatically:

```json
{
  "maxImageBytes": 33554432,
  "maxUploadBytesPerSecond": 0,
  "keepAspectRatio": false
}
```

| Key                       | Default             | Description                                                                                                                                                                                 |
|---------------------------|---------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `maxImageBytes`           | `33554432` (32 MiB) | Largest image file that is offered to clients. Larger files are skipped with a warning. Clamped to 1 MiB – 128 MiB.                                                                         |
| `maxUploadBytesPerSecond` | `0` (unlimited)     | Optional upload speed limit per player. `0` sends as fast as the player's connection accepts data (at most 4 MiB is buffered per player). Values above `0` are raised to at least 64 KiB/s. |
| `keepAspectRatio`         | `false`             | `false` stretches images to fill the screen. `true` scales them to fit while keeping their aspect ratio (black bars fill the rest).                                                         |

Run `/imageviewer reload` after editing the config — no restart required.

---

## Commands

| Command               | Permission              | Description                                                                                              |
|-----------------------|-------------------------|----------------------------------------------------------------------------------------------------------|
| `/imageviewer reload` | Op level 2 (gamemaster) | Reload `config.json`, re-scan `imageviewer/images/` and push the updated catalog to every online player. |

---

## Usage

| Action                             | Result                          |
|------------------------------------|---------------------------------|
| Press **I** (configurable)         | Open image viewer               |
| **Left click** / **→** / **Space** | Next image                      |
| **Right click** / **←**            | Previous image                  |
| **Left click** on last image       | Close viewer                    |
| **Mouse wheel**                    | Zoom in / out around the cursor |
| **Left drag**                      | Pan a zoomed image              |
| **0**                              | Reset zoom                      |
| **Escape**                         | Close viewer                    |

While an image is downloading, the viewer shows its progress (`42% (3.1 / 7.4 MB)`).

If multiple image categories (subdirectories) exist, a selection screen appears first.

---

## Categories

Subdirectories inside `imageviewer/images/` become selectable categories.

- Images directly in `imageviewer/images/` → **Main** category
- `imageviewer/images/rules/` → **rules** category
- `imageviewer/images/event/` → **event** category

A subdirectory named `main` is ignored because that name is reserved for the root category. If only one category exists,
the selection screen is skipped.

---

## Image Quality and Limits

- Files are never re-encoded or resized by the server.
- The client decodes images at full resolution and draws them in physical screen pixels. Mipmaps are generated in linear
  color space so images shrunk to fit the screen stay sharp without aliasing.
- Images wider or taller than the GPU's maximum texture size are split into tiles instead of being downscaled.
- The client refuses images above 8192 × 8192 pixels (≈ 64 megapixels) to bound memory use.

---

## Client Cache

Downloaded images are stored in `<game directory>/imageviewer-cache/`, named by their SHA-256 hash, so unchanged images
are not downloaded again on reconnect. Every cached file is re-verified before use. The cache is capped at 512 MiB and
the least recently used files are removed first. It is safe to delete the folder at any time.

---

## Troubleshooting Load Times

Start the client with `-Dimageviewer.debugTimings=true` to log how long each image spends in the disk cache lookup,
waiting in the server queue, transferring (with KiB/s), verifying, decoding and uploading.

---

## Dependencies

| Dependency                                        | Side        | Required |
|---------------------------------------------------|-------------|----------|
| [Fabric API](https://modrinth.com/mod/fabric-api) | Fabric only | Yes      |

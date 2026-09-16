# Image Viewer

## 1. Place Images

Drop image files (`.png`, `.jpg`, `.jpeg`) into the `images/` directory next to this file.

- Files placed directly in `images/` form the **Main** category.
- Each subdirectory in `images/` becomes its own category (a subdirectory named `main` is ignored).
- Files are shown in numerical order (the number extracted from the filename).

```
images/
├── 1.png          ← Main
├── 2.png
└── rules/         ← "rules" category
    ├── 1.png
    └── 2.png
```

Images are sent to players over the game connection at their original resolution. No extra port or firewall rule is needed.

## 2. Configure `config.json`

- `maxImageBytes` — largest image file offered to clients. Default `33554432` (32 MiB). Larger files are skipped.
- `maxUploadBytesPerSecond` — optional upload speed limit per player. Default `0` (unlimited, as fast as the connection allows).
- `keepAspectRatio` — `false` (default) stretches images to fill the screen, `true` fits them while keeping their aspect ratio.

Run `/imageviewer reload` after editing.

## Commands

- `/imageviewer reload` — reloads `config.json`, re-scans `images/` and pushes the updated list to every online player. Op level 2 (gamemaster) required.

---

Players press the **I** key (configurable client-side) in-game to open the viewer.

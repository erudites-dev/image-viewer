# Image Viewer

## 1. Place Images

Drop image files (`.png`, `.jpg`, `.jpeg`) into the `images/` directory next to this file.

- Files placed directly in `images/` form the **Main** category.
- Each subdirectory in `images/` becomes its own category.
- Files are shown in numerical order (the number extracted from the filename).

```
images/
├── 1.png          ← Main
├── 2.png
└── rules/         ← "rules" category
    ├── 1.png
    └── 2.png
```

## 2. Configure `config.json`

- `webServerPort` — HTTP port the image server listens on. Default `25580`. `0` = random available port (not recommended; clients may be blocked by firewall).

Restart the server after editing.

## 3. Open the Firewall

Open the configured port for clients to reach it, in addition to the Minecraft port (default `25565`).

## Commands

- `/imageviewer reload` — re-detects categories and pushes the updated list to every online player. Op level 2 (gamemaster) required. Use after adding/removing images or category folders. Port changes still need a server restart.

---

Players press the **I** key (configurable client-side) in-game to open the viewer.
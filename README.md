# Better Editor Tabs

Chrome-style tab groups for the editor tab bar in JetBrains IDEs.

## Features

### Tab groups

<p align="center">
  <img src="docs/images/tab-groups.gif" alt="Creating a named, coloured tab group" width="560"><br>
  Group editor tabs under a named, coloured header from the tab's context menu.
</p>

### Collapse and expand

<p align="center">
  <img src="docs/images/collapse.gif" alt="Collapsing and expanding a group" width="560"><br>
  Click a group's header to hide its tabs and show them again.
</p>

### Drag and drop

<p align="center">
  <img src="docs/images/drag-drop.gif" alt="Dragging tabs and a whole group" width="560"><br>
  Drag tabs into and out of groups, or drag a whole group by its header.
</p>

### Splits and windows

<p align="center">
  <img src="docs/images/splits.gif" alt="Moving a group into a new split" width="560"><br>
  Move or copy groups between splits and windows; a group stays linked across splits.
</p>

### Stash and restore

<p align="center">
  <img src="docs/images/stash.gif" alt="Stashing and restoring a group" width="560"><br>
  Stash a group to close its tabs, then bring it back later with <i>Restore Tab Group…</i>.
</p>

### Auto-group rules

<p align="center">
  <img src="docs/images/auto-group.gif" alt="Setting up an auto-group rule" width="560"><br>
  New files join a group automatically by module, directory or regex.
</p>

Requires IntelliJ Platform 2026.2 or later with the New UI. Works in every JetBrains IDE.

## Build

Requirements: JDK 25 or later.

```bash
./gradlew buildPlugin
```

The plugin zip is written to `build/distributions/`. To install it, open **Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk…** and pick the zip.

To try the plugin in a sandbox IDE:

```bash
./gradlew runIde
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`.

<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Better Editor Tabs Changelog

## [Unreleased]

### Added

- Tab groups: named, coloured groups of editor tabs, created from the tab context menu or a right click on the free tab strip.
- Collapse and expand groups by clicking their header, with an animation; a hover list of a collapsed group's tabs; a partial
  expand that shows only the file you navigate to.
- Drag tabs into and out of groups, and drag whole groups by their header, within a split, to another split, onto an editor edge
  (new split) or out of the window (new window); hold Ctrl to copy.
- Linked groups across splits and detached windows, sharing name, colour and rule.
- Stash groups and restore them later with *Restore Tab Group…*; *Reopen Closed Tab* brings back a deleted or stashed group.
- Auto-group rules by module, directory or regex, with a live preview.
- Group-aware close actions, tab limit, alphabetical sorting and tab list; every tab placement; single-row and multi-row tabs.
- Actions under **Window ▸ Editor Tabs ▸ Tab Groups** (no default shortcuts) and a settings page at
  **Settings ▸ Editor ▸ General ▸ Editor Tabs - Groups**.
- Groups are saved per project in the workspace file.
- Requires IntelliJ Platform 2026.2 or later with the New UI; with the Classic UI the plugin stays inactive and says so once.

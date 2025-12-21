# README2

This file documents the updated UI behavior and the hidden-control hooks.

## Main UI flow
- The server list and advanced settings buttons are hidden by default.
- "Update current group subscription" and "Test & select best server" are circular icon buttons.
- The connect button is centered with an outer ring and glow placeholder.

## Hidden controls hook
- Long-press the center connect button to toggle the hidden controls.
  - First long-press: show "Open server list" and "Advanced settings".
  - Long-press again: hide them.

## Notes
- The toggle is stored in `pref_show_main_shortcuts` and survives app restarts.

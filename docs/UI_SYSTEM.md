# UI System & Visual Craft

## 1. Design Philosophy
The user interface draws inspiration from precision photographic instruments (Leica M-series, Carl Zeiss optics, Hasselblad medium format) combined with the ergonomics of modern flagship smartphones.

### Principles:
- **No AI Slop**: Absolutely no rounded generic cards, meaningless gradient halos, purple washes, or fake badges.
- **Unobstructed Viewfinder**: Maximum screen estate reserved for the camera viewfinder. Overlays use minimal visual weight (10-35% opacity grid).
- **Tactile Optical Selector**: Physical-style zoom pills (`0.6x`, `1x`, `2x`, `3x`, `6x`) mapped to actual physical lenses on multi-camera hardware.
- **Restrained Typography**: Monospace / Grotesque font pairing with deliberate kerning and hierarchical sizing.
- **Subtle Neural Status**: A discrete 7dp indicator dot and `NEURAL` badge. The dot is lit only when a neural backend has actually run (currently never).
- **Diagnostics Drawer**: Telemetry sheet accessible on-demand. Every field reads `N/A` until a real measurement populates it; the UI never shows placeholder values.

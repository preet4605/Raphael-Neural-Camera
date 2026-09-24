# Data Lab & Regression Testing Suite

## 1. Controlled Reference Scenes
To prevent subjective bias and artificial metric inflation, the Data Lab maintains a 14-scene controlled evaluation suite:
1. `SCENE_01_DAYLIGHT`: Midday landscape, broad dynamic range (target 2500 lux).
2. `SCENE_02_INDOOR`: Mixed artificial office lighting (250 lux).
3. `SCENE_03_LOW_LIGHT`: Dim restaurant interior with warm point sources (15 lux).
4. `SCENE_04_VERY_LOW_LIGHT`: Exterior night streetscape with deep shadow (1.5 lux).
5. `SCENE_05_BACKLIGHT`: High contrast window silhouette (1200 lux).
6. `SCENE_06_HDR`: Tunnel entrance with extreme intra-frame luminance delta.
7. `SCENE_07_FINE_TEXTURE`: High-frequency fabric and stone texture resolution chart.
8. `SCENE_08_TEXT`: ISO 12233 optical test chart for edge sharpness verification.
9. `SCENE_09_FOLIAGE`: Micro-contrast and branch delineation in green leaves.
10. `SCENE_10_SKIN`: Human portrait with authentic pore detail and faithful skin tones.
11. `SCENE_11_MOVING_SUBJECTS`: Foreground motion with camera panning.
12. `SCENE_12_NIGHT_LIGHTS`: Point specular flares against black sky.
13. `SCENE_13_REFLECTIVE`: Highly specular metallic reflections and chrome glare.
14. `SCENE_14_REPEATED_PATTERNS`: Moiré-critical architectural grates.

Automated regression runs fail the build if measured PSNR drops by >0.5 dB against baseline or hallucination risk exceeds 15%.

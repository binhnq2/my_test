# UI gap report — design vs implementation

Spec §14 asks for a per-screen comparison against the latest design on ten
axes, and forbids redesigning or adding a left menu. This document answers that
with measurements, and then says which of the gaps were fixed and which were
deliberately left.

**No screenshot was captured.** `adb devices` is empty and the host cannot start
an emulator (`Virtualization Enabled In Firmware: No`), so this is a comparison
of `docs/UI_design.png` against the layout XML and `dimens.xml` — not of a
rendered frame against a design. `KNOWN_LIMITATIONS.md` D9 records that. Anyone
reading a "matches" below should read it as "the declared numbers match", and
the rows that could only be settled by looking at a running screen are marked
**not observable here**.

## How the design numbers were obtained

`docs/UI_design.png` is a 1536×1024 collage of seven mockup panels laid out in
a grid: two panels across the top, two in the middle, three along the bottom,
each with its own caption strip.

**The collage does not preserve the 1024×600 aspect ratio, and every absolute
dp figure below is therefore scaled from the panel width only.** The Home panel
measures 742 px wide but only ~389 px tall — a ratio of 1.91 where 1024×600 is
1.707. The panels were resized to fit the grid, not drawn to the screen's shape.
Two calibrations confirm the width scale is nonetheless the real one:

| calibration | measurement | declared | implied scale |
|---|---|---|---|
| status bar height | 32 px (y 28–59) | `status_height` 44 dp | **1.375** |
| status bar height | — | — | horizontal: 1024/742 = **1.380** |

They agree to 0.4 %. So the horizontal scale is sound, the vertical scale is
not defined by the collage at all, and this document uses **one scale factor,
1024/742 = 1.380**, for every conversion. dp figures are px × 1.380.

Where a finding depends on a ratio rather than an absolute size — the
digit-to-unit proportion, the map/music split, the ring percentage — the scale
cancels entirely and the ratio is exact regardless of how the collage was
fitted. Those are the findings this document is most confident in, and they are
marked as ratios rather than dp.

An earlier draft of this document quoted a vertical factor of 1.389 derived
from treating the panel as 432 px tall. That was wrong: 432 was measured to the
bottom of the caption strip of the panel *below*, not to the bottom of the Home
panel. The errant rows are visible in the record — y 419–439 are the "3. Màn
hình Âm nhạc" caption, which cannot be part of the Home screen.

## 1. Layout hierarchy

The design uses one structure on every screen: **status bar → content →
bottom nav**, with the content split horizontally where a secondary card is
needed (map + music, list + preview). No screen has a left rail, a drawer or a
side sheet.

| screen | design hierarchy | implementation | verdict |
|---|---|---|---|
| Home | status / map (left) + music card (right) / bottom nav | `activity_home.xml`: `ConstraintLayout`, map fills, `musicCard` anchored to the end | match |
| Navigation | status / maneuver card + map + music card / ETA strip / nav | same three bands plus `eta_strip_height`, `speedCluster` bottom-left (round 2) | match |
| Music | status / artwork + track info (left) + queue (right) / nav | `activity_music_fullscreen.xml`, two-column | match |
| Search | status / search field + list (left) + preview (right) / nav | `activity_search.xml`; **preview has no map image** — see §10 | partial, documented |
| App drawer | status / 6-column grid / nav | `activity_app_drawer.xml`, six columns at `app_icon_size` 64dp | match |
| Quick settings | status / 2-column tile grid + sliders / nav | `activity_quick_settings.xml` | match |
| Parking | status / clock + subtitle over a photo / nav | `activity_parking_mode.xml`, `parking_clock` 96sp | match |

No layout in `app/src/main/res/layout/` contains a `DrawerLayout`, a
`NavigationView`, or any panel anchored to the start edge that is not the map or
a list. That is a grep fact, not a visual one.

## 2. Map / music ratio

The only ratio §14 names explicitly, and the one figure in this document that
is exact — it is a division of two measured widths in the same panel, so the
collage's scaling cancels completely.

**Design (Home panel).** The music card begins at x 553 of a panel running
14–756. Map region 539 px, music card 195 px, so the split is **73.4 / 26.6**
of the panel width.

**Implementation.** `music_card_width` is **300 dp** of 1024, and the map takes
the remaining width through a constraint: **70.7 / 29.3**.

**Gap: 26.6 % against 29.3 %, i.e. 27 dp too wide.** The music card is not
overflowing — the map still gets 724 dp — so this is a proportion difference,
not a layout break.

**Left as is, deliberately.** Narrowing the card to 273 dp costs the album
title width on exactly the hardware this ships to: `Chạy Về Nơi Phía Anh` and
`Khắc Việt` are the design's own long strings, and at 273 dp minus two 16 dp
paddings they wrap where they do not wrap in the design. 300 dp was chosen from
the rendered string widths, and `music_card_width_compact` (236 dp) already
exists for the narrow-panel case. Recorded rather than "fixed" because changing
it would make the design's own text overflow — and an overflowing title is a
worse defect than a 27 dp proportion difference nobody can see without a
ruler.

## 3. Padding / margin

| token | design (dp) | implementation (`dimens.xml`) | verdict |
|---|---|---|---|
| outer screen padding | not directly measurable per-panel | `screen_pad` 16 | consistent with the 16 dp visible between the card and the panel edge |
| gap between major blocks | ~12 | `gap` 12 | match |
| small gap inside cards | ~8 | `gap_small` 8 | match |
| card internal padding | ~16 | `card_pad` 16 | match |

The panel gutter itself is 10 px in the collage ≈ **14 dp**, which is the
`screen_pad` 16 dp plus a rounding of the mockup. No contradiction found.

## 4. Typography

Measured as cap-height bands: rows containing glyph pixels, grouped where the
vertical gap stays under 2 px, then converted at 1.380 dp/px. The **ratio**
column is the load-bearing one, because a ratio is immune to how the collage
was fitted.

| element | cap band | cap px | cap dp | ratio | implementation | verdict |
|---|---|---|---|---|---|---|
| status clock `17:24` | y39–50 | **12** | 16.6 | — | `text_headline` 24 sp | match |
| speed digit `0` | y85–106 | **22** | 30.4 | digit : unit = **2.20 : 1** | was 24 sp → `text_display` **36 sp** | **was wrong, fixed** |
| speed unit `km/h` | y115–124 | **10** | 13.8 | — | was 10 sp → `text_title` **18 sp** | **was wrong, fixed** |
| nav label `Bản đồ` | y400–408 | **9** | 12.4 | label : icon = **0.75 : 1** | 12 sp | match |
| nav label `Âm nhạc` | y399–409 | **11** | 15.2 | — | 12 sp | match (within band error) |
| parking clock | — | — | — | — | 96 sp | not measurable (panel cropped) |

**The finding that survived every correction is the digit-to-unit ratio: 22 : 10
= 2.2.** The implementation had 24 sp : 10 sp = 2.4 — close on paper, but the
2.2 was achieved in the design at a *cap height of 22 px* while the app used a
24 sp digit whose cap is ~17 sp. Both lines were too small inside a chip drawn
at 146 dp wide, and a small digit in a large card is exactly what makes an app
look unlike its design.

**Fixed.** `speedValue` → `text_display` (36 sp) and `speedUnit` → `text_title`
(18 sp) on both screens that carry the chip. 36 : 18 = **2.0**, the nearest
token pair to the design's 2.2 that exists in `dimens.xml`; the alternatives
were 24 : 12 (2.0 at a much smaller size) or 36 : 24 (1.5, worse), so 36/18 is
the best fit on both axes at once.

**A correction worth recording.** An earlier draft said the digit was 47 px and
the unit "too small by 3:1". The 47 came from one contiguous row band spanning
both lines, because the 8 px gap between them closed up under a coarse
threshold. Row-by-row scanning shows the digit ends at y106, eight empty rows
follow, and the unit starts at y115. The corrected pair is 22 / 10. The wrong
number would have implied a 65 sp digit — and the fact that it did not look
absurd next to the mockup is why the row-by-row check had to be run rather than
trusted to a first impression.

**The status clock matches, and that is the calibration that proves the width
scale.** 12 px × 1.380 = 16.6 dp cap → ≈ 23.3 sp at the Roboto cap ratio
of 0.711, against `text_headline` 24 sp. A 1.380 scale makes this come out
right; a 1.389 or a 1.44 would not have. That is the independent check behind
the scale choice above.

## 5. Icon size / stroke

| element | design | implementation | verdict |
|---|---|---|---|
| bottom-nav icon | 16–27 px → 22–37 dp across the five glyphs | `bottom_nav_icon` 26 dp | match (inside the spread) |
| speed-limit roundel | **47 px → 64.9 dp** diameter, ring **5 px → 6.9 dp** | `speedLimit` **64 dp**, `bg_speed_limit` stroke **7 dp** | **fixed in round 1** |
| map round controls (compass, +, −, layers) | ≈ 44 dp | `map_control_size` 44 dp | match |
| app-drawer icon | 64 dp tile | `app_icon_size` 64 dp | match |
| music transport buttons | ≈ 44 dp | `music_control_size` 44 dp | match |
| music play button | **hollow ring**, 45 px on Home / 54 px on Music, white glyph | 64 dp ring, `bg_circle_ring` stroke 2 dp | **fixed in round 2** |

The roundel figures are not an eyeball estimate. Flood-filling the red pixels in
the Home panel isolates the ring as a single component — a first naive bounding
box over *all* red in that panel returns 395×81 px, because it swallows the red
of the Dragon Bridge photo behind the map. The isolated component is 47×47 px at
fill 0.30, which is what a hollow ring of that diameter must measure
(`1 − (d/D)² ≈ 0.28`). At 1.380 that is **64.9 dp**, and the leading run of red
on the centre row is **5 px → 6.9 dp**. Reproduce with:

```sh
python -I -c "
from PIL import Image
from collections import deque
im=Image.open('docs/UI_design.png').convert('RGB'); px=im.load()
x0,x1,y0,y1=14,756,28,420            # Home panel (caption strip excluded)
S=1024/742
red=lambda p:p[0]>150 and p[1]<90 and p[2]<90
seen=set()
for y in range(y0,y1):
  for x in range(x0,x1):
    if (x,y) in seen or not red(px[x,y]): continue
    q=deque([(x,y)]); seen.add((x,y)); pts=[]
    while q:
      cx,cy=q.popleft(); pts.append((cx,cy))
      for dx,dy in ((1,0),(-1,0),(0,1),(0,-1)):
        n=(cx+dx,cy+dy)
        if x0<=n[0]<x1 and y0<=n[1]<y1 and n not in seen and red(px[n[0],n[1]]):
          seen.add(n); q.append(n)
    if len(pts)>200:
      xs=[p[0] for p in pts]; ys=[p[1] for p in pts]
      w,h=max(xs)-min(xs)+1,max(ys)-min(ys)+1
      if w<200 and len(pts)/(w*h)>0.2:
        cy=(min(ys)+max(ys))//2; run=0
        for xx in range(min(xs),max(xs)+1):
          if red(px[xx,cy]): run+=1
          else: break
        print('%.1f dp, ring %.1f dp'%(w*S,run*S))
"
```

That prints `64.9 dp, ring 6.9 dp`. The implementation uses 64 dp because 64 is
the 8 dp step the rest of the layout is built on (`app_icon_size`,
`music_control_size_large`) and 65 dp is not a value anything else in this
project uses; the 0.9 dp difference is inside the measurement error.

**The roundel was 9 dp small with a ring 1 dp thin.** In the design the ring is
a thick European roundel — visible at arm's length, which is the whole point of
the shape. Ring as a fraction of diameter is the scale-free way to say it:
design **10.6 %** (5/47), implementation **10.7 %** (6/56). The percentage was
already right; the *diameter* was wrong, so the ring rendered 1 dp thinner in
absolute terms at a size where 1 dp is visible.

**Fixed.** `speedLimit` is now **64 dp** × 64 dp and the ring is **7 dp**
(10.9 % of 64). On Home `layout_marginStart` moved from 88 dp to 96 dp; on the
Map screen the roundel moved into the same bottom-left cluster as the chip
(round 2), so its clearance is now the container's 8 dp gap rather than a
hand-tuned offset. The roundel stays `visibility="gone"` until the pack carries
a limit — the size changed, the honest-hidden behaviour did not.

**The play button, measured the same way.** A horizontal scan through the
vertical centre of both music panels returns background / thin bright edge /
background / white glyph bars / background / thin bright edge / background —
the interior is the panel navy, so the shape is an outline, not a disc. The
ring is 45 px on the Home card and 54 px on the Music screen (bbox
(234,669)–(287,722)), i.e. ≈ 1 px of edge, or **1.4 dp** at the 1.380 scale.
Implementation is a 2 dp stroke — the nearest whole dp, under one pixel
different at the target density. The glyph inside is `rgb(252,253,251)`, so the
tint is `text_primary`, not an accent.

## 6. Card radius / border

| element | design | implementation | verdict |
|---|---|---|---|
| large card (music, maneuver) | ≈ 20 dp | `card_radius` 20 dp | match |
| small card (speed chip, chips) | ≈ 14 dp | `card_radius_small` 14 dp | match |

The corner arcs in the collage are 20–28 px on the 742 px panel, i.e. 28–39 dp
at the large-card scale and 19–26 dp at the small-card scale, with the
anti-aliasing making an exact read unreliable. Both declared values sit inside
the measured range, so no gap is claimed. This is the axis where measuring a
2× downscale most easily produces a false finding, and none is filed.

## 7. Active / inactive colours

| role | design | implementation | verdict |
|---|---|---|---|
| active nav item | cyan, icon and label both coloured | `nav_item_tint` selector, active = `icon_active` `#FF20E2FC` | **fixed in round 2** |
| inactive nav item | desaturated grey | `icon_inactive` / `text_secondary` | match |
| speed limit ring | red | `speed_limit_ring` | match |
| speed limit field | white | `speed_limit_fill` | match |
| primary CTA | `rgb(1,117,252)` | `accent` `#FF3B82F6` | match to within the sheet's noise |
| media slider fill | `rgb(0,163,247)` | `text_tertiary` track | documented, not changed |

The active item in the design is the *whole* icon-and-label pair, not just the
icon. That is what the selector does — `.active` in `nav_item_tint` colours
both, because `BottomNavIcon` and `BottomNavLabel` share the same selector.

**This axis was originally filed as "no colour read to better than ±10 per
channel", and that was too pessimistic.** A targeted sample — average a small
patch inside a saturated glyph instead of reading individual pixels through the
collage's artefacts — reproduces the nav accent to within 9/255 across three
independent panels: `rgb(32,226,252)` on Home, `rgb(26,218,251)` on Navigation,
`rgb(3,223,253)` on Search. Three separate panels agreeing means the colour is
the design's, not a compression artefact, and averaging is what makes it
readable.

What that measurement showed is that the sheet uses three different saturated
colours and this project had collapsed them into one `@color/accent`. They are
now separated, with the nav accent moved to `icon_active` `#FF20E2FC`. The CTA
blue was already correct and was left alone.

## 8. Bottom navigation position

| quantity | design | implementation | verdict |
|---|---|---|---|
| position | bottom edge, full width | `BottomNavView` anchored to parent bottom | match |
| height | nav band y374–418 = **44 px → 61 dp** | `bottom_nav_height` **72 dp** | **+11 dp, not changed** |
| item order | Home · Map · Music · Phone · Apps | same, fixed in `view_bottom_nav.xml` | match |
| item distribution | five equal items, then a volume stepper at the end | five `layout_weight=1` items, then `navVolume` | match |
| volume stepper | speaker + `< 24.0° >` | speaker + `−`/value/`+` | **different control, same slot** |
| icon : label ratio | 12–22 px icon, 9–11 px label → **1.3–2.4 : 1** | `bottom_nav_icon` 26 dp, `bottom_nav_label` 12 sp → **2.2 : 1** | match |

**The nav band measures 61 dp against a declared 72 dp. It was left alone, and
here is why that is a judgement and not a fix.** The band's edges sit at
y374–418 in the collage, but 418 is where the panel's own bottom edge is, and
the band is flush to it; an 11 px shortfall against a 44 px status bar
calibration (1.375) and a 1024/742 width calibration (1.380) is larger than the
±1 dp these methods carry. Two explanations fit equally well — the design draws
a 64 dp bar, or the collage's vertical fit compressed the bottom of the panel —
and this document already established that the collage does not preserve
1024×600 vertically. Changing `bottom_nav_height` on a measurement the method
cannot support would be exactly the false precision the spec forbids. Filed as
measured-and-open.

**The `< 24.0° >` is a climate setpoint, and this app does not draw one.** The
value is the media stream level, which the stepper does show. Presenting a
temperature the launcher cannot send to the HVAC is the "control that lies" the
spec forbids — the comment in `view_bottom_nav.xml` says so at the site. The
slot, position and footprint match the design; the content is the thing this
app can actually act on.

## 9. Status bar

| quantity | design | implementation | verdict |
|---|---|---|---|
| height | **≈ 44 dp** (y 28–60, ×1.389) | `status_height` **44 dp** | match |
| clock position | start, first element | start | match |
| right cluster | Bluetooth · signal · Wi-Fi · battery, battery last | same order in `view_status_bar.xml` | match |
| weather chip | `Đà Nẵng 28°C` present | present in the layout, `visibility="gone"` | **absent by design** |

The weather chip is not a gap to fix. There is no weather provider and no
network path, so any temperature printed there would be fabricated — the exact
mock data the spec bans. `KNOWN_LIMITATIONS.md` D4 records it. The chip being in
the layout but `gone` is deliberate: it is the slot for a future provider, not a
forgotten element.

## 10. Empty / loading / error states

This is the axis the design does not cover at all — every mockup shows a
populated screen — so there is nothing to compare against, and what follows is
the implementation's own inventory rather than a gap against a drawing.

| screen | empty | loading | error |
|---|---|---|---|
| Home map | "Chưa cài map pack" card with a retry button | progress in the map host | pack failure rendered from `PackFailureCode`, with the resolved path |
| Home speed | `--` when no trustworthy sample | — | `--` on a stale or unreliable sample |
| Home speed limit | roundel `gone` | — | `gone` when no valid limit |
| Music | "Chưa có nhạc" with the folder hint | scan progress | folder unreadable, with the path |
| Search | prompt before the first query | inline progress | "ngoài vùng dữ liệu" when the point is outside the pack |
| App drawer | "Không có ứng dụng" | — | a `PackageManager` failure is counted and reported, not swallowed |
| Quick settings | — | — | a refused toggle stays off rather than showing success |
| Parking | clock shows `--:--` only with `UNKNOWN`, never `PARKED` | — | manual entry always available |

The two rules worth stating, because they are what the design cannot show:

- **`--` is not `0`.** A speed that is unknown renders as `--`; a speed of zero
  renders as `0`. Conflating them would tell a driver they are stopped when the
  receiver has simply lost the sky.
- **The roundel is hidden, not zeroed.** No valid limit means no roundel, never
  a `0` in the circle.

## What was changed, round 1 — measurements

| # | file | change | reason |
|---|---|---|---|
| 1 | `activity_home.xml`, `activity_map.xml` | `speedValue` 24 sp → `text_display` (36 sp) | design digit : unit = 2.20 : 1 at a 22 px cap; app was 2.4 : 1 at a much smaller size |
| 2 | `activity_home.xml`, `activity_map.xml` | `speedUnit` 10 sp → `text_title` (18 sp) | with (1): 36 : 18 = 2.0, the closest token pair to 2.20 |
| 3 | `activity_home.xml` | `speedLimit` 56 dp → 64 dp, `marginStart` 88 → 96 dp | design roundel is 47 px = 64.9 dp |
| 4 | `activity_map.xml` | `speedLimit` 56 dp → 64 dp | same roundel, same design; the two screens must not disagree |
| 5 | `bg_speed_limit.xml` | ring stroke 6 dp → 7 dp | keeps the design's 10.6 % ring-to-diameter at the new 64 dp |

The roundel is one drawable shared by both screens, so changing its stroke
changes both at once — and leaving `activity_map.xml` at 56 dp would have made
the same limit circle a different size on the two screens that show it. Only
these two layouts declare `@+id/speedLimit`; `res/` has no `layout-*` variant
directory, so there is no third copy to miss.

The clearance was checked, not assumed. The chip is `wrap_content` with
`minWidth="72dp"` at an 8 dp margin on Home (so its end is ≥ 80 dp) and the
roundel now starts at 96 dp — a 16 dp gap survives. Raising the digit to 36 sp
widens the chip for a three-digit readout, which is the case the margin has to
survive; at 36 sp a three-digit string is still under 60 dp wide inside the
chip's 8 dp padding, so 80 dp holds. (On Map the chip and roundel are now one
side-by-side container — see round 2, row 10 — so they share a row there too
and the same clearance argument applies to both screens.)

**Not changed, and why:** `music_card_width` (26.6 % vs 29.3 %, costs title
width), `bottom_nav_height` (61 dp measured vs 72 dp declared, but the collage
does not preserve vertical scale so the measurement cannot support a change),
`bottom_nav_label` (12 sp, inside the 9–11 px band's error), the weather chip
(no provider), the climate setpoint (no HVAC path), the search preview (second
GL surface on hardware that cannot afford one).

## What was changed, round 2 — the sheet's own colours and shapes

Round 1 only compared sizes. Round 2 re-read the sheet looking for places where
the app had the right geometry and the wrong *appearance*, and found six.

| # | file | change | reason |
|---|---|---|---|
| 6 | `colors.xml` | `icon_active` `#FF3B82F6` → `#FF20E2FC` | the selected nav item is cyan on the sheet, not the CTA blue |
| 7 | `bg_eta_strip.xml` | solid `@color/success` → `@color/surface_card_raised` | the ETA band is navy; only the duration inside it is green |
| 8 | `activity_map.xml` | `etaStrip` `textColor` `@color/text_on_accent` → `@color/text_primary` | distance and arrival are white on the sheet |
| 9 | `MapActivity.kt` | `formatEta` colours only the duration span | green belongs to the number, not the surface |
| 10 | `activity_map.xml` | `speedChip` + `speedLimit` `top\|end` → one bottom-left row | design puts the pair bottom-left, side by side |
| 11 | `bg_tab_pill.xml` (new), `activity_music_fullscreen.xml` | selected library tab gets an outlined pill | the sheet draws an outline around the selected tab |
| 12 | `bg_circle_ring.xml` (new), `activity_music_fullscreen.xml`, `view_music_card.xml` | play button solid disc → hollow ring | sheet draws a hairline circle with a white glyph on both screens |
| 13 | `MusicCardView.kt` | compact card hides repeat only, not the whole secondary row | the compact card keeps heart, shuffle and queue |

Each of these was measured before it was changed, and three of them corrected an
earlier claim in this document:

- **The nav accent really is a separate colour.** Sampled `rgb(32,226,252)` on
  Home, `rgb(26,218,251)` on Navigation, `rgb(3,223,253)` on Search — three
  independent panels agreeing to within 9/255 on the same token. The project had
  collapsed three distinct sheet colours (nav active cyan, CTA `rgb(1,117,252)`,
  media slider `rgb(0,163,247)`) into one `@color/accent`. `grep` confirms
  `nav_item_tint` is the only consumer of `icon_active`, and `nav_item_tint` is
  used only by `themes.xml`'s `BottomNavIcon` / `BottomNavLabel`, so the change
  reaches the bottom bar and nothing else.
- **The ETA strip is not green.** Every scanned row of the band (y 320–356 on
  the Navigation panel) is dominantly `rgb(1,22,36)`, and the only saturated
  pixels in the whole band are 318 green ones inside "12 phút" (bbox
  x814–1136). At 3x the other two fields, `6,2 km` and `Đến 17:36`, are white.
  Filling the strip green inverted that.
- **The compact music card does have a secondary row.** Measured three glyphs
  at x 1342, 1408, 1481 on the Navigation panel — heart, shuffle, queue, and no
  repeat. `MusicCardView` was hiding the entire row, which removed three icons
  the sheet keeps.

**One correction to an earlier claim in this document.** `color/music_tab_tint`
carried a comment asserting the design "does not have" an indicator bar under
the selected tab. It does: at 5x the selected tab is an outlined rounded
rectangle with a brighter underscore inside it. The comment is now corrected in
place rather than deleted. The outline is implemented; the underscore is not,
because a 2 px accent line inside a 36 dp pill lands under the target density
and reads as a rendering artefact. Filed here rather than dropped silently.

**The speed cluster is one container now, not two positioned children.** The
roundel previously sat at `marginTop="96dp"` from the top edge, hand-tuned to
clear a chip whose height it did not know. Side by side in a `LinearLayout`,
the roundel's position follows from the chip's width, so a three-digit readout
cannot collide with it. `MapActivity` derives the cluster's visibility from its
two children so an empty container never keeps its place in the layout.

Everything else on the ten axes was already matching. No screen was
restructured, no element was added, and no left menu exists anywhere.

## What this document cannot claim

- **Not a visual comparison.** No frame was rendered, because there is no
  device and no emulator. A comparison of declared numbers against a mockup is
  not the same as seeing the screen, and the rows marked "match" are claims
  about values.
- **The collage does not preserve 1024×600.** The Home panel is 742×389, a
  ratio of 1.91 against the screen's 1.707 — the panels were fitted to a grid.
  Only the horizontal scale is usable, and it is validated twice (status bar
  1.375, panel width 1.380). Vertical sizes are therefore converted with the
  horizontal factor, which is valid only because the panels are drawn to a
  single aspect ratio internally; where that assumption would decide a finding
  (`bottom_nav_height`), the finding was recorded as open instead.
- **Ratios are exact; dp are not.** Every finding this document acts on is a
  ratio (digit:unit, map:music, ring:diameter) or a width-derived dp
  (roundel 47 px, play ring 45/54 px). Differences below ±1 px were not filed
  as gaps.
- **Colour is readable after all, but only by averaging.** Individual pixels in
  the collage are unusable — the sheet is a downscaled composite and carries
  ringing around every edge. Averaging a small patch inside a saturated area
  reproduces a colour to within a few units, which is how the nav accent
  (`rgb(32,226,252)`) and the ETA band (`rgb(1,22,36)`) were identified. What is
  *not* readable that way is anything one pixel wide: the play ring's exact
  stroke lands at 1.4 dp and is rounded to 2 dp rather than claimed precisely.
- **Five of seven panels were measured.** The parking panel is cropped and its
  clock size could not be read; `parking_clock` 96 sp is unchecked.
- **The voice overlay has no mockup.** It exists in the implementation and in
  no panel, so it could not be compared at all.
- **One element is measured but deliberately not drawn.** The selected library
  tab has an outline *and* a 2 px brighter underscore on the sheet. The outline
  is implemented in `bg_tab_pill.xml`; the underscore is not, because at the
  target density it lands under a pixel and reads as an artefact rather than a
  second element.
- **A correction is on the record above.** An earlier draft measured the speed
  glyphs as a single 47 px band and derived a 65 sp digit from it; row-by-row
  scanning showed two bands, 22 px and 10 px. The corrected ratio is what the
  fix implements. The first number is kept in the text because a report that
  silently drops its own error teaches the reader nothing about how much to
  trust the rest.
- **An earlier claim on axis 7 was withdrawn.** This document originally
  asserted that no colour could be read better than ±10 per channel. That was
  a statement about a bad method, not about the sheet — patch averaging reads
  the nav accent reliably. The section now says what was measured and how.

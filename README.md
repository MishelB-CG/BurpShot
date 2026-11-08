# BurpShot (Burp Suite extension, Montoya API)

Builds clean, consistent "Request / Response" evidence screenshots for pentest reports:
red Calibri labels, Burp-style syntax colours, grey separator line, red boxes, arrows, pixelation/blur.

The image is **drawn directly** (Java2D) from the captured message - no OCR, no screen-scraping - so it is
crisp at any scale and always the same format. A live preview, a separate "Preview window", *Copy image*
and *Save PNG* are built in.

## Install
Burp -> Extensions -> Add -> Extension type *Java* -> select `burpshot.jar`.

Build from source: `gradle jar` (output in `build/libs/`), or
`javac --release 17 -cp montoya-api.jar -d out src/main/java/ptshot/*.java` + copy `src/main/resources/META-INF`.

## Use
1. Right-click request(s) in Proxy / Repeater / Logger / Target... -> **Extensions -> BurpShot -> Send to BurpShot**.
2. Open the **BurpShot** tab. Each capture is a tab. Blocks (Request, Response, custom text, images) are listed top-left.
3. **Content tab** - tick first line / headers / body, hide single headers or form parameters, pretty-print JSON, cap body size,
   or edit the text directly. Select text and use:
   * *Selection -> ...*, *Ellipsize outside* (keep `Cookie: ...session=abc...`) for **parts of a value**
   * *Delete / Collapse to ... / Keep only these* for whole lines (JSON elements, headers)
   * *Box / Arrow / Pixelate / Blur* to annotate the selection. Annotations are anchored to the **text**,
     so they follow it when you change filters or edit other lines.
4. **Annotations tab** - colour, line width, padding, full-width box, arrow style (gradient block or simple),
   angle, length, head size, start edge, offsets, pixelate cell size / blur radius.
5. **Style & layout tab** - label text/font/colour/alignment, body font, syntax colouring on/off, background,
   wrap columns / max lines (keeps shots from getting too wide or tall), place a block **beside** the previous one,
   **separator line** after any block (colour/width), and add **custom headers/footers** (`+ Text / header`) or **image blocks**.
   Example: Request -sep- Response -sep- `11,898 bytes | 1,542 millis`.
6. **Global settings** - default label font (Calibri, red), body font (defaults to Burp's message-editor font),
   colours, separator, spacing, syntax colours, wrap/max-lines defaults, output scale. Stored in Burp's preferences.

## Live editing in the preview, undo / redo
* **Click an annotation in the preview** to select it (blue handles). Drag it to **move**; drag a handle to **resize**
  boxes / pixelate / blur regions (8 handles, min size enforced); for **arrows** drag the tail or head circle
  (Shift snaps the angle to 15 degrees) or the line to move it. Arrow keys nudge by 1 px (Shift = 10 px),
  Delete removes the selected annotation, Esc deselects. Clicking one also selects it in the *Annotations* tab.
* Moves/resizes are stored as adjustments relative to the annotated text, so the annotation still follows its text when
  you change filters, wrap width or fonts. *Reset position / size* (Annotations tab) snaps it back to the text.
* **Undo / redo** (toolbar buttons, Ctrl/Cmd+Z, Ctrl/Cmd+Y or Ctrl/Cmd+Shift+Z) cover every change in a capture: text edits,
  filters, annotations (add / edit / delete / move / resize), blocks (add / remove / reorder / duplicate), styles, fonts,
  output scale and max width. Rapid edits (typing, spinners) are merged into one step; each drag is one step. 100 steps are kept
  per capture; history is not saved in the project, but the resulting state is.

## Keeping screenshots from getting too wide
Every capture has a **Max width (px)** box in the preview toolbar (default 900 at 1x, `0` = unlimited; the default
for new captures is in Global settings). It is a hard limit on the final image width:
* text wraps to fit, preferring natural break points (after spaces and `& , ; / ? = - ) ] }`) before hard-breaking;
* blocks placed **beside** each other share the width (narrow blocks keep theirs, wide ones split the rest);
* images shrink to fit;
* annotations, syntax colours and Burp-style colouring follow the wrapped lines.
Per-block *Wrap at columns* still applies on top (the narrower of the two wins), and *Wrap long lines* off cuts lines with `...` instead.
The value is saved with the capture.

## Rendered HTML in screenshots
Two ways to add a rendered page (both end in a drag-a-rectangle picker, and the result becomes an image block that you can
move, resize, centre, separate and point an arrow at):
* **Render selection / body...** (Content tab) - select HTML code in the response text (or select nothing to use the whole body).
  It is rendered with Swing's HTML engine at an adjustable width. This engine is HTML 3.2 + a CSS subset: fine for forms,
  error pages, injected links/tables; not a browser. Scripts, frames, stylesheets, CSS `url()` and images are stripped
  (images become `[image: alt]`), form controls are drawn as `[ text ]`, so rendering an untrusted response never touches the network.
* **Capture screen region...** - for pixel-exact output: press **Render** on the response in Burp, press the button, switch back
  to Burp's Render view during the countdown, then drag the region you want. Multi-monitor and HiDPI aware.
  On macOS, Burp needs Screen Recording permission.

## Persistence
Captures are saved automatically (about a second after every change) into the **Burp project file** via the
extension-data API - the same way Repeater tabs persist - and restored when the project is reopened or the extension
is reloaded. Everything is kept: text, filters, annotations, per-block fonts/styles, layout, separators, image blocks
and output scale. Double-click a capture tab to rename it; closing a tab deletes it from the project.
Global settings (fonts, colours, defaults) are stored separately in Burp's user preferences.
Temporary projects are not saved by Burp itself, so captures vanish with them.

## Notes
* Label size: by default "Request"/"Response" labels are 1.4x the body font size, so they keep their proportion whatever font size
  Burp uses (Global settings -> "Label size = body size x"; set 0 for a fixed size, or give a block its own label font to override).
* Fonts: Calibri is used if installed (on Linux, Carlito is substituted). Any installed font family can be typed in.
* Pixelate/blur only touch the exported pixels; the original data never leaves the tab. Use a large cell size
  for secrets - light blur or tiny cells can sometimes be reversed.
* Image blocks can't carry text-anchored annotations; arrows can still point *at* them from text above.

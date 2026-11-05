# PT Screenshot Builder (Burp Suite extension, Montoya API)

Builds clean, consistent "Request / Response" evidence screenshots for pentest reports:
red Calibri labels, Burp-style syntax colours, grey separator line, red boxes, arrows, pixelation/blur.

The image is **drawn directly** (Java2D) from the captured message - no OCR, no screen-scraping - so it is
crisp at any scale and always the same format. A live preview, a separate "Preview window", *Copy image*
and *Save PNG* are built in.

## Install
Burp -> Extensions -> Add -> Extension type *Java* -> select `pt-screenshot-builder.jar`.

Build from source: `gradle jar` (output in `build/libs/`), or
`javac --release 17 -cp montoya-api.jar -d out src/main/java/ptshot/*.java` + copy `src/main/resources/META-INF`.

## Use
1. Right-click request(s) in Proxy / Repeater / Logger / Target... -> **Extensions -> PT Screenshot Builder -> Send to PT Screenshot Builder**.
2. Open the **PT Screenshot** tab. Each capture is a tab. Blocks (Request, Response, custom text, images) are listed top-left.
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

## Notes
* Fonts: Calibri is used if installed (on Linux, Carlito is substituted). Any installed font family can be typed in.
* Pixelate/blur only touch the exported pixels; the original data never leaves the tab. Use a large cell size
  for secrets - light blur or tiny cells can sometimes be reversed.
* Image blocks can't carry text-anchored annotations; arrows can still point *at* them from text above.

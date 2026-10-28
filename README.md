# Set Scale from Scale Bar

An ImageJ / Fiji plugin (tested with ImageJ 1.54p, Fiji) that calibrates an image from the **scale bar drawn in it**. You draw a rectangle around the scale bar and its label. The plugin measures the bar's length in pixels, reads the label (for example `10 µm`), and applies **Analyze › Set Scale**.

## Features

- **Bar detection:** the length is measured to a fraction of a pixel. It works with:
  - plain bars, with or without end ticks;
  - bars split by their label, as on SEM info bars (`|---- 10 µm ----|`);
  - coloured rulers with intermediate ticks on textured or coloured backgrounds, as in optical microscope software.
- **Avoiding false matches:** frame lines of the info bar and lines of text are not mistaken for the bar. When several lines could be the bar, the one with the label next to it wins.
- **Label reading:** built-in character recognition with no external software. It reads numbers (`500`, `2.5`, `0,5`) and the units nm, µm (or um), mm, cm and m. It handles light text on dark and dark text on light, including labels in a framed box.
- **Confirmation dialog:** shows the detected bar (red line) and the label it read. You can correct the values, and choose whether the bar is measured between its outer ends or between the centres of its end ticks.
- **Global:** an option applies the scale to all images.
- **Image series:** if no rectangle is drawn, the rectangle from the previous image is reused.
- **Faster use:** an option skips the dialog once you trust the reading. Hold Shift when starting the command to bring it back.

## Installation

1. Download `Scale_Bar_Calibration.jar` from the [Releases](../../releases) page.
2. Copy it into `Fiji.app/plugins/`.
3. Restart Fiji. The command is **Plugins › Measurement › Set Scale from Scale Bar**.

## Usage

1. Draw a rectangle (rectangle tool) around the scale bar and its label, with a little margin.
2. Run **Set Scale from Scale Bar**.
3. Check the values in the dialog and click OK.

Tip: assign a keyboard shortcut with Plugins › Shortcuts › Add Shortcut…

## Limitations

- Text recognition works best with sans-serif fonts (Arial, Tahoma, Segoe UI and similar), which SEM and optical microscope software usually use. Check the dialog for the first few images from a new instrument.
- Only horizontal scale bars are supported.
- If the label cannot be read reliably, the dialog asks you to enter the value.

## Building from source

You need a JDK (8 or newer). Fiji includes one in `Fiji.app/java/`.

```sh
./build.sh /path/to/Fiji.app/jars/ij-1.54p.jar          # Linux / macOS
build.bat "C:\path\to\Fiji.app\jars\ij-1.54p.jar"       # Windows
```

The plugin is written to `dist/Scale_Bar_Calibration.jar`.

## Authors

- **Justinas Jorudas**
- **Claude** (Anthropic), AI assistant who co-developed the code

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

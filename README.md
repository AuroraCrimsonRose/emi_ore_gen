# EMI Ore Generation

An EMI addon for Minecraft 1.21.1 (NeoForge) that shows where ores actually generate in your
world, including modded worldgen and GregTech CEu ore veins.

Instead of guessing at wiki numbers that may not match your pack, this reads the worldgen
registries on the server at runtime and reports what is really there.

## What it adds

Four EMI categories:

**Ore Generation.** One page per ore material. Shows the depth range on a chart, what the ore
drops when mined, and every source that produces it. Arrows cycle through the dimensions and
biomes it generates in. Each source lists its size class, how often it turns up, and (for vein
ores) what fraction of the vein it makes up.

**Ore Veins.** One page per GregTech vein: its contents, each ore's share, the spawn range, and
the surface rock that marks it. Replaces GregTech's own vein diagram by default so you are not
looking at two versions of the same thing. Configurable.

**Biome Generation.** One page per biome, listing everything that generates there sorted by how
common it is. Answers "I am standing here, what is under me".

**Dimension Generation.** The same thing one level up, covering a whole dimension.

Pages link to each other. Clicking a biome or dimension name on an ore page opens that region's
page, and clicking a vein name opens the vein.

## How it works

Dimensions and biomes are resolved structurally rather than guessed from feature names. Every
`LevelStem` exposes a chunk generator, which exposes a biome source, which lists its possible
biomes, each of which carries the placed features that generate inside it. That chain is the
only authoritative answer to where an ore spawns, and it means any mod using standard worldgen
is picked up without needing specific support.

GregTech is handled separately because its veins are not placed features. The mod reads the
`gtceu:ore_vein`, `gtceu:bedrock_ore` and `gtceu:bedrock_fluid` registries directly.

Drops come from each block's loot table, so looking up Raw Iron or Coal finds the ore that
produces it.

The index is built on the logical server, gzipped, and sent to the client on join. It is
rebuilt only when the data actually changes, so rejoining a world does not trigger a rebake.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.242 or newer
- EMI 1.1.0 or newer

GregTech CEu Modern 8.0.0 is optional. Without it the mod still indexes vanilla and any other
mod's ore features.

## Configuration

Config lives in `config/emioregeneration-common.toml`, or through the Mods screen.

| Option | Default | Effect |
| --- | --- | --- |
| `includeGregTech` | true | Index GregTech veins, bedrock ores and bedrock fluids |
| `includeBedrockFluids` | true | Index bedrock fluid reservoirs alongside solid ores |
| `showSurfaceIndicators` | true | Show the surface rock that marks a vein |
| `useOwnVeinDiagram` | true | Use this mod's vein pages and hide GregTech's |
| `maxEntriesPerPage` | 3 | How many sources to list before paging |

## Building

```
./gradlew build
```

The jar lands in `build/libs`. Java 21 is required.

For a dev client:

```
./gradlew runClient
```

The `libs` folder holds the mods used for testing. GregTech is on the compile classpath as
`compileOnly` so it stays optional at runtime; the rest are `localRuntime` only, present to
populate the dev world with ores to index.

## Known limitations

**GregTech disables vanilla ore generation.** With GregTech installed, coal, iron, copper and
the rest do not spawn as vanilla features. GregTech strips them from `OreConfiguration` at load
time, and this mod reports that honestly rather than showing generation that will not happen.
Their pages say so instead of being blank.

**Materials are grouped by `c:ores/*` tags.** Ores from different mods share a page when they
share a tag. If two mods tag the same material differently, they will end up on separate pages.

**Immersive Petroleum reservoirs are not indexed.** They use a recipe based system rather than
worldgen features and need their own extractor.

**GregTech bedrock fluids show as covering all biomes.** They use a biome weight modifier rather
than a biome list, which has to be evaluated per biome to get real numbers.

## Contributing

Bug reports are more useful with `logs/latest.log` attached, or at least the lines from
`com.catx.emioregen`. The mod logs how many occurrences it indexed, how many pages it
registered, and how many of those have no natural generation, which is usually enough to tell
whether a problem is in extraction or display.

## License

MIT. See `LICENSE`.

Minecraft mappings are covered by Mojang's license; see
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
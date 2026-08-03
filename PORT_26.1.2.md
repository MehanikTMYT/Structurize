# Structurize port: Minecraft 26.1.2 / NeoForge 26.1.2.68-beta

This document tracks the port of Structurize from `version/1.21` (Minecraft 1.21.1 /
NeoForge) to Minecraft **26.1.2** on **NeoForge 26.1.2.68-beta**, running on **Java 25**.

## Target versions

| Component      | Value                                             |
|-----------------|----------------------------------------------------|
| Minecraft       | 26.1.2                                             |
| NeoForge        | 26.1.2.68-beta                                     |
| Java            | 25 (`javaVersion=25` in `gradle.properties`)       |
| BlockUI         | `com.ldtteam:blockui:0.0.1-26.1.2` (local Maven)   |
| Domum Ornamentum| `com.ldtteam:domum-ornamentum:1.0.0-local` (local) |

Local dependencies resolve through `mavenLocal()` (see `build.gradle`); build them from the
sibling `BlockUI` / `Domum Ornamentum` repos beforehand (neither repo was modified by this
port).

## Build

```
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
PATH=/usr/lib/jvm/java-25-openjdk-amd64/bin:$PATH \
./gradlew \
  -PopcScript=/root/home/forks/OperaPublicaCreator/gradle/mod.gradle \
  build \
  --no-configuration-cache \
  --console=plain
```

`compileJava`, `processResources classes`, and the full `build` (including `assemble`,
`test`, `check`) all succeed as of this port. The resulting jar is written to
`build/libs/structurize-0.0.1-26.1.2.jar`.

`build.gradle` additionally sets `-Xdiags:verbose -Xmaxerrs 5000 -Xmaxwarns 5000` on all
`JavaCompile` tasks (javac's default 100-error cap otherwise hides most of the real error
count during a port of this size) and excludes three files from `sourceSets.main.java` (see
"Temporary exclusions" below).

`gradle.properties`: `forgeVersion` was updated from `26.1.2.78` to the requested
`26.1.2.68-beta`; Parchment mappings config (`usesParchment=false`) was left untouched since
it was already disabled and 26.1.2 mappings are handled by NeoForm directly.

## Changed API families

Every fix below follows the same rule the task specified: prefer a proper migration to the
current API over a compatibility shim, and never narrow behavior (e.g. hardcoding a vanilla
dimension) to route around a removed generic API. Fixes are marked `PORT-26.1.2:` in code
where the reasoning isn't obvious from the diff alone.

- **`ResourceLocation` → `Identifier`**: renamed to `net.minecraft.resources.Identifier`
  across the whole codebase (registries, key mappings, model loaders, tags).
- **NBT "Optional" API**: `CompoundTag`/`ListTag` getters now return `Optional<T>`
  (`getIntOr`, `getStringOr`, `getCompoundOrEmpty`, `contains`, etc. replace the old
  unconditional getters). `CompoundTag.putUUID(...)` was replaced by
  `CompoundTag.store(key, UUIDUtil.CODEC, uuid)`.
- **BlockEntity / Entity persistence**: raw `CompoundTag` + `HolderLookup.Provider`
  save/load signatures were replaced by the codec-based `ValueInput`/`ValueOutput`
  (`TagValueInput`/`TagValueOutput`) system. `BlockEntity#saveToItem(ItemStack,
  RegistryAccess)` was removed; the replacement is building a `TagValueOutput` via
  `entity.saveCustomOnly(output)` and handing it to
  `BlockItem.setBlockEntityData(stack, type, output)` (see `BlockTagSubstitution`).
  `Entity#getPickedResult(HitResult)` → `Entity#getPickResult()`.
  `Entity#moveTo(x,y,z,yaw,pitch)` → `Entity#snapTo(x,y,z,yaw,pitch)`.
- **Rendering pipeline overhaul**: `RenderType` moved to
  `net.minecraft.client.renderer.rendertype`; the old GL-immediate rendering APIs
  (`BlockRenderDispatcher#renderSingleBlock`, `Camera#setup`, direct `VertexBuffer`
  bind/draw, `GlStateManager` blend state, `ItemBlockRenderTypes.setRenderLayer`) were
  removed in favor of a `RenderPipeline`/`RenderSetup`/`GpuBuffer` declarative pipeline and
  a two-phase "extract render state, then submit" model for entity/block-entity rendering
  (`EntityRenderState`, `BlockEntityRenderState`, `SubmitNodeCollector`). See "Temporary
  limitations" below — this is the one area where functionality is currently stubbed rather
  than fully re-implemented.
  `RenderLevelStageEvent` lost its `Stage` enum/`getStage()` in favor of per-stage
  subclasses (`AfterOpaqueFeatures`, `AfterTranslucentBlocks`, etc.), so
  `WorldRenderMacros`/`WorldRenderContext` now branch on `instanceof`.
  `Camera#getPosition()` → `Camera#position()`.
- **`Capabilities.ItemHandler` → `Capabilities.Item`**: now typed
  `ResourceHandler<ItemResource>` instead of `IItemHandler`. `ItemStackUtils` bridges back
  to the still-supported (deprecated) `IItemHandler` via `IItemHandler.of(handler)` so the
  rest of the codebase's `IItemHandler`-based inventory code didn't need a full rewrite.
  `ItemStack#getCapability(ItemCapability<T,C>)` now requires an explicit `C` context
  argument for item capabilities — `ItemAccess.forStack(stack)` is used where the old
  no-arg call used to work.
- **Datagen**: `GatherDataEvent` is now abstract with `.Server`/`.Client` subclasses
  (replacing `event.includeServer()`/`includeClient()` boolean gates) and dropped
  `ExistingFileHelper` entirely; `IntrinsicHolderTagsProvider`/`TagsProvider` constructors
  lost the mod-id/`ExistingFileHelper` trailing parameters. `IntrinsicTagAppender<T>` was
  renamed to `TagAppender<T, T>`. `Registry#getTag(TagKey)` (returning
  `Optional<HolderSet.Named<T>>`) was removed in favor of `Holder#is(TagKey<T>)` for
  membership checks.
- **`InteractionResult`**: changed from an enum to a sealed interface with static
  instances (`SUCCESS`, `FAIL`, `PASS`, ...); `switch` statements on it now use type
  patterns (`case InteractionResult.Pass ignored:`) instead of enum constant labels.
- **`Item#canAttackBlock(BlockState, Level, BlockPos, Player)` removed**: the equivalent
  hook is now `PlayerInteractEvent.LeftClickBlock` (`setUseBlock`/`setUseItem` with
  `TriState.FALSE`). All three affected subclasses (`AbstractItemWithPosSelector`,
  `ItemScanTool`, `ItemTagTool`) were converted from an item-method override to a plain
  `onLeftClickBlock(BlockPos, Player)` method, dispatched from a new
  `EventSubscriber#onLeftClickBlock` handler registered on the common (both-sides) event
  bus — this preserves the original per-item pos-selection behavior without narrowing it to
  a single item class.
- **Crafting remainder**: `Item#getCraftingRemainingItem(ItemStack)` /
  `#hasCraftingRemainingItem(ItemStack)` were replaced by a single
  `IItemExtension#getCraftingRemainder(ItemInstance)` returning a nullable
  `ItemStackTemplate` (`ItemBuildTool`, `ItemShapeTool`). `ItemStack#getCraftingRemainingItem()`
  itself was removed too; callers now go through
  `stack.getItem().getCraftingRemainder(stack)` and `.create()` (`InventoryUtils`).
- **Registry/dimension lookups**: `RegistryAccess#registryOrThrow(ResourceKey)` →
  `#lookupOrThrow(ResourceKey)`. `ResourceKey#location()` → `#identifier()`.
  `LevelHeightAccessor#getMinBuildHeight()/getMaxBuildHeight()` (exclusive top) →
  `#getMinY()/#getMaxY()` (inclusive top), with off-by-one adjustments where ranges were
  iterated. `ChunkPos#x/#z` fields → `#x()/#z()` methods.
- **Dimension attributes (explicit correction, see below)**: `DimensionType#ultraWarm()`
  and similar boolean per-dimension flags were replaced by the generalized
  `EnvironmentAttributes` system (`level.environmentAttributes().getDimensionValue(...)`),
  which stays mod-agnostic for custom dimensions instead of hardcoding a vanilla dimension
  key comparison. See `BlockUtils#isWaterAbovePosNearby`/related.
- **`GameProfile`**: `com.mojang.authlib.GameProfile` is now a record; `getName()` →
  `name()`.
- **`FMLEnvironment`**: `.dist`/`.production` fields → `getDist()`/`isProduction()` methods.
- **`SharedConstants.getCurrentVersion()`**: `.getDataVersion().getVersion()` →
  `.dataVersion().version()` (the nested type is a distinct `world.level.storage.DataVersion`
  record, unrelated to this mod's own `DataVersion` class of the same simple name).
- **`AddClientReloadListenersEvent`** (renamed from `RegisterClientReloadListenersEvent`):
  `event.registerReloadListener(listener)` → `event.addListener(Identifier, listener)` —
  now requires an explicit name for sort/dependency graph purposes.
- **`Player#getCommandSenderWorld()`/`Entity#getCommandSenderWorld()`** → `Entity#level()`.
  **`Inventory#getSelected()`** → `#getSelectedItem()`. **`ServerPlayer#getServer()`**
  removed → `player.level().getServer()` (`ServerPlayer#level()` has a covariant
  `ServerLevel` return type).
- **`IModFile#findResource(String...)`** removed; the mod-relative blueprint folder path is
  now resolved manually against `IModFile#getContents()#getContentRoots()` (see
  `ClientStructurePackLoader`/`ServerStructurePackLoader`).
- **`DirectionProperty`** removed; it was already just `EnumProperty<Direction>` under the
  hood, so all usages (`FacingFixer`, `WindowReplaceBlock`) now reference
  `EnumProperty<Direction>` directly.
- **Tooltip rendering**: `ClientTooltipComponent`'s immediate-draw `GuiGraphics` methods
  were replaced by an "extract" model (`GuiGraphicsExtractor`/`target.text(...)`/
  `target.item(...)`) — `ClientItemStackTooltip` was rewritten against the new contract.
- **`net.neoforged.neoforge.client.model.data.ModelData`** moved to
  `net.neoforged.neoforge.model.data.ModelData` (dropped the `client` package segment).

## Explicit correction applied during the port

An early pass replaced `world.dimensionType().ultraWarm()` in `BlockUtils.java` with a
hardcoded `world.dimension() != Level.NETHER` comparison. This was **wrong** — it silently
narrows the check to vanilla's Nether key and breaks for any modded "ultrawarm" dimension.
It was corrected to use the mod-agnostic `EnvironmentAttributes` system
(`environmentAttributes().getDimensionValue(EnvironmentAttributes.WATER_EVAPORATES)`),
which is what `DimensionType#ultraWarm()` itself is now implemented in terms of. Any future
dimension-behavior check ported from an old boolean `DimensionType` property should use this
same generalized API rather than a vanilla dimension-key comparison.

## Temporary limitations / exclusions

- **Blueprint preview rendering is disabled** (`BlueprintRenderer#drawUnsafe` logs a
  one-time warning and returns without drawing). `BlockRenderDispatcher#renderSingleBlock`
  and `Camera#setup` were removed, and `EntityRenderDispatcher`/`BlockEntityRenderDispatcher`
  now require the two-phase extract-render-state/submit model instead of a direct
  `.render(...)` call — reimplementing the full blueprint quad-baking + entity/block-entity
  rendering against that model safely needs in-game iteration this port could not perform.
  Structure data, scanning, placement, and blueprint serialization are **not** affected —
  only the live preview overlay. `TagSubstitutionRenderer` (the held-item preview for the
  tag-substitution tool) is a documented no-op stub for the same reason.
  `WorldRenderMacros`/`WorldRenderContext` (box/line/debug-text overlays, frustum culling)
  were fully ported and are not part of this limitation; `isVisible(...)` frustum-culling
  currently always returns `true` since the old `Frustum`-based check isn't available in the
  new pipeline — a minor perf regression (things render even when off-screen), not a
  correctness one.
- **Three model-loader source files are excluded from compilation** via
  `sourceSets.main.java.exclude` in `build.gradle`:
  `client/model/OverlaidGeometry.java`, `OverlaidBakedModel.java`, `OverlaidModelLoader.java`
  (the custom `structurize:overlaid` geometry loader). Its registration call in
  `ClientLifecycleSubscriber` was removed as well. `blocktagsubstitution.json` was
  simplified to a plain `"parent"` reference instead of the custom loader. This is an
  isolated, optional visual integration (an overlay texture affordance), not core
  functionality.
- **Datagen is present but not runtime-verified beyond compilation.** `gradle.properties`
  already had `usesDatagen=false` before this port (datagen isn't wired into the OPC build
  pipeline for this project), and the generic `runServerData` NeoGradle task doesn't have
  program arguments configured for it in this project, so it just prints its own `--help`
  when invoked directly. `EntityTagProvider`/`BlockTagProvider`/`BlockEntityTagProvider`
  and `LifecycleSubscriber#onDatagen` were fully migrated to the new
  `GatherDataEvent.Server`/`.Client` API and compile/typecheck correctly, but exercising the
  full data-generation run wasn't part of this project's existing workflow either before or
  after the port.

## Remaining errors

None — `compileJava`, `processResources classes`, and the full `build` (assemble + test +
check) all succeed.

## Runtime testing still required

This port has **not** been verified in a running client/server. Before considering it
release-ready, at minimum:

- Load a world, scan a structure with the scan tool, verify the saved blueprint file.
- Place a structure with the build tool; verify block/entity/block-entity placement,
  rotation/mirror, and undo/redo.
- Verify block substitution / tag substitution placeholders behave correctly (they render
  via block models, not the disabled Java renderer, so should be visually correct even with
  preview rendering off).
- Confirm the (currently disabled) blueprint preview overlay is an acceptable gap for this
  release, or schedule the dedicated follow-up to reimplement it against the new
  extract/submit rendering model.
- Exercise BlockUI windows (scan/build/shape tool GUIs, replace-block, structure browser) —
  BlockUI itself was ported separately (`com.ldtteam:blockui:0.0.1-26.1.2`) and its own
  compatibility wasn't re-verified end-to-end here beyond compiling against it.
- Verify structure pack discovery from both the game directory and other mods' jars, given
  the `IModFile#findResource` → `getContentRoots()` rewrite in
  `ClientStructurePackLoader`/`ServerStructurePackLoader`.
- Verify the tag tool / pos-selector left-click flow in game, given the
  `canAttackBlock` → `PlayerInteractEvent.LeftClickBlock` migration.

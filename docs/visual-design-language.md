# StarChem Visual Design Language

Issue: #389  
Parent graphics initiative: #388

This document is the visual contract for world-space graphics in StarChem. New or reworked ships, stations, celestial bodies, resources, backgrounds, and effects should follow these rules unless a later design decision explicitly updates this document.

The goal is a readable 2D space RTS that looks authored, industrial, and game-specific rather than like a collection of generic procedural shapes with neon outlines.

## Core direction

**Authored identity first; procedural variation second.**

A player should be able to recognize what an object is from its silhouette, proportions, major structures, and material treatment before relying on labels, glow, or player color.

StarChem should feel like practical machinery operating in space:

- mass is visible;
- structure implies function;
- engines, hangars, sensors, tools, armor, storage, and weapons have obvious places to exist;
- surfaces are mostly neutral industrial materials;
- faction/player color identifies ownership but does not replace material design;
- emissive light is reserved for things that plausibly emit energy;
- backgrounds support the battlefield instead of competing with it.

Avoid visual decisions whose only justification is "the seed made it different." Randomness may decorate authored forms, but must not define primary identity.

## Visual hierarchy

At normal play distance, visual importance should generally read in this order:

1. active combat and danger VFX;
2. selected/commanded gameplay objects;
3. ships and stations;
4. harvestable resources and objectives;
5. planets, moons, stars, and environmental objects;
6. starfields, nebulae, dust, and decorative background detail.

A lower-priority layer should not routinely use more contrast, saturation, animation, or glow than the layer above it.

## Material language

The visual palette describes rendered surface materials. It is separate from the gameplay `Material` enum.

### Structural steel

Use for exposed frames, trusses, machinery housings, station skeletons, weapon mounts, and engine structures.

- Value: dark to mid-dark neutral gray.
- Character: cool, dense, utilitarian.
- Detail: seams, braces, bolts, ribs, edge wear.
- Do not tint the whole material with faction color.

Suggested reference range: `#1A2028` to `#59636F`.

### Industrial plating

Use for ordinary hull plates, cargo shells, maintenance covers, station modules, and service structures.

- Value: dark-mid to mid neutral gray.
- Character: panelized and replaceable.
- Detail: plate boundaries, access panels, vents, hazard markings, repair patches.

Suggested reference range: `#313A43` to `#7A838B`.

### Ceramic/composite armor

Use for military armor blocks and high-temperature/protected sections.

- Value: mid-light relative to steel, but not bright white.
- Character: smoother, heavier, cleaner than industrial plating.
- Detail: larger armor panels with fewer seams.

Suggested reference range: `#7D8284` to `#B1B4B2`.

### Painted identification panels

Use sparingly for role/faction markings, warning panels, squad markings, and manufactured modules.

- Can carry player/faction color.
- Should sit on top of a neutral material base.
- Typical visible coverage: about 10-20% of the object at detailed zoom.
- Avoid exceeding roughly 30% of visible hull area except on very small icons/markers where ownership would otherwise become unreadable.

### Glass / transparent sensor surfaces

Use for cockpits, observation sections, sensor lenses, and selected laboratory components.

- Prefer dark cyan, smoky blue-gray, or near-black reflective treatment.
- Bright cyan should be a reflection/emissive accent, not the entire glass surface.
- Large windows should be rare on military hulls.

Suggested body range: `#0B1720` to `#274352`, with small highlights up to `#A7E8F5`.

### Hazard and service markings

Use yellow/amber, white, muted orange, or red for industrial warnings, docking markings, weapon danger zones, cranes, and maintenance areas.

These markings should communicate function and scale rather than decorate every surface.

### Emissives

Emissive color is reserved for actual energy/light sources:

- engine exhaust and reactor vents;
- navigation lights;
- windows/interior lights;
- powered sensors;
- weapon charge/firing effects;
- shields;
- active ECM/radar effects;
- explosions and energized resource phenomena.

A hull edge is not emissive merely because the object belongs to a player.

## Player and faction color

Player color is an identification layer, not the base hull material.

Preferred uses:

- narrow hull stripes;
- ID panels;
- insignia blocks;
- navigation/formation lights;
- hangar markings;
- antenna tips or sensor nodes;
- small station ownership panels;
- command/selection overlays;
- far-zoom markers where detail is unavailable.

Avoid:

- filling an entire ship with player color;
- outlining every hull edge in a bright player color;
- using faction color as the main light source;
- making two ship roles identical except for ownership color.

At far zoom, player color may become stronger because the renderer is intentionally reducing geometry. At tactical and detailed zoom, neutral materials should dominate.

## Ship silhouette language

Primary silhouette communicates role. Minor procedural changes must not destroy the role signature.

### Scout

Read as fast, light, and sensor-oriented.

- narrow forward profile;
- low visual mass;
- pronounced nose/sensor feature;
- compact engines;
- limited bulky storage.

Do not make scouts broad, boxy, or station-like.

### Miner

Read as an industrial working ship.

- obvious forward or lateral mining equipment;
- reinforced working end;
- visible processing/storage modules;
- heavier utility structure than a scout;
- engines should look sized to move machinery, not like a fighter tail.

### Gas harvester

Read as a collection/intake vessel.

- scoops, intake mouths, compressor housings, or containment pods;
- broader working geometry than a conventional miner;
- tank/pressure-vessel cues are appropriate;
- avoid simply recoloring a mining hull cyan/green.

### Cargo / freighter

Read from storage mass first.

- large central or repeated cargo blocks;
- tug/drive section visually distinct from storage;
- long, wide, or segmented depending on class;
- limited weapon-like protrusions.

Cargo capacity should feel visible in silhouette.

### Builder / deployer

Read as fabrication/construction equipment.

- cranes, arms, fabrication bays, frames, or modular deployment hardware;
- asymmetric working detail is acceptable;
- broad utility geometry is preferable to fighter-like wings.

### Combat

Read as armored and weapon-oriented.

- clear forward threat direction;
- weapon hardpoints or armored weapon sections;
- compact protected core;
- less exposed cargo/industrial clutter than utility hulls.

Different combat classes should still have authored silhouettes; do not use one generic fighter polygon scaled to every size.

### Carrier

Read from flight operations.

- broad or elongated launch/hangar geometry;
- visible bay mouths/deck breaks;
- large support mass;
- defensive weapon structures secondary to the hangar signature.

### Siege / capital artillery

Read as massive weapon architecture.

- long axial weapon, armored spinal structure, heavy broadside sections, or another unmistakable siege feature;
- strong sense of mass and recoil/heat-management structure;
- engines and support structures should not visually outweigh the main weapon system.

### Monolith / special hulls

Special ships may deliberately break normal role rules, but their abnormality should be authored and consistent. "Special" is not permission for arbitrary random geometry.

## Ship size language

Scale should be legible from more than raw dimensions.

Larger ships should gain:

- more structural segmentation;
- multiple engine clusters instead of one enlarged engine dot;
- repeated windows/service lights;
- larger armor plates and visible secondary structures;
- hangars, antennae, turrets, radiators, or utility modules appropriate to role;
- more negative space and internal structure where appropriate.

Do not make a capital ship look like a small ship uniformly scaled 3x.

## Station architectural language

Stations should read as assembled infrastructure rather than oversized ship icons.

Common station cues:

- structural frames and trusses;
- docking arms and approach geometry;
- radial, axial, or modular construction logic;
- repeated habitat/service/industrial modules;
- antenna clusters;
- radiator panels;
- cargo tanks and service gantries;
- windows/service lights used as scale cues;
- clearly larger component repetition than ships.

### Station role cues

**Shipyard:** open construction space, docking arms, gantries, large hangar/construction bays.  
**Manufacturing:** bulky industrial modules, pipes, tanks, loading interfaces, repeated production blocks.  
**Laboratory:** cleaner modules, sensor dishes, observation/sensor elements, controlled emissive accents.  
**Radar:** physical mast/dish/array first; sweep/ring effects second.  
**Jammer/ECM:** believable antenna/emitter structure first; distortion/pulse VFX second.  
**Defense or military:** armored central mass, obvious weapon mounts, limited exposed industrial clutter.

A station should remain recognizable when its animated effect is temporarily removed.

## Celestial bodies

### Stars

Stars are environmental light sources, not simple glowing circles.

- establish the dominant lighting direction for nearby major bodies when practical;
- use restrained corona/halo falloff;
- reserve the brightest values for the stellar core and limited bloom;
- avoid large saturated glow that washes out nearby gameplay objects.

### Planets and moons

Planets should communicate volume through lighting.

- use a consistent lit side and terminator;
- add atmosphere only where appropriate;
- separate surface pattern from lighting so texture does not look self-illuminated;
- rings should have front/back depth ordering around the planet;
- city lights, lava, storms, or other emissives should be localized phenomena.

Planet detail can be procedural, but body type, palette, major bands/continents/craters, ring identity, and lighting direction should remain coherent.

### Asteroids and resource rocks

Asteroids should read from geology and deposits, not from a colored outline.

- use irregular authored families/shapes with local variation;
- add facets, cracks, shadows, embedded deposits, or exposed veins;
- use gameplay material color as a deposit/accent cue rather than coloring the full rock;
- depletion may reduce visible deposit coverage or apparent mass.

### Gas clouds

Gas may use color more strongly than solid resources, but it should still feel volumetric/environmental.

- use layered opacity and varied cloud structure;
- avoid a clean neon ring around the whole cloud;
- keep the center/edge opacity irregular;
- use brighter regions to imply density or active harvestable material.

## Lighting

StarChem uses stylized rather than physically exact lighting, but objects should obey a consistent logic.

- Neutral materials define the base object.
- A primary directional light should create a readable light side and shadow side on large/detailed objects where practical.
- Ambient light may keep the shadow side readable, but should not flatten the object.
- Emissives add localized light accents and do not recolor the entire hull.
- Specular/highlight treatment should depend on material: glass and ceramic can be cleaner/brighter; industrial steel should remain rougher/darker.
- Effects may temporarily exceed normal object brightness, especially weapons and explosions.

Do not add glow merely to compensate for weak silhouette or poor contrast.

## Backgrounds and battlefield contrast

Background detail must never become the highest-contrast layer of the scene.

Recommended relative value hierarchy on an 8-bit display:

- empty/deep background: roughly 4-20;
- most starfield/nebula body: roughly 10-45;
- occasional bright nebula/starfield detail: generally below 70;
- dark hull materials: roughly 20-80;
- lit hull materials: roughly 60-170;
- identity accents: roughly 80-220 depending on saturation;
- true emissives/combat flashes: roughly 180-255.

These are art targets, not renderer assertions. Preserve separation more than exact numeric values.

### Starfield

- use multiple star sizes sparingly;
- most stars should be dim and tiny;
- bright stars should be uncommon;
- avoid even distribution that looks like noise;
- do not animate the entire field in a way that distracts from unit movement.

### Nebulae and dust

- use broad low-frequency shapes rather than noisy equal-strength detail everywhere;
- keep high saturation localized;
- avoid bright cyan/magenta clouds behind common player colors;
- prefer regional identity over random full-screen color washes.

## VFX and glow budget

Transient effects are allowed to be visually stronger than persistent world geometry.

### Persistent effects

Examples: radar operation, ECM state, powered station emitters, engine idle.

- keep glow localized to emitter hardware;
- prefer thin pulses, directional sweeps, particles, or brief modulation over large permanent halos;
- the underlying object should stay readable without the effect;
- avoid stacking several saturated rings on one persistent object unless gameplay requires each ring.

### Combat effects

Weapons, impacts, shields, and explosions may use the highest brightness and saturation in the scene.

- make weapon families visually distinct through shape, travel behavior, impact, and color;
- use a bright core with controlled falloff rather than a uniformly glowing blob;
- explosions should expand/change over time rather than remain a static bloom;
- debris and smoke/dust should provide a lower-brightness tail after the flash.

### Engines

- exhaust direction must match ship orientation/thrust;
- engine glow belongs at engine hardware;
- larger ships should use clusters or larger structured exhausts rather than a single scaled oval;
- faction color is not the default engine color.

## UI and world-overlay hierarchy

World-space UI exists to communicate gameplay state and should not become permanent decoration.

Preferred rules:

- selection state should be clear but visually separate from hull art;
- range circles appear only when requested or gameplay-critical;
- route/work lines should remain thinner and lower-contrast than ships;
- HP, cargo, fuel, production, and status labels should appear only when the existing gameplay policy requires them;
- labels need a dark backing or other contrast treatment when placed over complex backgrounds;
- ownership can use player color strongly in overlays because overlays are explicitly informational.

Do not bake UI rings, ownership halos, HP bars, or labels into the visual identity of the underlying asset.

## Detail density and zoom

StarChem already renders ships at different detail levels. Art should be authored to survive those tiers.

### Far / strategic zoom

Priority:

- object presence;
- ownership;
- broad class/role where possible;
- direction only when useful.

Use simple markers and strong ownership cues. Do not attempt fine panel detail.

### Tactical / medium zoom

Priority:

- primary silhouette;
- role-defining geometry;
- faction accent;
- engine/hangar/weapon signature;
- a few major surface divisions.

This is the most important readability tier for normal play.

### Detailed / close zoom

Add:

- plating boundaries;
- windows/service lights;
- turrets and hardpoints;
- vents/radiators;
- antennae;
- cargo segmentation;
- maintenance markings;
- small emissive sources;
- restrained wear/variation.

Close detail should enrich an already recognizable silhouette, not create identity that disappears at tactical zoom.

Renderer thresholds are implementation tuning and are not part of this art contract. The design requirement is that each object has a useful far, tactical, and detailed representation.

## Procedural variation rules

Procedural generation is allowed only where it preserves authored recognition.

| Element | Procedural variation | Rule |
| --- | --- | --- |
| Primary ship silhouette | No / tightly bounded | Role and class identity must be authored. |
| Major ship proportions | Tightly bounded | Variation must not change perceived role or size class. |
| Weapon/hangar/mining/tool placement | Mostly authored | These are role-defining features. |
| Hull panel seams | Yes | Must respect the underlying structure. |
| Antennae/small service modules | Yes | Keep within authored placement zones and density limits. |
| Decals/wear/repair patches | Yes | Decorative only; never obscure faction/role cues. |
| Engine count/location | Authored by class | Minor nozzle/detail variation is allowed. |
| Station macro architecture | No | Station type must have a recognizable construction language. |
| Station minor modules | Yes | Add variety without changing role silhouette. |
| Asteroid local edge/facet noise | Yes | Use authored families as the base. |
| Resource deposit placement | Yes | Must remain readable as the intended resource. |
| Planet surface detail | Yes | Preserve planet type, lighting, and major authored features. |
| Star distribution | Yes | Keep brightness/density within background targets. |
| Nebula fine structure | Yes | Preserve authored regional palette and contrast. |
| Combat particles/debris | Yes | Preserve weapon/effect family identity. |
| Player/faction color placement | No / template-driven | Ownership markings must remain predictable and readable. |

A deterministic seed is useful for reproducibility, but determinism alone does not make variation good art direction.

## Do / don't examples

### Ships

**Do:** render a cargo ship with dark steel structure, gray cargo modules, a narrow player-color stripe, visible engine clusters, and a silhouette dominated by storage mass.  
**Don't:** fill a generic polygon with `playerColor.darker()` and use a bright player-color outline as the primary identity.

**Do:** keep the scout's primary proportions stable and vary antenna tips, panel seams, markings, and small engine details.  
**Don't:** let a random seed make one scout broad and another needle-thin enough that they look like unrelated roles.

### Stations

**Do:** make a radar station visibly contain a mast/dish/array and then add a restrained animated sweep.  
**Don't:** rely on concentric glowing rings to explain that an otherwise generic hexagon is a radar station.

**Do:** use docking arms, gantries, repeated modules, windows, and service structures to communicate enormous scale.  
**Don't:** create a station by scaling up ship-like geometry and adding a thick ownership outline.

### Resources

**Do:** show a mostly neutral asteroid with colored deposits, veins, or exposed resource faces.  
**Don't:** make the full asteroid or its whole outline equal to the gameplay material color.

**Do:** use layered, irregular gas density.  
**Don't:** define a gas cloud as several uniform translucent circles plus a clean neon border.

### Celestial bodies/backgrounds

**Do:** keep nebula contrast below unit contrast and give planets an obvious lit and shadowed side.  
**Don't:** place bright saturated nebula detail directly behind common combat silhouettes or light the entire planet uniformly.

### Effects

**Do:** let weapons and explosions briefly become the brightest elements on screen.  
**Don't:** keep every idle ship/station surrounded by equally bright persistent glow.

## Implementation guidance for the current renderer

This document intentionally does not require a renderer rewrite by itself, but later implementation work should use the current code boundaries where practical:

- `ShipShape` owns current role-family hull drawing and is the natural place to replace full-hull player-color treatment and excessive primary-shape randomness with authored role/class geometry.
- `UnitRenderer` already separates far, cached/medium, and detailed ship rendering. Preserve that LOD concept while improving what each tier shows.
- `Base` and `IntelStructureRenderer` should evolve from generic station hull + effect-driven identity toward role-specific physical architecture with effects layered on top.
- `ResourceNode` should evolve from generic polygon/oval identity toward authored resource families plus bounded deterministic variation.

When implementing later graphics issues, prefer shared material/color helpers and reusable authored shape components over copying ad-hoc `Color` values and glow logic into every renderer.

## Review checklist

A world-graphics change is aligned with this design language when the answer to these questions is yes:

- Can the object be recognized without its text label?
- Can its role be recognized without relying primarily on player color?
- Do neutral materials dominate the physical object?
- Is player color used as an ownership accent rather than a full-surface replacement?
- Does glow come from a plausible energy/light source or a deliberate gameplay effect?
- Does the silhouette survive tactical zoom?
- Does close detail reinforce the same identity instead of inventing a new one?
- Does procedural variation preserve the authored role/class silhouette?
- Are station scale and function communicated by physical structure?
- Are resources readable through form/deposits as well as color?
- Do celestial lighting and background contrast keep gameplay objects readable?
- Are overlays informational and visually separate from the underlying object art?

If a change fails several of these checks, fix the underlying art direction rather than adding more glow, outlines, labels, or random variation.
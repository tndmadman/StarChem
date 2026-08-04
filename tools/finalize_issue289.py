#!/usr/bin/env python3
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


runpy.run_path(str(ROOT / "tools/apply_issue289_phase3.py"), run_name="__main__")

fitting = read("src/main/java/com/tndmadman/rts/ShipFittingWindow.java")
fitting = replace_once(fitting,
'''            refitClass.setEnabled(validation.valid() && nearestRefitBase(world, unit) != null);''',
'''            ShipLoadoutDefinition previewDefinition = validation.valid()
                    ? PlayerFitRules.definition(named ? draftName : "Unsaved Fit", draftSpec) : null;
            refitClass.setEnabled(previewDefinition != null
                    && RefitQueuePlanner.bestStation(world, unit, previewDefinition,
                    world.devFreeBuildFor(unit.playerId)) != null);''',
"class-wide refit station availability")
fitting = replace_once(fitting,
'''                    + "\\nCOST // " + (definition.refitCost().isEmpty() ? "None" : Rules.formatCost(definition.refitCost()))
''',
'''                    + "\\nCONVERSION // " + refitCostSummary(unit, definition)
''',
"selected fit conversion preview")
fitting = replace_once(fitting,
'''        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s   •   COST "
                + (fit.refitCost().isEmpty() ? "None" : Rules.formatCost(fit.refitCost())),
                10, Font.PLAIN, MUTED));
''',
'''        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s", 10, Font.PLAIN, MUTED));
        details.add(label("CONVERSION  //  " + refitCostSummary(unit, fit),
                10, Font.PLAIN, MUTED));
''',
"fit card conversion preview")
fitting = replace_once(fitting,
'''    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {''',
'''    static String refitCostSummary(Unit unit, ShipLoadoutDefinition fit) {
        if (fit == null) return "Unavailable";
        if (unit == null || !fit.hullId().equals(unit.shipTypeId)) {
            List<Cost> installation = RefitQuote.fullInstallationCost(fit);
            return (installation.isEmpty() ? "No installation materials"
                    : "Install " + Rules.formatCost(installation))
                    + " • source conversion varies";
        }
        try {
            RefitQuote quote = RefitQuote.between(unit, fit);
            String required = quote.requiredMaterials().isEmpty()
                    ? "No added materials"
                    : "Add " + Rules.formatCost(quote.requiredMaterials());
            String removed = quote.removedComponents().isEmpty()
                    ? "Nothing removed"
                    : "Scrap " + String.join(", ", quote.removedComponents());
            return required + " • " + removed;
        } catch (RuntimeException ex) {
            return "Conversion unavailable";
        }
    }

    static FittingOption evaluate(World world, Unit unit, ShipLoadoutDefinition loadout) {''',
"conversion summary helper")
write("src/main/java/com/tndmadman/rts/ShipFittingWindow.java", fitting)

studio = read("src/main/java/com/tndmadman/rts/ShipFitStudioWindow.java")
studio = replace_once(studio,
'''                        + "\\nCOST // " + (definition.refitCost().isEmpty() ? "None" : Rules.formatCost(definition.refitCost()))
''',
'''                        + "\\nCONVERSION // "
                        + ShipFittingWindow.refitCostSummary(live, definition)
''',
"studio selected conversion preview")
studio = replace_once(studio,
'''        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s   •   COST "
                + (fit.refitCost().isEmpty() ? "None" : Rules.formatCost(fit.refitCost())),
                10, Font.PLAIN, accent));
''',
'''        details.add(label("RANGE " + whole(WeaponRules.maxRange(fit)) + "   •   REFIT "
                + whole(fit.refitTimeSeconds()) + "s", 10, Font.PLAIN, accent));
        details.add(label("CONVERSION  //  "
                + ShipFittingWindow.refitCostSummary(contextUnit().orElse(null), fit),
                10, Font.PLAIN, accent));
''',
"studio fit card conversion preview")
write("src/main/java/com/tndmadman/rts/ShipFitStudioWindow.java", studio)

validator = read("src/main/java/com/tndmadman/rts/CustomFitConstructionValidator.java")
validator = replace_once(validator,
'''            validateFundedStationSelection();
            validateFallbackOutpost();''',
'''            validateFundedStationSelection();
            validateQuotePresentation();
            validateFallbackOutpost();''',
"quote presentation validator call")
validator = replace_once(validator,
'''    private static void validateFallbackOutpost() {''',
'''    private static void validateQuotePresentation() {
        String player = "QUOTE_UI";
        World world = new World("Quote UI", Set.of(),
                StarSystems.DEFAULT_SYSTEM_ID, false);
        PlayerRegistry.activate(world);
        PlayerRegistry.reset(player, "Quote UI", 0x50BEFF);
        Unit unit = new Unit(player, 1, "prospector", 500, 500);
        world.units.put(unit.key(), unit);

        ShipFitSpec afterburnerSpec = new ShipFitSpec("prospector", List.of(),
                List.of("afterburner"));
        grant(world, player, afterburnerSpec);
        ShipLoadoutDefinition afterburner = PlayerFitRules.definition(
                "Quote Burner", afterburnerSpec);
        String add = ShipFittingWindow.refitCostSummary(unit, afterburner);
        require(add.startsWith("Add ") && add.contains("Nothing removed"),
                "UI did not present the exact added-material conversion quote: " + add);

        unit.loadoutId = afterburner.id();
        ShipLoadoutDefinition defaultFit = WeaponRules.defaultLoadout("prospector");
        String remove = ShipFittingWindow.refitCostSummary(unit, defaultFit);
        require(remove.contains("No added materials")
                        && remove.contains("Scrap 1× Afterburner"),
                "UI did not present the explicit removed-component scrap policy: " + remove);
    }

    private static void validateFallbackOutpost() {''',
"quote presentation validator")
write("src/main/java/com/tndmadman/rts/CustomFitConstructionValidator.java", validator)

build = read("build.gradle")
ship_task = '''tasks.register('validateShipLoadouts', JavaExec) {
    group = 'verification'
    description = 'Validate authored ship loadouts, construction selection, refitting, and persistence.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.ShipLoadoutValidator'
}

'''
additional_tasks = ship_task + '''tasks.register('validateConfiguredFitRules', JavaExec) {
    group = 'verification'
    description = 'Validate explicit configurable fit compatibility, research, and economics.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.ConfiguredFitRuleValidator'
}

tasks.register('validateRefitQuotePersistence', JavaExec) {
    group = 'verification'
    description = 'Validate conversion quotes, exact reservations, refunds, and legacy migration.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.RefitQuotePersistenceValidator'
}

tasks.register('validateAtomicRefitTransactions', JavaExec) {
    group = 'verification'
    description = 'Validate all-or-nothing distributed refit commits and rollback.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.AtomicRefitTransactionValidator'
}

tasks.register('validateCustomFitConstruction', JavaExec) {
    group = 'verification'
    description = 'Validate private and published custom-fit construction and station routing.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.CustomFitConstructionValidator'
    jvmArgs '-Djava.awt.headless=true'
}

tasks.register('validateShipModuleRefits', JavaExec) {
    group = 'verification'
    description = 'Validate fitted module behavior, visuals, audio, and authoritative correction.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.ShipModuleRefitValidator'
}

tasks.register('validateDistributedRefitQueues', JavaExec) {
    group = 'verification'
    description = 'Validate distributed refit planning across Outposts and Shipyards.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.RefitQueuePlannerValidator'
}

tasks.register('validateIssue289Completion', JavaExec) {
    group = 'verification'
    description = 'Validate issue 289 dedicated lifecycle, authority, persistence, and recovery.'
    dependsOn tasks.named('classes')
    classpath = sourceSets.main.runtimeClasspath
    mainClass = 'com.tndmadman.rts.Issue289CompletionValidator'
    jvmArgs '-Djava.awt.headless=true'
}

'''
build = replace_once(build, ship_task, additional_tasks,
                     "permanent issue 289 Gradle validators")
build = replace_once(build,
'''    dependsOn tasks.named('validateShipLoadouts')
''',
'''    dependsOn tasks.named('validateShipLoadouts')
    dependsOn tasks.named('validateConfiguredFitRules')
    dependsOn tasks.named('validateRefitQuotePersistence')
    dependsOn tasks.named('validateAtomicRefitTransactions')
    dependsOn tasks.named('validateCustomFitConstruction')
    dependsOn tasks.named('validateShipModuleRefits')
    dependsOn tasks.named('validateDistributedRefitQueues')
    dependsOn tasks.named('validateIssue289Completion')
''',
"issue 289 check dependencies")
write("build.gradle", build)

write("settings.gradle", '''pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

gradle.beforeProject { project ->
    project.tasks.withType(org.gradle.api.tasks.bundling.AbstractArchiveTask).configureEach {
        preserveFileTimestamps = false
        reproducibleFileOrder = true
    }
}

rootProject.name = 'starchem'
''')

write(".github/workflows/java.yml", '''name: Java CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - run: gradle build
      - name: Validate configured fit rules
        run: java -cp build/classes/java/main com.tndmadman.rts.ConfiguredFitRuleValidator
      - name: Validate refit quote persistence
        run: java -cp build/classes/java/main com.tndmadman.rts.RefitQuotePersistenceValidator
      - name: Validate atomic distributed refits
        run: java -cp build/classes/java/main com.tndmadman.rts.AtomicRefitTransactionValidator
      - name: Validate custom fit construction
        run: java -Djava.awt.headless=true -cp build/classes/java/main com.tndmadman.rts.CustomFitConstructionValidator
      - name: Validate refitted module effects
        run: java -cp build/classes/java/main com.tndmadman.rts.ShipModuleRefitValidator
      - name: Validate distributed refit queues
        run: java -cp build/classes/java/main com.tndmadman.rts.RefitQueuePlannerValidator
      - name: Validate issue 289 lifecycle and authority
        run: java -Djava.awt.headless=true -cp build/classes/java/main com.tndmadman.rts.Issue289CompletionValidator
''')

for path in (
    "tools/apply_issue289_phase3.py",
    "tools/apply_issue289_phase3_impl.py",
    "tools/sitecustomize.py",
):
    target = ROOT / path
    if target.exists():
        target.unlink()

Path(__file__).unlink()
print("Materialized and cleaned the final issue #289 source tree.")

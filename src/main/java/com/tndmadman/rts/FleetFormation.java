package com.tndmadman.rts;

enum FleetFormation {
    GRID("Grid"),
    LINE("Line"),
    COLUMN("Column"),
    WEDGE("Wedge");

    final String label;

    FleetFormation(String label) {
        this.label = label;
    }

    FleetFormation next() {
        FleetFormation[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}

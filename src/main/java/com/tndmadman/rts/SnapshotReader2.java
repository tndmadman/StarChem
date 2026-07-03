package com.tndmadman.rts;

import java.util.ArrayList;
import java.util.List;

final class SnapshotReader2 {
    private SnapshotReader2() { }
    static List<ResourceState> resources(String[] parts) { return new ArrayList<>(); }
    static List<BaseState> bases(String[] parts) { return new ArrayList<>(); }
    static List<StockState> stocks(String[] parts) { return new ArrayList<>(); }
}

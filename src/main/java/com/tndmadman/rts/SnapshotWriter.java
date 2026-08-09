package com.tndmadman.rts;

import java.util.Locale;

final class SnapshotWriter {
    private SnapshotWriter() { }

    static String write(Snapshot snapshot) {
        SnapshotValidator.validate(snapshot);
        StringBuilder players = new StringBuilder();
        for (PlayerInfo player : snapshot.players()) {
            if (!players.isEmpty()) players.append(';');
            players.append(player.id()).append(',').append(player.name()).append(',').append(player.rgb());
        }
        StringBuilder units = new StringBuilder();
        for (UnitState unit : snapshot.units()) {
            if (!units.isEmpty()) units.append(';');
            units.append(unit.playerId()).append(',').append(unit.unitId()).append(',').append(unit.shipTypeId()).append(',')
                    .append(Calc.round(unit.x())).append(',').append(Calc.round(unit.y())).append(',')
                    .append(Calc.round(unit.targetX())).append(',').append(Calc.round(unit.targetY())).append(',')
                    .append(Calc.round(unit.heading())).append(',').append(unit.task()).append(',').append(unit.resourceId()).append(',')
                    .append(CargoCodec.safe(unit.packageType())).append(',').append(CargoCodec.safe(unit.cargo())).append(',')
                    .append(Calc.round(unit.hp())).append(',').append(Calc.round(unit.shield())).append(',')
                    .append(CargoCodec.safe(unit.attackTarget())).append(',').append(Calc.round(unit.weaponFlashTimer())).append(',')
                    .append(unit.orderType()).append(',').append(Calc.round(unit.orderX1())).append(',').append(Calc.round(unit.orderY1())).append(',')
                    .append(Calc.round(unit.orderX2())).append(',').append(Calc.round(unit.orderY2())).append(',')
                    .append(Calc.round(unit.orderRadius())).append(',').append(CargoCodec.safe(unit.orderTarget())).append(',').append(unit.orderPhase()).append(',')
                    .append(CargoCodec.safe(unit.loadoutId()));
        }
        StringBuilder resources = new StringBuilder();
        for (ResourceState r : snapshot.resources()) {
            if (!resources.isEmpty()) resources.append(';');
            resources.append(r.id()).append(',').append(r.name()).append(',').append(r.kind()).append(',').append(r.material()).append(',')
                    .append(Calc.round(r.x())).append(',').append(Calc.round(r.y())).append(',')
                    .append(Calc.round(r.maxAmount())).append(',').append(Calc.round(r.harvestRate())).append(',')
                    .append(Calc.round(r.radius())).append(',').append(Calc.round(r.amount())).append(',')
                    .append(r.active()).append(',').append(Calc.round(r.respawnTimer())).append(',')
                    .append(precise(r.orbitCenterX())).append(',').append(precise(r.orbitCenterY())).append(',')
                    .append(precise(r.orbitRadius())).append(',').append(precise(r.orbitAngle())).append(',')
                    .append(precise(r.orbitSpeed())).append(',').append(r.orbiting());
        }
        StringBuilder bases = new StringBuilder();
        for (BaseState base : snapshot.bases()) {
            if (!bases.isEmpty()) bases.append(';');
            bases.append(base.id()).append(',').append(base.playerId()).append(',').append(base.typeId()).append(',')
                    .append(Calc.round(base.x())).append(',').append(Calc.round(base.y())).append(',')
                    .append(Calc.round(base.hp())).append(',').append(Calc.round(base.shield())).append(',')
                    .append(CargoCodec.safe(base.cargo())).append(',').append(CargoCodec.safe(base.productionQueue())).append(',')
                    .append(CargoCodec.safe(base.logisticsStatus()));
        }
        StringBuilder stocks = new StringBuilder();
        for (StockState stock : snapshot.stocks()) {
            if (!stocks.isEmpty()) stocks.append(';');
            stocks.append(stock.playerId()).append(',').append(CargoCodec.safe(stock.cargo()));
        }
        StringBuilder shots = new StringBuilder();
        for (ShotState shot : snapshot.shots()) {
            if (!shots.isEmpty()) shots.append(';');
            shots.append(shot.id()).append(',').append(shot.ownerId()).append(',').append(shot.weaponId()).append(',')
                    .append(CargoCodec.safe(shot.targetKey())).append(',')
                    .append(Calc.round(shot.x())).append(',').append(Calc.round(shot.y())).append(',')
                    .append(Calc.round(shot.lastX())).append(',').append(Calc.round(shot.lastY()));
        }
        StringBuilder items = new StringBuilder();
        for (ItemState item : snapshot.items()) {
            if (!items.isEmpty()) items.append(';');
            items.append(item.id()).append(',').append(item.material()).append(',').append(Calc.round(item.amount())).append(',')
                    .append(Calc.round(item.x())).append(',').append(Calc.round(item.y())).append(',')
                    .append(Calc.round(item.vx())).append(',').append(Calc.round(item.vy())).append(',')
                    .append(Calc.round(item.angle())).append(',').append(Calc.round(item.spin()));
        }
        StringBuilder research = new StringBuilder();
        for (ResearchState state : snapshot.research()) {
            if (!research.isEmpty()) research.append(';');
            research.append(state.playerId()).append(',');
            if (state.topicIds().isEmpty()) research.append('-');
            else {
                for (int i = 0; i < state.topicIds().size(); i++) {
                    if (i > 0) research.append('~');
                    research.append(state.topicIds().get(i));
                }
            }
        }
        ObjectiveState objectiveState = snapshot.objective();
        String objective = CargoCodec.safe(objectiveState.conditionId()) + ','
                + objectiveState.status().name() + ',' + objectiveState.current() + ','
                + objectiveState.target() + ',' + CargoCodec.safe(objectiveState.leaderId()) + ','
                + CargoCodec.safe(objectiveState.completedById()) + ','
                + precise(objectiveState.elapsedSeconds());
        return "SNAPSHOT|" + snapshot.sequence() + "|" + players + "|" + units + "|" + resources + "|" + bases + "|" + stocks + "|" + shots + "|" + items + "|" + snapshot.packedSystemField() + "|" + Calc.round(snapshot.systemTime()) + "|" + research + "|" + objective;
    }

    private static String precise(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}

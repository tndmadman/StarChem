package com.tndmadman.rts;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Small UI bridge into the existing authenticated client command path. */
final class DiplomacyNetworkBridge {
    private static final Field CLIENT = field(PeerNetwork.class, "client");
    private static final Method SEND_COMMAND = method(PeerClientSide.class,
            "sendCommandToServer", String.class);

    private DiplomacyNetworkBridge() { }

    static boolean send(PeerNetwork network, World world, String targetId,
                        DiplomacySystem.LiveAction action) {
        String actorId = PlayerRegistry.localId();
        if (world == null || actorId == null || actorId.isBlank()
                || targetId == null || targetId.isBlank() || action == null) return false;

        if (network == null || !network.clientMode()) {
            DiplomacySystem.LiveResult result = DiplomacyCommand.applyLocal(world, actorId, targetId, action);
            world.status = localStatus(targetId, result);
            return result != DiplomacySystem.LiveResult.INVALID_TARGET
                    && result != DiplomacySystem.LiveResult.MODE_LOCKED;
        }

        try {
            Object client = CLIENT.get(network);
            if (!(client instanceof PeerClientSide)) return false;
            String packet = "DIPLOMACY|" + clean(actorId) + '|' + clean(targetId) + '|' + action.name();
            SEND_COMMAND.invoke(client, packet);
            world.status = "Diplomacy request sent to the server.";
            return true;
        } catch (ReflectiveOperationException ex) {
            world.status = "Could not send the diplomacy request.";
            System.err.println(world.status + " " + ex.getClass().getSimpleName());
            return false;
        }
    }

    private static String localStatus(String targetId, DiplomacySystem.LiveResult result) {
        String name = PlayerRegistry.baseName(targetId);
        return switch (result) {
            case ALLIANCE_OFFERED -> "Alliance offer sent to " + name + ".";
            case ALLIANCE_ACCEPTED -> "Alliance formed with " + name + ".";
            case NEUTRAL_SET -> "Relationship with " + name + " is now neutral.";
            case HOSTILE_SET -> "Relationship with " + name + " is now hostile.";
            case UNCHANGED -> "That diplomacy state is already active.";
            case MODE_LOCKED -> "This match does not allow live diplomacy changes.";
            case INVALID_TARGET -> "Diplomacy request was rejected.";
        };
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.replace("|", "").trim();
        return clean.length() <= 64 ? clean : clean.substring(0, 64);
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}

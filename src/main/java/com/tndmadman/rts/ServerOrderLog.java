package com.tndmadman.rts;

final class ServerOrderLog {
    private ServerOrderLog() { }

    static void accepted(String type, String playerId, int unitId) {
        System.out.println("ORDER " + type + " " + playerId + ":" + unitId);
    }
}

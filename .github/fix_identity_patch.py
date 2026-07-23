from pathlib import Path

path = Path('.github/identity_lifecycle_patch.py')
text = path.read_text(encoding='utf-8')
label = "    'authenticated lifecycle admission',\n)\n"
end = text.index(label) + len(label)
start = text.rfind('replace_once(', 0, end)
replacement = '''replace_once(
    'src/main/java/com/tndmadman/rts/PeerNetworkFacade.java',
    ''' + "'''" + '''        String deviceId = deviceByConnection.getOrDefault(connectionId, \"\");\\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, address, deviceId, now);\\n''' + "'''" + ''',
    ''' + "'''" + '''        String lifecycleReason = identityStore == null ? \"\" : identityStore.denialReason(playerId);\\n        if (!lifecycleReason.isBlank()) {\\n            journal.add(\"ADMISSION_DENIED\", playerId.isBlank() ? playerName : playerId, \"identity-lifecycle\");\\n            return lifecycleReason;\\n        }\\n        String deviceId = deviceByConnection.getOrDefault(connectionId, \"\");\\n        ModerationEntry blocked = serverModeration().blocked(playerId, playerName, address, deviceId, now);\\n''' + "'''" + ''',
    'authenticated lifecycle admission',
)
'''
path.write_text(text[:start] + replacement + text[end:], encoding='utf-8')

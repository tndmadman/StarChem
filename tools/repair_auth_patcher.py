from pathlib import Path

path = Path(__file__).with_name("apply_server_scoped_auth_fix.py")
text = path.read_text(encoding="utf-8")
old = '''    ''' + "'''" + '''        Properties properties = readProperties();\\n        String value = properties.getProperty(key, \"\");''' + "'''" + ''',
    ''' + "'''" + '''        Properties properties = readProperties();\\n        properties.remove(scopedAuthKey(key));\\n        String value = properties.getProperty(key, \"\");''' + "'''" + ''',
'''
new = '''    ''' + "'''" + '''    static synchronized void saveAuthDigest(Config config, String authDigest) {\\n        String key = key(config);\\n        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;\\n        transientAuthDigests.put(key, authDigest);\\n        if (config != null && config.localHostClientMode()) return;\\n        Properties properties = readProperties();\\n        String value = properties.getProperty(key, \"\");''' + "'''" + ''',
    ''' + "'''" + '''    static synchronized void saveAuthDigest(Config config, String authDigest) {\\n        String key = key(config);\\n        if (key.isBlank() || !PasswordAuth.validVerifier(authDigest)) return;\\n        transientAuthDigests.put(key, authDigest);\\n        if (config != null && config.localHostClientMode()) return;\\n        Properties properties = readProperties();\\n        properties.remove(scopedAuthKey(key));\\n        String value = properties.getProperty(key, \"\");''' + "'''" + ''',
'''
if text.count(old) != 1:
    raise RuntimeError(f"Expected one patcher fragment, found {text.count(old)}")
text = text.replace(old, new, 1)
malformed_newline = '",\\n                    "'
if text.count(malformed_newline) != 3:
    raise RuntimeError(f"Expected three malformed newline tokens, found {text.count(malformed_newline)}")
text = text.replace(malformed_newline, '",\n                    "')
path.write_text(text, encoding="utf-8")
print("Repaired auth patch assertion target and newline syntax.")

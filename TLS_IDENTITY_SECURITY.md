# Dedicated-server TLS identity security

StarChem dedicated servers use a persistent PKCS#12 private key and certificate so clients can pin the server fingerprint. Anyone who obtains both the keystore and its password can impersonate that server without producing a changed-fingerprint warning.

## Managed identity

By default, StarChem creates these files in the configured save directory:

```text
<save-name>-tls.p12
<save-name>-tls.password
```

The password is generated independently for each server installation. StarChem creates the save directory, password file, temporary files, and final keystore with owner-only permissions where the operating system exposes POSIX permissions or ACLs.

Both files are secrets. Protect backups containing either file. Keeping the password file outside the save directory provides stronger separation when save archives are copied elsewhere.

Existing identities created with the former shared `starchem-local-tls` password are migrated on first startup. Migration re-encrypts the existing private key with the new password and verifies that the certificate fingerprint did not change. A corrupt or unreadable existing identity causes startup to fail; StarChem does not silently replace it.

## External password file

Set either the JVM property or environment variable below to choose a protected password file for the default managed keystore:

```text
-Dstarchem.tls.passwordFile=/secure/starchem-tls-password
STARCHEM_TLS_PASSWORD_FILE=/secure/starchem-tls-password
```

The file must be a regular, non-symlink UTF-8 file containing exactly one non-empty password. It must be owned by the server account and must not grant access to unrelated users.

## Operator-provided PKCS#12 keystore

Public-server operators may supply their own PKCS#12 identity:

```text
-Dstarchem.tls.keystore=/secure/server-identity.p12
-Dstarchem.tls.passwordFile=/secure/server-identity.password
-Dstarchem.tls.keyAlias=server-key
```

Equivalent environment variables are:

```text
STARCHEM_TLS_KEYSTORE=/secure/server-identity.p12
STARCHEM_TLS_PASSWORD_FILE=/secure/server-identity.password
STARCHEM_TLS_KEY_ALIAS=server-key
```

A password file is mandatory whenever an external keystore is configured. StarChem never generates, migrates, or overwrites an operator-provided keystore. If no alias is configured, the keystore must contain exactly one private-key entry.

## Failure behavior

StarChem stops server startup with a clear error when:

- The configured keystore or password file is missing, a symlink, or not a regular file.
- File ownership or permissions expose the secret to unrelated users on a supported filesystem.
- The password is incorrect.
- The PKCS#12 file is corrupt or truncated.
- The requested alias is missing or does not contain a private key and certificate.
- A generated or migrated identity cannot be written and verified safely.

Do not delete or replace the TLS identity unless clients are expected to approve a new server fingerprint.

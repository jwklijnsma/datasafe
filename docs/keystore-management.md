# Keystore management

This page describes how Datasafe creates, stores, opens and changes the keystores that hold a user's keys
(board item #256). It covers the `datasafe-encryption` keystore classes and the `datasafe-directory`
classes that read and write keystores.

## Overview

Every user has three kinds of key material:

| Keystore / file | Location (default `DefaultDFSConfig`) | Protected by | Content |
|---|---|---|---|
| **Private document keystore** | `<system-root>/users/<user>/private/keystore` | ReadStorePassword (system-wide) + the user's ReadKeyPassword | EC encryption key pair(s), AES secret keys for path and document encryption |
| **Public keys** | `<system-root>/users/<user>/public/pubkeys` | not encrypted | the user's public encryption key(s), used by others to share documents with the user |
| **Storage-credentials keystore** (optional) | next to the private profile | ReadStorePassword + ReadKeyPassword | credentials (access/secret key) for additional storages, e.g. a user's own S3 bucket (multi-DFS) |

The **ReadStorePassword** opens the keystore container. The **ReadKeyPassword** (part of
`UserIDAuth`) opens each key inside it. You need both to use a private key.

## Components

| Class | Module | Responsibility |
|---|---|---|
| `KeyStoreService` / `KeyStoreServiceImpl` | `datasafe-encryption` | Low-level keystore operations: create, read public, private and secret keys, add password-based secrets, change the key password, (de)serialize. Built on adorsys `keymanagement` (`Juggler`). |
| `KeyCreationConfig` | `datasafe-encryption-api` | Which keys a new keystore gets: algorithms, key sizes, curve, number of keys |
| `EncryptionConfig.keystore` (`KeyStoreConfig`) | `datasafe-encryption-api` | Keystore format, PBKDF and password-key algorithms |
| `PublicKeySerde` / `PublicKeySerdeImpl` | `datasafe-encryption` | Reads and writes public keys in `pubkeys`; reads both EC and RSA (legacy) keys |
| `GenericKeystoreOperations` | `datasafe-directory-impl` | Shared keystore logic: create empty, read with cache, re-read on password mismatch, change the key password, write |
| `DocumentKeyStoreOperationsImpl` | `datasafe-directory-impl` | The private document keystore: create it during user registration, publish the public keys, read keys and key pairs, change the password |
| `StorageKeyStoreOperationsImpl` | `datasafe-directory-impl` | The storage-credentials keystore: add, remove and read credentials per `StorageIdentifier` |
| `DFSPrivateKeyServiceImpl` | `datasafe-directory-impl` | High-level private key access: path encryption secret, document secret key, `keysByIds`, access check |
| `DFSPublicKeyServiceImpl` | `datasafe-directory-impl` | Reads (and caches) another user's public key for sharing |
| `KeyStoreCache` / `DefaultKeyStoreCache` | `datasafe-directory-impl` | In-memory cache of public keys, private keystores and storage-credential keystores per user |
| `DefaultKeyStoreModule`, `DefaultKeystoreCacheModule` | `datasafe-business` | Dagger wiring; replaceable through `OverridesRegistry` |

## Key creation

`KeyStoreServiceImpl.createKeyStore(auth, KeyCreationConfig)` creates the private keystore during user
registration (`DocumentKeyStoreOperations.createAndWriteKeyStore`). By default it contains:

- **Encryption key pair(s)**: `encKeyNumber` (default 1), algorithm `ECDH`, curve `secp256r1`, 256 bit,
  certificate signed with `SHA256withECDSA`. The aliases start with `ENC`.
- **Secret keys** (AES-256), each with a random UUID suffix:
  - `PATH_SECRET…`: SIV path encryption.
  - `PATH_CTR_SECRET_…`: CTR path encryption.
  - `PRIVATE_SECRET…`: encryption of the user's private documents.

The public parts of the encryption keys are written to `pubkeys`. Other users read them through
`PublicKeyService` to share documents with (send to the inbox of) this user.

Change the defaults by passing an `EncryptionConfig` with a different `KeyCreationConfig` (or
`MutableEncryptionConfig` from properties) when building `DefaultDatasafeServices`.

## Reading keys and caching

- Public keys come from `pubkeys` and are cached per user (`KeyStoreCache.getPublicKeys`).
- The private keystore is cached per user (`KeyStoreCache.getKeystore`). `GenericKeystoreOperations.getKey`
  first tries the cached keystore. If the supplied password cannot open it, the cache entry is dropped and
  the keystore is read from storage again, so a password changed on another node takes effect.
- Secret keys are looked up by alias prefix (`PATH_SECRET`, `PATH_CTR_SECRET_`, `PRIVATE_SECRET`).

## Changing the password

`updateReadKeyPassword(forUser, newPassword)` copies every key into a new keystore protected by the new
ReadKeyPassword (`KeyStoreServiceImpl.updateKeyStoreReadKeyPassword`). It writes that keystore over the old
one and invalidates the cache. It does this for the document keystore and the storage-credentials
keystore. The keys themselves do not change, so existing documents stay readable.

## Supported key algorithms (RSA and EC)

New keystores contain **EC** (`secp256r1`) encryption keys. Keystores created by older Datasafe versions
may contain **RSA** keys. Both are still supported:

- `PublicKeySerdeImpl` reads RSA and EC public keys.
- `CMSEncryptionServiceImpl` uses key transport for RSA recipients and key agreement for EC recipients.

Datasafe does **not** replace an existing user's RSA key with an EC key when it is upgraded. Users keep
their RSA key pair until their keystore is re-created. Automatic rotation (adding an EC key pair, publishing
it, and keeping the RSA private key so existing documents can still be decrypted) is board item #251 and
is not implemented.

## Security notes

- Private keystores are only written to the user's private profile and are always encrypted.
  `pubkeys` is public by design.
- The ReadStorePassword is usually a system-wide secret (`DefaultDFSConfig`). Protect it like a master
  password: with it and a user's ReadKeyPassword, that user's keys can be opened.
- Keystore caches hold opened key material in memory. Use `KeyStoreCache.remove(user)` (for example on
  logout) if your deployment needs to drop it early.

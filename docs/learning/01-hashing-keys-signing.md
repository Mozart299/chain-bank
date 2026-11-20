# 1. Hashing, keys and signatures

## Key ideas

- **A hash** (SHA-256, Keccak-256) is a fingerprint. The same input always gives the same 32 bytes, any change gives a completely different result, and you can't work backwards from it. ChainBank uses hashes to store secrets it never needs to read back (session tokens), to chain audit events together, and to build Merkle trees.
- **A password hash must be slow.** Argon2id deliberately costs about 19 MiB of memory and real CPU per guess, so a stolen database can't be brute-forced quickly. A fast hash like SHA-256 would be the wrong tool here.
- **HMAC** is a hash keyed with a secret. **TOTP** (the 6-digit authenticator code) is `HMAC-SHA1(secret, floor(time / 30 s))`, truncated to 6 digits. The server and your phone share the secret and compute the same code independently.
- **Asymmetric keys** come as a pair: a private key that *signs* and a public key that *verifies*. In Ethereum (secp256k1 ECDSA), the signature plus the message is enough to **recover** the public key, and the address is the last 20 bytes of `keccak256(publicKey)`.
- **A signature proves control of a key, not identity.** ChainBank never treats a wallet address as a login (spec §7.1).

## Read the code

| What | Where |
|---|---|
| Argon2id parameters, constant-time compare, dummy hash for unknown users | `services/api/.../auth/PasswordHasher.java` |
| TOTP (RFC 6238), ±1 step window, replay protection | `.../auth/Totp.java`, `.../auth/TotpVerifier.java` |
| TOTP secrets encrypted at rest (AES-256-GCM, bound to the user ID) | `.../auth/SecretCipher.java` |
| Session token: only its SHA-256 is stored | `.../auth/SessionService.java` |
| secp256k1 public-key recovery, EIP-191 prefix, EIP-55 checksums | `.../wallet/EthSignatures.java` |

## Hands-on

1. Add the demo secret `CHAINBANKDEMOTOTPSECRETXUGX23456` to an authenticator app and sign in as `alice@nile.demo`. Then try to reuse the same code within 30 seconds, for example by signing in from a private window: it's refused.
2. Look at what the database actually stores:
   ```sql
   SELECT email, left(password_hash, 40), length(totp_secret_cipher) FROM users LIMIT 3;
   SELECT encode(token_hash, 'hex') FROM sessions ORDER BY created_at DESC LIMIT 1;
   ```
   There is no password and no session token in there, only hashes, plus encrypted TOTP secrets.
3. Compare a signature made by our Java code with one made by viem (the library the browser uses): `EthSignaturesTest.testWalletMatchesViem` asserts they're byte-identical.

## Break it

| Change | Caught by |
|---|---|
| In `Totp.verify`, drop the `s > lastUsedStep` condition | `CryptoPrimitivesTest.totpAcceptsSkewAndRejectsReplay`, `AuthFlowTest.totpCodeCannotBeReplayed` |
| In `EthSignatures.recoverPersonalSigner`, remove the `HALF_N` (low-s) check | `EthSignaturesTest.rejectsMalformedAndHighSSignatures` |
| In `SecretCipher`, remove `updateAAD(owner…)` | `CryptoPrimitivesTest.secretCipherBindsOwnerAndDetectsTampering` |

Run them with `make test-api TEST=CryptoPrimitivesTest` (or `EthSignaturesTest`, `AuthFlowTest`).

## Check yourself

- Why does login run Argon2 even when the email doesn't exist?
- Why is a TOTP code accepted for one step either side of now, and what stops it being used twice?
- `(r, s)` and `(r, n − s)` are both valid ECDSA signatures for the same message. Why reject the high-s one?
- What exactly does a valid wallet signature prove, and what doesn't it prove?

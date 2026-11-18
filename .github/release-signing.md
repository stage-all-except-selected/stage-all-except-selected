# Release signing

Create the signing key and certificate once and keep them in a password manager:

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl req -key private_encrypted.pem -new -x509 -days 3650 -out chain.crt
```

Sign and verify locally:

```bash
CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private_encrypted.pem)" \
  PRIVATE_KEY_PASSWORD=... ./gradlew verifyPluginSignature   # signs, then verifies
```

Store the same three values as the repository secrets `CERTIFICATE_CHAIN`, `PRIVATE_KEY` and
`PRIVATE_KEY_PASSWORD`.

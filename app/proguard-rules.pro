# BouncyCastle registers algorithm implementations by class name and instantiates them
# reflectively (AGP 9 strict R8 mode no longer keeps constructors implicitly).
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# sshj looks this up by name to support encrypted PEM keys.
-keep class org.bouncycastle.openssl.PEMDecryptor
# Optional sshj features (GSSAPI/Kerberos, JNDI) that do not exist on Android and are never used.
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.login.**
-dontwarn javax.naming.**

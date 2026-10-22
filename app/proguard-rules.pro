# Apache MINA SSHD finds ciphers, MACs, key parsers and the EdDSA provider by
# reflection and service names; keep it whole rather than chase each one.
-keep class org.apache.sshd.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.tomcat.**
-dontwarn sun.security.x509.**

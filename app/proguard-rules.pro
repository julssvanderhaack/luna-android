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
# smbj dispatches its events through MBassador by annotations and reflection
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassy.**
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**

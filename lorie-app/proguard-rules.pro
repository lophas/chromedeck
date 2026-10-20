-dontobfuscate

# Only the TAR reader and the platform GZIP stream are used. These optional
# Commons Compress codecs are deliberately not bundled in this Android app.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**

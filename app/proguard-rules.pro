# ML Kit découvre ses composants par réflexion à partir du manifeste : sans ces règles,
# R8 supprime les « registrars » et Translation.getClient() plante en release.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.** { *; }

# Points d'entrée de libbergamot.so (traduction hors-ligne) : appelés par leur nom depuis le code natif.
-keep class app.bioread.Bergamot { native <methods>; }

# Lecture des commandes d'écran masquées des liseuses Onyx (voir Epd.unlock).
-keep class org.lsposed.hiddenapibypass.** { *; }

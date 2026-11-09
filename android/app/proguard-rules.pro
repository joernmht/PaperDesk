-dontobfuscate
# WorkManager's Room database is created by reflection (no-arg constructor); R8 full mode in AGP 9 strips it
# -> "NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>" at start-up (2026-11-09).
-keep class * extends androidx.room.RoomDatabase { <init>(); }

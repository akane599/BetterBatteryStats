# R8 / ProGuard rules for BetterBatteryStats.
#
# Two things in this app are fundamentally at odds with renaming, and both would fail silently:
#
#  1. References are persisted as Java-serialized ReferenceDto blobs in SQLite, and as Jackson JSON.
#     Java serialization matches fields by name, so a renamed field makes every reference already on
#     the device unreadable — the user loses their history on update, with no error to point at.
#
#  2. The battery statistics are read by reflection. The framework side is not shrunk, but the DTO
#     and StatElement types are reached by name from serialization frameworks.
#
# The rules below are therefore deliberately conservative about the model layer, and let R8 shrink
# the rest (UI, utilities, the bundled root shell).

# ---------------------------------------------------------------------------
# Serialized model: keep names and members exactly as they are on disk today.
# ---------------------------------------------------------------------------
-keep class com.asksven.android.common.dto.** { *; }
-keep class com.asksven.betterbatterystats.data.ReferenceDto { *; }
-keep class com.asksven.betterbatterystats.data.Reference { *; }
-keep class com.asksven.android.common.privateapiproxies.StatElement { *; }
-keep class * extends com.asksven.android.common.privateapiproxies.StatElement { *; }
-keep class com.asksven.android.common.nameutils.UidInfo { *; }

# Anything Serializable: the members Java serialization looks up by name.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------------------------------------------------------------------------
# Jackson (org.codehaus.jackson): annotation-driven, so annotations must survive.
# ---------------------------------------------------------------------------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep class org.codehaus.jackson.** { *; }
-dontwarn org.codehaus.jackson.**
-keepclassmembers class * {
    @org.codehaus.jackson.annotate.JsonProperty <fields>;
}

# ---------------------------------------------------------------------------
# WorkManager instantiates workers reflectively from the class name it stored when the work was
# enqueued, so a rename breaks work that was already pending across an update.
# ---------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------------------
# Preferences are inflated from res/xml by class name.
# ---------------------------------------------------------------------------
-keep class com.asksven.betterbatterystats.contrib.SeekBarPreference {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------------------------------------------------------------------------
# The bundled root shell libraries and the DashClock API are third-party AARs whose optional
# dependencies are not on the classpath.
# ---------------------------------------------------------------------------
-dontwarn com.stericson.**
-dontwarn com.google.android.apps.dashclock.**

# Keep the line numbers of crashes mappable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson JSON keys must stay stable across modern snapshot updates.
-keep class com.asksven.betterbatterystats.modern.SnapshotStore$Record { *; }

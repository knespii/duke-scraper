# Uložený stav (state.json / report.json) – serializovatelné třídy nechat beze změny názvů polí.
-keep,includedescriptorclasses class cz.duke390.hlidac.scrape.**$$serializer { *; }
-keepclassmembers class cz.duke390.hlidac.scrape.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class cz.duke390.hlidac.scrape.** { *; }

# Jsoup volitelně sahá na re2j, který nepoužíváme.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**

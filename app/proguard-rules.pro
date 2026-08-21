# http://developer.android.com/guide/developing/tools/proguard.html

-dontwarn **

# P1-5: the live a11y instance slot must stay a single Kotlin object across
# R8. If the registry is split/inlined, onCreate can stamp one copy while
# the UI reads another and the home screen stays on "正在恢复".
-keep class li.songe.gkd.service.A11yInstanceRegistry { *; }
-keepclassmembers class li.songe.gkd.service.A11yService {
    public static *;
}

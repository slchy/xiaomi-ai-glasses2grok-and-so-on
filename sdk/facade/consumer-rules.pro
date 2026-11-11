# ADR-008：公开面必须完整保留（默认会整个 SDK 被 shrink）
-keep public class com.miaiglasses.sdk.** { public *; }
-keep public interface com.miaiglasses.sdk.** { *; }
# sealed 类型的嵌套对象也要留，否则 when 分支的类名在堆栈里不可读
-keepnames class com.miaiglasses.sdk.**

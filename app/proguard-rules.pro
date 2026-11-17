# Shizuku 通过反射实例化 IUserService，不能混淆
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-keep class com.d.netgate.ShellService { *; }
-keep class com.d.netgate.IUserService$Stub { *; }

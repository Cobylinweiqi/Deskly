# 入口类名写死在 META-INF/xposed/java_init.list 里，R8 一旦重命名就会加载失败，
# 而加载失败在 LSPosed 里表现为"模块已启用但不生效"，极难排查。
-keep class io.github.cobylinweiqi.deskly.Boot { *; }

# 同一类问题：任何 XposedModule 子类都是框架反射实例化的入口，名字和方法都不能动
-keep,allowoptimization class * extends io.github.libxposed.api.XposedModule { *; }

# 保留行号，注入进程里崩溃时堆栈才可读（崩的是桌面，没有行号基本没法查）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# libxposed 是 compileOnly，运行时由框架提供，R8 看不到它属正常
-dontwarn io.github.libxposed.**

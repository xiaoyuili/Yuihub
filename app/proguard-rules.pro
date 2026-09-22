# YuiHub R8 keep 规则
# AGP 9 的 R8 默认包含 kotlinx-serialization / Room 的 consumer rules,
# 这里只补项目特有的反射/JNI/动态解析面。

# QuickJS: JS 桥通过 JNI 按名调用 Java 方法, 混淆后注册不上导致脚本回调失效
-keep class com.whl.quickjs.** { *; }
-keepclassmembers class me.yui.yuihub.** {
    @com.whl.quickjs.wrapper.QuickJSMethod <methods>;
}

# Pebble 模板: 模板在运行时编译, 保留 pebble 包公开 API 防内联破坏
-keep class io.pebbletemplates.pebble.** { *; }
-dontwarn io.pebbletemplates.pebble.**

# 枚举 values()/valueOf() 反射调用保留(R8 full mode 下仍有场景需要)
-keepclassmembers enum me.yui.yuihub.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# DataStore 反序列化兜底: 保留模型类的 serializer() 伴生对象入口
# (kotlinx-serialization consumer rules 已覆盖编译期插件生成的 serializer,
#  这里只防 R8 full mode 对伴生对象本身的合并改名)
-keepclassmembers class me.yui.yuihub.data.model.** {
    public static ** Companion;
}
-keepclassmembers @kotlinx.serialization.Serializable class me.yui.yuihub.data.model.** {
    *** Companion;
    *** instance$module;
}

# KeepAliveService 重建路径(START_STICKY 重启时系统按名构造)
-keep class me.yui.yuihub.service.KeepAliveService { *; }

# jieba/simple SQLite 扩展经 JNI 回调无需 Java 类, 但防 Requery 工厂被混淆
-keep class io.requery.android.database.** { *; }
-dontwarn io.requery.android.**

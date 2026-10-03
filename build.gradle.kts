// R90 闹钟 · Gradle 配置（根）
//
// 只列需要的插件。没有网络库、没有数据库、没有 DI 框架。
//
// 注意：Kotlin DSL 的注释只能用 //，不能像 gradle.properties 那样用 #。
// 用 # 会在脚本编译阶段直接报 "Unresolved reference"，构建起不来。

plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器独立成 Gradle 插件，必须显式声明。
    // 版本必须与上面的 Kotlin 版本严格一致，否则报编译器版本不匹配。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

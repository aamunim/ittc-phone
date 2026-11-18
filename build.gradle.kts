plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.kotlinAndroid).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
    // (Fossify Commons, kept in this repository)
    alias(clibs.plugins.library).apply(false)
    alias(clibs.plugins.ksp).apply(false)
    alias(clibs.plugins.compose.compiler).apply(false)
    alias(clibs.plugins.parcelize).apply(false)
}

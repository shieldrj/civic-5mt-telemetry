# Minification is on for release (see build.gradle.kts). Room, Compose and Coroutines all
# ship consumer rules, and org.json is the framework's - so far nothing app-specific needs
# keeping. If an R8 build breaks something at runtime, the first suspects are the JSON
# parsing in PersistentStores (reflection-free, so safe) and anything reached only from
# RemoteViews in widget/TankWidget.kt.

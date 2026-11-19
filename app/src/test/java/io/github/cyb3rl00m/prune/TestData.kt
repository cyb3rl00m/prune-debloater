package io.github.cyb3rl00m.prune

import io.github.cyb3rl00m.prune.core.db.Classifier
import io.github.cyb3rl00m.prune.core.db.DynamicProtections
import io.github.cyb3rl00m.prune.core.db.PackageDatabase
import io.github.cyb3rl00m.prune.core.model.AppPackage
import io.github.cyb3rl00m.prune.core.model.PackageFacts
import java.io.File

/** Loads the real database assets shipped in the APK. */
object TestData {
    val db: PackageDatabase by lazy {
        val dir = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.isDirectory }
        PackageDatabase.parse(File(dir, "prune_rules.json").readText(), File(dir, "packages_uad.json").readText())
    }
    val classifier by lazy { Classifier(db) }

    fun facts(pkg: String, system: Boolean = true, enabled: Boolean = true, installed: Boolean = true,
              persistent: Boolean = false, sharedUid: String? = null) =
        PackageFacts(pkg, pkg.substringAfterLast('.'), system, false, persistent, sharedUid, installed, enabled, "1.0", 10_123)

    fun app(pkg: String, system: Boolean = true, enabled: Boolean = true, lastUsed: Long? = null,
            dynamic: DynamicProtections = DynamicProtections(), installed: Boolean = true): AppPackage {
        val f = facts(pkg, system, enabled, installed)
        return AppPackage(f, classifier.classify(f, dynamic), lastUsed)
    }
}

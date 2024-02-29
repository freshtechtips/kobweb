package com.varabyte.kobweb.gradle.application.tasks

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.Tracing
import com.varabyte.kobweb.common.navigation.RoutePrefix
import com.varabyte.kobweb.gradle.application.extensions.AppBlock
import com.varabyte.kobweb.gradle.application.extensions.app
import com.varabyte.kobweb.gradle.application.extensions.export
import com.varabyte.kobweb.gradle.application.util.PlaywrightCache
import com.varabyte.kobweb.gradle.application.util.kebabCaseToCamelCase
import com.varabyte.kobweb.gradle.core.extensions.KobwebBlock
import com.varabyte.kobweb.gradle.core.tasks.KobwebModuleTask
import com.varabyte.kobweb.project.conf.KobwebConf
import com.varabyte.kobweb.project.frontend.AppData
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kobweb.server.api.SiteLayout
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jsoup.Jsoup
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.time.DurationUnit
import com.varabyte.kobweb.gradle.application.Browser as KobwebBrowser

class KobwebExportConfInputs(
    @get:Input val siteRoot: String,
    @get:Input val routePrefix: String,
    @get:Input val script: String,
    @get:Input @get:Optional val api: String?,
) {
    constructor(kobwebConf: KobwebConf) : this(
        siteRoot = kobwebConf.server.files.prod.siteRoot,
        routePrefix = kobwebConf.site.routePrefix,
        script = kobwebConf.server.files.prod.script,
        api = kobwebConf.server.files.dev.api,
    )
}

abstract class KobwebExportTask @Inject constructor(
    @get:Nested val confInputs: KobwebExportConfInputs,
    @get:Input val siteLayout: SiteLayout,
    kobwebBlock: KobwebBlock,
) : KobwebModuleTask(kobwebBlock, "Export the Kobweb project into a static site") {
    @get:InputFile
    abstract val appDataFile: RegularFileProperty

    @get:Input
    @get:Optional
    val legacyRouteRedirectStrategy: Provider<AppBlock.LegacyRouteRedirectStrategy> =
        kobwebBlock.app.legacyRouteRedirectStrategy

    @OutputDirectory
    fun getSiteDir(): File {
        return projectLayout.projectDirectory.dir(confInputs.siteRoot).asFile
    }

    private fun Page.takeSnapshot(url: String): String {
        onPageError {
            logger.error(
                "e: Route \"/${
                    url.removePrefix("http://").substringAfter('/')
                }\" crashed mid-export. You should investigate this by using `kobweb run` and visiting that route."
            )
        }
        navigate("${url}?_kobwebIsExporting=true")

        // First, we bake dynamic styles into static ones. Let me explain :)
        // Compose HTML creates empty style nodes and then adds styles to them programmatically, meaning the page
        // works right but if you go to inspect the DOM using debugging tools or save the page, all you see is an empty
        // style tag and the information is lost.
        // By iterating over those style nodes and explicitly overwriting them with their own values, we can then save
        // the page with filled out style tags. This ensures that when a user first downloads the page, that things look
        // right even before the javascript is downloaded. When the javascript runs, it will simply clear our baked in
        // styles and replace them with the programmatic ones (but users won't be able to tell because the values should
        // be the same).
        // If we didn't do this, then what would happen is the user would download the page, see raw text unadorned
        // without any styles, and then after a brief period of time (depending on download speeds) styles would pop
        // in, quite jarringly.
        evaluate(
            """
                for (let s = 0; s < document.styleSheets.length; s++) {
                    var stylesheet = document.styleSheets[s]
                    stylesheet = stylesheet instanceof CSSStyleSheet ? stylesheet : null;

                    // Trying to peek at external stylesheets causes a security exception so step over them
                    if (stylesheet != null && stylesheet.href == null) {
                        var styleNode = stylesheet.ownerNode
                        styleNode = styleNode instanceof Element ? styleNode : null
                        if (styleNode != null && styleNode.innerHTML == '') {
                            const rules = []
                            for (let r = 0; r < stylesheet.cssRules.length; ++r) {
                                rules.push(stylesheet.cssRules[r].cssText.replace(/(\n)/gm, ''))
                            }
                            styleNode.innerHTML = rules.join('')
                        }
                    }
                }
            """.trimIndent()
        )

        // Use Jsoup for pretty printing
        return Jsoup.parse(content()).toString()
    }

    private fun Browser.takeSnapshot(route: String, url: String): String {
        newContext().use { context ->
            kobwebBlock.app.export.timeout.orNull?.let { context.setDefaultTimeout(it.toDouble(DurationUnit.MILLISECONDS)) }
            val traceConfig = kobwebBlock.app.export.traceConfig.orNull
                ?.takeIf { it.filter(route) }
            if (traceConfig != null) {
                val traceRoot = traceConfig.root
                traceRoot.toFile().mkdirs()
                traceRoot.resolve("README.md").writeText(
                    """
                        # Export Traces

                        This directory contains traces of your exported pages. These traces can be opened in the
                        [Playwright Trace Viewer](https://trace.playwright.dev/).

                        To open a trace, open the link above and then drag and drop it onto that page.

                        For understanding trace results, see: https://playwright.dev/docs/trace-viewer
                    """.trimIndent()
                )

                context.tracing().start(
                    Tracing.StartOptions()
                        .setTitle(route)
                        .setScreenshots(traceConfig.includeScreenshots)
                        .setSnapshots(true)
                        .setSources(true)
                )
            }
            context.newPage().use { page ->
                try {
                    return page.takeSnapshot(url)
                } finally {
                    traceConfig?.let { traceConfig ->
                        val traceRelativePath = traceConfig.root.resolve(
                            (if (route.endsWith('/')) route + "index" else route).removePrefix(
                                "/"
                            ) + ".trace.zip"
                        )
                        context.tracing().stop(Tracing.StopOptions().setPath(traceRelativePath))
                        logger.lifecycle("Saved export trace to: $traceRelativePath")
                    }
                }
            }
        }
    }

    private fun <T> T.toTriple() = Triple(this, this, this)
    private fun <T, S> Triple<T, T, T>.map(transform: (T) -> S) =
        Triple(transform(first), transform(second), transform(third))

    @TaskAction
    fun execute() {
        // Sever should be running since "kobwebStart" is a prerequisite for this task
        val port = ServerStateFile(kobwebApplication.kobwebFolder).content!!.port

        val frontendData = Json.decodeFromString<AppData>(appDataFile.get().asFile.readText()).frontendData
            .also { data ->
                data.pages.toList().let { entries ->
                    if (entries.isEmpty()) {
                        throw GradleException("No pages were defined. You must tag at least one page with the `@Page` annotation!")
                    } else if (entries.none { it.route == "/" }) {
                        throw GradleException(
                            "No root route was defined for your site. This means if people visit your website URL, they'll get a 404 error. Create a `@Page` in a root `pages/Index.kt` file to make this warning go away."
                        )
                    }
                }
            }

        val (pagesRoot, resourcesRoot, systemRoot) = when {
            siteLayout.isFullstack -> Triple("pages", "resources", "system").map { getSiteDir().resolve(it) }
            else -> getSiteDir().toTriple()
        }

        frontendData.pages.takeIf { it.isNotEmpty() }?.let { pages ->
            val browser = kobwebBlock.app.export.browser.get()
            PlaywrightCache().install(browser)
            Playwright.create(
                Playwright.CreateOptions().setEnv(
                    mapOf(
                        // Should have been downloaded above, by PlaywrightCache()
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"
                    )
                )
            ).use { playwright ->
                val browserType = when (browser) {
                    KobwebBrowser.Chromium -> playwright.chromium()
                    KobwebBrowser.Firefox -> playwright.firefox()
                    KobwebBrowser.WebKit -> playwright.webkit()
                }
                browserType.launch().use { browser ->
                    val routePrefix = RoutePrefix(confInputs.routePrefix)
                    pages
                        .asSequence()
                        .map { it.route }
                        // Skip export routes with dynamic parts, as they are dynamically generated based on their URL
                        // anyway
                        .filter { !it.contains('{') }
                        .filter { route ->
                            val ctx = AppBlock.ExportBlock.ExportFilterContext(route)
                            (kobwebBlock.app.export.filter.orNull?.invoke(ctx) ?: true)
                                .also { shouldExport ->
                                    if (!shouldExport) {
                                        logger.lifecycle("\nSkipped export for \"$route\".")
                                    }
                                }
                        }
                        .map { route -> AppBlock.ExportBlock.RouteConfig(route) }
                        .toSet()
                        .let { pageRoutes ->
                            pageRoutes + kobwebBlock.app.export.extraRoutes.orNull.orEmpty()
                        }
                        .takeIf { routes -> routes.isNotEmpty() }
                        ?.forEach { routeConfig ->
                            val route = routeConfig.route
                            logger.lifecycle("\nSnapshotting html for \"$route\"...")

                            val prefixedRoute = routePrefix.prependTo(route)

                            try {
                                val snapshot: String
                                val elapsedMs = measureTimeMillis {
                                    snapshot = browser.takeSnapshot(route, "http://localhost:$port$prefixedRoute")
                                }

                                pagesRoot
                                    .resolve(routeConfig.exportPath)
                                    .run {
                                        if (this.exists()) {
                                            logger.warn("w: Export for \"${routeConfig.route}\" overwrote existing file \"$this\".")
                                        }

                                        parentFile.mkdirs()
                                        writeText(snapshot)
                                    }

                                logger.lifecycle("Snapshot finished in ${elapsedMs}ms (saved to: \"${routeConfig.exportPath}\").")
                            } catch (ex: TimeoutError) {
                                logger.error(buildString {
                                    append("e: Export for \"${routeConfig.route}\" skipped due to timeout.")
                                    if (siteLayout.isFullstack) {
                                        append(" It might be worth reviewing if any of your API routes have blocking logic in them (e.g. a database failing to connect), as this can eventually cause the Kobweb server to hang if too many blocking calls accumulate.")
                                    }
                                    append(" In your build script, consider calling `kobweb.app.export.enableTraces(...)` to generate snapshots which can help understanding. Finally, you can try increasing the timeout by setting `kobweb.app.export.timeout`.")
                                })
                            }
                        }
                        ?: run {
                            val noPagesExportedMessage = buildString {
                                append("No pages were found to export.")
                                if (kobwebBlock.app.export.filter.isPresent) {
                                    append(" This may be because your build script's `kobweb.app.export.filter` is filtering out all pages.")
                                }
                            }
                            // This case is an error in static layout mode, because with no pages, there's nothing for
                            // the user to visit. For a kobweb layout, however, there is always at least a minimal
                            // index.html file included.
                            when {
                                siteLayout.isFullstack -> logger.warn("w: $noPagesExportedMessage")
                                else -> logger.error("e: $noPagesExportedMessage")
                            }
                        }

                    // If we're exporting a static site and we want to support legacy site routes, then we need to walk
                    // through the pages we just exported and create copies of them at their legacy locations. We'll do
                    // two passes -- a first pass where we try to create symbolic links, which works on *nix systems
                    // and Windows *if* the user has the right permissions. If that fails, we'll do a second pass where
                    // we copy the files over manually.
                    val legacyRouteRedirectStrategy =
                        legacyRouteRedirectStrategy.getOrElse(AppBlock.LegacyRouteRedirectStrategy.WARN)
                    if (siteLayout.isStatic && legacyRouteRedirectStrategy != AppBlock.LegacyRouteRedirectStrategy.DISALLOW) {
                        val pagesRootPath = pagesRoot.toPath()
                        var duplicationOccurred = false
                        var symbolicLinksUsed = false
                        fun reportPathDuplicatedForLegacySupport(modernPath: Path, legacyPath: Path) {
                            duplicationOccurred = true
                            logger.lifecycle("\nDuplicating \"/${pagesRootPath.relativize(modernPath)}\" as \"${legacyPath.name}\".")
                        }

                        try {
                            if (kobwebBlock.app.export.forceCopyingForRedirects.get()) throw IOException("Forcefully abort symbolic link step")

                            pagesRoot.walkTopDown().forEach { file ->
                                if (file.isDirectory) {
                                    // In legacy Kobweb, a package called "multiWord" became the folder "multiWord".
                                    // Now it would become "multi-word".
                                    if (file.name.contains('-')) {
                                        run { // Reverse camel case: exampleRoute -> example-route
                                            val modernPath = file.toPath()
                                            val legacyPath =
                                                modernPath.parent.resolve(modernPath.name.kebabCaseToCamelCase())
                                            if (!legacyPath.exists()) Files.createSymbolicLink(legacyPath, modernPath)
                                        }
                                        run { // Reverse snake case -> example_route -> example-route
                                            val modernPath = file.toPath()
                                            val legacyPath =
                                                modernPath.parent.resolve(modernPath.name.replace('-', '_'))
                                            if (!legacyPath.exists()) Files.createSymbolicLink(legacyPath, modernPath)
                                        }
                                    }
                                } else {
                                    // In legacy Kobweb, a source file called "MultiWord.kt" became the file
                                    // "multiworld.html". Now it would become "multi-word.html".
                                    if (file.extension == "html" && file.name.contains('-')) {
                                        val modernPath = file.toPath()
                                        val legacyPath = modernPath.parent.resolve(modernPath.name.replace("-", ""))
                                        if (!legacyPath.exists()) {
                                            Files.createSymbolicLink(legacyPath, modernPath)
                                            reportPathDuplicatedForLegacySupport(modernPath, legacyPath)
                                        }
                                    }
                                }
                            }

                            symbolicLinksUsed = true
                        } catch (_: IOException) {
                            // If here, symbolic links aren't supported on this system. We'll fall back to manual
                            // copying. In this case, we will only copy the html files and leave the paths alone.
                            pagesRoot.walkTopDown().forEach { file ->
                                if (file.isFile && file.extension == "html" && file.nameWithoutExtension.contains('-')) {
                                    val modernPath = file.toPath()
                                    val legacyPath = modernPath.parent.resolve(modernPath.name.replace("-", ""))
                                    if (!legacyPath.exists()) {
                                        Files.copy(modernPath, legacyPath)
                                        reportPathDuplicatedForLegacySupport(modernPath, legacyPath)
                                    }
                                }
                            }
                        }

                        if (duplicationOccurred && legacyRouteRedirectStrategy == AppBlock.LegacyRouteRedirectStrategy.WARN) {
                            logger.lifecycle("") // Blank line before warning
                            logger.warn("w: At least one page was intentionally duplicated because your site is configured to support legacy routes. You can read more about this at https://github.com/varabyte/kobweb#legacy-routes. You can disable this behavior by setting `kobweb.app.legacyRouteRedirectStrategy` to `DISALLOW` in your site's build script. Alternately, if you set it to `ALLOW`, this message will not be shown.")
                            if (symbolicLinksUsed) {
                                logger.lifecycle("\n⚠\uFE0F Symbolic links were used to perform the duplication(s). If this causes issues with your static hosting provider, you can set the `kobweb.export.forceCopyingForRedirects` property to true and try again.")
                            }
                        }
                    }
                }
            }
        }

        // Copy resources.
        // Note: The "index.html" file that comes from here is auto-generated and useful as a fallback for dynamic
        // export layouts but shouldn't be copied over in static layouts as those should only include pages explicitly
        // defined by the site.
        getResourceFilesJsWithRoots().forEach { rootAndFile ->
            // Drop the leading slash so we don't confuse File resolve logic
            val relativePath = rootAndFile.relativeFile.invariantSeparatorsPath.substringAfter(getPublicPath()).drop(1)
            if (relativePath == "index.html" && siteLayout.isStatic) return@forEach

            (if (relativePath != "index.html") resourcesRoot else systemRoot)
                .resolve(relativePath)
                .let { destFile ->
                    rootAndFile.file.copyTo(destFile, overwrite = true)
                }
        }

        val scriptFileStr = confInputs.script
        val scriptFile = projectLayout.projectDirectory.file(scriptFileStr).asFile
        if (!scriptFile.exists()) {
            throw GradleException(
                "e: Your .kobweb/conf.yaml prod script (\"$scriptFileStr\") could not be found. This must be fixed before exporting. Perhaps search your build/ directory for \"${
                    scriptFileStr.substringAfterLast(
                        '/'
                    )
                }\" to find the right path."
            )
        }

        run {
            val destFile = systemRoot.resolve(scriptFile.name)
            scriptFile.copyTo(destFile, overwrite = true)
        }

        if (kobwebBlock.app.export.includeSourceMap.get()) {
            val scriptMapFile = File("${scriptFile}.map")
            val destFile = systemRoot.resolve(scriptMapFile.name)
            scriptMapFile.copyTo(destFile, overwrite = true)
        }

        // API routes are only supported by the fullstack layout
        if (siteLayout.isFullstack) {
            // The api.jar is not guaranteed to exist -- not every project needs to have API routes defined.
            confInputs.api?.let { apiFile ->
                val apiJarFile = projectLayout.projectDirectory.file(apiFile).asFile
                if (apiJarFile.exists()) {
                    val destFile = systemRoot.resolve(apiJarFile.name)
                    apiJarFile.copyTo(destFile, overwrite = true)
                }
            }
        }
    }
}

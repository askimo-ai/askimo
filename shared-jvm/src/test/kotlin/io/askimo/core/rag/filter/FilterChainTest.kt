/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.rag.filter

import io.askimo.core.config.AppConfig
import io.askimo.test.extensions.AskimoTestHome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@AskimoTestHome
class FilterChainTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var filterChain: FilterChain

    @BeforeEach
    fun setup() {
        filterChain = FilterChain.DEFAULT
    }

    // ============================================================================
    // Project Type Detection Tests
    // ============================================================================

    @Test
    fun `should detect Gradle project type`() {
        // Setup: Create a Gradle project
        val projectRoot = tempDir.resolve("gradle-project")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle build file")
        projectRoot.resolve("settings.gradle.kts").writeText("// Settings")

        // Test: Gradle-specific directories should be excluded
        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()

        val gradleDir = projectRoot.resolve(".gradle")
        gradleDir.createDirectories()

        assertTrue(filterChain.shouldExclude(buildDir, projectRoot), "build/ should be excluded for Gradle projects")
        assertTrue(filterChain.shouldExclude(gradleDir, projectRoot), ".gradle/ should be excluded for Gradle projects")
    }

    @Test
    fun `should detect Maven project type`() {
        // Setup: Create a Maven project
        val projectRoot = tempDir.resolve("maven-project")
        projectRoot.createDirectories()
        projectRoot.resolve("pom.xml").writeText("<project></project>")

        // Test: Maven-specific directories should be excluded
        val targetDir = projectRoot.resolve("target")
        targetDir.createDirectories()

        val mvnDir = projectRoot.resolve(".mvn")
        mvnDir.createDirectories()

        assertTrue(filterChain.shouldExclude(targetDir, projectRoot), "target/ should be excluded for Maven projects")
        assertTrue(filterChain.shouldExclude(mvnDir, projectRoot), ".mvn/ should be excluded for Maven projects")
    }

    @Test
    fun `should detect Node_js project type`() {
        // Setup: Create a Node.js project
        val projectRoot = tempDir.resolve("nodejs-project")
        projectRoot.createDirectories()
        projectRoot.resolve("package.json").writeText("{}")

        // Test: Node.js-specific directories should be excluded
        val nodeModules = projectRoot.resolve("node_modules")
        nodeModules.createDirectories()

        val distDir = projectRoot.resolve("dist")
        distDir.createDirectories()

        val nextDir = projectRoot.resolve(".next")
        nextDir.createDirectories()

        assertTrue(filterChain.shouldExclude(nodeModules, projectRoot), "node_modules/ should be excluded for Node.js projects")
        assertTrue(filterChain.shouldExclude(distDir, projectRoot), "dist/ should be excluded for Node.js projects")
        assertTrue(filterChain.shouldExclude(nextDir, projectRoot), ".next/ should be excluded for Node.js projects")
    }

    @Test
    fun `should detect Python project type`() {
        // Setup: Create a Python project
        val projectRoot = tempDir.resolve("python-project")
        projectRoot.createDirectories()
        projectRoot.resolve("requirements.txt").writeText("# requirements")

        // Test: Python-specific directories should be excluded
        val pycacheDir = projectRoot.resolve("__pycache__")
        pycacheDir.createDirectories()

        val venvDir = projectRoot.resolve("venv")
        venvDir.createDirectories()

        val pytestCache = projectRoot.resolve(".pytest_cache")
        pytestCache.createDirectories()

        assertTrue(filterChain.shouldExclude(pycacheDir, projectRoot), "__pycache__/ should be excluded for Python projects")
        assertTrue(filterChain.shouldExclude(venvDir, projectRoot), "venv/ should be excluded for Python projects")
        assertTrue(filterChain.shouldExclude(pytestCache, projectRoot), ".pytest_cache/ should be excluded for Python projects")
    }

    @Test
    fun `should detect Go project type`() {
        // Setup: Create a Go project
        val projectRoot = tempDir.resolve("go-project")
        projectRoot.createDirectories()
        projectRoot.resolve("go.mod").writeText("module example.com/test")

        // Test: Go-specific directories should be excluded
        val vendorDir = projectRoot.resolve("vendor")
        vendorDir.createDirectories()

        assertTrue(filterChain.shouldExclude(vendorDir, projectRoot), "vendor/ should be excluded for Go projects")
    }

    @Test
    fun `should detect Rust project type`() {
        // Setup: Create a Rust project
        val projectRoot = tempDir.resolve("rust-project")
        projectRoot.createDirectories()
        projectRoot.resolve("Cargo.toml").writeText("[package]")

        // Test: Rust-specific directories should be excluded
        val targetDir = projectRoot.resolve("target")
        targetDir.createDirectories()

        assertTrue(filterChain.shouldExclude(targetDir, projectRoot), "target/ should be excluded for Rust projects")
    }

    // ============================================================================
    // Multiple Project Types Tests
    // ============================================================================

    @Test
    fun `should detect multiple project types in same project`() {
        // Setup: Create a project with both Gradle and Node.js
        val projectRoot = tempDir.resolve("multi-project")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")
        projectRoot.resolve("package.json").writeText("{}")

        // Test: Both Gradle and Node.js exclusions should apply
        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()

        val nodeModules = projectRoot.resolve("node_modules")
        nodeModules.createDirectories()

        assertTrue(filterChain.shouldExclude(buildDir, projectRoot), "build/ should be excluded (Gradle)")
        assertTrue(filterChain.shouldExclude(nodeModules, projectRoot), "node_modules/ should be excluded (Node.js)")
    }

    // ============================================================================
    // Nested Path Tests
    // ============================================================================

    @Test
    fun `should exclude nested paths under excluded directories`() {
        // Setup: Create a Gradle project
        val projectRoot = tempDir.resolve("gradle-nested")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")

        // Test: Files deep inside build/ should be excluded
        val nestedFile = projectRoot.resolve("build/classes/kotlin/main/MyClass.class")
        Files.createDirectories(nestedFile.parent)
        nestedFile.writeText("binary")

        assertTrue(filterChain.shouldExclude(nestedFile, projectRoot), "Files inside build/ should be excluded")
    }

    @Test
    fun `should not exclude source files in src directory`() {
        // Setup: Create a Gradle project with src
        val projectRoot = tempDir.resolve("gradle-src")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")

        val srcDir = projectRoot.resolve("src/main/kotlin")
        srcDir.createDirectories()

        val sourceFile = srcDir.resolve("Main.kt")
        sourceFile.writeText("fun main() {}")

        assertFalse(filterChain.shouldExclude(sourceFile, projectRoot), "Source files should not be excluded")
    }

    // ============================================================================
    // Common Excludes Tests (Applied to All Projects)
    // ============================================================================

    @Test
    fun `should exclude git directory for all projects`() {
        // Setup: Create a project with .git
        val projectRoot = tempDir.resolve("any-project")
        projectRoot.createDirectories()

        val gitDir = projectRoot.resolve(".git")
        gitDir.createDirectories()

        assertTrue(filterChain.shouldExclude(gitDir, projectRoot), ".git/ should always be excluded")
    }

    @Test
    fun `should exclude IDE directories for all projects`() {
        val projectRoot = tempDir.resolve("any-project")
        projectRoot.createDirectories()

        val ideaDir = projectRoot.resolve(".idea")
        ideaDir.createDirectories()

        val vscodeDir = projectRoot.resolve(".vscode")
        vscodeDir.createDirectories()

        assertTrue(filterChain.shouldExclude(ideaDir, projectRoot), ".idea/ should always be excluded")
        assertTrue(filterChain.shouldExclude(vscodeDir, projectRoot), ".vscode/ should always be excluded")
    }

    @Test
    fun `should exclude log files for all projects`() {
        val projectRoot = tempDir.resolve("any-project")
        projectRoot.createDirectories()

        val logFile = projectRoot.resolve("app.log")
        logFile.writeText("logs")

        assertTrue(filterChain.shouldExclude(logFile, projectRoot), "*.log files should always be excluded")
    }

    // ============================================================================
    // No Project Type Detected Tests
    // ============================================================================

    @Test
    fun `should apply only common excludes when no project type detected`() {
        // Setup: Create a generic directory without project markers
        val projectRoot = tempDir.resolve("generic-project")
        projectRoot.createDirectories()

        // Test: Common excludes should still apply (without creating .git to avoid global gitignore)
        // Create a .vscode directory which is in commonExcludes
        val vscodeDir = projectRoot.resolve(".vscode")
        vscodeDir.createDirectories()

        assertTrue(filterChain.shouldExclude(vscodeDir, projectRoot), ".vscode/ should be excluded even without project type")

        // Test: Project-specific excludes should NOT apply
        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()

        // Note: build/ is NOT in commonExcludes, only in Gradle/Node.js/Python excludes
        // Without project markers, build/ should not be excluded
        assertFalse(filterChain.shouldExclude(buildDir, projectRoot), "build/ should not be excluded without project type markers")
    }

    // ============================================================================
    // Wildcard Pattern Tests
    // ============================================================================

    @Test
    fun `should handle wildcard patterns in project markers`() {
        // Setup: Create a .NET project with *.csproj file
        val projectRoot = tempDir.resolve("dotnet-project")
        projectRoot.createDirectories()
        projectRoot.resolve("MyApp.csproj").writeText("<Project></Project>")

        // Test: .NET-specific directories should be excluded
        val binDir = projectRoot.resolve("bin")
        binDir.createDirectories()

        val objDir = projectRoot.resolve("obj")
        objDir.createDirectories()

        assertTrue(filterChain.shouldExclude(binDir, projectRoot), "bin/ should be excluded for .NET projects")
        assertTrue(filterChain.shouldExclude(objDir, projectRoot), "obj/ should be excluded for .NET projects")
    }

    // ============================================================================
    // Edge Cases
    // ============================================================================

    @Test
    fun `should handle paths with similar names but different types`() {
        // Setup: Create a Gradle project
        val projectRoot = tempDir.resolve("gradle-edge")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")

        // Test: "build" as directory vs file with "build" in name
        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()

        val buildFile = projectRoot.resolve("build.txt")
        buildFile.writeText("not a directory")

        assertTrue(filterChain.shouldExclude(buildDir, projectRoot), "build/ directory should be excluded")
        assertFalse(filterChain.shouldExclude(buildFile, projectRoot), "build.txt file should not be excluded")
    }

    @Test
    fun `should handle deep nested project structure`() {
        // Setup: Create a monorepo with nested projects
        val rootDir = tempDir.resolve("monorepo")
        rootDir.createDirectories()

        val subProject = rootDir.resolve("packages/app")
        subProject.createDirectories()
        subProject.resolve("package.json").writeText("{}")

        val nodeModules = subProject.resolve("node_modules")
        nodeModules.createDirectories()

        // Test: Using subproject as root
        assertTrue(filterChain.shouldExclude(nodeModules, subProject), "node_modules/ should be excluded in subproject")
    }

    @Test
    fun `should handle relative paths correctly`() {
        // Setup: Create a Node.js project
        val projectRoot = tempDir.resolve("nodejs-rel")
        projectRoot.createDirectories()
        projectRoot.resolve("package.json").writeText("{}")

        val srcDir = projectRoot.resolve("src")
        srcDir.createDirectories()

        val nodeModulesInSrc = srcDir.resolve("node_modules")
        nodeModulesInSrc.createDirectories()

        // Test: node_modules anywhere in the tree should be excluded
        assertTrue(filterChain.shouldExclude(nodeModulesInSrc, projectRoot), "node_modules/ should be excluded at any level")
    }

    // ============================================================================
    // Performance and Caching Tests
    // ============================================================================

    @Test
    fun `should cache project type detection results`() {
        // Setup: Create a Gradle project
        val projectRoot = tempDir.resolve("gradle-cache")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")

        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()

        // First call - should detect and cache
        assertTrue(filterChain.shouldExclude(buildDir, projectRoot))

        // Second call - should use cached result (faster)
        assertTrue(filterChain.shouldExclude(buildDir, projectRoot))
    }

    // ============================================================================
    // Project Root Finding Tests
    // ============================================================================

    @Test
    fun `should find project root by walking up directory tree`() {
        // Setup: Create nested structure with project root higher up
        val projectRoot = tempDir.resolve("find-root")
        projectRoot.createDirectories()
        projectRoot.resolve("build.gradle.kts").writeText("// Gradle")

        val deepNested = projectRoot.resolve("src/main/kotlin/com/example")
        deepNested.createDirectories()

        val sourceFile = deepNested.resolve("Main.kt")
        sourceFile.writeText("fun main() {}")

        // Test: Should find project root and apply Gradle excludes
        // When checking a deep file, it should walk up to find build.gradle.kts
        assertFalse(filterChain.shouldExcludePath(sourceFile), "Source file should not be excluded")

        // But build directory should still be excluded
        val buildDir = projectRoot.resolve("build")
        buildDir.createDirectories()
        assertTrue(filterChain.shouldExcludePath(buildDir), "build/ should be excluded after finding root")
    }

    @Test
    fun `should use git directory as fallback for project root`() {
        // Setup: Create a project with .git but no specific project markers
        val projectRoot = tempDir.resolve("git-fallback")
        projectRoot.createDirectories()

        val gitDir = projectRoot.resolve(".git")
        gitDir.createDirectories()

        val srcDir = projectRoot.resolve("src")
        srcDir.createDirectories()

        // Test: .git should be excluded
        assertTrue(filterChain.shouldExcludePath(gitDir), ".git/ should be excluded")

        // Source should not be excluded
        assertFalse(filterChain.shouldExcludePath(srcDir), "src/ should not be excluded")
    }

    // ============================================================================
    // Monorepo Support Tests
    // ============================================================================

    @Test
    fun `should handle monorepo with different project types in subprojects`() {
        // Setup: Create a monorepo with multiple project types
        val monorepo = tempDir.resolve("monorepo")
        monorepo.createDirectories()
        monorepo.resolve(".git").createDirectories() // Git root

        // Backend - Gradle
        val backend = monorepo.resolve("backend")
        backend.createDirectories()
        backend.resolve("build.gradle.kts").writeText("// Gradle backend")

        val backendBuild = backend.resolve("build")
        backendBuild.createDirectories()

        val backendSrc = backend.resolve("src")
        backendSrc.createDirectories()

        // Frontend - Node.js
        val frontend = monorepo.resolve("frontend")
        frontend.createDirectories()
        frontend.resolve("package.json").writeText("{}")

        val frontendNodeModules = frontend.resolve("node_modules")
        frontendNodeModules.createDirectories()

        val frontendSrc = frontend.resolve("src")
        frontendSrc.createDirectories()

        // Test: Each subproject applies its own excludes
        assertTrue(
            filterChain.shouldExclude(backendBuild, backend),
            "backend/build/ should be excluded (Gradle)",
        )
        assertTrue(
            filterChain.shouldExclude(frontendNodeModules, frontend),
            "frontend/node_modules/ should be excluded (Node.js)",
        )

        // Source directories should not be excluded
        assertFalse(filterChain.shouldExclude(backendSrc, backend), "backend/src/ should not be excluded")
        assertFalse(filterChain.shouldExclude(frontendSrc, frontend), "frontend/src/ should not be excluded")

        // Compiled directory in frontend should NOT be excluded (not in Node.js excludes)
        val frontendCompiled = frontend.resolve("compiled")
        frontendCompiled.createDirectories()
        assertFalse(
            filterChain.shouldExclude(frontendCompiled, frontend),
            "frontend/compiled/ should NOT be excluded (not in Node.js excludes)",
        )

        // But Gradle-specific directory should be excluded in backend
        val gradleDir = backend.resolve(".gradle")
        gradleDir.createDirectories()
        assertTrue(
            filterChain.shouldExclude(gradleDir, backend),
            "backend/.gradle/ should be excluded (Gradle)",
        )
    }

    @Test
    fun `should find closest project root in monorepo`() {
        // Setup: Create a nested monorepo structure
        val monorepo = tempDir.resolve("monorepo")
        monorepo.createDirectories()
        monorepo.resolve(".git").createDirectories()

        val services = monorepo.resolve("services")
        services.createDirectories()

        val authService = services.resolve("auth-service")
        authService.createDirectories()
        authService.resolve("go.mod").writeText("module auth-service")

        val deepFile = authService.resolve("internal/handler/auth.go")
        Files.createDirectories(deepFile.parent)
        deepFile.writeText("package handler")

        // Test: Should find auth-service as the closest root, not monorepo
        val vendorDir = authService.resolve("vendor")
        vendorDir.createDirectories()

        assertTrue(filterChain.shouldExclude(vendorDir, authService), "vendor/ should be excluded (Go project)")

        // When using shouldExcludePath (auto-detection), it should find the closest root
        assertTrue(filterChain.shouldExcludePath(vendorDir), "vendor/ should be excluded via auto-detection")
    }

    @Test
    fun `should handle monorepo with nested projects of same type`() {
        // Setup: Monorepo with multiple Node.js projects
        val monorepo = tempDir.resolve("js-monorepo")
        monorepo.createDirectories()
        monorepo.resolve("package.json").writeText("{}") // Root package.json

        val packages = monorepo.resolve("packages")
        packages.createDirectories()

        val packageA = packages.resolve("package-a")
        packageA.createDirectories()
        packageA.resolve("package.json").writeText("{}")

        val packageB = packages.resolve("package-b")
        packageB.createDirectories()
        packageB.resolve("package.json").writeText("{}")

        // Test: node_modules in each package should be excluded
        val nodeModulesA = packageA.resolve("node_modules")
        nodeModulesA.createDirectories()

        val nodeModulesB = packageB.resolve("node_modules")
        nodeModulesB.createDirectories()

        assertTrue(filterChain.shouldExclude(nodeModulesA, packageA), "package-a/node_modules/ should be excluded")
        assertTrue(filterChain.shouldExclude(nodeModulesB, packageB), "package-b/node_modules/ should be excluded")
    }

    @Test
    fun `should discover all project roots in monorepo`() {
        // Setup: Create a monorepo with multiple projects
        val monorepo = tempDir.resolve("discovery-monorepo")
        monorepo.createDirectories()
        monorepo.resolve(".git").createDirectories()

        val backend = monorepo.resolve("backend")
        backend.createDirectories()
        backend.resolve("pom.xml").writeText("<project></project>")

        val frontend = monorepo.resolve("frontend")
        frontend.createDirectories()
        frontend.resolve("package.json").writeText("{}")

        val mobile = monorepo.resolve("mobile")
        mobile.createDirectories()
        mobile.resolve("build.gradle.kts").writeText("// Android")

        // Test: findAllProjectRoots should discover all subprojects
        val roots = filterChain.findAllProjectRoots(monorepo)

        assertTrue(roots.size >= 3, "Should find at least 3 project roots (git + backend + frontend + mobile)")
        assertTrue(roots.any { it.endsWith("backend") }, "Should find backend project")
        assertTrue(roots.any { it.endsWith("frontend") }, "Should find frontend project")
        assertTrue(roots.any { it.endsWith("mobile") }, "Should find mobile project")
    }

    @Test
    fun `should not search inside build directories when discovering projects`() {
        // Setup: Monorepo with build artifacts
        val monorepo = tempDir.resolve("monorepo-with-builds")
        monorepo.createDirectories()

        val app = monorepo.resolve("app")
        app.createDirectories()
        app.resolve("package.json").writeText("{}")

        // Nested package.json inside node_modules (should be skipped)
        val nodeModules = app.resolve("node_modules/some-lib")
        nodeModules.createDirectories()
        nodeModules.resolve("package.json").writeText("{}")

        // Nested package.json inside build directory (should be skipped)
        val build = app.resolve("build/output")
        build.createDirectories()
        build.resolve("package.json").writeText("{}")

        // Test: Should only find the app root, not artifacts
        val roots = filterChain.findAllProjectRoots(monorepo)

        assertTrue(roots.size == 1, "Should find only 1 project root (app), not nested artifacts")
        assertTrue(roots.first().endsWith("app"), "Should find app project")
    }

    @Test
    fun `should handle monorepo with mixed language polyglot project`() {
        // Setup: Single project with multiple language ecosystems
        val project = tempDir.resolve("polyglot-project")
        project.createDirectories()

        // Both Gradle (backend) and Node.js (frontend assets) in same project
        project.resolve("build.gradle.kts").writeText("// Gradle")
        project.resolve("package.json").writeText("{}") // For frontend tooling

        val buildDir = project.resolve("build")
        buildDir.createDirectories()

        val nodeModules = project.resolve("node_modules")
        nodeModules.createDirectories()

        // Test: Both Gradle and Node.js exclusions should apply
        assertTrue(filterChain.shouldExclude(buildDir, project), "build/ should be excluded (Gradle)")
        assertTrue(filterChain.shouldExclude(nodeModules, project), "node_modules/ should be excluded (Node.js)")
    }

    @Test
    fun `should apply correct excludes when subproject path used as root`() {
        // Setup: Monorepo where we index only a subproject
        val monorepo = tempDir.resolve("partial-index-monorepo")
        monorepo.createDirectories()

        val services = monorepo.resolve("services")
        services.createDirectories()

        val userService = services.resolve("user-service")
        userService.createDirectories()
        userService.resolve("go.mod").writeText("module user-service")

        val otherService = services.resolve("other-service")
        otherService.createDirectories()
        otherService.resolve("pom.xml").writeText("<project></project>")

        // Test: When indexing only user-service, it should use Go excludes
        val vendorDir = userService.resolve("vendor")
        vendorDir.createDirectories()

        assertTrue(
            filterChain.shouldExclude(vendorDir, userService),
            "vendor/ should be excluded when user-service is the root",
        )

        // Maven target in other-service should be excluded when other-service is root
        val targetDir = otherService.resolve("target")
        targetDir.createDirectories()

        assertTrue(
            filterChain.shouldExclude(targetDir, otherService),
            "target/ should be excluded when other-service is the root",
        )
    }

    @Test
    fun `should handle deeply nested monorepo structure`() {
        // Setup: Deeply nested project structure
        val monorepo = tempDir.resolve("deep-monorepo")
        monorepo.createDirectories()

        val path = monorepo.resolve("teams/backend/services/auth/api")
        path.createDirectories()
        path.resolve("go.mod").writeText("module auth-api")

        val vendorDir = path.resolve("vendor")
        vendorDir.createDirectories()

        // Test: Should detect Go project even when deeply nested
        assertTrue(filterChain.shouldExclude(vendorDir, path), "vendor/ should be excluded in deeply nested project")

        // Auto-detection should also work
        assertTrue(filterChain.shouldExcludePath(vendorDir), "vendor/ should be excluded via auto-detection")
    }

    @Test
    fun `should cache project type detection per subproject in monorepo`() {
        // Setup: Monorepo with multiple projects
        val monorepo = tempDir.resolve("cache-monorepo")
        monorepo.createDirectories()

        val projectA = monorepo.resolve("project-a")
        projectA.createDirectories()
        projectA.resolve("build.gradle.kts").writeText("// Gradle")

        val projectB = monorepo.resolve("project-b")
        projectB.createDirectories()
        projectB.resolve("package.json").writeText("{}")

        val buildA = projectA.resolve("build")
        buildA.createDirectories()

        val nodeModulesB = projectB.resolve("node_modules")
        nodeModulesB.createDirectories()

        // First calls - should detect and cache
        assertTrue(filterChain.shouldExclude(buildA, projectA))
        assertTrue(filterChain.shouldExclude(nodeModulesB, projectB))

        // Second calls - should use cache
        assertTrue(filterChain.shouldExclude(buildA, projectA))
        assertTrue(filterChain.shouldExclude(nodeModulesB, projectB))
    }

    @Test
    fun `should never exclude the project root directory itself`() {
        // Setup: Create a Maven project
        val projectRoot = tempDir.resolve("maven-root")
        projectRoot.createDirectories()
        projectRoot.resolve("pom.xml").writeText("<project></project>")

        // Test: The project root itself should NEVER be excluded
        // even though it's a directory
        assertFalse(
            filterChain.shouldExclude(projectRoot, projectRoot),
            "Project root itself should never be excluded",
        )

        // But subdirectories that match exclude patterns should be excluded
        val targetDir = projectRoot.resolve("target")
        targetDir.createDirectories()
        assertTrue(
            filterChain.shouldExclude(targetDir, projectRoot),
            "target/ subdirectory should be excluded",
        )
    }

    @Test
    fun `should handle empty relative path correctly`() {
        // Setup: Create a project
        val projectRoot = tempDir.resolve("test-project")
        projectRoot.createDirectories()
        projectRoot.resolve("package.json").writeText("{}")

        // When checking the root itself, relativePath will be empty
        // This should not cause false positives with wildcard patterns
        val context = FilterContext(
            rootPath = projectRoot,
            relativePath = "",
            fileName = projectRoot.fileName.toString(),
            extension = "",
            projectTypes = listOf(
                AppConfig.indexing.projectTypes.first { it.name == "Node.js" },
            ),
        )

        // The empty relative path should not match any exclude patterns
        val filter = ProjectTypeFilter()
        assertFalse(
            filter.shouldExclude(projectRoot, isDirectory = true, context),
            "Empty relative path (root itself) should not be excluded",
        )
    }
}

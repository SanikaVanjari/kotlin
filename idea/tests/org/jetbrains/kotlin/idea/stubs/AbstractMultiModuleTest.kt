/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.stubs

import com.intellij.codeInsight.daemon.DaemonAnalyzerTestCase
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.externalSystem.service.project.IdeModifiableModelsProviderImpl
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.module.StdModuleTypes
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.PersistentLibraryKind
import com.intellij.openapi.roots.ui.configuration.libraryEditor.NewLibraryEditor
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.idea.facet.getOrCreateFacet
import org.jetbrains.kotlin.idea.facet.initializeIfNeeded
import org.jetbrains.kotlin.idea.test.ConfigLibraryUtil
import org.jetbrains.kotlin.idea.test.KotlinJdkAndLibraryProjectDescriptor
import org.jetbrains.kotlin.idea.test.PluginTestCaseBase
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import org.junit.Assert
import java.io.File

abstract class AbstractMultiModuleTest : DaemonAnalyzerTestCase() {

    abstract override fun getTestDataPath(): String

    override fun setUp() {
        super.setUp()
        VfsRootAccess.allowRootAccess(KotlinTestUtils.getHomeDirectory())
    }

    fun module(name: String, jdk: TestJdkKind = TestJdkKind.MOCK_JDK, hasTestRoot: Boolean = false): Module {
        val srcDir = testDataPath + "${getTestName(true)}/$name"
        val moduleWithSrcRootSet = createModuleFromTestData(srcDir, name, StdModuleTypes.JAVA, true)
        if (hasTestRoot) {
            addRoot(
                moduleWithSrcRootSet,
                File(testDataPath + "${getTestName(true)}/${name}Test"),
                true
            )
        }

        ConfigLibraryUtil.configureSdk(moduleWithSrcRootSet, PluginTestCaseBase.addJdk(testRootDisposable) { PluginTestCaseBase.jdk(jdk) })

        return moduleWithSrcRootSet
    }

    override fun tearDown() {
        VfsRootAccess.disallowRootAccess(KotlinTestUtils.getHomeDirectory())
        super.tearDown()
    }

    public override fun createModule(path: String, moduleType: ModuleType<*>): Module {
        return super.createModule(path, moduleType)
    }

    fun addRoot(module: Module, sourceDirInTestData: File, isTestRoot: Boolean, transformContainedFiles: ((File) -> Unit)? = null) {
        val tmpDir = createTempDirectory()

        // Preserve original root name. This might be useful for later matching of copied files to original ones
        val tmpRootDir = File(tmpDir, sourceDirInTestData.name).also { it.mkdir() }

        FileUtil.copyDir(sourceDirInTestData, tmpRootDir)

        if (transformContainedFiles != null) {
            tmpRootDir.listFiles().forEach(transformContainedFiles)
        }

        val virtualTempDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmpRootDir)!!
        object : WriteCommandAction.Simple<Unit>(project) {
            override fun run() {
                virtualTempDir.refresh(false, isTestRoot)
            }
        }.execute().throwException()
        PsiTestUtil.addSourceRoot(module, virtualTempDir, isTestRoot)
    }

    fun Module.addDependency(
        other: Module,
        dependencyScope: DependencyScope = DependencyScope.COMPILE,
        exported: Boolean = false
    ): Module = this.apply { ModuleRootModificationUtil.addDependency(this, other, dependencyScope, exported) }

    fun Module.addLibrary(
        jar: File,
        name: String = KotlinJdkAndLibraryProjectDescriptor.LIBRARY_NAME,
        kind: PersistentLibraryKind<*>? = null
    ) {
        ConfigLibraryUtil.addLibrary(NewLibraryEditor().apply {
            this.name = name
            addRoot(VfsUtil.getUrlForLibraryRoot(jar), OrderRootType.CLASSES)
        }, this, kind)
    }

    fun Module.enableMultiPlatform(additionalCompilerArguments: String = "") {
        createFacet()
        val facetSettings = KotlinFacetSettingsProvider.getInstance(project).getInitializedSettings(this)
        facetSettings.useProjectSettings = false
        facetSettings.compilerSettings = CompilerSettings().apply {
            additionalArguments += " -Xmulti-platform $additionalCompilerArguments"
        }
    }

    fun Module.enableCoroutines() {
        createFacet()
        val facetSettings = KotlinFacetSettingsProvider.getInstance(project).getInitializedSettings(this)
        facetSettings.useProjectSettings = false
        facetSettings.coroutineSupport = LanguageFeature.State.ENABLED
    }

    protected fun checkFiles(
        findFiles: () -> List<PsiFile>,
        check: () -> Unit
    ) {
        var atLeastOneFile = false
        findFiles().forEach { file ->
            configureByExistingFile(file.virtualFile!!)
            atLeastOneFile = true
            check()
        }
        Assert.assertTrue(atLeastOneFile)
    }
}

fun Module.createFacet(
    platformKind: TargetPlatform? = null,
    useProjectSettings: Boolean = true
) {
    createFacetWithAdditionalSetup(platformKind, useProjectSettings) { }
}

fun Module.createMultiplatformFacetM1(
    platformKind: TargetPlatform? = null,
    useProjectSettings: Boolean = true,
    implementedModuleNames: List<String>
) {
    createFacetWithAdditionalSetup(platformKind, useProjectSettings) {
        this.implementedModuleNames = implementedModuleNames
    }
}

fun Module.createMultiplatformFacetM3(
    platformKind: TargetPlatform? = null,
    useProjectSettings: Boolean = true,
    dependsOnModuleNames: List<String>
) {
    createFacetWithAdditionalSetup(platformKind, useProjectSettings) {
        this.dependsOnModuleNames = dependsOnModuleNames
        this.isHmppEnabled = true
    }
}

private fun Module.createFacetWithAdditionalSetup(
    platformKind: TargetPlatform?,
    useProjectSettings: Boolean,
    additionalSetup: KotlinFacetSettings.() -> Unit
) {
    WriteAction.run<Throwable> {
        val modelsProvider = IdeModifiableModelsProviderImpl(project)
        with(getOrCreateFacet(modelsProvider, useProjectSettings).configuration.settings) {
            initializeIfNeeded(
                this@createFacetWithAdditionalSetup,
                modelsProvider.getModifiableRootModel(this@createFacetWithAdditionalSetup),
                platformKind
            )
            additionalSetup()
        }
        modelsProvider.commit()
    }
}
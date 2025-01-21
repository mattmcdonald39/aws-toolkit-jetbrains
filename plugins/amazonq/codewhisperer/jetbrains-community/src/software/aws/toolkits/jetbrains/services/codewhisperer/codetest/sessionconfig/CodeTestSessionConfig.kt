// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.codetest.sessionconfig

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessModuleDir
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.project.modules
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import kotlinx.coroutines.runBlocking
import software.aws.toolkits.core.utils.createTemporaryZipFile
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.core.utils.putNextEntry
import software.aws.toolkits.jetbrains.services.amazonq.FeatureDevSessionContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.Payload
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PayloadContext
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.PayloadMetadata
import software.aws.toolkits.jetbrains.services.codewhisperer.codetest.cannotFindBuildArtifacts
import software.aws.toolkits.jetbrains.services.codewhisperer.codetest.cannotFindFile
import software.aws.toolkits.jetbrains.services.codewhisperer.codetest.cannotFindValidFile
import software.aws.toolkits.jetbrains.services.codewhisperer.codetest.fileTooLarge
import software.aws.toolkits.jetbrains.services.codewhisperer.codetest.noFileOpenError
import software.aws.toolkits.jetbrains.services.codewhisperer.language.CodeWhispererProgrammingLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.language.languages.CodeWhispererUnknownLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.language.programmingLanguage
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.CODE_SCAN_CREATE_PAYLOAD_TIMEOUT_IN_SECONDS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.DEFAULT_CODE_SCAN_TIMEOUT_IN_SECONDS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.DEFAULT_PAYLOAD_LIMIT_IN_BYTES
import software.aws.toolkits.resources.message
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.util.Stack
import java.util.zip.ZipEntry
import kotlin.io.path.name
import kotlin.io.path.relativeTo

// TODO: share huge duplicates with CodeScanSessionConfig need to abstract to a ZipSessionConfig
class CodeTestSessionConfig(
    private val selectedFile: VirtualFile?,
    private val project: Project,
    private val buildAndExecuteLogFile: VirtualFile? = null,
) {
    val projectRoot = project.basePath?.let { Path.of(it) }?.toFile()?.toVirtualFile() ?: run {
        project.guessProjectDir() ?: error("Cannot guess base directory for project ${project.name}")
    }

    private val featureDevSessionContext = FeatureDevSessionContext(project)

    val fileIndex = ProjectRootManager.getInstance(project).fileIndex

    /**
     * return default timeout
     */
    fun overallJobTimeoutInSeconds(): Long = DEFAULT_CODE_SCAN_TIMEOUT_IN_SECONDS

    fun getPayloadLimitInBytes(): Long = DEFAULT_PAYLOAD_LIMIT_IN_BYTES

    private fun willExceedPayloadLimit(currentTotalFileSize: Long, currentFileSize: Long): Boolean =
        currentTotalFileSize.let { totalSize -> totalSize > (getPayloadLimitInBytes() - currentFileSize) }

    private var programmingLanguage: CodeWhispererProgrammingLanguage = selectedFile?.programmingLanguage() ?: CodeWhispererUnknownLanguage.INSTANCE

    fun getProgrammingLanguage(): CodeWhispererProgrammingLanguage = programmingLanguage

    fun getSelectedFile(): VirtualFile? = selectedFile

    fun createPayload(): Payload {
        // Fail fast if the selected file is null for UTG
        if (selectedFile == null) {
            noFileOpenError()
        }

        // Fail fast if the selected file size is greater than the payload limit.
        if (selectedFile.length > getPayloadLimitInBytes()) {
            fileTooLarge()
        }

        val start = Instant.now().toEpochMilli()

        LOG.debug { "Creating payload. File selected as root for the context truncation: ${projectRoot.path}" }

        val payloadMetadata: PayloadMetadata = try {
            getProjectPayloadMetadata()
        } catch (e: Exception) {
            val errorMessage = when {
                e.message?.contains("Illegal repetition near index") == true -> "Illegal repetition near index"
                else -> e.message
            }
            LOG.debug { "Error creating payload metadata: $errorMessage" }
            cannotFindBuildArtifacts(errorMessage ?: message("testgen.message.failed"))
        }

        // Copy all the included source files to the source zip
        val srcZip = zipFiles(payloadMetadata.sourceFiles.map { Path.of(it) })
        val payloadContext = PayloadContext(
            payloadMetadata.language,
            payloadMetadata.linesScanned,
            payloadMetadata.sourceFiles.size,
            Instant.now().toEpochMilli() - start,
            payloadMetadata.sourceFiles.mapNotNull { Path.of(it).toFile().toVirtualFile() },
            payloadMetadata.payloadSize,
            srcZip.length()
        )

        return Payload(payloadContext, srcZip)
    }

    /**
     * Timeout for creating the payload [createPayload]
     */
    fun createPayloadTimeoutInSeconds(): Long = CODE_SCAN_CREATE_PAYLOAD_TIMEOUT_IN_SECONDS

    private fun countLinesInVirtualFile(virtualFile: VirtualFile): Int {
        try {
            val bufferedReader = virtualFile.inputStream.bufferedReader()
            return bufferedReader.useLines { lines -> lines.count() }
        } catch (e: Exception) {
            cannotFindFile("Line count error: ${e.message}", virtualFile.path)
        }
    }

    private fun zipFiles(files: List<Path>): File = createTemporaryZipFile {
        files.forEach { file ->
            try {
                val relativePath = file.relativeTo(projectRoot.toNioPath())
                val projectBaseName = projectRoot.name
                val zipEntryPath = "$projectBaseName/${relativePath.toString().replace("\\", "/")}"
                LOG.debug { "Adding file to ZIP: $zipEntryPath" }
                it.putNextEntry(zipEntryPath, file)
            } catch (e: Exception) {
                cannotFindFile("Zipping error: ${e.message}", file.toString())
            }
        }

        // 2. Add the "utgRequiredArtifactsDir" directory
        val utgDir = "utgRequiredArtifactsDir/" // Note the trailing slash which adds it as a directory and not a file
        LOG.debug { "Adding directory to ZIP: $utgDir" }
        val utgEntry = ZipEntry(utgDir)
        it.putNextEntry(utgEntry)

        // 3. Add the three empty subdirectories
        val buildAndExecuteLogDir = "buildAndExecuteLogDir"
        val subDirs = listOf(buildAndExecuteLogDir, "repoMapData", "testCoverageDir")
        subDirs.forEach { subDir ->
            val subDirPathString = Path.of(utgDir, subDir).toString() + "/" // Added trailing slash similar to utgRequiredArtifactsDir
            LOG.debug { "Adding empty directory to ZIP: $subDirPathString" }
            val zipEntry = ZipEntry(subDirPathString)
            it.putNextEntry(zipEntry)
        }
        if (buildAndExecuteLogFile != null) {
            it.putNextEntry(Path.of(utgDir, buildAndExecuteLogDir, "buildAndExecuteLog").name, buildAndExecuteLogFile.inputStream)
        }
    }.toFile()

    fun getProjectPayloadMetadata(): PayloadMetadata {
        val files = mutableSetOf<String>()
        val traversedDirectories = mutableSetOf<VirtualFile>()
        val stack = Stack<VirtualFile>()
        var currentTotalFileSize = 0L
        var currentTotalLines = 0L
        val languageCounts = mutableMapOf<CodeWhispererProgrammingLanguage, Int>()

        // Adding Target File to make sure target file doesn't get filtered out.
        selectedFile?.let { selected ->
            files.add(selected.path)
            currentTotalFileSize += selected.length
            currentTotalLines += countLinesInVirtualFile(selected)
            selected.programmingLanguage().let { language ->
                if (language !is CodeWhispererUnknownLanguage) {
                    languageCounts[language] = (languageCounts[language] ?: 0) + 1
                }
            }
        }

        moduleLoop@ for (module in project.modules) {
            val changeListManager = ChangeListManager.getInstance(module.project)
            if (module.guessModuleDir() != null) {
                stack.push(module.guessModuleDir())
                while (stack.isNotEmpty()) {
                    val current = stack.pop()

                    if (!current.isDirectory) {
                        if (current.isFile && current.path != selectedFile?.path &&
                            !changeListManager.isIgnoredFile(current) &&
                            runBlocking { !featureDevSessionContext.ignoreFile(current) } &&
                            runReadAction { !fileIndex.isInLibrarySource(current) }
                        ) {
                            if (willExceedPayloadLimit(currentTotalFileSize, current.length)) {
                                fileTooLarge()
                            } else {
                                try {
                                    val language = current.programmingLanguage()
                                    if (language !is CodeWhispererUnknownLanguage) {
                                        languageCounts[language] = (languageCounts[language] ?: 0) + 1
                                    }
                                    files.add(current.path)
                                    currentTotalFileSize += current.length
                                    currentTotalLines += countLinesInVirtualFile(current)
                                } catch (e: Exception) {
                                    LOG.debug { "Error parsing the file: ${current.path} with error: ${e.message}" }
                                    continue
                                }
                            }
                        }
                    } else {
                        // Directory case: only traverse if not ignored
                        if (!changeListManager.isIgnoredFile(current) &&
                            runBlocking { !featureDevSessionContext.ignoreFile(current) } &&
                            !traversedDirectories.contains(current) && current.isValid &&
                            runReadAction { !fileIndex.isInLibrarySource(current) }
                        ) {
                            for (child in current.children) {
                                stack.push(child)
                            }
                        }
                        traversedDirectories.add(current)
                    }
                }
            }
        }

        val maxCount = languageCounts.maxByOrNull { it.value }?.value ?: 0
        val maxCountLanguage = languageCounts.filter { it.value == maxCount }.keys.firstOrNull()

        if (maxCountLanguage == null) {
            programmingLanguage = CodeWhispererUnknownLanguage.INSTANCE
            cannotFindValidFile("Amazon Q: doesn't contain valid files to generate tests")
        }
        programmingLanguage = maxCountLanguage
        return PayloadMetadata(files, currentTotalFileSize, currentTotalLines, maxCountLanguage.toTelemetryType())
    }

    fun getPath(root: String, relativePath: String = ""): Path? = try {
        Path.of(root, relativePath).normalize()
    } catch (e: Exception) {
        LOG.debug { "Cannot find file at path $relativePath relative to the root $root" }
        null
    }

    fun getRelativePath(): Path? = try {
        selectedFile?.path?.let { Path.of(projectRoot.path).relativize(Path.of(it)).normalize() }
    } catch (e: Exception) {
        LOG.debug { "Cannot calculate relative path of $selectedFile with respect to $projectRoot" }
        null
    }

    fun File.toVirtualFile() = LocalFileSystem.getInstance().findFileByIoFile(this)

    companion object {
        private val LOG = getLogger<CodeTestSessionConfig>()
        fun create(file: VirtualFile?, project: Project): CodeTestSessionConfig = CodeTestSessionConfig(file, project, null)
    }
}

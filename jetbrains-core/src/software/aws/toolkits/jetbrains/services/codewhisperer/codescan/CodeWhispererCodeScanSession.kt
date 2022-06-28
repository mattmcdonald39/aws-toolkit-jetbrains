// Copyright 2022 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.codewhisperer.codescan

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.TimeoutUtil.sleep
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.time.withTimeout
import org.apache.commons.codec.digest.DigestUtils
import software.amazon.awssdk.services.codewhisperer.CodeWhispererClient
import software.amazon.awssdk.services.codewhisperer.model.ArtifactType
import software.amazon.awssdk.services.codewhisperer.model.CodeScanFindingsSchema
import software.amazon.awssdk.services.codewhisperer.model.CodeScanStatus
import software.amazon.awssdk.services.codewhisperer.model.CreateCodeScanResponse
import software.amazon.awssdk.services.codewhisperer.model.CreateUploadUrlResponse
import software.amazon.awssdk.services.codewhisperer.model.GetCodeScanResponse
import software.amazon.awssdk.services.codewhisperer.model.ListCodeScanFindingsResponse
import software.amazon.awssdk.utils.IoUtils
import software.aws.toolkits.core.utils.Waiters.waitUntil
import software.aws.toolkits.core.utils.debug
import software.aws.toolkits.core.utils.getLogger
import software.aws.toolkits.jetbrains.core.AwsClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.codescan.sessionconfig.CodeScanSessionConfig
import software.aws.toolkits.jetbrains.services.codewhisperer.credentials.CodeWhispererClientManager
import software.aws.toolkits.jetbrains.services.codewhisperer.model.CodeScanResponseContext
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.CODE_SCAN_POLLING_INTERVAL_IN_SECONDS
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.TOTAL_BYTES_IN_KB
import software.aws.toolkits.jetbrains.services.codewhisperer.util.CodeWhispererConstants.TOTAL_MILLIS_IN_SECOND
import software.aws.toolkits.jetbrains.utils.assertIsNonDispatchThread
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal class CodeWhispererCodeScanSession(private val sessionContext: CodeScanSessionContext) {
    private val clientToken: UUID = UUID.randomUUID()
    private val urlResponse = mutableMapOf<ArtifactType, CreateUploadUrlResponse>()
    private val codewhispererClient: CodeWhispererClient = CodeWhispererClientManager.getInstance().getClient()

    private fun now() = Instant.now().toEpochMilli()

    /**
     * Note that this function makes network calls and needs to be run from a background thread.
     * Runs a code scan session which comprises the following steps:
     *  1. Generate truncation (zip files) based on the truncation in the session context.
     *  2. CreateUploadURL to upload the context.
     *  3. Upload the zip files using the URL
     *  4. Call createCodeScan to start a code scan
     *  5. Keep polling the API GetCodeScan to wait for results for a given timeout period.
     *  6. Return the results from the ListCodeScan API.
     */
    suspend fun run(): CodeScanResponse {
        assertIsNonDispatchThread()
        val startTime = now()
        val (payloadContext, sourceZip, buildZip) = withTimeout(Duration.ofSeconds(sessionContext.sessionConfig.createPayloadTimeoutInSeconds())) {
            runReadAction { sessionContext.sessionConfig.createPayload() }
        }

        LOG.debug {
            "Total size of payload in KB: ${payloadContext.payloadSize * 1.0 / TOTAL_BYTES_IN_KB} \n" +
                "Total number of lines scanned: ${payloadContext.totalLines} \n" +
                "Total number of files included in payload: ${payloadContext.totalFiles} \n" +
                "Total time taken for creating payload: ${payloadContext.totalTimeInMilliseconds * 1.0 / TOTAL_MILLIS_IN_SECOND} seconds\n" +
                "Payload context language: ${payloadContext.language}"
        }

        // 2 & 3. CreateUploadURL and upload the context.
        LOG.debug { "Uploading source zip located at ${sourceZip.path} to s3" }
        val sourceZipUploadResponse = createUploadUrlAndUpload(sourceZip, "SourceCode")
        LOG.debug {
            "Successfully uploaded source zip to s3: " +
                "Upload id: ${sourceZipUploadResponse.uploadId()} " +
                "Request id: ${sourceZipUploadResponse.responseMetadata().requestId()}"
        }
        urlResponse[ArtifactType.SOURCE_CODE] = sourceZipUploadResponse
        if (buildZip != null) {
            LOG.debug { "Uploading build zip located at ${buildZip.path} to s3" }
            val buildZipUploadResponse = createUploadUrlAndUpload(buildZip, "BuiltJars")
            LOG.debug {
                "Successfully uploaded build zip to s3: " +
                    "Upload id: ${buildZipUploadResponse.uploadId()} " +
                    "Request id: ${buildZipUploadResponse.responseMetadata().requestId()}"
            }
            urlResponse[ArtifactType.BUILT_JARS] = buildZipUploadResponse
        }

        // 5. Call createCodeScan to start a code scan
        LOG.debug { "Requesting security scan for the uploaded artifacts, language: ${payloadContext.language}" }
        val createCodeScanResponse = createCodeScan(payloadContext.language.toString())
        LOG.debug {
            "Successfully created security scan with " +
                "status: ${createCodeScanResponse.status()} " +
                "for request id: ${createCodeScanResponse.responseMetadata().requestId()}"
        }
        var codeScanStatus = createCodeScanResponse.status()
        if (codeScanStatus == CodeScanStatus.FAILED) {
            LOG.debug {
                "CodeWhisperer service error occurred. Something went wrong when creating a security scan: $createCodeScanResponse " +
                    "Status: ${createCodeScanResponse.status()} for request id: ${createCodeScanResponse.responseMetadata().requestId()}"
            }
            codeScanFailed()
        }
        val jobId = createCodeScanResponse.jobId()
        LOG.debug { "Job ID for create security scan job: $jobId" }
        // 6. Keep polling the API GetCodeScan to wait for results for a given timeout period.
        waitUntil(
            succeedOn = { codeScanStatus == CodeScanStatus.COMPLETED },
            maxDuration = Duration.ofSeconds(sessionContext.sessionConfig.overallJobTimeoutInSeconds())
        ) {
            val elapsedTime = (now() - startTime) * 1.0 / TOTAL_MILLIS_IN_SECOND
            LOG.debug { "Waiting for security scan to complete. Elapsed time: $elapsedTime sec." }
            val getCodeScanResponse = getCodeScan(jobId)
            codeScanStatus = getCodeScanResponse.status()
            LOG.debug {
                "Get security scan status: ${getCodeScanResponse.status()}, " +
                    "request id: ${getCodeScanResponse.responseMetadata().requestId()}"
            }
            sleep(CODE_SCAN_POLLING_INTERVAL_IN_SECONDS * TOTAL_MILLIS_IN_SECOND)
            if (codeScanStatus == CodeScanStatus.FAILED) {
                LOG.debug {
                    "CodeWhisperer service error occurred. Something went wrong fetching results for security scan: $getCodeScanResponse " +
                        "Status: ${getCodeScanResponse.status()} for request id: ${getCodeScanResponse.responseMetadata().requestId()}"
                }
                codeScanFailed()
            }
        }

        LOG.debug { "Security scan completed successfully by CodeWhisperer." }

        // 7. Return the results from the ListCodeScan API.
        LOG.debug { "Fetching results for the completed security scan" }
        var listCodeScanFindingsResponse = listCodeScanFindings(jobId)
        LOG.debug {
            "Successfully fetched results for security scan with " +
                "job id: $jobId, request id: ${listCodeScanFindingsResponse.responseMetadata().requestId()}"
        }
        val documents = mutableListOf<String>()
        documents.add(listCodeScanFindingsResponse.codeScanFindings())
        while (listCodeScanFindingsResponse.nextToken() != null) {
            documents.add(listCodeScanFindingsResponse.codeScanFindings())
            listCodeScanFindingsResponse = listCodeScanFindings(jobId)
        }
        LOG.debug { "Successfully fetched results for the security scan." }
        LOG.debug { "Rendering response to display security scan results." }
        val issues = mapToCodeScanIssues(documents)
        val responseContext = CodeScanResponseContext(
            codeScanJobId = jobId,
            codewhispererLanguage = payloadContext.language,
            payloadSizeInBytes = payloadContext.payloadSize,
            codeScanLines = payloadContext.totalLines,
            codeScanTotalIssues = issues.size
        )
        return CodeScanResponse(issues, responseContext)
    }

    /**
     * Creates an upload URL and uplaods the zip file to the presigned URL
     */
    private fun createUploadUrlAndUpload(zipFile: File, artifactType: String): CreateUploadUrlResponse = try {
        val fileMd5: String = Base64.getEncoder().encodeToString(DigestUtils.md5(FileInputStream(zipFile)))
        LOG.debug { "Fetching presigned URL for uploading $artifactType." }
        val createUploadUrlResponse = createUploadUrl(fileMd5, artifactType)
        LOG.debug { "Successfully fetched presigned URL for uploading $artifactType." }
        val url = createUploadUrlResponse.uploadUrl()
        LOG.debug { "Uploading $artifactType using the presigned URL." }
        uploadArtifactTOS3(url, zipFile, fileMd5)
        createUploadUrlResponse
    } catch (e: Exception) {
        LOG.debug { "Security scan failed. Something went wrong uploading artifacts: ${e.message}" }
        throw e
    }

    private fun createUploadUrl(md5Content: String, artifactType: String): CreateUploadUrlResponse = codewhispererClient.createUploadUrl {
        it.contentMd5(md5Content)
        it.artifactType(artifactType)
    }

    @Throws(IOException::class)
    private fun uploadArtifactTOS3(url: String, fileToUpload: File, md5: String) {
        HttpRequests.put(url, "application/zip").userAgent(AwsClientManager.userAgent).tuner {
            it.setRequestProperty(CONTENT_MD5, md5)
            it.setRequestProperty(SERVER_SIDE_ENCRYPTION, AES256)
        }.connect {
            val connection = it.connection as HttpURLConnection
            connection.setFixedLengthStreamingMode(fileToUpload.length())
            IoUtils.copy(fileToUpload.inputStream(), connection.outputStream)
        }
    }

    private fun createCodeScan(language: String): CreateCodeScanResponse {
        val artifactsMap = mapOf(
            ArtifactType.SOURCE_CODE to urlResponse[ArtifactType.SOURCE_CODE]?.uploadId(),
            ArtifactType.BUILT_JARS to urlResponse[ArtifactType.BUILT_JARS]?.uploadId()
        ).filter { (_, v) -> v != null }

        try {
            return codewhispererClient.createCodeScan {
                it.clientToken(clientToken.toString())
                it.programmingLanguage { builder -> builder.languageName(language) }
                it.artifacts(artifactsMap)
            }
        } catch (e: Exception) {
            LOG.debug { "Creating security scan failed: ${e.message}" }
            throw e
        }
    }

    private fun getCodeScan(jobId: String): GetCodeScanResponse = try {
        codewhispererClient.getCodeScan { it.jobId(jobId) }
    } catch (e: Exception) {
        LOG.debug { "Getting security scan failed: ${e.message}" }
        throw e
    }

    private fun listCodeScanFindings(jobId: String): ListCodeScanFindingsResponse = try {
        codewhispererClient.listCodeScanFindings {
            it.jobId(jobId)
            it.codeScanFindingsSchema(CodeScanFindingsSchema.CODESCAN_FINDINGS_1_0)
        }
    } catch (e: Exception) {
        LOG.debug { "Listing security scan failed: ${e.message}" }
        throw e
    }

    private fun mapToCodeScanIssues(recommendations: List<String>): List<CodeWhispererCodeScanIssue> {
        val scanRecommendations: List<CodeScanRecommendation> = recommendations.map {
            val value: List<CodeScanRecommendation> = MAPPER.readValue(it)
            value
        }.flatten()
        return scanRecommendations.mapNotNull {
            val file = LocalFileSystem.getInstance().findFileByIoFile(
                Path.of(File.separator, it.filePath).toFile()
            )
            when (file?.isDirectory) {
                false -> {
                    runReadAction {
                        FileDocumentManager.getInstance().getDocument(file)
                    }?.let { document ->
                        val endCol = document.getLineEndOffset(it.endLine - 1) - document.getLineStartOffset(it.endLine - 1) + 1
                        CodeWhispererCodeScanIssue(
                            startLine = it.startLine,
                            startCol = 1,
                            endLine = it.endLine,
                            endCol = endCol,
                            file = file,
                            project = sessionContext.project,
                            title = it.title,
                            description = it.description
                        )
                    }
                }
                else -> null
            }
        }.onEach { issue ->
            // Add range highlighters for all the issues found.
            runInEdt {
                issue.rangeHighlighter = issue.addRangeHighlighter()
            }
        }
    }
    companion object {
        private val LOG = getLogger<CodeWhispererCodeScanSession>()
        private val MAPPER = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        private const val AES256 = "AES256"
        private const val CONTENT_MD5 = "Content-MD5"
        private const val SERVER_SIDE_ENCRYPTION = "x-amz-server-side-encryption"
    }
}

internal data class CodeScanResponse(
    val issues: List<CodeWhispererCodeScanIssue>,
    val responseContext: CodeScanResponseContext
)

internal data class CodeScanRecommendation(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val title: String,
    val description: Description
)

internal data class Description(val text: String, val markdown: String)

internal data class CodeScanSessionContext(
    val project: Project,
    val sessionConfig: CodeScanSessionConfig
)
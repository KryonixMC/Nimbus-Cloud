package dev.nimbuspowered.nimbus.module.storage.driver

import dev.nimbuspowered.nimbus.module.storage.StorageConfig
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import java.net.URI
import java.nio.file.Path

class S3StorageDriver(private val config: StorageConfig) : StorageDriver {

    private val logger = LoggerFactory.getLogger(S3StorageDriver::class.java)

    private val client: S3Client = S3Client.builder()
        .region(Region.of(config.region))
        .apply { if (config.endpoint.isNotBlank()) endpointOverride(URI.create(config.endpoint)) }
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(config.accessKey, config.secretKey)
            )
        )
        .apply { if (config.pathStyle) forcePathStyle(true) }
        .httpClientBuilder(ApacheHttpClient.builder())
        .build()

    override fun listObjects(prefix: String): List<StorageObject> {
        val request = ListObjectsV2Request.builder()
            .bucket(config.bucket)
            .prefix(prefix)
            .build()
        return try {
            client.listObjectsV2Paginator(request)
                .contents()
                .map { obj ->
                    StorageObject(
                        key = obj.key(),
                        etag = obj.eTag().trim('"'),
                        size = obj.size()
                    )
                }
        } catch (e: Exception) {
            logger.error("Failed to list objects under prefix '{}': {}", prefix, e.message)
            emptyList()
        }
    }

    override fun putObject(key: String, file: Path): String {
        val request = PutObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .build()
        val response = client.putObject(request, RequestBody.fromFile(file))
        return response.eTag().trim('"')
    }

    override fun getObject(key: String, destination: Path) {
        val request = GetObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .build()
        client.getObject(request, destination)
    }

    override fun headObject(key: String): String? {
        return try {
            val request = HeadObjectRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .build()
            client.headObject(request).eTag().trim('"')
        } catch (e: NoSuchKeyException) {
            null
        } catch (e: Exception) {
            logger.debug("headObject failed for key '{}': {}", key, e.message)
            null
        }
    }

    override fun deleteObject(key: String) {
        val request = DeleteObjectRequest.builder()
            .bucket(config.bucket)
            .key(key)
            .build()
        client.deleteObject(request)
    }

    override fun close() {
        client.close()
    }
}

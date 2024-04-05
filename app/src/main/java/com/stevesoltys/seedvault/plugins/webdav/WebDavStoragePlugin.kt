package com.stevesoltys.seedvault.plugins.webdav

import android.content.Context
import android.util.Log
import at.bitfire.dav4jvm.DavCollection
import at.bitfire.dav4jvm.Response.HrefRelation.SELF
import at.bitfire.dav4jvm.exception.NotFoundException
import at.bitfire.dav4jvm.property.DisplayName
import at.bitfire.dav4jvm.property.ResourceType
import com.stevesoltys.seedvault.plugins.EncryptedMetadata
import com.stevesoltys.seedvault.plugins.StoragePlugin
import com.stevesoltys.seedvault.plugins.chunkFolderRegex
import com.stevesoltys.seedvault.plugins.saf.FILE_BACKUP_METADATA
import com.stevesoltys.seedvault.plugins.saf.FILE_NO_MEDIA
import com.stevesoltys.seedvault.plugins.tokenRegex
import com.stevesoltys.seedvault.settings.Storage
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal class WebDavStoragePlugin(
    context: Context,
    webDavConfig: WebDavConfig,
    root: String = DIRECTORY_ROOT,
) : WebDavStorage(webDavConfig, root), StoragePlugin {

    @Throws(IOException::class)
    override suspend fun startNewRestoreSet(token: Long) {
        val location = "$url/$token".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        val response = davCollection.createFolder()
        debugLog { "startNewRestoreSet($token) = $response" }
    }

    @Throws(IOException::class)
    override suspend fun initializeDevice() {
        // TODO does it make sense to delete anything
        //  when [startNewRestoreSet] is always called first? Maybe unify both calls?
        val location = url.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            davCollection.head { response ->
                debugLog { "Root exists: $response" }
            }
        } catch (e: NotFoundException) {
            val response = davCollection.createFolder()
            debugLog { "initializeDevice() = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun hasData(token: Long, name: String): Boolean {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        return try {
            val response = suspendCoroutine { cont ->
                davCollection.head { response ->
                    cont.resume(response)
                }
            }
            debugLog { "hasData($token, $name) = $response" }
            response.isSuccessful
        } catch (e: NotFoundException) {
            debugLog { "hasData($token, $name) = $e" }
            false
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getOutputStream(token: Long, name: String): OutputStream {
        val location = "$url/$token/$name".toHttpUrl()
        return try {
            getOutputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting OutputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun getInputStream(token: Long, name: String): InputStream {
        val location = "$url/$token/$name".toHttpUrl()
        return try {
            getInputStream(location)
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException("Error getting InputStream for $token and $name: ", e)
        }
    }

    @Throws(IOException::class)
    override suspend fun removeData(token: Long, name: String) {
        val location = "$url/$token/$name".toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        try {
            val response = suspendCoroutine { cont ->
                davCollection.delete { response ->
                    cont.resume(response)
                }
            }
            debugLog { "removeData($token, $name) = $response" }
        } catch (e: Exception) {
            if (e is IOException) throw e
            else throw IOException(e)
        }
    }

    @Throws(IOException::class)
    override suspend fun hasBackup(storage: Storage): Boolean {
        // TODO this requires refactoring
        return true
    }

    override suspend fun getAvailableBackups(): Sequence<EncryptedMetadata>? {
        return try {
            doGetAvailableBackups()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting available backups: ", e)
            null
        }
    }

    private suspend fun doGetAvailableBackups(): Sequence<EncryptedMetadata> {
        val location = url.toHttpUrl()
        val davCollection = DavCollection(okHttpClient, location)

        // get all restore set tokens in root folder
        val tokens = ArrayList<Long>()
        davCollection.propfind(
            depth = 2,
            reqProp = arrayOf(DisplayName.NAME, ResourceType.NAME),
        ) { response, relation ->
            debugLog { "getAvailableBackups() = $response" }
            // This callback will be called for every file in the folder
            if (relation != SELF && !response.isFolder() && response.href.pathSize >= 2 &&
                response.hrefName() == FILE_BACKUP_METADATA
            ) {
                val tokenName = response.href.pathSegments[response.href.pathSegments.size - 2]
                getTokenOrNull(tokenName)?.let { token ->
                    tokens.add(token)
                }
            }
        }
        val tokenIterator = tokens.iterator()
        return generateSequence {
            if (!tokenIterator.hasNext()) return@generateSequence null // end sequence
            val token = tokenIterator.next()
            EncryptedMetadata(token) {
                getInputStream(token, FILE_BACKUP_METADATA)
            }
        }
    }

    private fun getTokenOrNull(name: String): Long? {
        val looksLikeToken = name.isNotEmpty() && tokenRegex.matches(name)
        if (looksLikeToken) {
            return try {
                name.toLong()
            } catch (e: NumberFormatException) {
                throw AssertionError(e) // regex must be wrong
            }
        }
        if (isUnexpectedFile(name)) {
            Log.w(TAG, "Found invalid backup set folder: $name")
        }
        return null
    }

    private fun isUnexpectedFile(name: String): Boolean {
        return name != FILE_NO_MEDIA &&
            !chunkFolderRegex.matches(name) &&
            !name.endsWith(".SeedSnap")
    }

    override val providerPackageName: String = context.packageName // 100% built-in plugin

}

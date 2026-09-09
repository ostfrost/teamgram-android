package com.sousa.avatar.standalone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.sousa.feature_avatar.data.network.*
import com.sousa.feature_avatar.data.repository.*
import com.sousa.feature_avatar.data.session.AvatarSessionStore
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AAR/GLB smoke test, isolated from services and user accounts. */
@RunWith(AndroidJUnit4::class)
class GalleryNativeRenderTest {
    @Test fun rendersFivePortraitsAndReusesDiskCache() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val types = listOf(AvatarType.human_male, AvatarType.human_female, AvatarType.human_male, AvatarType.human_female, AvatarType.human_male)
        val rows = types.mapIndexed { index, type ->
            AvatarServiceAvatar(id = 90001L + index, userId = 0, name = "Render fixture", type = type,
                privacy = AvatarPrivacy.nobody, isActive = index == 0, paramsJson = JsonObject(),
                paramsHash = (index + 1).toString().repeat(64), createdAt = "fixture", updatedAt = "fixture")
        }
        val service = Proxy.newProxyInstance(AvatarServiceClient::class.java.classLoader, arrayOf(AvatarServiceClient::class.java)) { _, method, _ ->
            when (method.name) {
                "getAvatarCollection" -> AvatarCollection(rows)
                "uploadThumb" -> AvatarThumb("https://example.invalid/unused", rows.first().paramsHash!!)
                else -> error("Unexpected service call: ${method.name}")
            }
        } as AvatarServiceClient
        val wardrobeService = Proxy.newProxyInstance(WardrobeServiceClient::class.java.classLoader, arrayOf(WardrobeServiceClient::class.java)) { _, method, _ ->
            error("Unexpected wardrobe network call: ${method.name}")
        } as WardrobeServiceClient
        val repository = AvatarRepository(service)
        repository.refresh()
        val thumbs = GalleryThumbRepository(context, repository, AvatarSessionStore(context), WardrobeRepository(context, wardrobeService, OkHttpClient()), OkHttpClient())
        val output = File(context.getExternalFilesDir(null), "gallery-smoke").apply { mkdirs() }
        for ((index, avatar) in rows.withIndex()) {
            val file = thumbs.get(avatar)
            assertTrue(file.length() in 12..GalleryThumbRepository.MAX_BYTES)
            val bytes = file.readBytes()
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            assertEquals(512, bitmap.width); assertEquals(512, bitmap.height)
            assertTrue((0 until bitmap.height).any { y -> (0 until bitmap.width).any { x -> (bitmap.getPixel(x, y) ushr 24) > 8 } })
            bitmap.recycle()
            file.copyTo(File(output, "portrait-$index.webp"), overwrite = true)
            val modified = file.lastModified()
            assertEquals(file, thumbs.get(avatar))
            assertEquals(modified, file.lastModified())
        }
    }
}

package se.oxelv.mintracker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.delay
import se.oxelv.mintracker.data.AvatarPaths
import se.oxelv.mintracker.data.AvatarRepository
import se.oxelv.mintracker.data.DogEntity
import se.oxelv.mintracker.data.Profile
import se.oxelv.mintracker.data.TrackerDatabaseProvider
import se.oxelv.mintracker.data.supabase

@Composable
fun ProfileAvatar(profile: Profile, size: Dp = 64.dp) {
    AvatarImage(profile.id, profile.displayName, profile.avatarPath, profile.avatarUrl,
        AvatarPaths.PROFILE_BUCKET, size)
}

@Composable
fun DogAvatar(dog: DogEntity, size: Dp = 52.dp) {
    AvatarImage(dog.ownerId, dog.name, dog.avatarPath, null, AvatarPaths.DOG_BUCKET, size,
        dogId = dog.id)
}

@Composable
private fun AvatarImage(ownerId: String, name: String, path: String?, legacyUrl: String?, bucket: String, size: Dp, dogId: String? = null) {
    val context = LocalContext.current
    val viewerId = supabase.auth.currentUserOrNull()?.id
    val repo = remember(context) { AvatarRepository(context.applicationContext, TrackerDatabaseProvider.get(context)) }
    val safePath = path?.takeIf {
        if (dogId == null) AvatarPaths.isProfile(ownerId, it) else AvatarPaths.isDog(ownerId, dogId, it)
    }
    var signedUrl by remember(viewerId, bucket, safePath) { mutableStateOf<String?>(null) }
    LaunchedEffect(viewerId, bucket, safePath) {
        if (viewerId != null && safePath != null) {
            while (true) {
                signedUrl = runCatching { repo.signedUrl(bucket, safePath, viewerId) }.getOrNull()
                delay(if (signedUrl == null) 30_000L else 7 * 60_000L)
            }
        }
    }
    val model = if (safePath != null) signedUrl else legacyUrl
    val fallback: @Composable () -> Unit = {
        Surface(shape = CircleShape, color = AppColors.PaleBlue, modifier = Modifier.size(size)) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (dogId != null) "🐕" else name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" },
                    fontSize = (size.value * 0.38f).sp, fontWeight = FontWeight.Bold, color = AppColors.Blue)
            }
        }
    }
    if (model == null) fallback() else {
        val request = remember(viewerId, bucket, safePath, model) {
            ImageRequest.Builder(context).data(model)
                .memoryCacheKey("$viewerId|$bucket|${safePath ?: model}")
                .diskCacheKey("$viewerId|$bucket|${safePath ?: model}")
                .build()
        }
        SubcomposeAsyncImage(model = request, contentDescription = "Bild för $name",
            onError = { se.oxelv.mintracker.data.Diagnostics.report(se.oxelv.mintracker.data.DiagnosticCode.IMAGE_LOAD_FAILED, it.result.throwable) },
            modifier = Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop,
            loading = { fallback() }, error = { fallback() })
    }
}

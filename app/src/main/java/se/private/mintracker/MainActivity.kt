package se.oxelv.mintracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import android.view.MotionEvent
import se.oxelv.mintracker.data.*
import se.oxelv.mintracker.tracking.*
import se.oxelv.mintracker.updates.*
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.status.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = AppColors.Blue
private val CardColor = AppColors.Surface
private val Background = AppColors.Background

class MainActivity : FragmentActivity() {
    private var uiHasUnsavedTraining = false
    private var authCallbackMessage by mutableStateOf<String?>(null)
    private val db by lazy { TrackerDatabaseProvider.get(this) }
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var permissionReady by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionReady = hasLocation() }
    private var pendingStart: (() -> Unit)? = null
    private val stepPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingStart?.invoke()
        pendingStart = null
    }
    private var pendingImageBytes: ByteArray? = null
    private val saveImageLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = pendingImageBytes
        pendingImageBytes = null
        if (uri != null && bytes != null) runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Bilden kunde inte skrivas.")
        }
    }
    private var pendingReportBytes: ByteArray? = null
    private var pendingReportOwner: String? = null
    private val savePdfLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> saveReport(uri) }
    private val saveXlsxLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri -> saveReport(uri) }
    private fun saveReport(uri: android.net.Uri?) {
        val bytes = pendingReportBytes
        val owner = pendingReportOwner
        pendingReportBytes = null
        pendingReportOwner = null
        if (uri != null && bytes != null && owner == supabase.auth.currentUserOrNull()?.id) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Filen kunde inte skrivas.") } }
            android.widget.Toast.makeText(this@MainActivity,
                if (result.isSuccess) "Rapporten sparades." else "Rapporten kunde inte sparas.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun handleAuthCallback(incoming: Intent?) {
        val callbackIntent = incoming ?: return
        val link = callbackIntent.data
        when (val callback = AuthRedirect.classify(link)) {
            AuthRedirect.Callback.Unrelated -> return
            is AuthRedirect.Callback.Failure -> authCallbackMessage = callback.message
            AuthRedirect.Callback.Recovery -> {
                PasswordRecovery.showLoading()
                lifecycleScope.launch { PasswordRecovery.open(checkNotNull(link)) }
            }
            AuthRedirect.Callback.Session -> {
                val imported = CompletableDeferred<Boolean>()
                supabase.handleDeeplinks(callbackIntent) { imported.complete(true) }
                lifecycleScope.launch {
                    val finished = withTimeoutOrNull(20_000) { imported.await() } == true
                    authCallbackMessage = if (finished && supabase.auth.currentSessionOrNull() != null) {
                        try {
                            val user = supabase.auth.retrieveUserForCurrentSession()
                            if (user.emailConfirmedAt != null) "E-postadressen är verifierad. Du är nu inloggad."
                            else "Inloggningen lyckades, men e-postadressen är ännu inte verifierad."
                        } catch (_: Exception) {
                            "Länken öppnades, men verifieringsstatus kunde inte hämtas. Kontrollera internet och prova att logga in."
                        }
                    } else "Verifieringen kunde inte slutföras. Kontrollera internet eller prova att logga in om kontot redan är verifierat."
                }
            }
        }
        // The URL may contain session credentials. Do not retain it in the Activity intent.
        callbackIntent.data = null
    }
    private fun service(action: String, kind: String? = null, dogId: String? = null) {
        val intent = Intent(this, TrackingService::class.java).setAction(action).apply {
            kind?.let { putExtra(TrackingService.EXTRA_KIND, it) }
            dogId?.let { putExtra(TrackingService.EXTRA_DOG_ID, it) }
            if (action == TrackingService.START) putExtra(TrackingService.EXTRA_USER_ID, supabase.auth.currentUserOrNull()?.id)
        }
        if (action == TrackingService.START) ContextCompat.startForegroundService(this, intent) else startService(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleAuthCallback(intent)
        hideNavigationBar()
        window.statusBarColor = android.graphics.Color.rgb(243, 247, 253)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        MapLibre.getInstance(this)
        permissionReady = hasLocation()
        setContent {
            val scope = rememberCoroutineScope()
            val updateManager = remember { AppUpdateManager.get(applicationContext) }
            val updateState by updateManager.state.collectAsStateWithLifecycle()
            val updateRequired = UpdateRules.requirement(BuildConfig.VERSION_CODE.toLong(), updateState.policy) == UpdateRequirement.REQUIRED
            val authSnackbar = remember { SnackbarHostState() }
            LaunchedEffect(authCallbackMessage) {
                authCallbackMessage?.let { message ->
                    authSnackbar.showSnackbar(message, duration = SnackbarDuration.Long)
                    if (authCallbackMessage == message) authCallbackMessage = null
                }
            }
            val activityRepository = remember { ActivityRepository(db) }
            val dogRepository = remember { DogRepository(db) }
            val avatarRepository = remember { AvatarRepository(applicationContext, db) }
            val authStatus by supabase.auth.sessionStatus.collectAsStateWithLifecycle()
            val userId = if (authStatus is SessionStatus.Authenticated) supabase.auth.currentUserOrNull()?.id else null
            LaunchedEffect(userId) { if (userId != null) runCatching { avatarRepository.retryCleanup(userId) } }
            val profileRepository = remember { ProfileRepository() }
            val groupRepository = remember { GroupRepository(profileRepository) }
            val friendRepository = remember { FriendRepository(profileRepository) }
            val challengeRepository = remember { ChallengeRepository() }
            val adminRepository = remember { se.oxelv.mintracker.data.AdminRepository() }
            var serverAdmin by remember(userId) { mutableStateOf(false) }
            var serverConfiguration by remember(userId) { mutableStateOf<kotlinx.serialization.json.JsonObject?>(null) }
            var accessReload by remember { mutableIntStateOf(0) }
            val accessLifecycle = LocalLifecycleOwner.current
            DisposableEffect(accessLifecycle) {
                val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) accessReload++ }
                accessLifecycle.lifecycle.addObserver(observer)
                onDispose { accessLifecycle.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(authStatus, accessReload) {
                serverAdmin = false
                val configuration = runCatching { adminRepository.configuration() }.getOrNull()
                if (userId != null) {
                    val actor = userId
                    val verified = runCatching { adminRepository.isAdmin() }.getOrDefault(false)
                    if (supabase.auth.currentUserOrNull()?.id == actor) {
                        serverAdmin = verified
                        serverConfiguration = configuration
                    }
                } else serverConfiguration = configuration
            }
            var profile by remember(userId) { mutableStateOf<Profile?>(null) }
            var profileLoading by remember(userId) { mutableStateOf(false) }
            var profileError by remember(userId) { mutableStateOf<String?>(null) }
            var profileReload by remember(userId) { mutableIntStateOf(0) }
            LaunchedEffect(profile?.id) { if (profile != null) accessReload++ }
            var discoverQuery by remember(userId) { mutableStateOf("") }
            var discoverResults by remember(userId) { mutableStateOf<List<Profile>>(emptyList()) }
            var discoverPage by remember(userId) { mutableIntStateOf(0) }
            var discoverMore by remember(userId) { mutableStateOf(false) }
            var discoverLoading by remember(userId) { mutableStateOf(false) }
            var discoverError by remember(userId) { mutableStateOf<String?>(null) }
            var publicProfileId by remember(userId) { mutableStateOf<String?>(null) }
            var publicProfile by remember(userId) { mutableStateOf<Profile?>(null) }
            var publicProfileLoading by remember(userId) { mutableStateOf(false) }
            var publicProfileError by remember(userId) { mutableStateOf<String?>(null) }
            var publicProfileReload by remember(userId) { mutableIntStateOf(0) }
            var socialBack by remember(userId) { mutableStateOf("discover") }
            var incomingRequests by remember(userId) { mutableStateOf<List<SocialInboxItem>>(emptyList()) }
            var sentRequests by remember(userId) { mutableStateOf<List<SocialInboxItem>>(emptyList()) }
            var challengeIncoming by remember(userId) { mutableStateOf<List<ChallengeInvitation>>(emptyList()) }
            var challengeSent by remember(userId) { mutableStateOf<List<ChallengeInvitation>>(emptyList()) }
            var challengeInboxError by remember(userId) { mutableStateOf<String?>(null) }
            var challengeInboxLoading by remember(userId) { mutableStateOf(false) }
            var challengeActionLoading by remember(userId) { mutableStateOf(false) }
            var challengeReload by remember(userId) { mutableIntStateOf(0) }
            var openChallengeId by remember(userId) { mutableStateOf<String?>(null) }
            var homeChallenge by remember(userId) { mutableStateOf<ChallengeSummary?>(null) }
            var homeChallengeProgress by remember(userId) { mutableStateOf<Double?>(null) }
            var manageableGroupIds by remember(userId) { mutableStateOf<Set<String>>(emptySet()) }
            var friends by remember(userId) { mutableStateOf<List<FriendListItem>>(emptyList()) }
            var friendLoading by remember(userId) { mutableStateOf(false) }
            var friendError by remember(userId) { mutableStateOf<String?>(null) }
            var friendActionError by remember(userId) { mutableStateOf<String?>(null) }
            var friendActionLoading by remember(userId) { mutableStateOf(false) }
            var friendReload by remember(userId) { mutableIntStateOf(0) }
            var relation by remember(userId) { mutableStateOf<FriendRelation?>(null) }
            var relationLoading by remember(userId) { mutableStateOf(false) }
            var relationError by remember(userId) { mutableStateOf<String?>(null) }
            var relationReload by remember(userId) { mutableIntStateOf(0) }
            var groups by remember(userId) { mutableStateOf<List<SocialGroup>>(emptyList()) }
            var groupsLoading by remember(userId) { mutableStateOf(false) }
            var groupsError by remember(userId) { mutableStateOf<String?>(null) }
            var groupActionError by remember(userId) { mutableStateOf<String?>(null) }
            var groupNotice by remember(userId) { mutableStateOf<String?>(null) }
            var groupReload by remember(userId) { mutableIntStateOf(0) }
            var previewGroup by remember(userId) { mutableStateOf<GroupPreview?>(null) }
            var selectedGroupId by remember(userId) { mutableStateOf<String?>(null) }
            var selectedGroup by remember(userId) { mutableStateOf<SocialGroup?>(null) }
            var groupMembers by remember(userId) { mutableStateOf<List<GroupMemberView>>(emptyList()) }
            var groupDetailLoading by remember(userId) { mutableStateOf(false) }
            var groupDetailError by remember(userId) { mutableStateOf<String?>(null) }
            var groupDetailReload by remember(userId) { mutableIntStateOf(0) }
            var weeklyStats by remember(userId) { mutableStateOf<List<WeeklyGroupStat>>(emptyList()) }
            var statsLoading by remember(userId) { mutableStateOf(false) }
            var statsError by remember(userId) { mutableStateOf<String?>(null) }
            var statsReload by remember(userId) { mutableIntStateOf(0) }
            LaunchedEffect(userId, profileReload) {
                if (userId != null) {
                    profileLoading = true
                    profileError = null
                    try { profile = profileRepository.getOrCreate() }
                    catch (error: Exception) { profileError = profileErrorMessage(error) }
                    finally { profileLoading = false }
                }
            }
            LaunchedEffect(userId, discoverQuery, discoverPage) {
                if (userId != null) {
                    delay(250)
                    discoverLoading = true; discoverError = null
                    try {
                        val found = profileRepository.searchPublic(discoverQuery, discoverPage)
                        discoverResults = if (discoverPage == 0) found else discoverResults + found
                        discoverMore = found.size == 25
                    } catch (error: Exception) { discoverError = profileErrorMessage(error) }
                    finally { discoverLoading = false }
                }
            }
            LaunchedEffect(userId, publicProfileId, publicProfileReload) {
                val id = publicProfileId
                if (userId != null && id != null) {
                    publicProfileLoading = true; publicProfileError = null; publicProfile = null
                    try { publicProfile = profileRepository.publicProfile(id) }
                    catch (error: Exception) { publicProfileError = profileErrorMessage(error) }
                    finally { publicProfileLoading = false }
                }
            }
            LaunchedEffect(userId, friendReload) {
                if (userId != null) {
                    friendLoading = true; friendError = null
                    incomingRequests = emptyList(); sentRequests = emptyList(); friends = emptyList()
                    try {
                        incomingRequests = friendRepository.inbox()
                        sentRequests = friendRepository.sent()
                        friends = friendRepository.friends()
                    } catch (error: Exception) { friendError = friendErrorMessage(error) }
                    finally { friendLoading = false }
                }
            }
            LaunchedEffect(userId, publicProfileId, relationReload) {
                val otherId = publicProfileId
                relation = null; relationError = null
                if (userId != null && otherId != null) {
                    relationLoading = true
                    try { relation = friendRepository.relation(otherId) }
                    catch (error: Exception) { relationError = friendErrorMessage(error) }
                    finally { relationLoading = false }
                }
            }
            LaunchedEffect(userId, groupReload) {
                if (userId != null) {
                    groupsLoading = true; groupsError = null
                    try { groups = groupRepository.myGroups() }
                    catch (error: Exception) { groupsError = socialErrorMessage(error) }
                    finally { groupsLoading = false }
                }
            }
            LaunchedEffect(userId, selectedGroupId, groupDetailReload) {
                val id = selectedGroupId
                if (userId != null && id != null) {
                    groupDetailLoading = true; groupDetailError = null; selectedGroup = null; groupMembers = emptyList()
                    try {
                        val fresh = groupRepository.detail(id)
                        if (fresh == null) groupDetailError = "Gruppen finns inte längre eller du saknar åtkomst."
                        else { selectedGroup = fresh; groupMembers = groupRepository.memberViews(id) }
                    }
                    catch (error: Exception) { groupDetailError = socialErrorMessage(error) }
                    finally { groupDetailLoading = false }
                }
            }
            LaunchedEffect(userId, selectedGroupId, statsReload) {
                val id = selectedGroupId
                if (userId != null && id != null) {
                    statsLoading = true; statsError = null; weeklyStats = emptyList()
                    try { weeklyStats = groupRepository.weeklyStats(id) }
                    catch (error: Exception) { statsError = groupStatsErrorMessage(error) }
                    finally { statsLoading = false }
                }
            }
            val live by TrackingState.session.collectAsStateWithLifecycle()
            val activities by remember(userId) { db.dao().activities(userId ?: "") }.collectAsStateWithLifecycle(emptyList())
            val dogs by remember(userId) { dogRepository.dogs(userId ?: "") }.collectAsStateWithLifecycle(emptyList())
            LaunchedEffect(userId) {
                if (userId != null) {
                    activityRepository.prepareLegacy(userId)
                    ActivitySyncWorker.enqueue(this@MainActivity, userId)
                }
            }
            var screen by remember(userId) { mutableStateOf(if (live.running && live.ownerUserId == userId) "active" else "home") }
            LaunchedEffect(userId, screen, challengeReload) {
                if (userId == null) return@LaunchedEffect
                if (screen == "social_hub" || screen == "challenges") {
                    challengeInboxLoading = true; challengeInboxError = null
                    try {
                        challengeIncoming = challengeRepository.invitations(false)
                        challengeSent = challengeRepository.invitations(true)
                        if (screen == "challenges") manageableGroupIds = challengeRepository.manageableGroupIds()
                    } catch (error: Exception) { challengeInboxError = challengeErrorMessage(error) }
                    finally { challengeInboxLoading = false }
                }
                if (screen == "home") {
                    runCatching {
                        val active = challengeRepository.mine().firstOrNull { it.joined && ChallengeRules.state(it.startAt, it.endAt, status = it.status) == "ACTIVE" }
                        homeChallenge = active
                        homeChallengeProgress = active?.let { challenge -> challengeRepository.leaderboard(challenge.id).firstOrNull { it.userId == userId }?.progressValue }
                    }
                }
            }
            fun challengeInvitationAction(action: suspend () -> Unit) {
                scope.launch {
                    challengeActionLoading = true; challengeInboxError = null
                    try { action(); if (supabase.auth.currentUserOrNull()?.id == userId) challengeReload++ }
                    catch (error: Exception) { if (supabase.auth.currentUserOrNull()?.id == userId) challengeInboxError = challengeErrorMessage(error) }
                    finally { if (supabase.auth.currentUserOrNull()?.id == userId) challengeActionLoading = false }
                }
            }
            var statisticsBack by remember(userId) { mutableStateOf("history") }
            var dogBackRoute by remember(userId) { mutableStateOf("profile") }
            var dogBusy by remember(userId) { mutableStateOf(false) }
            var dogError by remember(userId) { mutableStateOf<String?>(null) }
            fun performDogAction(failureMessage: String? = null, action: suspend () -> Unit) {
                dogBusy = true; dogError = null
                scope.launch {
                    try {
                        action()
                        if (userId != null && supabase.auth.currentUserOrNull()?.id == userId) ActivitySyncWorker.enqueue(this@MainActivity, userId)
                    } catch (error: Exception) {
                        if (supabase.auth.currentUserOrNull()?.id == userId) dogError = when (error) {
                            is java.time.format.DateTimeParseException -> "Ange födelsedatum som ÅÅÅÅ-MM-DD."
                            is IllegalArgumentException -> error.message ?: "Kontrollera hundens uppgifter."
                            else -> failureMessage ?: "Hunden kunde inte sparas. Kontrollera internet och försök igen."
                        }
                    } finally { if (supabase.auth.currentUserOrNull()?.id == userId) dogBusy = false }
                }
            }
            fun performGroupAction(groupId: String, action: suspend () -> Unit, onSuccess: () -> Unit) {
                scope.launch {
                    groupDetailLoading = true; groupActionError = null
                    try {
                        action()
                        if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                            onSuccess()
                            groupReload++; groupDetailReload++; statsReload++
                        }
                    } catch (error: Exception) {
                        if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                            groupActionError = socialErrorMessage(error)
                            val refreshed = runCatching { groupRepository.detail(groupId) }
                            if (refreshed.isSuccess && refreshed.getOrNull() == null) {
                                selectedGroupId = null; screen = "groups"; groupReload++
                            } else { groupDetailReload++; statsReload++ }
                        }
                    } finally { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupDetailLoading = false }
                }
            }
            fun performFriendAction(action: suspend () -> Unit) {
                scope.launch {
                    friendActionLoading = true; friendActionError = null
                    try {
                        action()
                        if (FriendUiRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                            incomingRequests = emptyList(); sentRequests = emptyList(); friends = emptyList()
                            relation = null; relationLoading = publicProfileId != null
                            friendReload++; relationReload++
                        }
                    } catch (error: Exception) {
                        if (FriendUiRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                            friendActionError = friendErrorMessage(error)
                            incomingRequests = emptyList(); sentRequests = emptyList(); friends = emptyList()
                            relation = null; relationLoading = publicProfileId != null
                            friendReload++; relationReload++
                        }
                    } finally { if (FriendUiRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) friendActionLoading = false }
                }
            }
            var kind by remember(userId) { mutableStateOf(ActivityKind.RUN.name) }
            var selectedDogId by remember(userId) { mutableStateOf<String?>(null) }
            var gpsReady by remember { mutableStateOf(false) }
            var accuracy by remember { mutableFloatStateOf(0f) }
            var prepareLocation by remember { mutableStateOf<TrackPoint?>(null) }
            var countdown by remember { mutableIntStateOf(-1) }
            var summary by remember(userId) { mutableStateOf<Session?>(null) }
            val unsavedTraining = live.running || summary != null
            SideEffect { uiHasUnsavedTraining = unsavedTraining }
            LaunchedEffect(updateRequired, live.running, summary) {
                if (updateRequired) {
                    if (summary != null) screen = "summary" else if (live.running) screen = "active"
                }
            }
            var summaryEndedAt by remember { mutableLongStateOf(0L) }
            var saving by remember { mutableStateOf(false) }
            var saveError by remember { mutableStateOf<String?>(null) }
            var detailNoteError by remember(userId) { mutableStateOf<String?>(null) }
            var deleteBusy by remember(userId) { mutableStateOf(false) }
            var deleteError by remember(userId) { mutableStateOf<String?>(null) }
            var shareBusy by remember(userId) { mutableStateOf(false) }
            var shareError by remember(userId) { mutableStateOf<String?>(null) }
            var statisticsExportBusy by remember(userId) { mutableStateOf(false) }
            var statisticsExportError by remember(userId) { mutableStateOf<String?>(null) }
            var selected by remember(userId) { mutableStateOf<ActivityEntity?>(null) }
            var detailPoints by remember(userId) { mutableStateOf<List<TrackPoint>>(emptyList()) }
            var detailSplits by remember(userId) { mutableStateOf<List<KmSplit>>(emptyList()) }
            var confirm by remember { mutableStateOf(false) }
            val fused = remember { LocationServices.getFusedLocationProviderClient(this) }
            fun openActivity(item: ActivityEntity) {
                detailNoteError = null
                deleteError = null
                shareError = null
                selected = item
                scope.launch {
                    detailPoints = db.dao().points(item.id).map { TrackPoint(it.latitude, it.longitude, it.timestamp, it.accuracy, it.altitude) }
                    detailSplits = db.dao().splits(item.id).map { KmSplit(it.kilometer, it.durationMs, it.latitude, it.longitude) }
                    screen = "detail"
                }
            }
            fun exportActivity(item: ActivityEntity, save: Boolean, format: ShareFormat, options: ShareOptions) {
                if (shareBusy) return
                shareBusy = true; shareError = null
                scope.launch {
                    try {
                        val route = detailPoints.toList()
                        val dogName = dogs.firstOrNull { it.id == item.dogId }?.name
                        val mapBitmap = if (options.map) {
                            val (width, height) = ShareImage.mapSize(item, format, options)
                            MapExportRenderer.capture(this@MainActivity, route, width, height)
                        } else null
                        val bytes = withContext(Dispatchers.Default) {
                            try {
                                val bitmap = ShareImage.render(item, format, options, dogName, mapBitmap)
                                try { ShareImage.png(bitmap) } finally { bitmap.recycle() }
                            } finally { mapBitmap?.recycle() }
                        }
                        if (save) {
                            if (Build.VERSION.SDK_INT >= 29) withContext(Dispatchers.IO) { ShareImage.saveModern(this@MainActivity, bytes) }
                            else { pendingImageBytes = bytes; saveImageLauncher.launch("min-tracker-pass.png") }
                        } else {
                            val intent = withContext(Dispatchers.IO) { ShareImage.shareIntent(this@MainActivity, bytes) }
                            startActivity(intent)
                        }
                    } catch (error: Exception) {
                        shareError = if (options.map) "Kartbilden kunde inte laddas. Kontrollera internet och försök igen, eller välj utan karta."
                        else "Bilden kunde inte skapas. Försök igen."
                    }
                    finally { shareBusy = false }
                }
            }
            fun exportStatistics(period: StatisticsPeriod, filter: StatisticsFilter, exportKind: StatisticsExportKind, options: StatisticsExportOptions) {
                if (statisticsExportBusy) return
                statisticsExportBusy = true; statisticsExportError = null
                scope.launch {
                    try {
                        val owner = userId ?: return@launch
                        val own = activities.filter { it.userId == owner && it.syncStatus != SyncStatus.RESTORING }
                        val stats = withContext(Dispatchers.Default) { Statistics.calculate(own, dogs, period, filter) }
                        when (exportKind) {
                            StatisticsExportKind.IMAGE -> {
                                val bytes = withContext(Dispatchers.Default) { StatisticsExport.image(stats, period, options) }
                                if (supabase.auth.currentUserOrNull()?.id != owner) return@launch
                                val intent = withContext(Dispatchers.IO) { ShareImage.shareIntent(this@MainActivity, bytes, "min-tracker-statistik.png", "Dela statistik") }
                                startActivity(intent)
                            }
                            StatisticsExportKind.PDF -> {
                                val bytes = withContext(Dispatchers.Default) { StatisticsExport.pdf(stats, period, options, dogs) }
                                if (supabase.auth.currentUserOrNull()?.id != owner) return@launch
                                pendingReportBytes = bytes
                                pendingReportOwner = owner
                                savePdfLauncher.launch("min-tracker-rapport.pdf")
                            }
                            StatisticsExportKind.EXCEL -> {
                                val splits = withContext(Dispatchers.IO) { stats.activities.associate { it.id to db.dao().splits(it.id) } }
                                val bytes = withContext(Dispatchers.Default) { StatisticsXlsx.write(stats, period, filter, dogs, splits) }
                                if (supabase.auth.currentUserOrNull()?.id != owner) return@launch
                                pendingReportBytes = bytes
                                pendingReportOwner = owner
                                saveXlsxLauncher.launch("min-tracker-statistik.xlsx")
                            }
                        }
                    } catch (_: Exception) { statisticsExportError = "Exporten kunde inte skapas. Försök igen." }
                    finally { statisticsExportBusy = false }
                }
            }

            LaunchedEffect(live.running, live.ownerUserId, userId) {
                if (live.running && live.ownerUserId == userId && screen != "summary") screen = "active"
            }
            LaunchedEffect(screen, permissionReady) {
                if (screen == "prepare" && permissionReady) {
                    while (screen == "prepare") {
                        try {
                            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
                                gpsReady = location != null && location.accuracy in 0f..40f
                                accuracy = location?.accuracy ?: 0f
                                prepareLocation = location?.let { TrackPoint(it.latitude, it.longitude, it.time, it.accuracy, it.altitude.takeIf { _ -> it.hasAltitude() }) }
                            }
                        } catch (_: SecurityException) { gpsReady = false }
                        delay(3000)
                    }
                }
            }
            LaunchedEffect(countdown, updateRequired) {
                if (updateRequired && !live.running) { countdown = -1; return@LaunchedEffect }
                if (countdown >= 0) {
                    if (countdown > 0) { delay(1000); countdown-- }
                    else { service(TrackingService.START, kind, if (ActivityKind.fromStored(kind)?.requiresDog == true) selectedDogId else null); countdown = -1; screen = "active" }
                }
            }
            val bottomDestination = when (screen) {
                "home" -> "home"
                "history" -> "history"
                "prepare" -> "prepare"
                "discover", "friends", "groups", "social_hub", "challenges" -> "social"
                "profile", "settings" -> "profile"
                else -> null
            }
            val socialTab: (Int) -> Unit = { tab ->
                screen = listOf("discover", "friends", "groups", "social_hub", "challenges")[tab]
                if (tab == 1 || tab == 3) friendReload++
                if (tab == 2) groupReload++
            }
            TrackerTheme {
                AppUpdateHost(updateManager, unsavedTraining) {
                AuthSecurityHost(unsavedTraining, serverConfiguration?.get("registration_enabled")?.toString() != "false") {
                Scaffold(
                    containerColor = Background,
                    snackbarHost = { SnackbarHost(authSnackbar) },
                    bottomBar = { if (!updateRequired && userId != null && bottomDestination != null) AppBottomBar(bottomDestination, incomingRequests.size + challengeIncoming.size) { destination ->
                        when (destination) {
                            "social" -> { screen = "discover"; friendReload++; groupReload++ }
                            "prepare" -> if (!live.running) screen = "prepare" else screen = "active"
                            else -> screen = destination
                        }
                    } },
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding -> Surface(color = Background, modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    if (authStatus is SessionStatus.Initializing) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else if (userId == null) {
                        AuthScreen(registrationEnabled = serverConfiguration?.get("registration_enabled")?.toString() != "false")
                    } else if (!unsavedTraining && !serverAdmin && serverConfiguration?.get("maintenance_mode")?.toString() == "true") {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("MinTracker", style = MaterialTheme.typography.headlineSmall)
                            Text(if (serverConfiguration?.get("maintenance_mode")?.toString() == "true") "Underhåll pågår. Försök igen senare." else "Uppdatera appen för att fortsätta.")
                            TextButton(onClick = { scope.launch { serverConfiguration = runCatching { adminRepository.configuration() }.getOrNull() } }) { Text("Försök igen") }
                        }
                    } else if (screen == "challenges" && serverConfiguration?.get("challenges_enabled")?.toString() == "false") {
                        Column(Modifier.padding(24.dp)) {
                            Text("Utmaningar är tillfälligt avstängda.")
                            TextButton(onClick = { screen = "home" }) { Text("Till startsidan") }
                        }
                    } else when (screen) {
                        "admin" -> key(userId) { AdminScreen(onBack = { screen = "profile" }) }
                        "notifications" -> key(userId) { NotificationsScreen(onBack = { screen = "profile" }, onChallenge = { id -> openChallengeId = id; screen = "challenges" }) }
                        "home" -> HomeDashboard(profile, activities, dogs, live, { screen = "prepare" }, { openActivity(it) }, { screen = "history" }, { statisticsBack = "home"; screen = "statistics" }, { screen = "profile" }, homeChallenge, homeChallengeProgress, { id -> openChallengeId = id; screen = "challenges" })
                        "prepare" -> PrepareActivityScreen(kind, selectedDogId, dogs, gpsReady, accuracy, permissionReady, prepareLocation, { kind = it }, { selectedDogId = it }, {
                                val start = { countdown = prefs.getInt("countdown", 5); screen = "countdown" }
                                if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                                    pendingStart = start
                                    stepPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                } else start()
                            }, {
                                val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                                permissionLauncher.launch(permissions.toTypedArray())
                            }, { screen = "settings" }, { dogBackRoute = "prepare"; screen = "dogs" })
                        "countdown" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(ActivityKind.label(kind), fontSize = 28.sp); Spacer(Modifier.height(48.dp)); Text(if (countdown == 0) "START" else "$countdown", fontSize = 104.sp, fontWeight = FontWeight.Bold); Text("Startar om…") }
                        }
                        "active" -> ActiveActivityScreen(live, dogs.firstOrNull { it.id == live.dogId }?.name, { service(TrackingService.PAUSE) }, { service(TrackingService.RESUME) }, { confirm = true })
                        "summary" -> summary?.let { saved -> SessionResultScreen(saved, dogs.firstOrNull { it.id == saved.dogId }?.name, saving, saveError, { note ->
                                if (!saving) {
                                    saving = true
                                    scope.launch {
                                        saveError = null
                                        try {
                                            val savedId = activityRepository.saveLocal(saved, userId, summaryEndedAt, note)
                                            selected = db.dao().activity(savedId)
                                            detailPoints = saved.points
                                            detailSplits = saved.splits
                                            summary = null
                                            screen = "detail"
                                            runCatching { ActivitySyncWorker.enqueue(this@MainActivity, userId) }
                                        } catch (_: Exception) {
                                            saveError = "Passet kunde inte sparas lokalt. Försök igen."
                                        } finally {
                                            saving = false
                                        }
                                    }
                                }
                            }, { if (!updateRequired) { summary = null; screen = "home" } }, allowDiscard = !updateRequired) }
                        "history" -> HistoryActivityScreen(activities, dogs, { statisticsBack = "history"; screen = "statistics" }) { openActivity(it) }
                        "statistics" -> StatisticsScreen(activities, dogs, onBack = { screen = statisticsBack }, onExport = ::exportStatistics,
                            exportBusy = statisticsExportBusy, exportError = statisticsExportError)
                        "detail" -> selected?.let { item ->
                            val current = activities.firstOrNull { it.id == item.id } ?: item
                            ActivityDetailScreen(current, dogs.firstOrNull { it.id == current.dogId }?.name, detailPoints, detailSplits, syncLabel(current.syncStatus), detailNoteError, deleteBusy, deleteError, shareBusy, shareError, { note ->
                                scope.launch {
                                    detailNoteError = null
                                    runCatching { activityRepository.updateNote(current.id, userId, note) }
                                        .onSuccess { ActivitySyncWorker.enqueue(this@MainActivity, userId) }
                                        .onFailure { detailNoteError = it.message ?: "Anteckningen kunde inte sparas." }
                                }
                            }, {
                                if (!deleteBusy) scope.launch {
                                    deleteBusy = true; deleteError = null
                                    try {
                                        activityRepository.deleteLocal(current.id, userId)
                                        selected = null
                                        screen = "history"
                                        ActivitySyncWorker.enqueue(this@MainActivity, userId)
                                    } catch (error: Exception) {
                                        deleteError = error.message ?: "Passet kunde inte raderas lokalt. Försök igen."
                                    } finally { deleteBusy = false }
                                }
                            }, { format, options -> exportActivity(current, false, format, options) },
                                { format, options -> exportActivity(current, true, format, options) }) { screen = "history" }
                        }
                        "dogs" -> DogManagementScreen(dogs, dogBusy, dogError, { screen = dogBackRoute },
                            { name, breed, birthDate -> performDogAction { dogRepository.create(userId, name, breed, birthDate) } },
                            { id, name, breed, birthDate -> performDogAction { dogRepository.edit(userId, id, name, breed, birthDate) } },
                            { id -> performDogAction { dogRepository.archive(userId, id) } },
                            { ActivitySyncWorker.enqueue(this@MainActivity, userId) },
                            { dogId, uri -> performDogAction("Hundbilden kunde inte sparas. Den tidigare bilden finns kvar.") { avatarRepository.saveDog(userId, dogId, uri) } },
                            { dogId -> performDogAction("Hundbilden kunde inte tas bort. Försök igen.") { avatarRepository.removeDog(userId, dogId) } })
                        "settings" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Inställningar", style = MaterialTheme.typography.titleLarge)
                            AppUpdateSettings(updateManager)
                            SecuritySettings()
                            AppCard(Modifier.fillMaxWidth()) {
                                Text("Konto", style = MaterialTheme.typography.titleMedium)
                                Text(supabase.auth.currentUserOrNull()?.email ?: "Inloggad användare", color = AppColors.Muted)
                                TextButton(onClick = { scope.launch { ActivitySyncWorker.cancel(this@MainActivity, userId); runCatching { AuthRepository().signOut() }; screen = "home" } }, enabled = !unsavedTraining) { Text("Logga ut") }
                            }
                            Text("Nedräkning före start", style = MaterialTheme.typography.titleMedium)
                            Text("Välj hur lång nedräkning som visas innan passet startar.", color = AppColors.Muted)
                            var value by remember { mutableIntStateOf(prefs.getInt("countdown", 5)) }
                            AppCard(Modifier.fillMaxWidth()) {
                                listOf(0, 3, 5, 10).forEach { seconds ->
                                    Row(Modifier.fillMaxWidth().clickable { value = seconds; prefs.edit().putInt("countdown", seconds).apply() }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = value == seconds, onClick = { value = seconds; prefs.edit().putInt("countdown", seconds).apply() })
                                        Text("$seconds sekunder")
                                    }
                                }
                            }
                        }
                        "profile" -> ProfileScreen(
                            isAdmin = serverAdmin,
                            onAdmin = { screen = "admin" },
                            onNotifications = { screen = "notifications" },
                            profile = profile,
                            email = supabase.auth.currentUserOrNull()?.email.orEmpty(),
                            activities = activities,
                            loading = profileLoading,
                            error = profileError,
                            onBack = { screen = "home" },
                            onSettings = { screen = "settings" },
                            onDogs = { dogBackRoute = "profile"; screen = "dogs" },
                            onRetry = { profileReload++ },
                            onSave = { name, username, bio, isPublic ->
                                scope.launch {
                                    profileLoading = true
                                    profileError = null
                                    try { profile = profileRepository.updateSocialProfile(name, username, bio, isPublic) }
                                    catch (error: Exception) { profileError = profileErrorMessage(error) }
                                    finally { profileLoading = false }
                                }
                            },
                            onSaveAvatar = { uri ->
                                scope.launch {
                                    profileLoading = true; profileError = null
                                    try {
                                        val updated = avatarRepository.saveProfile(userId, profile?.avatarPath, uri)
                                        if (supabase.auth.currentUserOrNull()?.id == userId) profile = updated
                                    } catch (error: Exception) {
                                        if (supabase.auth.currentUserOrNull()?.id == userId) profileError = if (error is IllegalArgumentException) error.message else "Profilbilden kunde inte sparas. Den tidigare bilden finns kvar."
                                    } finally { if (supabase.auth.currentUserOrNull()?.id == userId) profileLoading = false }
                                }
                            },
                            onRemoveAvatar = {
                                scope.launch {
                                    profileLoading = true; profileError = null
                                    try {
                                        val updated = avatarRepository.removeProfile(userId, profile?.avatarPath)
                                        if (supabase.auth.currentUserOrNull()?.id == userId) profile = updated
                                    } catch (error: Exception) {
                                        if (supabase.auth.currentUserOrNull()?.id == userId) profileError = "Profilbilden kunde inte tas bort. Försök igen."
                                    } finally { if (supabase.auth.currentUserOrNull()?.id == userId) profileLoading = false }
                                }
                            }
                        )
                        "discover" -> DiscoverScreen(discoverQuery, { discoverQuery = it; discoverPage = 0; discoverResults = emptyList(); discoverMore = false }, discoverResults, friends, incomingRequests, sentRequests, friendActionLoading, { id -> performFriendAction { friendRepository.send(id) } }, discoverLoading, discoverError ?: friendActionError, discoverMore, { discoverPage++ }, { id -> friendActionError = null; publicProfileId = id; socialBack = "discover"; screen = "public_profile" }, { screen = "home" }, socialTab)
                        "public_profile" -> PublicProfileScreen(publicProfileId, publicProfile, publicProfileLoading, publicProfileError, relation, relationLoading, friendActionError ?: relationError, friendActionLoading,
                            { screen = socialBack }, { publicProfileReload++ }, { friendActionError = null; relationReload++ },
                            { publicProfileId?.let { id -> performFriendAction { friendRepository.send(id) } } },
                            { requestId -> performFriendAction { friendRepository.accept(requestId) } },
                            { requestId -> performFriendAction { friendRepository.decline(requestId) } },
                            { requestId -> performFriendAction { friendRepository.cancel(requestId) } },
                            { publicProfileId?.let { id -> performFriendAction { friendRepository.remove(id) } } })
                        "social_hub" -> SocialHubScreen(incomingRequests, sentRequests, challengeIncoming, challengeSent, friendLoading || challengeInboxLoading, friendActionLoading || challengeActionLoading, challengeInboxError ?: friendActionError ?: friendError,
                            { screen = "home" }, { friendActionError = null; challengeInboxError = null; friendReload++; challengeReload++ },
                            { id -> friendActionError = null; publicProfileId = id; socialBack = "social_hub"; screen = "public_profile" },
                            { requestId -> performFriendAction { friendRepository.accept(requestId) } },
                            { requestId -> performFriendAction { friendRepository.decline(requestId) } },
                            { requestId -> performFriendAction { friendRepository.cancel(requestId) } },
                            { id -> challengeInvitationAction { challengeRepository.accept(id) } },
                            { id -> challengeInvitationAction { challengeRepository.decline(id) } },
                            { id -> challengeInvitationAction { challengeRepository.cancel(id) } },
                            { id -> openChallengeId = id; screen = "challenges" }, socialTab)
                        "challenges" -> ChallengeScreen(userId!!, challengeRepository, groups.filter { it.id in manageableGroupIds }, friends, openChallengeId,
                            { openChallengeId = null },
                            { id -> publicProfileId = id; socialBack = "challenges"; screen = "public_profile" }, socialTab)
                        "friends" -> FriendsScreen(friends, friendLoading, friendActionError ?: friendError,
                            { screen = "home" }, { friendActionError = null; friendReload++ },
                            { id -> friendActionError = null; publicProfileId = id; socialBack = "friends"; screen = "public_profile" }, socialTab)
                        "groups" -> GroupsScreen(groups, groupsLoading, groupActionError ?: groupsError, groupNotice,
                            { screen = "home" }, { groupReload++ },
                            { name, type -> scope.launch {
                                groupsLoading = true; groupsError = null; groupActionError = null; groupNotice = null
                                try {
                                    val made = groupRepository.create(name, type)
                                    if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) { groupReload++; selectedGroupId = made.id; screen = "group_detail" }
                                } catch (error: Exception) { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupsError = socialErrorMessage(error) }
                                finally { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupsLoading = false }
                            } },
                            { code -> scope.launch {
                                groupsLoading = true; groupsError = null; groupActionError = null; previewGroup = null
                                try {
                                    val found = groupRepository.preview(code)
                                    if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                                        previewGroup = found
                                        if (found == null) groupsError = "Ingen grupp hittades med den koden."
                                        else screen = "group_join"
                                    }
                                } catch (error: Exception) { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupsError = socialErrorMessage(error) }
                                finally { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupsLoading = false }
                            } },
                            { id -> groupActionError = null; selectedGroupId = id; screen = "group_detail" }, socialTab)
                        "group_join" -> previewGroup?.let { preview -> JoinGroupScreen(preview, groupsLoading, groupsError,
                            { screen = "groups" },
                            { scope.launch {
                                groupsLoading = true; groupsError = null
                                try {
                                    groupRepository.join(preview.inviteCode)
                                    if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                                        groupReload++; groupNotice = "Du gick med i ${preview.name}."
                                        if (preview.id != null) { selectedGroupId = preview.id; screen = "group_detail" }
                                        else screen = "groups"
                                    }
                                } catch (error: Exception) {
                                    if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) {
                                        groupsError = socialErrorMessage(error)
                                        runCatching { groupRepository.preview(preview.inviteCode) }.getOrNull()?.let { previewGroup = it }
                                    }
                                } finally { if (GroupActionRules.canApplyResponse(userId, supabase.auth.currentUserOrNull()?.id)) groupsLoading = false }
                            } }) }
                        "group_detail" -> GroupDetailScreen(selectedGroup, groupMembers, userId, groupDetailLoading, groupActionError ?: groupDetailError, weeklyStats, statsLoading, statsError,
                            { groupActionError = null; screen = "groups" }, { groupActionError = null; groupDetailReload++ }, { statsReload++ },
                            { id -> friendActionError = null; publicProfileId = id; socialBack = "group_detail"; screen = "public_profile" },
                            { selectedGroupId?.let { id -> performGroupAction(id, { groupRepository.leave(id) }) { selectedGroupId = null; screen = "groups"; groupNotice = "Du har lämnat gruppen." } } },
                            { targetId -> selectedGroupId?.let { id -> performGroupAction(id, { groupRepository.removeMember(id, targetId) }) { groupNotice = "Medlemmen har tagits bort." } } },
                            { targetId -> selectedGroupId?.let { id -> performGroupAction(id, { groupRepository.transferOwnership(id, targetId) }) { groupMembers = emptyList(); groupNotice = "Ägarskapet har överförts." } } },
                            { selectedGroupId?.let { id -> performGroupAction(id, { groupRepository.delete(id) }) { selectedGroupId = null; groups = groups.filterNot { it.id == id }; screen = "groups"; groupNotice = "Gruppen har raderats." } } })
                    }
                    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Vill du avsluta passet?") }, text = { Text("Passet kan sparas på nästa skärm.") }, confirmButton = {
                        TextButton(onClick = { summary = live; summaryEndedAt = System.currentTimeMillis(); service(TrackingService.STOP); confirm = false; screen = "summary" }) { Text("Avsluta pass") }
                    }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Avbryt") } })
                }
            }
            }
            }
        }
    }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthCallback(intent)
    }

    override fun onResume() {
        super.onResume()
        AppSecurity.resumed(uiHasUnsavedTraining || TrackingState.session.value.running)
        hideNavigationBar()
        AppUpdateManager.get(applicationContext).check()
    }

    override fun onStop() {
        AppSecurity.stopped()
        super.onStop()
    }

    private fun hideNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable private fun AppColumn(content: @Composable ColumnScope.() -> Unit) = Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
@Composable private fun Header(title: String, back: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = back) { Text("‹", fontSize = 32.sp) }; Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun MainButton(label: String, enabled: Boolean = true, color: Color = Green, onClick: () -> Unit) { Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(32.dp), colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.Black)) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold) } }
@Composable private fun MenuCard(title: String, subtitle: String, onClick: () -> Unit) { Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = CardColor), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold); Text(subtitle, fontSize = 13.sp) } } }
@Composable private fun Choice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) { Card(onClick = onClick, modifier = modifier.height(120.dp), colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF1B4530) else CardColor), border = if (selected) BorderStroke(2.dp, Green) else null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(label, fontSize = 21.sp, lineHeight = 32.sp, textAlign = TextAlign.Center) } } }
@Composable private fun Stats(s: Session) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Stat("DISTANS", "${"%.2f".format(s.distanceM / 1000)} km", Modifier.weight(1f)); Stat("TEMPO", if (s.running) "${formatPace(s.currentPaceSeconds)} min/km" else "${pace(s.activeMs, s.distanceM)} min/km", Modifier.weight(1f)) }; Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Stat("HASTIGHET", "${"%.1f".format(if (s.running) s.speedKmh else if (s.activeMs > 0) s.distanceM / s.activeMs * 3600 else 0.0)} km/h", Modifier.weight(1f)); Stat("SNITT TEMPO", "${pace(s.activeMs, s.distanceM)} min/km", Modifier.weight(1f)) } } }
@Composable private fun ExtraStats(ascentM: Double?, descentM: Double?, steps: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("STIGNING", ascentM?.let { "${it.toInt()} m" } ?: "—", Modifier.weight(1f))
            Stat("UTFÖR", descentM?.let { "${it.toInt()} m" } ?: "—", Modifier.weight(1f))
        }
        Stat("STEG", steps?.toString() ?: "—", Modifier.fillMaxWidth())
    }
}
@Composable private fun Stat(label: String, value: String, modifier: Modifier) { Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = CardColor)) { Column(Modifier.padding(12.dp)) { Text(label, fontSize = 11.sp); Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold) } } }
@Composable private fun Splits(splits: List<KmSplit>) { splits.forEach { Row(Modifier.fillMaxWidth().padding(7.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("${it.kilometer} km"); Text(time(it.durationMs)) } } }
private fun time(ms: Long): String { val sec = ms / 1000; return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60) }
private fun pace(ms: Long, meters: Double): String { if (meters < 1) return "--:--"; val sec = (ms / 1000.0 / (meters / 1000)).toInt(); return "%d:%02d".format(sec / 60, sec % 60) }
private fun formatPace(sec: Int) = if (sec <= 0) "--:--" else "%d:%02d".format(sec / 60, sec % 60)
private fun date(epoch: Long) = SimpleDateFormat("d MMM yyyy HH:mm", Locale("sv", "SE")).format(Date(epoch))
private fun syncLabel(status: String) = when (status) {
    SyncStatus.SYNCED -> "Synkroniserad"
    SyncStatus.ERROR -> "Synkfel"
    else -> "Väntar på synk"
}

@Composable fun TrackMap(points: List<TrackPoint>, splits: List<KmSplit>, follow: Boolean, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember(context) { MapView(context).apply {
        onCreate(null)
        setOnTouchListener { view, event ->
            view.parent?.requestDisallowInterceptTouchEvent(
                event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL
            )
            false
        }
    } }
    DisposableEffect(mapView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }
    val route by produceState<RouteDisplay?>(initialValue = null, points, follow) {
        value = if (follow) null else withContext(Dispatchers.Default) { RouteDisplayBuilder.build(points) }
    }
    var fitRequest by remember(mapView) { mutableIntStateOf(0) }
    LaunchedEffect(mapView, if (follow) points else route, splits, follow) {
        if (!follow && route == null) return@LaunchedEffect
        val coords = if (follow) points.map { LatLng(it.latitude, it.longitude) }
        else route?.coordinates.orEmpty().map { LatLng(it.latitude, it.longitude) }
        mapView.getMapAsync { map ->
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isScrollGesturesEnabled = true
            val draw = {
                map.clear()
                if (coords.size > 1) map.addPolyline(PolylineOptions().addAll(coords).color(android.graphics.Color.rgb(22, 138, 251)).width(6f))
                splits.forEach { split ->
                    if (split.latitude != null && split.longitude != null)
                        map.addMarker(MarkerOptions().position(LatLng(split.latitude, split.longitude)).title("${split.kilometer} km"))
                }
                if (follow && coords.isNotEmpty()) {
                    map.addMarker(MarkerOptions().position(coords.last()).title("Aktuell position"))
                    map.cameraPosition = CameraPosition.Builder().target(coords.last()).zoom(15.0).build()
                } else route?.let { fitRoute(map, mapView, it) }
            }
            if (map.style == null) map.setStyle("https://tiles.openfreemap.org/styles/liberty") { draw() }
            else draw()
        }
    }
    LaunchedEffect(fitRequest) {
        if (fitRequest > 0) route?.let { display -> mapView.getMapAsync { fitRoute(it, mapView, display) } }
    }
    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize(), update = {})
        if (!follow && route != null) Surface(
            onClick = { fitRequest++ }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            shape = RoundedCornerShape(18.dp), color = Color.White
        ) { Text("Visa hela rutten", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = AppColors.Blue) }
    }
}

private fun fitRoute(map: org.maplibre.android.maps.MapLibreMap, view: MapView, route: RouteDisplay) {
    if (route.originalCount == 1 || route.minLatitude == route.maxLatitude && route.minLongitude == route.maxLongitude) {
        map.cameraPosition = CameraPosition.Builder().target(LatLng(route.minLatitude, route.minLongitude)).zoom(15.0).build()
        return
    }
    val bounds = LatLngBounds.Builder()
        .include(LatLng(route.minLatitude, route.minLongitude))
        .include(LatLng(route.maxLatitude, route.maxLongitude)).build()
    view.post { map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 56)) }
}



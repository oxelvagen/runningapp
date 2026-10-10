package se.oxelv.mintracker

import org.junit.Assert.*
import org.junit.Test
import se.oxelv.mintracker.updates.*

class AppUpdateRulesTest {
    private val policy = AppUpdatePolicy(3,2,"1.0.2","v1.0.2","MinTracker-v1.0.2.apk","a".repeat(64),"Nyheter",4,"2026-10-10T08:00:00Z")
    private fun rejected(block: () -> Unit) { try { block(); fail("Should reject") } catch (_: IllegalArgumentException) {} }
    private fun release(assets: String, tag: String = policy.tag, draft: Boolean = false) = """{"tag_name":"$tag","draft":$draft,"assets":$assets}"""
    private val asset = """{"name":"MinTracker-v1.0.2.apk","state":"uploaded","size":123,"browser_download_url":"https://github.com/oxelvagen/runningapp/releases/download/v1.0.2/MinTracker-v1.0.2.apk"}"""

    @Test fun currentVersion() { assertEquals(UpdateRequirement.CURRENT,UpdateRules.requirement(3,policy)) }
    @Test fun optionalVersion() { assertEquals(UpdateRequirement.OPTIONAL,UpdateRules.requirement(2,policy)) }
    @Test fun forcedVersion() { assertEquals(UpdateRequirement.REQUIRED,UpdateRules.requirement(1,policy)) }
    @Test fun firstStartOfflineDoesNotInventPolicy() { assertEquals(UpdateRequirement.UNKNOWN,UpdateRules.requirement(1,null)); assertFalse(UpdateRules.block(1,null,false)) }
    @Test fun cachedForcedPolicyRemainsEffectiveOffline() { assertTrue(UpdateRules.block(1,policy,false)); assertEquals(UpdateRequirement.REQUIRED,UpdateRules.requirement(1,policy)) }
    @Test fun serverCannotReturnContradictoryVersions() { rejected { policy.copy(minimumCode=4).validated() } }
    @Test fun oldResponsesAndSameRevisionConflictsCannotOverwrite() {
        assertFalse(UpdateRules.accept(policy,policy.copy(revision=3)))
        assertFalse(UpdateRules.accept(policy,policy.copy(notes="Other")))
        assertFalse(UpdateRules.accept(policy,policy.copy(revision=5,updatedAt="2026-10-09T08:00:00Z")))
        assertTrue(UpdateRules.accept(policy,policy.copy(revision=5)))
    }
    @Test fun exactGitHubAsset() { assertEquals(123L,GitHubReleaseRules.asset(release("[$asset]"),"oxelvagen/runningapp",policy).size) }
    @Test fun missingReleaseOrWrongTag() { rejected { GitHubReleaseRules.asset(release("[$asset]","v0"),"oxelvagen/runningapp",policy) }; rejected { GitHubReleaseRules.asset(release("[$asset]",draft=true),"oxelvagen/runningapp",policy) } }
    @Test fun missingOrAmbiguousAsset() {
        rejected { GitHubReleaseRules.asset(release("[]"),"oxelvagen/runningapp",policy) }
        rejected { GitHubReleaseRules.asset(release("[$asset,$asset]"),"oxelvagen/runningapp",policy) }
    }
    @Test fun hostileDownloadAddressesRejected() {
        for (url in listOf("http://github.com/oxelvagen/runningapp/releases/download/v1.0.2/a.apk","https://github.com.attacker.test/oxelvagen/runningapp/releases/download/v1.0.2/a.apk","https://github.com/other/repo/releases/download/v1/a.apk")) {
            val hostile=asset.replace("https://github.com/oxelvagen/runningapp/releases/download/v1.0.2/MinTracker-v1.0.2.apk",url)
            rejected { GitHubReleaseRules.asset(release("[$hostile]"),"oxelvagen/runningapp",policy) }
        }
    }
    private fun verify(pkg: String = "se.oxelv.mintracker",code: Long=3,name: String="1.0.2",hash: String=policy.sha256,signing: Boolean=true) =
        UpdateRules.verifyIdentity(policy,2,"se.oxelv.mintracker",pkg,code,name,hash,signing)
    @Test fun invalidHash() { rejected {verify(hash="b".repeat(64))} }
    @Test fun wrongPackage() { rejected {verify(pkg="other.app")} }
    @Test fun wrongSignature() { rejected {verify(signing=false)} }
    @Test fun downgradeWrongCodeOrName() { rejected {verify(code=1)}; rejected {verify(code=4)}; rejected {verify(name="other")} }
    @Test fun validatedCandidateAccepted() {verify()}
    @Test fun cancelInstallerDoesNotLiftForcedGate() { assertTrue(UpdateRules.block(1,policy,false)); assertTrue(UpdateRules.block(1,policy,false)) }
    @Test fun successfulInstallationClearsGateOnRestart() { assertFalse(UpdateRules.block(3,policy,false)); assertEquals(UpdateRequirement.CURRENT,UpdateRules.requirement(3,policy)) }
    @Test fun ongoingWorkoutCanBeSavedBeforeGateAppears() { assertFalse(UpdateRules.block(1,policy,true)); assertTrue(UpdateRules.block(1,policy,false)) }
    @Test fun optionalDismissalCannotBecomeForced() { assertFalse(UpdateRules.block(2,policy,false)) }
}

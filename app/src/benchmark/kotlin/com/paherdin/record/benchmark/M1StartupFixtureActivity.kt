package com.paherdin.record.benchmark

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.paherdin.record.core.data.startup.M1StartupFixture
import com.paherdin.record.core.data.startup.M1StartupFixtureGenerator
import java.util.UUID

/**
 * M1-T09 Step 3 — benchmark-only exported fixture setup surface (plan Task
 * M1-T09 Step 3). Declared only in `app/src/benchmark/AndroidManifest.xml`, so
 * it is absent from `main`/`release`; it does not rely on any generated
 * BuildConfig field.
 *
 * The out-of-process `:benchmark` harness launches it exactly once per fixture
 * restoration with an explicit fixture enum name and a fresh one-shot setup
 * token. The Activity fails closed unless
 *  - its own package name is exactly the benchmark variant package
 *    `com.paherdin.record.benchmark`, and
 *  - its application's merged manifest carries the `m1BenchmarkVariant=true`
 *    meta-data marker,
 * then it consumes the one-shot token (a replayed intent is refused for the
 * lifetime of the install; `pm clear` resets the prefs, which is acceptable
 * because the harness always mints a fresh token per setup), publishes the
 * harness identity facts (signing-cert SHA-256, variant marker, app data
 * root), prepares and validates exactly one `m1-startup-v1` fixture into the
 * benchmark application's own private generation/recovery root, publishes the
 * fixture facts (digest, counts, record/snapshot hashes), stamps `setupDone`,
 * and finishes. Any failure is published on the `error` sentinel instead of
 * crashing, so the harness fails closed with precise evidence.
 */
class M1StartupFixtureActivity : Activity() {

    private lateinit var recorder: M1StartupSentinelRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = M1StartupSentinelRecorder(this)
        recorder.startFreshRun(
            mode = M1StartupSentinelRecorder.MODE_M1_SETUP,
            keysToClear = SETUP_SENTINELS,
        )
        try {
            runSetup()
        } catch (t: Throwable) {
            Log.e(TAG, "fixture setup failed: ${t.message}", t)
            recorder.write(
                M1StartupSentinelRecorder.KEY_ERROR,
                t.message ?: t::class.java.simpleName,
            )
        } finally {
            finish()
        }
    }

    private fun runSetup() {
        // Gate 1: the exact benchmark-variant package (applicationIdSuffix).
        check(packageName == TARGET_PACKAGE) {
            "M1StartupFixtureActivity must run in the benchmark variant app " +
                "('$packageName' != '$TARGET_PACKAGE')"
        }
        recorder.write(SENTINEL_PACKAGE_NAME, packageName)

        // Gate 2: the manifest meta-data variant marker (not a BuildConfig).
        val marker = applicationInfo.metaData?.getString(VARIANT_METADATA_KEY) ?: ""
        check(marker == "true") {
            "merged manifest meta-data $VARIANT_METADATA_KEY must be 'true', got '$marker'"
        }
        recorder.write(SENTINEL_VARIANT_MARKER, marker)

        // Identity facts the harness mirrors into the run manifest.
        recorder.write(SENTINEL_SIGNING_CERT_SHA256, signingCertSha256())
        recorder.write(SENTINEL_DATA_ROOT, dataRoot())

        // The one-shot setup token: consumed before any fixture work so a
        // replayed setup intent can never re-run preparation.
        val token = requireNotNull(intent.getStringExtra(EXTRA_SETUP_TOKEN)) {
            "fixture setup requires the one-shot setup token"
        }
        consumeOneShotSetupToken(token)
        recorder.write(SENTINEL_SETUP_TOKEN_DIGEST, sha256Hex(token.toByteArray(Charsets.UTF_8)).take(16))

        val fixture = M1StartupFixture.fromWire(
            requireNotNull(intent.getStringExtra(EXTRA_FIXTURE)) {
                "fixture setup requires the explicit fixture enum name"
            },
        )
        recorder.write(SENTINEL_FIXTURE, fixture.name)

        val report = M1StartupFixtureGenerator.prepare(applicationContext, fixture)
        recorder.write(SENTINEL_FIXTURE_VERSION, report.fixtureVersion)
        recorder.write(SENTINEL_FIXTURE_CONTENT_DIGEST, report.contentDigest)
        recorder.write(SENTINEL_RECORD_COUNTS, report.recordCounts.toString())
        report.activationRecordSha256?.let { recorder.write(SENTINEL_ACTIVATION_RECORD_SHA256, it) }
        report.snapshotSha256?.let { recorder.write(SENTINEL_SNAPSHOT_SHA256, it) }
        recorder.write(SENTINEL_GENERATION_IDS, report.generationIds.toString())
        check(report.problems.isEmpty()) {
            "fixture ${fixture.name} failed on-device validation: ${report.problems}"
        }
        recorder.write(SENTINEL_FIXTURE_VALIDATED, "true")
        recorder.write(M1StartupSentinelRecorder.KEY_SETUP_DONE, "true")
    }

    /**
     * Fails an already-consumed setup token for the lifetime of this install.
     * The digest (never the raw token) is the only persisted evidence.
     */
    private fun consumeOneShotSetupToken(token: String) {
        val prefs = getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        check(prefs.getString(CONSUMED_TOKEN_DIGEST, null) == null) {
            "the setup token was already consumed within this install"
        }
        prefs.edit().putString(CONSUMED_TOKEN_DIGEST, sha256Hex(token.toByteArray(Charsets.UTF_8))).commit()
    }

    private fun signingCertSha256(): String {
        val packageInfo = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val certBytes = if (android.os.Build.VERSION.SDK_INT >= 28) {
            packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures?.firstOrNull()?.toByteArray()
        }
        val cert = requireNotNull(certBytes) { "no signing certificate for $packageName" }
        return sha256Hex(cert)
    }

    /** The app-private data root (`/data/user/0/<benchmark package>`). */
    private fun dataRoot(): String =
        filesDir.parentFile?.absolutePath
            ?: error("app data root unavailable")

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    internal companion object {
        private const val TAG = "M1FixtureActivity"

        /** The exact benchmark-variant package (applicationIdSuffix ".benchmark"). */
        internal const val TARGET_PACKAGE = "com.paherdin.record.benchmark"

        /** The frozen merged-manifest variant marker key. */
        internal const val VARIANT_METADATA_KEY = "m1BenchmarkVariant"

        internal const val EXTRA_FIXTURE = "m1Fixture"
        internal const val EXTRA_SETUP_TOKEN = "m1SetupToken"

        private const val TOKEN_PREFS = "m1_setup_tokens"
        private const val CONSUMED_TOKEN_DIGEST = "consumedTokenDigest"

        // Sentinel keys (mirror the frozen harness constants).
        internal const val SENTINEL_PACKAGE_NAME = "packageName"
        internal const val SENTINEL_VARIANT_MARKER = "variantMarker"
        internal const val SENTINEL_SIGNING_CERT_SHA256 = "signingCertSha256"
        internal const val SENTINEL_DATA_ROOT = "dataRoot"
        internal const val SENTINEL_SETUP_TOKEN_DIGEST = "setupTokenDigest"
        internal const val SENTINEL_FIXTURE = "fixture"
        internal const val SENTINEL_FIXTURE_VERSION = "fixtureVersion"
        internal const val SENTINEL_FIXTURE_CONTENT_DIGEST = "fixtureContentDigest"
        internal const val SENTINEL_RECORD_COUNTS = "recordCounts"
        internal const val SENTINEL_ACTIVATION_RECORD_SHA256 = "activationRecordSha256"
        internal const val SENTINEL_SNAPSHOT_SHA256 = "snapshotSha256"
        internal const val SENTINEL_GENERATION_IDS = "generationIds"
        internal const val SENTINEL_FIXTURE_VALIDATED = "fixtureValidated"

        /** Every sentinel the Activity owns; each setup launch clears them. */
        internal val SETUP_SENTINELS = listOf(
            SENTINEL_PACKAGE_NAME,
            SENTINEL_VARIANT_MARKER,
            SENTINEL_SIGNING_CERT_SHA256,
            SENTINEL_DATA_ROOT,
            SENTINEL_SETUP_TOKEN_DIGEST,
            SENTINEL_FIXTURE,
            SENTINEL_FIXTURE_VERSION,
            SENTINEL_FIXTURE_CONTENT_DIGEST,
            SENTINEL_RECORD_COUNTS,
            SENTINEL_ACTIVATION_RECORD_SHA256,
            SENTINEL_SNAPSHOT_SHA256,
            SENTINEL_GENERATION_IDS,
            SENTINEL_FIXTURE_VALIDATED,
            M1StartupSentinelRecorder.KEY_SETUP_DONE,
            M1StartupSentinelRecorder.KEY_ERROR,
        )

        /** The fresh one-shot token the harness mints per setup launch. */
        internal fun newSetupToken(): String = UUID.randomUUID().toString()
    }
}
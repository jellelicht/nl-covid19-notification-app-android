/*
 * Copyright (c) 2020 De Staat der Nederlanden, Ministerie van Volksgezondheid, Welzijn en Sport.
 *  Licensed under the EUROPEAN UNION PUBLIC LICENCE v. 1.2
 *
 *  SPDX-License-Identifier: EUPL-1.2
 */
package nl.rijksoverheid.en

import android.app.Application
import android.os.Build
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration
import com.google.android.gms.nearby.exposurenotification.ExposureSummary
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nl.rijksoverheid.en.api.ExposureNotificationService
import nl.rijksoverheid.en.api.model.AppConfig
import nl.rijksoverheid.en.api.model.Manifest
import nl.rijksoverheid.en.api.model.RiskCalculationParameters
import nl.rijksoverheid.en.enapi.DiagnosisKeysResult
import nl.rijksoverheid.en.job.ProcessManifestWorkerScheduler
import nl.rijksoverheid.en.test.FakeExposureNotificationApi
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.O_MR1])
class ExposureNotificationsRepositoryTest {
    private lateinit var mockWebServer: MockWebServer
    private val fakeScheduler = object : ProcessManifestWorkerScheduler {
        override fun schedule(intervalMinutes: Int) {
            throw NotImplementedError()
        }

        override fun cancel() {
            throw NotImplementedError()
        }
    }

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `processExposureKeySets processes exposure key sets`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("dummy_key_file"))
        mockWebServer.enqueue(
            MockResponse().setBody(
                """
                        {
              "MinimumRiskScore": 1,
              "AttenuationScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DaysSinceLastExposureScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DurationScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "TransmissionRiskScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DurationAtAttenuationThresholds": [
                42,
                56
              ]
            }
            """.trimIndent()
            )
        )
        mockWebServer.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = ExposureNotificationService.create(
            context,
            OkHttpClient(),
            mockWebServer.url("/").toString()
        )
        val config = AtomicReference<ExposureConfiguration>()

        val api = object : FakeExposureNotificationApi() {
            override suspend fun provideDiagnosisKeys(
                files: List<File>,
                configuration: ExposureConfiguration,
                token: String
            ): DiagnosisKeysResult {
                if (files.size != 1) throw AssertionError("Expected one file")
                config.set(configuration)
                return DiagnosisKeysResult.Success
            }
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)
        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(), api, service, sharedPrefs, fakeScheduler
        )

        val result =
            repository.processExposureKeySets(
                Manifest(
                    listOf("test"),
                    "",
                    "config-params",
                    "appConfigId"
                )
            )

        assertEquals(
            ExposureConfiguration.ExposureConfigurationBuilder()
                .setMinimumRiskScore(1)
                .setAttenuationScores(1, 2, 3, 4, 5, 6, 7, 8)
                .setDaysSinceLastExposureScores(1, 2, 3, 4, 5, 6, 7, 8)
                .setDurationScores(1, 2, 3, 4, 5, 6, 7, 8)
                .setTransmissionRiskScores(1, 2, 3, 4, 5, 6, 7, 8)
                .setDurationAtAttenuationThresholds(42, 56).build().toString().trim(),
            config.get().toString().trim()
        )
        assertEquals(2, mockWebServer.requestCount)
        assertEquals(ProcessExposureKeysResult.Success, result)
        assertEquals("/v1/exposurekeyset/test", mockWebServer.takeRequest().path)
        assertEquals(setOf("test"), sharedPrefs.getStringSet("exposure_key_sets", emptySet()))
    }

    @Test
    fun `processExposureKeySets processes only new exposure key sets`() = runBlocking {
        mockWebServer.enqueue(MockResponse().setBody("dummy_key_file"))
        mockWebServer.enqueue(
            MockResponse().setBody(
                """
                        {
              "MinimumRiskScore": 1,
              "AttenuationScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DaysSinceLastExposureScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DurationScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "TransmissionRiskScores": [
                1,
                2,
                3,
                4,
                5,
                6,
                7,
                8
              ],
              "DurationAtAttenuationThresholds": [
                42,
                56
              ]
            }
            """.trimIndent()
            )
        )
        mockWebServer.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = ExposureNotificationService.create(
            context,
            OkHttpClient(),
            mockWebServer.url("/").toString()
        )

        val api = object : FakeExposureNotificationApi() {
            override suspend fun provideDiagnosisKeys(
                files: List<File>,
                configuration: ExposureConfiguration,
                token: String
            ): DiagnosisKeysResult {
                if (files.size != 1) throw AssertionError("Expected one file")
                return DiagnosisKeysResult.Success
            }
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        sharedPrefs.edit(commit = true) {
            putStringSet("exposure_key_sets", setOf("test"))
        }

        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(), api, service, sharedPrefs, fakeScheduler
        )

        val result = repository.processExposureKeySets(
            Manifest(
                listOf("test", "test2"),
                "",
                "config-params",
                "appConfigId"
            )
        )

        assertEquals(2, mockWebServer.requestCount)
        assertEquals(ProcessExposureKeysResult.Success, result)

        assertEquals("/v1/exposurekeyset/test2", mockWebServer.takeRequest().path)
        assertEquals(
            "/v1/riskcalculationparameters/config-params",
            mockWebServer.takeRequest().path
        )

        assertEquals(
            setOf("test", "test2"),
            sharedPrefs.getStringSet("exposure_key_sets", emptySet())
        )
    }

    @Test
    fun `processExposureKeySets already processed are not processed again`() = runBlocking {
        mockWebServer.start()
        val context = ApplicationProvider.getApplicationContext<Application>()
        val service = ExposureNotificationService.create(
            context,
            OkHttpClient(),
            mockWebServer.url("/").toString()
        )

        val api = object : FakeExposureNotificationApi() {
            override suspend fun provideDiagnosisKeys(
                files: List<File>,
                configuration: ExposureConfiguration,
                token: String
            ): DiagnosisKeysResult {
                throw java.lang.AssertionError("Should not be processed")
            }
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        sharedPrefs.edit(commit = true) {
            putStringSet("exposure_key_sets", setOf("test"))
        }
        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(), api, service, sharedPrefs, fakeScheduler
        )

        val result = repository.processExposureKeySets(
            Manifest(
                listOf("test"),
                "",
                "config-param",
                "appConfigId"
            )
        )

        assertEquals(ProcessExposureKeysResult.Success, result)
        assertEquals(0, mockWebServer.requestCount)
        assertEquals(setOf("test"), sharedPrefs.getStringSet("exposure_key_sets", emptySet()))
    }

    @Test
    fun `processExposureKeySets should remove exposure key sets that are no longer live`() =
        runBlocking {
            mockWebServer.start()
            val context = ApplicationProvider.getApplicationContext<Application>()
            val service = ExposureNotificationService.create(
                context,
                OkHttpClient(),
                mockWebServer.url("/").toString()
            )

            val api = object : FakeExposureNotificationApi() {
                override suspend fun provideDiagnosisKeys(
                    files: List<File>,
                    configuration: ExposureConfiguration,
                    token: String
                ): DiagnosisKeysResult {
                    throw java.lang.AssertionError("Should not be processed")
                }
            }

            val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("repository_test", 0)

            sharedPrefs.edit(commit = true) {
                putStringSet("exposure_key_sets", setOf("test"))
            }

            val repository = ExposureNotificationsRepository(
                ApplicationProvider.getApplicationContext(),
                api,
                service,
                sharedPrefs,
                fakeScheduler
            )

            val result = repository.processExposureKeySets(
                Manifest(
                    listOf(),
                    "",
                    "config-params",
                    "appConfigId"
                )
            )

            assertEquals(ProcessExposureKeysResult.Success, result)
            assertEquals(0, mockWebServer.requestCount)
            assertEquals(
                emptySet<String>(),
                sharedPrefs.getStringSet("exposure_key_sets", setOf("not-empty"))
            )
        }

    @Test
    fun `processExposureKeySets failing to download files returns error`() =
        runBlocking {
            mockWebServer.enqueue(MockResponse().setResponseCode(500))
            mockWebServer.start()
            val context = ApplicationProvider.getApplicationContext<Application>()
            val service = ExposureNotificationService.create(
                context,
                OkHttpClient(),
                mockWebServer.url("/").toString()
            )

            val api = object : FakeExposureNotificationApi() {
                override suspend fun provideDiagnosisKeys(
                    files: List<File>,
                    configuration: ExposureConfiguration,
                    token: String
                ): DiagnosisKeysResult {
                    throw java.lang.AssertionError("Should not be processed")
                }
            }

            val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("repository_test", 0)

            val repository = ExposureNotificationsRepository(
                ApplicationProvider.getApplicationContext(),
                api,
                service,
                sharedPrefs,
                fakeScheduler
            )

            val result =
                repository.processExposureKeySets(
                    Manifest(
                        listOf("test"),
                        "",
                        "config-params",
                        "appConfigId"
                    )
                )

            assertEquals(ProcessExposureKeysResult.ServerError, result)
            assertEquals(1, mockWebServer.requestCount)
            assertEquals(
                emptySet<String>(),
                sharedPrefs.getStringSet("exposure_key_sets", setOf())
            )
        }

    @Test
    fun `processExposureKeySets failing to download some files processes files and returns error`() =
        runBlocking {
            // use dispatcher since the key files are fetched in parallel
            mockWebServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when (request.path) {
                        "/v1/exposurekeyset/test" -> {
                            MockResponse().setResponseCode(500)
                        }
                        "/v1/exposurekeyset/test2" -> {
                            MockResponse().setBody("dummy_key_file")
                        }
                        "/v1/riskcalculationparameters/config-params" -> {
                            MockResponse().setBody(
                                """
                                {"MinimumRiskScore":1,"AttenuationScores":[1,2,3,4,5,6,7,8],"DaysSinceLastExposureScores":[1,2,3,4,5,6,7,8],"DurationScores":[1,2,3,4,5,6,7,8],"TransmissionRiskScores":[1,2,3,4,5,6,7,8],"DurationAtAttenuationThresholds":[42,56]}
                                """.trimIndent()
                            )
                        }
                        else -> {
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }

            mockWebServer.start()
            val context = ApplicationProvider.getApplicationContext<Application>()
            val service = ExposureNotificationService.create(
                context,
                OkHttpClient(),
                mockWebServer.url("/").toString()
            )

            val processed = AtomicBoolean(false)
            val api = object : FakeExposureNotificationApi() {
                override suspend fun provideDiagnosisKeys(
                    files: List<File>,
                    configuration: ExposureConfiguration,
                    token: String
                ): DiagnosisKeysResult {
                    if (files.size != 1) throw java.lang.AssertionError()
                    processed.set(true)
                    return DiagnosisKeysResult.Success
                }
            }

            val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("repository_test", 0)

            val repository = ExposureNotificationsRepository(
                ApplicationProvider.getApplicationContext(),
                api,
                service,
                sharedPrefs,
                fakeScheduler
            )

            val result = repository.processExposureKeySets(
                Manifest(
                    listOf("test", "test2"),
                    "",
                    "config-params",
                    "appConfigId"
                )
            )

            val request1 = mockWebServer.takeRequest()
            val request2 = mockWebServer.takeRequest()
            val requests = listOf(request1, request2).sortedBy { it.path }

            assertEquals("/v1/exposurekeyset/test", requests[0].path)
            assertEquals("/v1/exposurekeyset/test2", requests[1].path)
            assertEquals(
                "/v1/riskcalculationparameters/config-params",
                mockWebServer.takeRequest().path
            )
            assertTrue(processed.get())
            assertEquals(ProcessExposureKeysResult.ServerError, result)
            assertEquals(3, mockWebServer.requestCount)
            assertEquals(
                setOf("test2"),
                sharedPrefs.getStringSet("exposure_key_sets", emptySet())
            )
        }

    @Test
    fun `addExposure adds exposure`() = runBlocking {
        val dateTime = "2020-06-20T10:15:30.00Z"
        val service = object : ExposureNotificationService {
            override suspend fun getExposureKeySetFile(id: String): Response<ResponseBody> {
                throw NotImplementedError()
            }

            override suspend fun getManifest(): Manifest {
                throw NotImplementedError()
            }

            override suspend fun getRiskCalculationParameters(id: String): RiskCalculationParameters {
                throw NotImplementedError()
            }

            override suspend fun getAppConfig(id: String): AppConfig {
                throw NotImplementedError()
            }
        }

        val api = object : FakeExposureNotificationApi() {
            override suspend fun getSummary(token: String) =
                if (token == "sample-token") {
                    ExposureSummary.ExposureSummaryBuilder().setDaysSinceLastExposure(4).build()
                } else null
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(),
            api,
            service,
            sharedPrefs,
            fakeScheduler,
            Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
        )

        repository.addExposure("sample-token")
        assertEquals(
            LocalDate.of(2020, 6, 20).minusDays(4),
            repository.getLastExposureDate().filterNotNull().first()
        )
    }

    @Test
    fun `addExposure while newer exposure exists keeps newer exposure`() = runBlocking {
        val dateTime = "2020-06-20T10:15:30.00Z"
        val service = object : ExposureNotificationService {
            override suspend fun getExposureKeySetFile(id: String): Response<ResponseBody> {
                throw NotImplementedError()
            }

            override suspend fun getManifest(): Manifest {
                throw NotImplementedError()
            }

            override suspend fun getRiskCalculationParameters(id: String): RiskCalculationParameters {
                throw NotImplementedError()
            }

            override suspend fun getAppConfig(id: String): AppConfig {
                throw NotImplementedError()
            }
        }

        val api = object : FakeExposureNotificationApi() {
            override suspend fun getSummary(token: String) =
                when (token) {
                    "sample-token-old" -> ExposureSummary.ExposureSummaryBuilder()
                        .setDaysSinceLastExposure(8).build()
                    "sample-token-new" -> ExposureSummary.ExposureSummaryBuilder()
                        .setDaysSinceLastExposure(4).build()
                    else -> null
                }
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(),
            api,
            service,
            sharedPrefs,
            fakeScheduler,
            Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
        )

        repository.addExposure("sample-token-new")
        repository.addExposure("sample-token-old")

        assertEquals(
            LocalDate.of(2020, 6, 20).minusDays(4),
            repository.getLastExposureDate().filterNotNull().first()
        )
    }

    @Test
    fun `processManifest marks the timestamp of last successful time the keys have been processed and returns Success`() =
        runBlocking {
            val dateTime = "2020-06-20T10:15:30.00Z"
            val fakeService = object : ExposureNotificationService {
                override suspend fun getExposureKeySetFile(id: String): Response<ResponseBody> {
                    throw NotImplementedError()
                }

                override suspend fun getManifest(): Manifest =
                    Manifest(emptyList(), "dummy", "riskParamId", "configId")

                override suspend fun getRiskCalculationParameters(id: String): RiskCalculationParameters {
                    throw NotImplementedError()
                }

                override suspend fun getAppConfig(id: String): AppConfig = AppConfig(1, 5, 0)
            }

            val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("repository_test", 0)

            val repository = ExposureNotificationsRepository(
                ApplicationProvider.getApplicationContext(),
                object : FakeExposureNotificationApi() {},
                fakeService,
                sharedPrefs,
                fakeScheduler,
                Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
            )

            val result = repository.processManifest()

            assertTrue(result is ProcessManifestResult.Success)
            assertEquals(5, (result as ProcessManifestResult.Success).nextIntervalMinutes)
            assertEquals(
                Instant.parse(dateTime),
                Instant.ofEpochMilli(sharedPrefs.getLong("last_keys_processed", 0))
            )
        }

    @Test
    fun `processManifest does not update the timestamp of last successful time if manifest cannot be fetched and returns Error`() =
        runBlocking {
            val dateTime = "2020-06-20T10:15:30.00Z"
            val dateTimeSuccess = "2020-06-20T01:15:30.00Z"

            mockWebServer.enqueue(MockResponse().setResponseCode(500))
            mockWebServer.start()

            val fakeService = ExposureNotificationService.create(
                ApplicationProvider.getApplicationContext<Application>(),
                OkHttpClient(),
                mockWebServer.url("/").toString()
            )

            val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("repository_test", 0)

            sharedPrefs.edit {
                putLong("last_keys_processed", Instant.parse(dateTimeSuccess).toEpochMilli())
            }

            val repository = ExposureNotificationsRepository(
                ApplicationProvider.getApplicationContext(),
                object : FakeExposureNotificationApi() {},
                fakeService,
                sharedPrefs,
                fakeScheduler,
                Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
            )

            val result = repository.processManifest()

            assertTrue(result is ProcessManifestResult.Error)
            assertEquals(1, mockWebServer.requestCount)
            assertEquals(
                Instant.parse(dateTimeSuccess),
                Instant.ofEpochMilli(sharedPrefs.getLong("last_keys_processed", 0))
            )
        }

    @Test
    fun `keyProcessingOverdue returns true if last successful time of key processing is more than 24 hours in the past`() {
        val lastSyncDateTime = "2020-06-20T10:15:30.00Z"
        val dateTime = "2020-06-21T10:16:30.00Z"
        val fakeService = object : ExposureNotificationService {
            override suspend fun getExposureKeySetFile(id: String): Response<ResponseBody> {
                throw NotImplementedError()
            }

            override suspend fun getManifest(): Manifest {
                throw NotImplementedError()
            }

            override suspend fun getRiskCalculationParameters(id: String): RiskCalculationParameters {
                throw NotImplementedError()
            }

            override suspend fun getAppConfig(id: String): AppConfig = AppConfig(1, 5, 0)
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        sharedPrefs.edit {
            putLong("last_keys_processed", Instant.parse(lastSyncDateTime).toEpochMilli())
        }

        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(),
            object : FakeExposureNotificationApi() {},
            fakeService,
            sharedPrefs,
            fakeScheduler,
            Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
        )

        assertTrue(repository.keyProcessingOverdue)
    }

    @Test
    fun `keyProcessingOverdue returns false if no timestamp is stored`() {
        val dateTime = "2020-06-21T10:15:30.00Z"
        val fakeService = object : ExposureNotificationService {
            override suspend fun getExposureKeySetFile(id: String): Response<ResponseBody> {
                throw NotImplementedError()
            }

            override suspend fun getManifest(): Manifest {
                throw NotImplementedError()
            }

            override suspend fun getRiskCalculationParameters(id: String): RiskCalculationParameters {
                throw NotImplementedError()
            }

            override suspend fun getAppConfig(id: String): AppConfig = AppConfig(1, 5, 0)
        }

        val sharedPrefs = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("repository_test", 0)

        val repository = ExposureNotificationsRepository(
            ApplicationProvider.getApplicationContext(),
            object : FakeExposureNotificationApi() {},
            fakeService,
            sharedPrefs,
            fakeScheduler,
            Clock.fixed(Instant.parse(dateTime), ZoneId.of("UTC"))
        )

        assertFalse(repository.keyProcessingOverdue)
    }
}

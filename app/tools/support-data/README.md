# App data / integration lightweight tests

Run from the repository root after building the web and core JVM test jars:

```powershell
python web/tools/fetch-test-deps.py
python web/tools/fetch-test-deps.py --shared
pwsh -File web/tools/test-web.ps1
pwsh -File core/tools/test-core-ai.ps1
pwsh -File app/tools/test-app-data.ps1
```

`test-app-data.ps1` does not install tools or download dependencies. It accepts `-ToolRoot`, `-DependencyRoot`, `-WebJar`, and `-CoreJar`; the defaults reuse the workspace `.panelfm-tools`, `.tools/olfact/lib`, and `web/build/pure-jvm/tests.jar`. The default core artifact must be recorded in `.tools/olfact/build/core-ai/latest-success.txt`, written only after a successful core test run. It never implicitly selects an arbitrary GUID output or the legacy stable `core-ai/tests.jar`. The runner prints the selected path and refuses core or web artifacts older than their relevant production sources and platform substitutes. Rebuild prerequisite jars after their sources change. Generated classes and the extracted LRU contract are written to ignored `app/build/pure-data`.

The dependency directory needs okhttp-jvm 5.5.1, okio-jvm 3.18.1, kotlinx-serialization-core/json-jvm 1.11.0, and json 20250107. The tool directory supplies JDK 21, Kotlin 2.4.10, JUnit 4.13.2, Hamcrest 1.3 and coroutines-core/test 1.11.0. These are lightweight test tools; production Gradle dependency versions are unchanged.

The runner compiles actual production sources for BrowserSession, BookmarkStore/HistoryStore, FavoriteStore, JsonStore/JsonPersist, DohUpstreams, DohAdapter, AiAdapter, ScriptAdapter, DownloadAdapter, LinkExtractor/SearchEngines, SurfaceCache, BottomBarSlots, AiRequestHandle and MediaDownloadContext. Actual core API contracts and download models are compiled too. ScriptStore/GM classes and web DNS classes come from the prerequisite jars; their sources are not replaced with business-logic copies.

The runner includes existing app pure logic suites, including IntegrationMappingTest, and excludes SettingItemClickTest because it requires actual Android resources and Robolectric. New session merge and DoH parsing assertions live in the normal module tests. SurfaceCacheTest contains one common LRU behavior contract plus a concrete Robolectric runner for CI. The lightweight script extracts that unchanged contract, removes only platform runner imports, and executes it as SurfaceCachePureTest with identity-only View tokens. It does not load or emulate Robolectric.

`tests-data` adds integration checks specific to this environment:

- BrowserSessionPersistenceTest exercises actual JSON save/restore: never/manual/always policies, selected normal tab, exclusion of private URL/title/id, and legacy private records.
- DohAdapterIntegrationTest passes actual adapter configurations into the actual manager: preset selection, custom-only local HTTP/IPv6 endpoint, automatic pool, rules and dynamic enabled status. Its StandardTestDispatcher remains unadvanced and is cancelled on cleanup; it never schedules real DNS or tunnel self-check IO. Reflection reads the manager's stored config solely to verify the adapter/manager boundary.
- StoreSnapshotTest compiles actual BookmarkStore/HistoryStore and FavoriteStore; it checks negative restored history limits on record/import/trim, safe favorite source metadata, awaited favorite restore JSON commit receipt, stable favorite snapshots after add/remove, and snapshot export while another thread edits favorites. The in-memory preference substitute's synchronous commit does not prove Android disk durability.
- BrowserFileChooserTest exercises the pure single-flight callback gate, stale-result cancellation, extension-to-MIME normalization, and rejection of non-content/private-provider/ungranted URI metadata. It does not launch a real picker or emulate Android URI grants.
- SurfaceCachePureTest shares the same retention, eviction, destruction, dead-page and replacement assertions as the formal Robolectric test.
- AiAdapterIntegrationTest uses the actual file-backed AI ProviderStore and InMemorySecretBox to verify editing visible fields retains keyRef, temperature, maxTokens, topP, prompt and extraHeaders, while blank displayed keys preserve secrets and explicit replacement updates the same key reference.
- DownloadAdapterDispatchTest uses the actual adapter and an instrumented facade shape to verify pause/resume/remove run off the caller thread in requested order and clearFinished delegates one batch with deletion disabled. It does not exercise the actual download queue implementation or Toast rendering.

The substitutes in this directory are deliberately narrow:

| Substitute | What it supplies | What remains unverified |
|---|---|---|
| AndroidContext | In-memory SharedPreferences strings, synchronous apply | Android disk writes, lifecycle, async apply durability |
| AndroidView / AndroidUri | Identity-only View plus URI type shape | WebView state, attachment and URI grant behavior |
| AppSettings/K | Memory setting values and the seven keys used by these tests | App/Core/DataStore initialization and full production key wiring |
| DownloadFacade | Compile-time facade/queue shape | Download execution, queue scheduling and storage |
| BottomBar / Resources | Item data shape and distinct integer icons | Layout, accessibility, real resource resolution |
| AndroidToast / AndroidLog / VisibleForTesting | Compile-time platform shapes | Toast/logging behavior |

On 2026-11-21 the runner completed with `OK (104 tests)` and no failures, using the current core-ai GUID artifact and 14 actual app production source files. Earlier runs exposed the numeric-prefix public-host HTTP classification bug in DohUpstreams; the parent agent fixed it and the final suite passes. This entry point is not a full app build or UI test. CI still needs `:app:testDebugUnitTest`, including the real Robolectric SurfaceCacheTest and SettingItemClickTest, plus Kotlin/Android compilation and lint. BrowserFragment and WebBrowserSurface lifecycle/GM bridge integration remain outside this runner; they need CI/platform checks and device smoke tests for switching tabs, returning from settings, renderer recovery and private/normal metadata isolation.

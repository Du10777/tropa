package io.github.du10777.tropa.data

import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import io.github.du10777.tropa.service.ProbeEvent
import java.net.URI

/** One Tropa connection: an upstream subscription group with its server profiles. */
data class Connection(
    val id: String,
    val name: String,
    /** Subscription address; empty when the connection was added from links or a file. */
    val url: String,
    val serverCount: Int,
    val lastUpdated: Long,
    val info: SubscriptionInfo?,
    /** From the subscription headers: a message from the server, support and subscription pages. */
    val announce: String? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
) {
    val isSubscription: Boolean get() = url.isNotEmpty()
}

/** A config of a connection for the details screen: its servers and whether Tropa runs it. */
data class ProfileDetails(
    val name: String,
    val inUse: Boolean,
    val balanced: Boolean,
    val nodes: List<Node>,
    /** The routing rules of this config, in order (for share links: the upstream routing settings). */
    val routing: List<RouteRule> = emptyList(),
)

sealed interface AddResult {
    data class Added(val id: String) : AddResult
    data class AlreadyAdded(val id: String) : AddResult
    data class NetworkError(val message: String) : AddResult
    data object Unrecognized : AddResult
}

/**
 * Tropa's view of the upstream storage: every subscription group is a connection. Tropa never
 * shows the internal default group unless it holds servers added in the advanced mode.
 */
object Connections {
    private const val TAG = "Tropa"
    private const val DEFAULT_UPDATE_INTERVAL_MINUTES = 12 * 60L
    private const val MIN_UPDATE_INTERVAL_MINUTES = 15L
    private const val MAX_ANNOUNCE_CHARS = 500

    /** Hidden upstream policy group that balances the plain servers of a connection. */
    private const val POLICY_GROUP_PREFIX = "tropa-pg-"

    fun list(): List<Connection> =
        MmkvManager.decodeSubscriptions()
            .map { cache ->
                Connection(
                    id = cache.guid,
                    name = cache.subscription.remarks,
                    url = cache.subscription.url,
                    serverCount = MmkvManager.decodeServerList(cache.guid).count { !it.startsWith(POLICY_GROUP_PREFIX) },
                    lastUpdated = cache.subscription.lastUpdated,
                    info = SubscriptionInfo.parse(TropaPrefs.subscriptionInfo(cache.guid)),
                    announce = TropaPrefs.announce(cache.guid),
                    supportUrl = TropaPrefs.supportUrl(cache.guid),
                    webPageUrl = TropaPrefs.webPageUrl(cache.guid),
                )
            }
            .filterNot { it.id == AppConfig.DEFAULT_SUBSCRIPTION_ID && it.serverCount == 0 }

    /** The connection the switch controls; falls back to the group of the selected server. */
    fun activeId(connections: List<Connection> = list()): String? {
        val stored = TropaPrefs.activeConnectionId
        if (stored != null && connections.any { it.id == stored }) return stored

        val selectedGroup = MmkvManager.getSelectServer()
            ?.let { MmkvManager.decodeServerConfig(it)?.subscriptionId }
            ?.ifEmpty { AppConfig.DEFAULT_SUBSCRIPTION_ID }
        val fallback = connections.firstOrNull { it.id == selectedGroup }?.id ?: connections.firstOrNull()?.id
        TropaPrefs.activeConnectionId = fallback
        return fallback
    }

    /** Makes [id] the active connection; the quick settings tile and widget start its server too. */
    fun setActive(id: String) {
        TropaPrefs.activeConnectionId = id
        serverToStart(id)?.let(MmkvManager::setSelectServer)
    }

    /**
     * Profile to start for a connection:
     * - a config from the server that already balances several servers (3X-UI "Sub Balancers");
     * - several plain servers: a hidden upstream policy group over all of them, so the watchdog
     *   can steer new connections between them;
     * - otherwise the selected server of the connection, or its first one.
     */
    fun serverToStart(id: String): String? {
        val servers = MmkvManager.decodeServerList(id)
        if (servers.isEmpty()) return null
        val profiles = servers.mapNotNull { guid -> MmkvManager.decodeServerConfig(guid)?.let { guid to it } }
        profiles.firstOrNull { (guid, profile) -> profile.configType == EConfigType.CUSTOM && planOf(guid, profile).isBalanced }
            ?.let { return it.first }
        val plain = profiles.filter { (_, profile) -> profile.configType.isPlainServer() }
        if (plain.size >= 2) return ensurePolicyGroup(id)
        val selected = MmkvManager.getSelectServer()
        return if (selected != null && selected in servers) selected else servers.first()
    }

    /** Configs of a connection with their servers, for the details screen. */
    fun details(id: String): List<ProfileDetails> {
        val running = serverToStart(id)
        val groupInUse = running?.startsWith(POLICY_GROUP_PREFIX) == true
        return MmkvManager.decodeServerList(id).mapNotNull { guid ->
            val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
            if (profile.configType == EConfigType.CUSTOM) {
                val plan = planOf(guid, profile)
                ProfileDetails(
                    profile.remarks,
                    inUse = guid == running,
                    balanced = plan.isBalanced,
                    nodes = plan.proxies,
                    routing = GeoRouting.rules(MmkvManager.decodeServerRaw(guid).orEmpty()),
                )
            } else {
                val address = listOfNotNull(profile.server, profile.serverPort).joinToString(":").ifEmpty { null }
                val transport = listOfNotNull(profile.network, profile.security?.takeIf { it.isNotEmpty() && it != "none" })
                    .joinToString(", ").ifEmpty { null }
                val node = Node(guid, profile.remarks, NodePlanner.displayProtocol(profile.configType.name), address, transport)
                ProfileDetails(
                    profile.remarks,
                    inUse = guid == running || (groupInUse && profile.configType.isPlainServer()),
                    balanced = false,
                    nodes = listOf(node),
                    routing = GeoRouting.fromRulesets(MmkvManager.decodeRoutingRulesets()),
                )
            }
        }
    }

    private fun planOf(guid: String, profile: ProfileItem): NodePlan =
        if (profile.configType == EConfigType.CUSTOM) {
            NodePlanner.fromConfigJson(MmkvManager.decodeServerRaw(guid).orEmpty())
        } else {
            NodePlan(null, emptyList())
        }

    private fun EConfigType.isPlainServer(): Boolean =
        this != EConfigType.CUSTOM && this != EConfigType.POLICYGROUP && this != EConfigType.PROXYCHAIN

    private fun ensurePolicyGroup(id: String): String {
        val guid = POLICY_GROUP_PREFIX + id
        val name = MmkvManager.decodeSubscription(id)?.remarks.orEmpty().ifEmpty { "Tropa" }
        val existing = MmkvManager.decodeServerConfig(guid)
        if (existing?.policyGroupSubscriptionId != id || existing.remarks != name) {
            val profile = ProfileItem.create(EConfigType.POLICYGROUP).apply {
                remarks = name
                policyGroupSubscriptionId = id
                // leastPing keeps balancing if the watchdog is not running; Tropa overrides it otherwise.
                policyGroupType = BalancerStrategyType.LEAST_PING.policyGroupTypeValue
                subscriptionId = ""
            }
            MmkvManager.encodeServerConfig(guid, profile)
        }
        return guid
    }

    /** Downloads a subscription and stores it as a new connection. Blocking: call off the main thread. */
    fun addSubscription(candidate: LinkCandidate.Subscription): AddResult {
        findBySubscriptionUrl(candidate.url)?.let { return AddResult.AlreadyAdded(it) }

        val fetched = try {
            SubscriptionFetcher.fetch(candidate.url)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to download subscription", e)
            return AddResult.NetworkError(e.message ?: e.javaClass.simpleName)
        }
        findBySubscriptionUrl(fetched.finalUrl)?.let { return AddResult.AlreadyAdded(it) }

        val id = Utils.getUuid()
        val item = SubscriptionItem(
            remarks = SubscriptionInfo.decodeTitle(fetched.headers["profile-title"]) ?: hostName(fetched.finalUrl),
            url = fetched.finalUrl,
            // Tropa updates it itself (SubscriptionRefresher): upstream's updater would drop the headers.
            autoUpdate = false,
            updateInterval = updateIntervalMinutes(fetched.headers["profile-update-interval"]),
            userAgent = SubscriptionFetcher.userAgent,
        )
        MmkvManager.encodeSubscription(id, item)
        if (!importServers(fetched.body, id)) {
            SettingsManager.removeSubscriptionWithDefault(id)
            return AddResult.Unrecognized
        }
        item.lastUpdated = System.currentTimeMillis()
        MmkvManager.encodeSubscription(id, item)
        saveHeaders(id, fetched.headers)
        TropaPrefs.setSubscriptionHash(id, bodyHash(fetched.body))
        return AddResult.Added(id)
    }

    /** Stores share links or a JSON config as a new connection named after its first server. */
    fun addConfigs(text: String, fallbackName: String): AddResult {
        val id = Utils.getUuid()
        val item = SubscriptionItem(remarks = fallbackName)
        MmkvManager.encodeSubscription(id, item)
        if (!importServers(text, id)) {
            SettingsManager.removeSubscriptionWithDefault(id)
            return AddResult.Unrecognized
        }
        MmkvManager.decodeServerList(id).firstOrNull()
            ?.let { MmkvManager.decodeServerConfig(it)?.remarks }
            ?.takeIf { it.isNotBlank() }
            ?.let {
                item.remarks = it
                MmkvManager.encodeSubscription(id, item)
            }
        return AddResult.Added(id)
    }

    enum class Refresh { UPDATED, UNCHANGED, FAILED }

    /**
     * Re-downloads a subscription connection. Blocking: call off the main thread. With [keepIfSame]
     * an unchanged server list is not imported again, so the profiles keep their ids (an import
     * recreates them, and the profile to start must be picked again).
     */
    fun refresh(id: String, keepIfSame: Boolean = false): Refresh {
        val item = MmkvManager.decodeSubscription(id) ?: return Refresh.FAILED
        if (item.url.isEmpty()) return Refresh.FAILED
        val fetched = try {
            SubscriptionFetcher.fetch(item.url)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to refresh subscription", e)
            return Refresh.FAILED
        }
        val hash = bodyHash(fetched.body)
        val same = keepIfSame && hash == TropaPrefs.subscriptionHash(id)
        if (!same && !importServers(fetched.body, id)) return Refresh.FAILED
        TropaPrefs.setSubscriptionHash(id, hash)
        item.lastUpdated = System.currentTimeMillis()
        SubscriptionInfo.decodeTitle(fetched.headers["profile-title"])?.let { item.remarks = it }
        fetched.headers["profile-update-interval"]?.let { item.updateInterval = updateIntervalMinutes(it) }
        MmkvManager.encodeSubscription(id, item)
        saveHeaders(id, fetched.headers)
        return if (same) Refresh.UNCHANGED else Refresh.UPDATED
    }

    private fun bodyHash(body: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }

    /** The response headers Tropa shows or uses (lower-case names). */
    private fun saveHeaders(id: String, headers: Map<String, String>) {
        TropaPrefs.setSubscriptionInfo(id, headers["subscription-userinfo"])
        TropaPrefs.setReportUrl(id, headers["tropa-report-url"])
        TropaPrefs.setAnnounce(id, SubscriptionInfo.decodeTitle(headers["announce"])?.take(MAX_ANNOUNCE_CHARS))
        TropaPrefs.setLinks(id, headers["support-url"], headers["profile-web-page-url"])
        saveServerChecks(id, headers["tropa-checks"])
    }

    /**
     * Check parameters from the server (`Tropa-Checks`): the watchdog takes them on its next round.
     * A change goes into the connection log, so a report shows what the phone runs with.
     */
    private fun saveServerChecks(id: String, header: String?) {
        val checks = CheckParams.parse(header)
        val stored = checks?.toJson()
        if (stored == TropaPrefs.serverChecks(id)) return
        TropaPrefs.setServerChecks(id, stored)
        val detail = when {
            checks != null -> checks.describe()
            header.isNullOrBlank() -> "none from the server, defaults apply"
            else -> "nothing usable in Tropa-Checks, defaults apply"
        }
        ConnectionLog.append(
            AngApplication.application,
            ProbeEvent(
                System.currentTimeMillis(), "checks", MmkvManager.decodeSubscription(id)?.remarks.orEmpty(), null, "",
                ok = checks != null || header.isNullOrBlank(), detail = detail,
            ),
        )
    }

    fun delete(id: String) {
        SettingsManager.removeSubscriptionWithDefault(id)
        MmkvManager.removeServer(POLICY_GROUP_PREFIX + id)
        TropaPrefs.setSubscriptionInfo(id, null)
        TropaPrefs.setAnnounce(id, null)
        TropaPrefs.setLinks(id, null, null)
        TropaPrefs.setServerChecks(id, null)
        if (TropaPrefs.activeConnectionId == id) {
            TropaPrefs.activeConnectionId = list().firstOrNull()?.id
        }
    }

    private fun importServers(text: String, id: String): Boolean {
        val (count, _) = AngConfigManager.importBatchConfig(text, id, false)
        return count > 0 && MmkvManager.decodeServerList(id).isNotEmpty()
    }

    private fun findBySubscriptionUrl(url: String): String? =
        MmkvManager.decodeSubscriptions().firstOrNull { it.subscription.url == url }?.guid

    private fun hostName(url: String): String = try {
        URI(url).host?.removePrefix("www.").orEmpty()
    } catch (e: Exception) {
        ""
    }.ifEmpty { "Tropa" }

    private fun updateIntervalMinutes(hoursHeader: String?): Long =
        hoursHeader?.trim()?.toDoubleOrNull()
            ?.let { (it * 60).toLong() }
            ?.coerceAtLeast(MIN_UPDATE_INTERVAL_MINUTES)
            ?: DEFAULT_UPDATE_INTERVAL_MINUTES
}

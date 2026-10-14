package io.github.du10777.tropa.data

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.BalancerStrategyType
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
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
) {
    val isSubscription: Boolean get() = url.isNotEmpty()
}

/** A config of a connection for the details screen: its servers and whether Tropa runs it. */
data class ProfileDetails(
    val name: String,
    val inUse: Boolean,
    val balanced: Boolean,
    val nodes: List<Node>,
    /** Where the routing of this config sends which addresses (geoip, ext files, subnets, domains). */
    val geo: List<GeoRule> = emptyList(),
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
                    geo = GeoRouting.rules(MmkvManager.decodeServerRaw(guid).orEmpty()),
                )
            } else {
                val address = listOfNotNull(profile.server, profile.serverPort).joinToString(":").ifEmpty { null }
                val transport = listOfNotNull(profile.network, profile.security?.takeIf { it.isNotEmpty() && it != "none" })
                    .joinToString(", ").ifEmpty { null }
                val node = Node(guid, profile.remarks, NodePlanner.displayProtocol(profile.configType.name), address, transport)
                ProfileDetails(profile.remarks, inUse = guid == running || (groupInUse && profile.configType.isPlainServer()), balanced = false, nodes = listOf(node))
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
            autoUpdate = true,
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
        TropaPrefs.setSubscriptionInfo(id, fetched.headers["subscription-userinfo"])
        TropaPrefs.setReportUrl(id, fetched.headers["tropa-report-url"])
        SubscriptionUpdater.syncOne(subId = id)
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

    /** Re-downloads a subscription connection. Blocking: call off the main thread. */
    fun refresh(id: String): Boolean {
        val item = MmkvManager.decodeSubscription(id) ?: return false
        if (item.url.isEmpty()) return false
        val fetched = try {
            SubscriptionFetcher.fetch(item.url)
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to refresh subscription", e)
            return false
        }
        if (!importServers(fetched.body, id)) return false
        item.lastUpdated = System.currentTimeMillis()
        SubscriptionInfo.decodeTitle(fetched.headers["profile-title"])?.let { item.remarks = it }
        fetched.headers["profile-update-interval"]?.let { item.updateInterval = updateIntervalMinutes(it) }
        MmkvManager.encodeSubscription(id, item)
        TropaPrefs.setSubscriptionInfo(id, fetched.headers["subscription-userinfo"])
        TropaPrefs.setReportUrl(id, fetched.headers["tropa-report-url"])
        return true
    }

    fun delete(id: String) {
        SettingsManager.removeSubscriptionWithDefault(id)
        MmkvManager.removeServer(POLICY_GROUP_PREFIX + id)
        TropaPrefs.setSubscriptionInfo(id, null)
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

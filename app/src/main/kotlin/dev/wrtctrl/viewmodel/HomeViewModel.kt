package dev.wrtctrl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.wrtctrl.bridge.WrtCore
import dev.wrtctrl.data.DashboardCardId
import dev.wrtctrl.data.DashboardConfig
import dev.wrtctrl.data.DashboardPrefs
import dev.wrtctrl.data.Device
import dev.wrtctrl.data.DeviceRepository
import dev.wrtctrl.util.Format
import dev.wrtctrl.util.Format.bandwidthRates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

data class HomeUiState(
    val loading: Boolean = true,
    // 身份区（UI 改版 P1：SYSTEM 卡解体——型号/固件/运行时间升为 hero，架构/目标平台不再上首页）
    val model: String = "--",
    val hostname: String = "--",
    /** 固件短版本号（release.version，如 23.05.5；缺失 "--" 不显 chip） */
    val releaseVersion: String = "--",
    val uptime: String = "--",
    // 资源环：null = 数据不可得（无传感器/解析失败）→ 该环隐藏（空态守卫）
    val cpuPercent: Int? = null,
    val tempC: Int? = null,
    // 内存
    val memoryPercent: Int = 0,
    val memoryDetail: String = "--",
    // 网络结论区
    val wanIp: String = "--",
    val gateway: String = "--",
    val dns: String = "--",
    /** NAT 会话计数/上限（进度条分母）；任一缺失 null → 行显 "--" 不画进度条 */
    val connCount: Int? = null,
    val connMax: Int? = null,
    /** 接口 UP/DOWN chip 行（iface dump 数组序，无 loopback） */
    val ifaceChips: List<IfaceChip> = emptyList(),
    // 客户端计数（慢拍 9s：无线=assocList 合计、租约=DHCP 双栈合计；null=尚未拉到）
    val assocCount: Int? = null,
    val leaseCount: Int? = null,
    // 当前设备 ping 徽章（慢拍）：pingMs=null+offline=false=未测不占位
    val pingMs: Long? = null,
    val pingOffline: Boolean = false,
    // 存储（磁盘环数据源：overlay 优先）
    val mounts: List<MountInfo> = emptyList(),
    // 实时带宽
    val rxRate: Long = 0,
    val txRate: Long = 0,
    val rxSeries: List<Double> = emptyList(),
    val txSeries: List<Double> = emptyList(),
    val timestamps: List<Long> = emptyList(),
    val bandwidthSource: String = "lan",
    // 卡片自定义
    val cardOrder: List<DashboardCardId> = DashboardPrefs.DEFAULT.order,
    val cardEnabled: Set<DashboardCardId> = DashboardPrefs.DEFAULT.enabled,
    val collapsed: Set<DashboardCardId> = DashboardPrefs.DEFAULT.collapsed,
    /** DataStore 持久化值已落地（编辑页以此为界同步本地初值，见 DashboardEditScreen） */
    val cardConfigLoaded: Boolean = false,
    /** 下拉刷新进行中 */
    val refreshing: Boolean = false,
)

/**
 * 首页仪表盘轮询：快拍（3s）= info / conntrack_count / iface dump / getCPUUsage +
 * 带宽差分（getRealtimeStats），board 与 conntrack_max 为会话/重启内恒定值缓存后不再占快拍
 * （缓存在切设备时失效）；慢拍（每 SLOW_EVERY 拍 ≈9s）= 无线客户端数 / DHCP 租约数 /
 * 当前设备 ping + 温度 / 挂载点（秒级缓变量，不上 3s 快拍）。
 * 
 * 轮询门控：pollingActive 默认关，由屏幕层 PollingGate 驱动（组合可见 × 前台）；
 * 循环先等一个周期再刷——进页/切设备的立即拉取（switchDevice/refresh 走 slow=true）不与之重复；
 * 一轮拉取互斥（pollMutex）：tick 与下拉刷新重叠时串行化，防瞬时双倍请求。
 * 带宽目标 = wan 优先（l3_device），无 wan 回落 br-lan（旧 getQuickBandwidthTarget）。
 * 解析逻辑全部在 HomeParsers（纯函数，JVM 单测覆盖）。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state

    private var bandwidthDevice: String? = null
    private val dashboardPrefs = DashboardPrefs(application)
    private val deviceRepository = DeviceRepository(application)
    private var loadedDeviceId: String? = null
    /** 慢拍用设备档案缓存（切设备失效；避免每拍重读 DataStore） */
    private var cachedDevice: Device? = null
    private var slowTick = 0

    /** system board 全量（会话内恒定，applyPoll 只消费型号/主机名/固件三字段）；切设备失效 */
    private var boardCache: JSONObject? = null

    /** nf_conntrack_max（重启才变）；切设备失效 */
    private var connMaxCache: String? = null

    /** 一轮拉取互斥：tick 与下拉刷新/切设备补拉重叠时串行化（防瞬时 18 路请求） */
    private val pollMutex = Mutex()

    /** 设备代次（reqSeq 守卫）：切设备 +1，在飞响应按代次丢弃，防旧设备数据覆盖新设备首拉 */
    private var generation = 0

    /** 轮询开关：页面可见才轮询（由 HomeScreen 的 PollingGate 驱动） */
    private val pollingActive = MutableStateFlow(false)

    fun setPollingActive(active: Boolean) {
        pollingActive.value = active
    }

    init {
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                // 不可见时在此挂起（不占任何资源），回到可见等一个周期再刷；
                // delay 期间门控可能关闭：拉取前复查，避免切走后多发一轮
                pollingActive.first { it }
                delay(POLL_INTERVAL)
                if (pollingActive.value) {
                    slowTick = (slowTick + 1) % SLOW_EVERY
                    // 循环级兜底：任何一轮的非取消异常（如畸形响应触发解析抛错）
                    // 都不允许杀死轮询协程——否则首页从此静默停更
                    try {
                        pollOnce(slow = slowTick == 0)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("wrtctrl", "home poll failed: ${e.message}")
                    }
                }
            }
        }
        viewModelScope.launch {
            dashboardPrefs.configFlow().collect { config ->
                _state.update {
                    it.copy(
                        cardOrder = config.order,
                        cardEnabled = config.enabled,
                        collapsed = config.collapsed,
                        cardConfigLoaded = true,
                    )
                }
            }
        }
    }

    /** 切换设备：重置回 loading 过渡态（与进 app 一致）并立即补一轮拉取——
     *  否则旧设备数据要挂到下个 3s 轮询节拍才被新数据覆盖，观感是"变化很慢"。
     *  卡片自定义三件套（顺序/显隐/折叠）跨设备保留 */
    fun switchDevice(deviceId: String?) {
        if (deviceId == loadedDeviceId) return
        loadedDeviceId = deviceId
        cachedDevice = null
        boardCache = null
        connMaxCache = null
        generation++
        bandwidthDevice = null
        _state.update {
            HomeUiState(
                cardOrder = it.cardOrder,
                cardEnabled = it.cardEnabled,
                collapsed = it.collapsed,
                cardConfigLoaded = it.cardConfigLoaded,
            )
        }
        viewModelScope.launch { pollOnce(slow = true) }
    }

    /** 编辑页即时保存（顺序 + 显隐；折叠态保持当前值不被动） */
    fun saveCardConfig(order: List<DashboardCardId>, enabled: Set<DashboardCardId>) {
        val collapsed = _state.value.collapsed
        viewModelScope.launch { dashboardPrefs.save(DashboardConfig(order, enabled, collapsed)) }
    }

    /** 卡头点击展开/收起：本地立即生效 + 异步持久化（跨重启记忆） */
    fun toggleCollapsed(cardId: DashboardCardId) {
        val newCollapsed = _state.value.collapsed
            .let { if (cardId in it) it - cardId else it + cardId }
        _state.update { it.copy(collapsed = newCollapsed) }
        val snapshot = _state.value
        viewModelScope.launch {
            dashboardPrefs.save(DashboardConfig(snapshot.cardOrder, snapshot.cardEnabled, newCollapsed))
        }
    }

    /** 下拉刷新：立即补一轮拉取（含慢拍项；与 3s 自动轮询并存；进行中重复触发忽略） */
    fun refresh() {
        if (_state.value.refreshing) return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            pollOnce(slow = true)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun pollOnce(slow: Boolean = false) {
        pollMutex.withLock {
            val gen = generation
            val poll = coroutineScope {
                // 恒定数据缓存命中则不发起（board/conntrack_max，P0-2）
                val boardJob = if (boardCache == null) async { ubusSafe("system", "board") } else null
                val infoJob = async { ubusSafe("system", "info") }
                val connCountJob = async { readSafe("/proc/sys/net/netfilter/nf_conntrack_count") }
                val connMaxJob = if (connMaxCache == null) async { readSafe("/proc/sys/net/netfilter/nf_conntrack_max") } else null
                val ifaceJob = async { ubusSafe("network.interface", "dump") }
                // 温度/挂载点秒级缓变：只在慢拍拉，快拍轮保留旧值
                val tempJob = if (slow) async { ubusSafe("luci", "getTempInfo") } else null
                val mountsJob = if (slow) async { ubusSafe("luci", "getMountPoints") } else null
                val cpuJob = async { ubusSafe("luci", "getCPUUsage") }
                HomePoll(
                    board = boardJob?.await() ?: boardCache,
                    info = infoJob.await(),
                    connCount = connCountJob.await(),
                    connMax = connMaxJob?.await() ?: connMaxCache,
                    ifaceDump = ifaceJob.await(),
                    temp = tempJob?.await(),
                    mounts = mountsJob?.await(),
                    cpu = cpuJob.await(),
                )
            }
            // 拉取期间设备已切换：整轮丢弃（失败项保留旧值语义不变），恒定缓存也不落
            if (gen != generation) return
            poll.board?.let { boardCache = it }
            poll.connMax?.let { connMaxCache = it }
            _state.update { applyPoll(it, poll) }

            // 带宽依赖 iface dump 的目标设备
            poll.ifaceDump?.let { fetchBandwidth(it, gen) }
            if (slow) fetchSlow(gen)
        }
    }

    /** 一轮拉取结果 → 状态字段映射：各字段失败/缺失时保留旧值（静默保留） */
    private fun applyPoll(state: HomeUiState, poll: HomePoll): HomeUiState {
        val conn = HomeParsers.connections(poll.connCount, poll.connMax)
        val chips = poll.ifaceDump?.let(HomeParsers::ifaceChips)
        return state.copy(
            loading = false,
            model = poll.board?.let(HomeParsers::model) ?: state.model,
            hostname = poll.board?.optString("hostname", state.hostname) ?: state.hostname,
            releaseVersion = poll.board?.let(HomeParsers::releaseVersion) ?: state.releaseVersion,
            uptime = poll.info?.optLong("uptime")?.let(Format::durationBrief) ?: state.uptime,
            cpuPercent = poll.cpu?.let(HomeParsers::cpuPercent) ?: state.cpuPercent,
            tempC = poll.temp?.let(HomeParsers::tempC) ?: state.tempC,
            memoryPercent = poll.info?.let(HomeParsers::memoryPercent) ?: state.memoryPercent,
            memoryDetail = poll.info?.let(HomeParsers::memoryDetail) ?: state.memoryDetail,
            connCount = conn?.first ?: state.connCount,
            connMax = conn?.second ?: state.connMax,
            wanIp = poll.ifaceDump?.let(HomeParsers::wanIp) ?: state.wanIp,
            gateway = poll.ifaceDump?.let(HomeParsers::gateway) ?: state.gateway,
            dns = poll.ifaceDump?.let(HomeParsers::dns) ?: state.dns,
            // 内容不变的列表保持同实例：引用稳定才能让未变化的卡在重组中被跳过
            ifaceChips = chips?.takeIf { it != state.ifaceChips } ?: state.ifaceChips,
            mounts = poll.mounts?.let(HomeParsers::mountList)
                ?.takeIf { it != state.mounts } ?: state.mounts,
        )
    }

    private suspend fun fetchBandwidth(ifaceDump: JSONObject, gen: Int) {
        val interfaces = ifaceDump.optJSONArray("interface") ?: return
        var wanDevice: String? = null
        for (i in 0 until interfaces.length()) {
            val entry = interfaces.optJSONObject(i) ?: continue
            if (entry.optString("interface") == "wan") {
                wanDevice = entry.optString("l3_device", entry.optString("device", "wan"))
                break
            }
        }
        val target = wanDevice ?: "br-lan"
        val source = if (wanDevice != null) "wan" else "lan"
        if (target != bandwidthDevice) {
            // 目标切换（wan↔br-lan）时清空序列，避免混入旧设备的差分
            bandwidthDevice = target
            _state.update {
                it.copy(
                    rxSeries = emptyList(),
                    txSeries = emptyList(),
                    timestamps = emptyList(),
                    rxRate = 0,
                    txRate = 0,
                    bandwidthSource = source,
                )
            }
        }
        val payload = try {
            withContext(Dispatchers.IO) {
                WrtCore.callUbus("luci", "getRealtimeStats", JSONObject().put("mode", "interface").put("device", target), 3000)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        val samples = payload.optJSONArray("result") ?: return
        // 差分解析单独包护：畸形样本行（非数组元素）不得外溢——外层轮询循环虽有兜底，
        // 但那会让本轮 info 等已到手的更新一起作废；解析在 IO
        val series = try {
            withContext(Dispatchers.IO) { bandwidthRates(samples) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("wrtctrl", "bandwidth parse failed: ${e.message}")
            return
        }
        if (series.timestamps.isEmpty()) return
        var rx = series.rx; var tx = series.tx; var ts = series.timestamps
        if (rx.size > 60) {
            rx = rx.takeLast(60).toMutableList()
            tx = tx.takeLast(60).toMutableList()
            ts = ts.takeLast(60).toMutableList()
        }
        // 时间戳锚定到设备墙钟：luci 实时统计的 ts 语义随固件而异（epoch / 路由 uptime），
        // 但都按真实秒推进——用「最后一次采样 ≈ 本次拉取时刻」线性平移，X 轴与查值标记
        // 的时间对一切语义都正确（若本就是 epoch，偏移仅为网络延迟，无影响）
        val wallNowSec = System.currentTimeMillis() / 1000
        val shiftSec = wallNowSec - ts.last()
        // 断档截断（详见 contiguousBandwidthTail）：停轮询期间设备缓冲冻结，恢复后新旧样本假连续
        val (tailRx, tailTx, tailTs) =
            contiguousBandwidthTail(rx, tx, ts.map { it + shiftSec }, POLL_INTERVAL / 1000 * 3 + 2)
        if (gen != generation) return
        _state.update {
            it.copy(
                rxSeries = tailRx,
                txSeries = tailTx,
                timestamps = tailTs,
                rxRate = tailRx.last().roundToLong(),
                txRate = tailTx.last().roundToLong(),
                bandwidthSource = source,
            )
        }
    }

    // 轮询容错吞掉业务异常，但 CancellationException 必须外抛（吞掉会破坏结构化取消）
    private suspend fun ubusSafe(objectName: String, method: String): JSONObject? = try {
        withContext(Dispatchers.IO) { WrtCore.callUbus(objectName, method) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun readSafe(path: String): String? = try {
        withContext(Dispatchers.IO) { WrtCore.readFile(path) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ── 慢拍项（约 9s）：客户端计数 + ping。失败一律静默保留旧值 ──

    /** 无线客户端合计 = 逐 ifname assoclist 长度和（口径与客户端页同源） */
    private suspend fun assocTotal(): Int? {
        val radios = ubusSafe("luci-rpc", "getWirelessDevices") ?: return null
        var total = 0
        ClientParsers.wifiIfaces(radios).forEach { (ifname, _) ->
            total += try {
                withContext(Dispatchers.IO) { WrtCore.assocList(ifname) }.length()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                0
            }
        }
        return total
    }

    /** DHCP 租约合计 = v4 + v6 双栈（口径与客户端页同源） */
    private suspend fun leaseTotal(): Int? {
        val payload = ubusSafe("luci-rpc", "getDHCPLeases") ?: return null
        return try {
            val (v4, v6) = ClientParsers.dhcpLeases(payload)
            v4.size + v6.size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** ICMP 优先 ping（既有策略）：null = 不可达 → 离线徽章 */
    private suspend fun pingDevice(baseUrl: String): Long? = try {
        withContext(Dispatchers.IO) { WrtCore.pingUrl(baseUrl) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchSlow(gen: Int) {
        val device = resolveDevice() ?: return
        // 协程闭包内赋值需 var（val 不能跨闭包赋值）
        var assoc: Int? = null
        var lease: Int? = null
        var ping: Long? = null
        coroutineScope {
            val assocJob = async { assocTotal() }
            val leaseJob = async { leaseTotal() }
            val pingJob = async { pingDevice(device.baseUrl) }
            assoc = assocJob.await()
            lease = leaseJob.await()
            ping = pingJob.await()
        }
        if (gen != generation) return
        _state.update {
            it.copy(
                assocCount = assoc ?: it.assocCount,
                leaseCount = lease ?: it.leaseCount,
                pingMs = ping,
                pingOffline = ping == null,
            )
        }
    }

    private suspend fun resolveDevice(): Device? {
        cachedDevice?.let { return it }
        val id = loadedDeviceId ?: return null
        return try {
            deviceRepository.get(id)?.also { cachedDevice = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private data class HomePoll(
        val board: JSONObject?,
        val info: JSONObject?,
        val connCount: String?,
        val connMax: String?,
        val ifaceDump: JSONObject?,
        val temp: JSONObject?,
        val mounts: JSONObject?,
        val cpu: JSONObject?,
    )

    private companion object {
        /** 轮询周期：与其他数据页一致（可见时静默刷新） */
        const val POLL_INTERVAL = 3000L

        /** 慢拍间隔（拍数）：客户端计数/ping 约 9s 一次 */
        const val SLOW_EVERY = 3
    }
}

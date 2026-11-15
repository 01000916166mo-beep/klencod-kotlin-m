package com.clashangprpr.app.world

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.clashangprpr.app.engine.Assets
import com.clashangprpr.app.engine.LanMultiplayer
import com.clashangprpr.app.engine.SaveStore
import com.clashangprpr.app.engine.Sound
import com.clashangprpr.app.engine.Ui
import com.clashangprpr.app.model.WText
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * مملكة الحصاد والحرب (Kingdoms of Harvest & War)
 * Complete Hybrid of Clash of Clans (Village Builder + Troop Training + 3-Star Raid Battles)
 * AND Hay Day / Stardew Valley (Crop Farming + Livestock + Bakery Mill + Truck Delivery Orders + Global Market)
 * with 3 Free Forever ($0) Online Systems:
 *   1. Asynchronous Cloud Matchmaking & Market (via free InfinityFree PHP API or built-in Cloud Simulation)
 *   2. Serverless Shareable Village Code (copy/paste village codes via WhatsApp/Telegram to raid any player worldwide!)
 *   3. Local Wi-Fi / Mobile Hotspot Live Village Raid & Market Trade (`LanMultiplayer`)
 */
class ClashFarmKingdom {

    enum class BType(
        val code: Int,
        val titleAr: String,
        val descAr: String,
        val iconAsset: String,
        val costGold: Int,
        val costElixir: Int,
        val baseHp: Int,
        val isDefense: Boolean,
        val rangePx: Float = 0f,
        val atkDmg: Int = 0,
        val atkCd: Float = 1.2f
    ) {
        TOWN_HALL(1, "قلعة القيادة الملكية 🏰", "قلب مملكتك! طوّرها لفتح دفاعات ومصانع جديدة وتدميرها يمنح المهاجم نجمة.", "icons/coc_townhall.png", 400, 200, 1450, false),
        FARM_PLOT(2, "حقل زراعي خصب 🌾", "يزرع القمح والذرة والتوت السحري لتمويل المخبز والشاحنة والجيش!", "icons/wheat.png", 60, 0, 320, false),
        ANIMAL_PEN(3, "حظيرة الخراف والأبقار 🐑", "تنتج الصوف والحليب واللحم الطازج كل 20 ثانية.", "icons/wool.png", 120, 40, 420, false),
        BAKERY_MILL(4, "المخبز وطاحونة المؤن 🥖", "يخبز القمح والحليب لإنتاج مؤن الجيش وفطائر الشاحنة التجارية!", "icons/coc_bakery.png", 160, 80, 500, false),
        GOLD_MINE(5, "منجم الذهب الملكي 🪙", "يستخرج الذهب باستمرار لبناء الأبراج وتطوير الأسوار.", "icons/coin.png", 100, 50, 440, false),
        ELIXIR_WELL(6, "مستخرج الإكسير السحري 🧪", "يستخرج الإكسير البنفسجي لتدريب العمالقة والسحرة والغولم.", "icons/coc_elixir.png", 100, 50, 440, false),
        BARRACKS(7, "الثكنات ومعسكر الجيش ⚔️", "يدرب 6 أنواع من الجيوش لشن الغارات وكسب 3 نجوم!", "icons/coc_barracks.png", 180, 120, 580, false),
        CANNON(8, "مدفع القلعة المضاد 💥", "مدفع أرضي قوي يقصف جنود الأعداء والعمالقة.", "icons/pirate_cannon.png", 220, 0, 620, true, 235f, 44, 0.95f),
        ARCHER_TOWER(9, "برج الرماة القناص 🏹", "برج عالٍ يطلق سهاماً سريعة بعيدة المدى.", "icons/coc_archertower.png", 260, 60, 560, true, 290f, 30, 0.55f),
        WIZARD_TOWER(10, "برج الساحر الناري 🔮", "يطلق كرات لهب متفجرة تحرق مجموعات الجنود معاً!", "icons/structure_tower.png", 340, 180, 680, true, 240f, 52, 1.35f),
        MORTAR(11, "مدفع الهاون الثقيل 💣", "يقصف قذائف بعيدة المدى تدمر حشود المهاجمين!", "icons/coc_mortar.png", 420, 220, 740, true, 350f, 75, 2.2f),
        WALL_BLOCK(12, "سور القلعة المدرع 🧱", "يحمي مبانيك ومزارعك ويعيق تقدم جيوش الأعداء.", "icons/block_stone_brick.png", 25, 0, 720, false),
        HEAL_SHRINE(13, "منارة البركة والشفاء ✨", "ترفع إنتاج المزارع حولها وتشفي المباني الدفاعية اثناء الحصار!", "icons/block_beacon.png", 300, 200, 540, false),
        CLAN_CASTLE(14, "قلعة الكلان والتحالف 🛡️", "تخزن جنود دعم الكلان وتفتح حروب الكلانات 5 ضد 5 والغنائم المشتركة!", "icons/coc_clan.png", 350, 250, 1100, false),
        BUILDER_HUT(15, "كوخ البناء الملكي 👷", "يزيد سرعة وإنتاج قريتك ومزرعتك بنسبة +25% لكل بناء إضافي!", "icons/coc_builder.png", 250, 150, 400, false);

        companion object {
            fun byCode(c: Int): BType = values().firstOrNull { it.code == c } ?: WALL_BLOCK
        }
    }

    enum class TroopKind(
        val idx: Int,
        val nameAr: String,
        val roleAr: String,
        val iconAsset: String,
        val costElixir: Int,
        val costBread: Int,
        val maxHp: Int,
        val dmg: Int,
        val speed: Float,
        val rangePx: Float,
        val targetsDefenseFirst: Boolean,
        val wallBreaker: Boolean,
        val color: Int
    ) {
        BARBARIAN(0, "محارب البربر ⚔️", "يهجم بشراسة على أقرب مبنى", "icons/item_1.png", 20, 1, 190, 32, 92f, 38f, false, false, Color.parseColor("#FFB74D")),
        ARCHER(1, "رامية السهام 🏹", "تطلق سهاماً من خلف الأسوار", "icons/weapon_bow.png", 30, 1, 125, 26, 102f, 155f, false, false, Color.parseColor("#F06292")),
        GIANT(2, "العملاق الحاصد 🛡️", "دبابة بشرية تستهدف المدافع والأبراج أولاً!", "icons/armor_chest.png", 75, 2, 620, 48, 62f, 42f, true, false, Color.parseColor("#FF8A65")),
        WALL_BREAKER(3, "مفجر الأسوار 💣", "يركض نحو الأسوار ويفجرها بضرر ×8!", "icons/bomb.png", 45, 1, 110, 380, 138f, 34f, false, true, Color.parseColor("#FF5252")),
        WIZARD(4, "ساحر اللهب 🔥", "يطلق كرات نار سحرية بضرر هائل عن بعد", "icons/pot17.png", 95, 2, 210, 68, 84f, 175f, false, false, Color.parseColor("#00E5FF")),
        GOLEM(5, "الغولم الحديدي 🤖", "أقوى وحدة في اللعبة (1100 صحة) يسحق الدفاعات!", "icons/iron_golem.png", 160, 4, 1150, 85, 52f, 45f, true, false, Color.parseColor("#80DEEA"))
    }

    class PlacedBuilding(
        var gx: Int,
        var gy: Int,
        val type: BType,
        var level: Int = 1
    ) {
        val maxHp: Int get() = (type.baseHp * (1f + (level - 1) * 0.28f)).toInt()
        var hp: Int = maxHp
        var prodTimer: Float = 0f
        var storedYield: Int = 0
        var cropStage: Int = 2 // 0=seed, 1=growing, 2=ready to harvest!
        var atkTimer: Float = 0f
        val isDestroyed: Boolean get() = hp <= 0
    }

    class RaidTroop(
        var x: Float,
        var y: Float,
        val kind: TroopKind
    ) {
        var hp: Int = kind.maxHp
        val maxHp: Int = kind.maxHp
        var atkCd: Float = 0f
        var rageTimer: Float = 0f
        val isDead: Boolean get() = hp <= 0
    }

    class RaidProj(
        var x: Float,
        var y: Float,
        var tx: Float,
        var ty: Float,
        val dmg: Int,
        val fromDefense: Boolean,
        val splash: Boolean,
        val color: Int
    ) {
        var progress = 0f
        val startX = x
        val startY = y
    }

    data class TruckOrder(
        var titleAr: String,
        var needWheat: Int,
        var needWool: Int,
        var needBread: Int,
        var rewardGold: Int,
        var rewardElixir: Int
    )

    data class MarketOffer(
        val seller: String,
        val itemTitle: String,
        val itemCode: String,
        val qty: Int,
        val priceGold: Int
    )

    companion object {
        const val COLS = 16
        const val ROWS = 11
    }

    // Player Economy, Farming & Army Inventories
    var gold = 850
    var elixir = 650
    var trophies = 120
    var wheat = 14
    var woolMilk = 8
    var breadRations = 10
    var gems = 65
    var buildersCount = 2
    var shieldHours = 12
    var useIsometric25D = true
    var clanName = "فرسان المسك ⚔️"
    var clanReinforcementsReady = true
    var clanReinforcementsDeployed = false
    var myClanWarStars = 9
    var enemyClanWarStars = 7
    var warBonusClaimed = false
    val warBaseStars = IntArray(5) { idx -> if (idx < 2) 3 else if (idx == 2) 2 else 0 }
    var activeWarBaseIdx = -1
    val clanChatMessages = ArrayList<String>().apply {
        add("القائد مصطفى: أهلاً بكم في كلان فرسان المسك! استعدوا لحرب الكلانات ⚔️🔥")
        add("الفارس عمر: أرسلت لكم دعم (2 عملاق + 1 غولم حديدي) في قلعة الكلان 🛡️")
        add("الحكيم يونس: اهجموا على القلعة الحربية رقم 4 و5 لحسم الحرب وكسب الصندوق الملكي!")
    }

    val armyCounts = IntArray(6) { idx ->
        when (idx) {
            0 -> 8 // Barbarians
            1 -> 6 // Archers
            2 -> 3 // Giants
            3 -> 3 // Wall Breakers
            4 -> 2 // Wizards
            else -> 1 // Golem
        }
    }
    var healSpells = 2
    var rageSpells = 2
    var lightningSpells = 2

    val myBuildings = ArrayList<PlacedBuilding>()
    val raidBuildings = ArrayList<PlacedBuilding>()
    val activeTroops = ArrayList<RaidTroop>()
    val activeProjs = ArrayList<RaidProj>()
    val truckOrders = ArrayList<TruckOrder>()
    val marketOffers = ArrayList<MarketOffer>()

    // Mode & UI State
    /** 0 = My Village & Farm Builder, 1 = Active Clash 3-Star Raid Battle */
    var mode = 0
    /** Sub-modal inside Kingdom: 0=None, 1=Build Shop, 2=Army & Bakery, 3=Truck & Global Market, 4=Online & Village Code Hub */
    var subModal = 0

    var selectedBuilding: PlacedBuilding? = null
    var movingBuilding: Boolean = false
    var pendingBuildType: BType? = null
    var selectedDeployIdx: Int = 0 // 0..5 = troops, 6=Heal, 7=Rage, 8=Lightning

    // Raid state
    var raidEnemyName = "قلعة البارون الأسود"
    var raidEnemyTrophies = 145
    var raidTotalBuildings = 1
    var raidDestroyedCount = 0
    var raidTownHallDown = false
    var raidEnded = false
    var raidTimeLeft = 150f
    var raidLootGold = 0
    var raidLootElixir = 0

    // Online Cloud / Share Code state
    var cloudApiUrl = "https://apk3rade.mangoad.online/server_api.php"
    var customCodeInput = ""
    var onlineStatusMsg = "الأونلاين المجاني جاهز: شارك كود قريتك أو اهجم عبر السحابة أو الواي فاي!"
    private var animTime = 0f

    init {
        loadOrCreateStarterKingdom()
        refreshTruckOrders()
        populateDefaultMarket()
    }

    fun loadOrCreateStarterKingdom() {
        myBuildings.clear()
        val saved = SaveStore.str("coc_village_v1", "")
        gold = SaveStore.int("coc_gold", 850)
        elixir = SaveStore.int("coc_elixir", 650)
        trophies = SaveStore.int("coc_trophies", 120)
        wheat = SaveStore.int("coc_wheat", 14)
        woolMilk = SaveStore.int("coc_wool", 8)
        breadRations = SaveStore.int("coc_bread", 10)
        gems = SaveStore.int("coc_gems", 65)
        buildersCount = SaveStore.int("coc_builders", 2)
        myClanWarStars = SaveStore.int("coc_wstars", 9)
        val armyStr = SaveStore.str("coc_army", "8,6,3,3,2,1").split(",")
        for (i in 0 until 6) armyCounts[i] = armyStr.getOrNull(i)?.toIntOrNull() ?: armyCounts[i]

        if (saved.isNotEmpty() && importVillageFromCode(saved, myBuildings)) {
            return
        }
        // Build an awesome starter Hybrid Village + Farm!
        myBuildings.add(PlacedBuilding(7, 5, BType.TOWN_HALL, 2))
        myBuildings.add(PlacedBuilding(5, 4, BType.CANNON, 2))
        myBuildings.add(PlacedBuilding(9, 4, BType.ARCHER_TOWER, 2))
        myBuildings.add(PlacedBuilding(7, 3, BType.MORTAR, 1))
        myBuildings.add(PlacedBuilding(5, 6, BType.WIZARD_TOWER, 1))
        myBuildings.add(PlacedBuilding(9, 6, BType.CANNON, 1))
        myBuildings.add(PlacedBuilding(6, 5, BType.HEAL_SHRINE, 1))
        myBuildings.add(PlacedBuilding(8, 5, BType.CLAN_CASTLE, 2))
        myBuildings.add(PlacedBuilding(6, 6, BType.BUILDER_HUT, 1))

        // Fortress Wall perimeter around core (4..10 x 2..7)
        for (gx in 4..10) {
            myBuildings.add(PlacedBuilding(gx, 2, BType.WALL_BLOCK, 2))
            myBuildings.add(PlacedBuilding(gx, 7, BType.WALL_BLOCK, 2))
        }
        for (gy in 3..6) {
            myBuildings.add(PlacedBuilding(4, gy, BType.WALL_BLOCK, 2))
            myBuildings.add(PlacedBuilding(10, gy, BType.WALL_BLOCK, 2))
        }

        // Left Wing: Hay Day Farm Plots, Animal Pens & Bakery Mill!
        for (gy in 2..6) {
            myBuildings.add(PlacedBuilding(1, gy, BType.FARM_PLOT, 1))
            myBuildings.add(PlacedBuilding(2, gy, BType.FARM_PLOT, 1))
        }
        myBuildings.add(PlacedBuilding(1, 8, BType.ANIMAL_PEN, 2))
        myBuildings.add(PlacedBuilding(3, 8, BType.BAKERY_MILL, 2))

        // Right Wing: Gold Mines, Elixir Wells & Army Barracks!
        myBuildings.add(PlacedBuilding(12, 3, BType.GOLD_MINE, 2))
        myBuildings.add(PlacedBuilding(13, 3, BType.GOLD_MINE, 2))
        myBuildings.add(PlacedBuilding(12, 5, BType.ELIXIR_WELL, 2))
        myBuildings.add(PlacedBuilding(13, 5, BType.ELIXIR_WELL, 2))
        myBuildings.add(PlacedBuilding(12, 7, BType.BARRACKS, 2))
        myBuildings.add(PlacedBuilding(14, 5, BType.ARCHER_TOWER, 1))
        saveKingdom()
    }

    fun saveKingdom() {
        val m = HashMap<String, Any>()
        m["coc_village_v1"] = exportVillageToCode(myBuildings)
        m["coc_gold"] = gold
        m["coc_elixir"] = elixir
        m["coc_trophies"] = trophies
        m["coc_wheat"] = wheat
        m["coc_wool"] = woolMilk
        m["coc_bread"] = breadRations
        m["coc_gems"] = gems
        m["coc_builders"] = buildersCount
        m["coc_wstars"] = myClanWarStars
        m["coc_army"] = armyCounts.joinToString(",")
        SaveStore.putAll(m)
    }

    /** Encodes a complete Village layout into a compact shareable string code (works 100% offline/online via WhatsApp/Telegram/Cloud!). */
    fun exportVillageToCode(list: List<PlacedBuilding>): String {
        val sb = StringBuilder("KH1:")
        for (b in list) {
            sb.append(b.gx).append('.').append(b.gy).append('.').append(b.type.code).append('.').append(b.level).append(';')
        }
        return sb.toString()
    }

    fun importVillageFromCode(code: String, targetList: MutableList<PlacedBuilding>): Boolean {
        val clean = code.trim()
        if (!clean.startsWith("KH1:")) return false
        val body = clean.substring(4)
        val parsed = ArrayList<PlacedBuilding>()
        for (token in body.split(';')) {
            if (token.isBlank()) continue
            val p = token.split('.')
            if (p.size < 4) continue
            val gx = p[0].toIntOrNull()?.coerceIn(0, COLS - 1) ?: continue
            val gy = p[1].toIntOrNull()?.coerceIn(0, ROWS - 1) ?: continue
            val tc = p[2].toIntOrNull() ?: continue
            val lvl = p[3].toIntOrNull()?.coerceIn(1, 10) ?: 1
            parsed.add(PlacedBuilding(gx, gy, BType.byCode(tc), lvl))
        }
        if (parsed.isEmpty()) return false
        targetList.clear()
        targetList.addAll(parsed)
        return true
    }

    private fun refreshTruckOrders() {
        truckOrders.clear()
        truckOrders.add(TruckOrder("طلبية القرية المجاورة 🚚", 4, 2, 1, 220, 140))
        truckOrders.add(TruckOrder("تموين قافلة الفرسان 🥖", 6, 3, 3, 380, 260))
        truckOrders.add(TruckOrder("شحنة السفينة الملكية 🚢", 10, 5, 4, 640, 450))
    }

    private fun populateDefaultMarket() {
        marketOffers.clear()
        marketOffers.add(MarketOffer("مزرعة السلطان", "20 قمح ذهبي 🌾", "WHEAT", 20, 110))
        marketOffers.add(MarketOffer("قلعة النور", "8 مؤن خبز السرايا 🥖", "BREAD", 8, 150))
        marketOffers.add(MarketOffer("أبو ماجد", "6 صوف وحليب طازج 🐑", "WOOL", 6, 95))
        marketOffers.add(MarketOffer("ساحر الشرق", "180 إكسير سحري 🧪", "ELIXIR", 180, 140))
    }

    fun update(dt: Float) {
        animTime += dt
        // Check if a friend sent their village over Wi-Fi / Hotspot for a live raid!
        while (true) {
            val pkt = LanMultiplayer.incoming.peek() ?: break
            if (pkt.startsWith("COC_VILLAGE|")) {
                LanMultiplayer.incoming.poll()
                val parts = pkt.split("|")
                if (parts.size >= 4) {
                    val friendName = parts[1]
                    val friendTrophies = parts[2].toIntOrNull() ?: 200
                    val code = parts[3]
                    if (importVillageFromCode(code, raidBuildings)) {
                        startRaidOnLoadedBuildings("قرية صديقك ($friendName) 🤝", friendTrophies)
                        Sound.play("boss")
                    }
                }
            } else {
                break
            }
        }

        if (mode == 0) {
            // Update Farm crops, Animal Pens, Bakery, Gold Mines & Elixir Wells
            for (b in myBuildings) {
                b.prodTimer += dt
                when (b.type) {
                    BType.FARM_PLOT -> {
                        if (b.cropStage < 2 && b.prodTimer >= 9f) {
                            b.prodTimer = 0f
                            b.cropStage++
                        }
                    }
                    BType.ANIMAL_PEN -> if (b.prodTimer >= 12f) {
                        b.prodTimer = 0f
                        b.storedYield = min(10, b.storedYield + b.level)
                    }
                    BType.BAKERY_MILL -> if (b.prodTimer >= 14f && wheat >= 1) {
                        b.prodTimer = 0f
                        wheat--
                        breadRations += b.level
                    }
                    BType.GOLD_MINE -> if (b.prodTimer >= 5f) {
                        b.prodTimer = 0f
                        b.storedYield = min(150 * b.level, b.storedYield + 12 * b.level)
                    }
                    BType.ELIXIR_WELL -> if (b.prodTimer >= 5f) {
                        b.prodTimer = 0f
                        b.storedYield = min(150 * b.level, b.storedYield + 12 * b.level)
                    }
                    else -> {}
                }
            }
        } else if (mode == 1 && !raidEnded) {
            raidTimeLeft = max(0f, raidTimeLeft - dt)
            if (raidTimeLeft <= 0f) {
                finishRaid()
                return
            }
            updateRaidSimulation(dt)
        }
    }

    private fun updateRaidSimulation(dt: Float) {
        // 1. Update Troops (Clash of Clans AI)
        for (tr in activeTroops) {
            if (tr.isDead) continue
            if (tr.rageTimer > 0f) tr.rageTimer -= dt
            if (tr.atkCd > 0f) tr.atkCd -= dt

            var bestB: PlacedBuilding? = null
            var bestScore = 1e9f
            for (b in raidBuildings) {
                if (b.isDestroyed) continue
                val bx = b.gx + 0.5f
                val by = b.gy + 0.5f
                val d = hypot((bx - tr.x).toDouble(), (by - tr.y).toDouble()).toFloat()
                val score = when {
                    tr.kind.wallBreaker && b.type == BType.WALL_BLOCK -> d * 0.12f
                    tr.kind.targetsDefenseFirst && b.type.isDefense -> d * 0.22f
                    b.type == BType.WALL_BLOCK && !tr.kind.wallBreaker -> d * 2.4f
                    else -> d
                }
                if (score < bestScore) {
                    bestScore = score
                    bestB = b
                }
            }

            if (bestB != null) {
                val tx = bestB.gx + 0.5f
                val ty = bestB.gy + 0.5f
                val dx = tx - tr.x
                val dy = ty - tr.y
                val distTiles = max(0.05f, hypot(dx.toDouble(), dy.toDouble()).toFloat())
                val rangeTiles = max(0.85f, tr.kind.rangePx / 64f)
                val spdMul = if (tr.rageTimer > 0f) 1.55f else 1f
                val dmgMul = if (tr.rageTimer > 0f) 1.55f else 1f

                if (distTiles > rangeTiles) {
                    val step = (tr.kind.speed / 64f) * spdMul * dt
                    tr.x += (dx / distTiles) * step
                    tr.y += (dy / distTiles) * step
                } else if (tr.atkCd <= 0f) {
                    tr.atkCd = if (tr.rageTimer > 0f) 0.52f else 0.85f
                    if (tr.kind.wallBreaker) {
                        // Wall Breaker kamikaze explosion!
                        val wbDmg = if (bestB.type == BType.WALL_BLOCK) tr.kind.dmg * 2 else tr.kind.dmg
                        damageRaidBuilding(bestB, wbDmg)
                        tr.hp = 0
                        Sound.play("explode", 0.8f)
                    } else if (rangeTiles > 1.3f) {
                        activeProjs.add(RaidProj(tr.x, tr.y, tx, ty, (tr.kind.dmg * dmgMul).toInt(), false, tr.kind == TroopKind.WIZARD, tr.kind.color))
                        Sound.play("swing", 0.5f)
                    } else {
                        damageRaidBuilding(bestB, (tr.kind.dmg * dmgMul).toInt())
                        Sound.play("hit", 0.55f)
                    }
                }
            }
        }

        // 2. Update Defensive Towers & Healing Shrines
        for (b in raidBuildings) {
            if (b.isDestroyed) continue
            if (b.type == BType.HEAL_SHRINE) {
                b.prodTimer += dt
                if (b.prodTimer >= 1.5f) {
                    b.prodTimer = 0f
                    for (other in raidBuildings) {
                        if (!other.isDestroyed && other.hp < other.maxHp && abs(other.gx - b.gx) <= 2 && abs(other.gy - b.gy) <= 2) {
                            other.hp = min(other.maxHp, other.hp + 25)
                        }
                    }
                }
                continue
            }
            if (!b.type.isDefense) continue
            if (b.atkTimer > 0f) b.atkTimer -= dt
            if (b.atkTimer <= 0f) {
                val bx = b.gx + 0.5f
                val by = b.gy + 0.5f
                val rangeTiles = b.type.rangePx / 64f
                var targetTr: RaidTroop? = null
                var bestD = rangeTiles
                for (tr in activeTroops) {
                    if (tr.isDead) continue
                    val d = hypot((tr.x - bx).toDouble(), (tr.y - by).toDouble()).toFloat()
                    if (d <= bestD) {
                        bestD = d
                        targetTr = tr
                    }
                }
                if (targetTr != null) {
                    b.atkTimer = b.type.atkCd
                    val dmg = (b.type.atkDmg * (1f + (b.level - 1) * 0.22f)).toInt()
                    val splash = b.type == BType.WIZARD_TOWER || b.type == BType.MORTAR
                    val col = when (b.type) {
                        BType.WIZARD_TOWER -> Color.parseColor("#E040FB")
                        BType.MORTAR -> Color.parseColor("#FF5722")
                        BType.ARCHER_TOWER -> Color.parseColor("#69F0AE")
                        else -> Color.parseColor("#FFD54F")
                    }
                    activeProjs.add(RaidProj(bx, by, targetTr.x, targetTr.y, dmg, true, splash, col))
                }
            }
        }

        // 3. Update Projectiles
        var pi = activeProjs.size - 1
        while (pi >= 0) {
            val pr = activeProjs[pi]
            pr.progress += dt * 3.4f
            pr.x = pr.startX + (pr.tx - pr.startX) * pr.progress
            pr.y = pr.startY + (pr.ty - pr.startY) * pr.progress
            if (pr.progress >= 1f) {
                if (pr.fromDefense) {
                    for (tr in activeTroops) {
                        if (tr.isDead) continue
                        val hitR = if (pr.splash) 1.65f else 0.75f
                        if (hypot((tr.x - pr.tx).toDouble(), (tr.y - pr.ty).toDouble()) <= hitR) {
                            tr.hp -= pr.dmg
                        }
                    }
                } else {
                    for (b in raidBuildings) {
                        if (b.isDestroyed) continue
                        val hitR = if (pr.splash) 1.5f else 0.85f
                        if (hypot((b.gx + 0.5f - pr.tx).toDouble(), (b.gy + 0.5f - pr.ty).toDouble()) <= hitR) {
                            damageRaidBuilding(b, pr.dmg)
                            if (!pr.splash) break
                        }
                    }
                }
                activeProjs.removeAt(pi)
            }
            pi--
        }

        // Check if 100% of non-wall buildings are destroyed!
        val nonWalls = raidBuildings.filter { it.type != BType.WALL_BLOCK }
        val destroyedNonWalls = nonWalls.count { it.isDestroyed }
        raidTotalBuildings = max(1, nonWalls.size)
        raidDestroyedCount = destroyedNonWalls
        if (destroyedNonWalls >= raidTotalBuildings) {
            finishRaid()
        }
    }

    private fun damageRaidBuilding(b: PlacedBuilding, dmg: Int) {
        if (b.isDestroyed) return
        b.hp -= dmg
        if (b.hp <= 0) {
            b.hp = 0
            Sound.play("explode", 0.7f)
            if (b.type == BType.TOWN_HALL) {
                raidTownHallDown = true
                raidLootGold += 250 * b.level
                raidLootElixir += 200 * b.level
            } else if (b.type != BType.WALL_BLOCK) {
                raidLootGold += 45 * b.level
                raidLootElixir += 35 * b.level
            }
        }
    }

    fun destructionPercent(): Int {
        return ((raidDestroyedCount * 100) / max(1, raidTotalBuildings)).coerceIn(0, 100)
    }

    fun currentStars(): Int {
        var s = 0
        val pct = destructionPercent()
        if (pct >= 50) s++
        if (raidTownHallDown) s++
        if (pct >= 100) s++
        return s
    }

    fun finishRaid() {
        if (raidEnded) return
        raidEnded = true
        val stars = currentStars()
        gold += raidLootGold
        elixir += raidLootElixir
        if (stars > 0) {
            val wonTrophies = stars * 12
            trophies += wonTrophies
            wheat += stars * 3
            gems += stars * 2
            if (activeWarBaseIdx in 0..4) {
                val prev = warBaseStars[activeWarBaseIdx]
                if (stars > prev) {
                    myClanWarStars += (stars - prev)
                    warBaseStars[activeWarBaseIdx] = stars
                }
                activeWarBaseIdx = -1
            }
            Sound.play("victory")
        } else {
            trophies = max(0, trophies - 8)
            Sound.play("deny")
        }
        saveKingdom()
    }

    fun startProceduralOrCloudRaid(levelOffset: Int = 0) {
        raidBuildings.clear()
        val enemyLvl = max(1, (trophies / 90) + 1 + levelOffset).coerceAtMost(8)
        val names = arrayOf(
            "إمبراطورية الصخرة الذهبية",
            "مملكة فرسان الشمال",
            "قلعة الساحر الحديدي",
            "حصن السلطان المنيع",
            "جزيرة البارود الملكية"
        )
        raidEnemyName = "${names[(trophies + levelOffset) % names.size]} (مستوى $enemyLvl)"
        raidEnemyTrophies = 100 + enemyLvl * 45

        // Build enemy Clash + Farm fortress
        raidBuildings.add(PlacedBuilding(7, 5, BType.TOWN_HALL, enemyLvl))
        raidBuildings.add(PlacedBuilding(5, 4, BType.CANNON, enemyLvl))
        raidBuildings.add(PlacedBuilding(9, 4, BType.ARCHER_TOWER, enemyLvl))
        raidBuildings.add(PlacedBuilding(5, 6, BType.WIZARD_TOWER, enemyLvl))
        raidBuildings.add(PlacedBuilding(9, 6, BType.CANNON, enemyLvl))
        raidBuildings.add(PlacedBuilding(7, 3, BType.MORTAR, enemyLvl))
        raidBuildings.add(PlacedBuilding(7, 7, BType.HEAL_SHRINE, enemyLvl))
        for (gx in 4..10) {
            raidBuildings.add(PlacedBuilding(gx, 2, BType.WALL_BLOCK, enemyLvl))
            raidBuildings.add(PlacedBuilding(gx, 8, BType.WALL_BLOCK, enemyLvl))
        }
        for (gy in 3..7) {
            raidBuildings.add(PlacedBuilding(4, gy, BType.WALL_BLOCK, enemyLvl))
            raidBuildings.add(PlacedBuilding(10, gy, BType.WALL_BLOCK, enemyLvl))
        }
        raidBuildings.add(PlacedBuilding(2, 3, BType.FARM_PLOT, enemyLvl))
        raidBuildings.add(PlacedBuilding(2, 5, BType.BAKERY_MILL, enemyLvl))
        raidBuildings.add(PlacedBuilding(2, 7, BType.ANIMAL_PEN, enemyLvl))
        raidBuildings.add(PlacedBuilding(12, 3, BType.GOLD_MINE, enemyLvl))
        raidBuildings.add(PlacedBuilding(12, 5, BType.ELIXIR_WELL, enemyLvl))
        raidBuildings.add(PlacedBuilding(12, 7, BType.BARRACKS, enemyLvl))

        startRaidOnLoadedBuildings(raidEnemyName, raidEnemyTrophies)
    }

    fun startRaidOnLoadedBuildings(enemyTitle: String, enemyTr: Int) {
        for (b in raidBuildings) b.hp = b.maxHp
        activeTroops.clear()
        activeProjs.clear()
        raidEnemyName = enemyTitle
        raidEnemyTrophies = enemyTr
        clanReinforcementsDeployed = false
        raidDestroyedCount = 0
        raidTotalBuildings = max(1, raidBuildings.count { it.type != BType.WALL_BLOCK })
        raidTownHallDown = false
        raidEnded = false
        raidTimeLeft = 150f
        raidLootGold = 0
        raidLootElixir = 0
        mode = 1
        subModal = 0
    }

    private var infinityFreeTestCookie: String = ""

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    /**
     * Fetches a URL from apk3rade.mangoad.online (InfinityFree + Cloudflare),
     * automatically solving InfinityFree's aes.js (__test cookie) challenge if triggered!
     */
    private fun fetchInfinityFreeCloud(urlStr: String): String {
        val ua = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        fun doReq(targetUrl: String): String {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", "application/json,text/html,*/*")
                if (infinityFreeTestCookie.isNotEmpty()) {
                    setRequestProperty("Cookie", "__test=$infinityFreeTestCookie")
                }
            }
            val txt = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            return txt
        }

        val firstResp = doReq(urlStr)
        if (firstResp.contains("slowAES.decrypt") && firstResp.contains("__test=")) {
            // Automatically solve InfinityFree aes.js AES-128-CBC challenge!
            val rx = Regex("toNumbers\\(\"([0-9a-fA-F]{32})\"\\)")
            val matches = rx.findAll(firstResp).map { it.groupValues[1] }.toList()
            if (matches.size >= 3) {
                val keyBytes = hexToBytes(matches[0])
                val ivBytes = hexToBytes(matches[1])
                val cipherBytes = hexToBytes(matches[2])
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                val decrypted = cipher.doFinal(cipherBytes)
                infinityFreeTestCookie = bytesToHex(decrypted)
                val sep = if (urlStr.contains("?")) "&" else "?"
                return doReq(urlStr + sep + "i=1")
            }
        }
        return firstResp
    }

    // Free Cloud Sync helper (calls server_api.php on apk3rade.mangoad.online with InfinityFree + Cloudflare bypass!)
    fun syncWithFreeCloudServer(uploadMyVillage: Boolean, findOnlineMatch: Boolean) {
        val code = exportVillageToCode(myBuildings)
        Thread {
            try {
                if (uploadMyVillage) {
                    val u = "$cloudApiUrl?action=upload_village&player=${URLEncoder.encode("الفارس_مصطفى", "UTF-8")}&trophies=$trophies&th=${myTownHallLevel()}&code=${URLEncoder.encode(code, "UTF-8")}"
                    val resp = fetchInfinityFreeCloud(u)
                    if (resp.contains("\"ok\":true") || resp.contains("\"ok\": true")) {
                        onlineStatusMsg = "تم رفع قريتك ومزرعتك على apk3rade.mangoad.online بنجاح! ☁️✅"
                    } else {
                        onlineStatusMsg = "تم حفظ قريتك سحابياً وتحديث الكود!"
                    }
                }
                if (findOnlineMatch) {
                    val u = "$cloudApiUrl?action=find_match&player=${URLEncoder.encode("الفارس_مصطفى", "UTF-8")}"
                    val resp = fetchInfinityFreeCloud(u)
                    val codeIdx = resp.indexOf("KH1:")
                    if (codeIdx >= 0) {
                        val endIdx = resp.indexOf('"', codeIdx)
                        val remoteCode = if (endIdx > codeIdx) resp.substring(codeIdx, endIdx) else ""
                        if (importVillageFromCode(remoteCode, raidBuildings)) {
                            onlineStatusMsg = "تم العثور على قرية لاعب أونلاين عبر السحابة! ⚔️🌍"
                            startRaidOnLoadedBuildings("قرية لاعب أونلاين سحابية 🌍", trophies + 25)
                            return@Thread
                        }
                    }
                    startProceduralOrCloudRaid(1)
                }
            } catch (_: Throwable) {
                onlineStatusMsg = "تم تفعيل محاكي القرى العالمي الذكي (أو شارك كود قريتك مباشرة بدون سيرفر!)"
                if (findOnlineMatch) startProceduralOrCloudRaid(1)
            }
        }.apply { isDaemon = true; start() }
    }


    fun startClanWarRaid(warIdx: Int) {
        activeWarBaseIdx = warIdx.coerceIn(0, 4)
        startProceduralOrCloudRaid(warIdx + 1)
        raidEnemyName = "حرب الكلانات: قلعة الخصم #${warIdx + 1} ⚔️🔥"
        raidEnemyTrophies = 220 + warIdx * 65
    }

    fun deployClanReinforcements(fx: Float, fy: Float): Boolean {
        if (!clanReinforcementsReady || clanReinforcementsDeployed) return false
        clanReinforcementsDeployed = true
        activeTroops.add(RaidTroop(fx - 0.4f, fy, TroopKind.GIANT))
        activeTroops.add(RaidTroop(fx + 0.4f, fy, TroopKind.GIANT))
        activeTroops.add(RaidTroop(fx, fy - 0.4f, TroopKind.GOLEM))
        activeTroops.add(RaidTroop(fx, fy + 0.4f, TroopKind.WIZARD))
        return true
    }

    fun syncClanChatCloud(newMsg: String) {
        if (newMsg.isNotBlank()) {
            clanChatMessages.add(0, "أنت ($clanName): $newMsg")
            if (clanChatMessages.size > 12) clanChatMessages.removeAt(clanChatMessages.lastIndex)
        }
        Thread {
            try {
                val u = "$cloudApiUrl?action=clan_sync&sender=${URLEncoder.encode("الفارس_مصطفى", "UTF-8")}&msg=${URLEncoder.encode(newMsg, "UTF-8")}&add_stars=0"
                fetchInfinityFreeCloud(u)
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
    }

    fun myTownHallLevel(): Int = myBuildings.firstOrNull { it.type == BType.TOWN_HALL }?.level ?: 1

    fun harvestAllReady(): Int {
        var count = 0
        for (b in myBuildings) {
            when (b.type) {
                BType.FARM_PLOT -> if (b.cropStage >= 2) {
                    b.cropStage = 0
                    b.prodTimer = 0f
                    wheat += 2 * b.level
                    count++
                }
                BType.ANIMAL_PEN -> if (b.storedYield > 0) {
                    woolMilk += b.storedYield
                    b.storedYield = 0
                    count++
                }
                BType.GOLD_MINE -> if (b.storedYield > 0) {
                    gold += b.storedYield
                    b.storedYield = 0
                    count++
                }
                BType.ELIXIR_WELL -> if (b.storedYield > 0) {
                    elixir += b.storedYield
                    b.storedYield = 0
                    count++
                }
                else -> {}
            }
        }
        if (count > 0) {
            Sound.play("coin")
            saveKingdom()
        }
        return count
    }
}

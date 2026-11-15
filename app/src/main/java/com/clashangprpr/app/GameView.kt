package com.clashangprpr.app

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.text.InputType
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.math.*
import kotlin.random.Random

/**
 * FARM WARS (حروب المزارع) - v5 Walls & Zero-to-Empire Progression Edition
 * - Starts from SCRATCH: Only Lv.1 Manor Hall + Wild Obstacles (0 pre-built buildings, 0 pre-trained troops!)
 * - Step-by-step Interactive Quest Guide (Quests 1..7) with rewards & pointer arrows
 * - Auto-Connecting 2.5D Isometric Walls (Corner Watchtowers, Arched Gates, / and \ directional segments,
 *   continuous finger-drag wall drawing, and 1-tap Auto-Surround Wall Ring!)
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    @Volatile private var running = false
    private var gameThread: Thread? = null
    // v5 prefs key so the game starts cleanly from scratch (Stage 0)
    private val prefs = context.getSharedPreferences("farm_wars_25d_save_v5", Context.MODE_PRIVATE)

    // Screen & Camera
    private var screenW = 1280f
    private var screenH = 720f
    private var camX = 0f
    private var camY = 0f
    private var camZoom = 1.0f

    // Isometric 14x14 Farm Grid aligned to the island clearing
    private val gridSize = 14
    private val tileW = 78f
    private val tileH = 39f

    // Dedicated Paints (bitmapPaint is locked at alpha=255 so sprites are NEVER transparent!)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Assets
    private val bitmaps = HashMap<String, Bitmap>()
    private var splashBg: Bitmap? = null
    private var islandBg: Bitmap? = null

    // Professional Loading Screen State
    private var isLoadingScreen = true
    private var loadingProgress = 0f
    private var loadingTipIndex = 0
    private val loadingTips = listOf(
        "نصيحة: تبدأ مزرعتك بقاعة واحدة وأرض برية؛ اتبع المهام لبناء حقولك وأسوارك وجيشك من الصفر!",
        "نصيحة: اسحب إصبعك في وضع بناء الأسوار لرسم حزام أسوار متصل مع أبراج ركنية وبوابات تلقائية!",
        "نصيحة: أزل صخور الزمرد وجذوع الأشجار البرية من أرضك للحصول على جواهر مجانية 💎!",
        "نصيحة: اطبخ مؤونة الجيش 🍗 في المطبخ من القمح واللحوم قبل تدريب الجنود أو شن الهجوم."
    )

    data class SplashEmber(var x: Float, var y: Float, var vx: Float, var vy: Float, var size: Float, var color: Int, var alpha: Int)
    private val splashEmbers = CopyOnWriteArrayList<SplashEmber>()

    // Core Player Resources (Starts modest from scratch!)
    private var gold = 650
    private var greenElixir = 450
    private var armyRations = 0
    private var gems = 45
    private var trophies = 0
    private var wheatCount = 3
    private var meatCount = 0
    private var breadCount = 0
    private var attackBuffPercent = 0
    private var currentQuestStep = 1 // 1..7 step-by-step progression quests

    private var playerName = "LordFarmer"
    private var guildName = "Emerald Harvest"
    private var serverUrl = "https://apk3rade.mangoad.online/server_api.php"
    private var ifCookie: String? = null
    private var statusBanner = ""
    private var statusBannerTimer = 0

    // Building Catalog
    enum class BType(
        val titleAr: String,
        val titleEn: String,
        val spriteKey: String,
        val footprint: Int,
        val baseCostGold: Int,
        val baseCostElixir: Int,
        val maxHp: Int,
        val unlockManorLv: Int,
        val isDefense: Boolean,
        val atkRange: Float,
        val atkDamage: Int,
        val atkCooldownMax: Int
    ) {
        MANOR_HALL("قاعة المزرعة الكبرى", "Manor Hall", "manor_lv1", 2, 900, 0, 2600, 1, false, 0f, 0, 0),
        WHEAT_FIELD("حقل القمح الذهبي", "Wheat Field", "wheat_field", 2, 150, 0, 750, 1, false, 0f, 0, 0),
        GREEN_ELIXIR("مضخة الإكسير الأخضر", "Bio-Elixir Pump", "green_elixir", 2, 180, 0, 850, 1, false, 0f, 0, 0),
        GOLD_SILO("صومعة الذهب", "Gold Silo", "gold_silo", 2, 0, 180, 950, 1, false, 0f, 0, 0),
        FIELD_KITCHEN("مطبخ مؤونة الجيش", "Field Kitchen", "field_kitchen", 2, 200, 100, 900, 1, false, 0f, 0, 0),
        ARMY_CAMP("معسكر تدريب المزارعين", "Army Camp", "army_camp", 2, 180, 180, 950, 1, false, 0f, 0, 0),
        CORN_CANNON("مدفع الذرة المدرع", "Corn Cannon", "corn_cannon", 1, 250, 0, 980, 1, true, 4.8f, 48, 35),
        STONE_WALL("سور الحصن المتصل", "Fortress Wall", "wall_lv1", 1, 25, 0, 1600, 1, false, 0f, 0, 0),
        RANCH_BARN("حظيرة الماشية واللحوم", "Ranch Barn", "ranch_barn", 2, 380, 180, 1050, 2, false, 0f, 0, 0),
        WINDMILL_BAKERY("طاحونة المخبز الآلي", "Windmill Bakery", "windmill_bakery", 2, 420, 220, 1100, 2, false, 0f, 0, 0),
        GUILD_HALL("قلعة تحالف المزارعين", "Guild Hall", "guild_hall", 2, 500, 300, 1400, 2, false, 0f, 0, 0),
        EMERALD_TOWER("برج الزمرد الأخضر", "Emerald Tower", "emerald_tower", 1, 700, 400, 1350, 3, true, 5.8f, 72, 30),
        HONEY_MORTAR("هاون قذائف العسل", "Honey Mortar", "honey_mortar", 1, 780, 450, 1450, 3, true, 6.5f, 90, 62)
    }

    data class PlacedBuilding(
        var id: Int,
        val type: BType,
        var gx: Int,
        var gy: Int,
        var level: Int = 1,
        var hp: Int = type.maxHp,
        var maxHp: Int = type.maxHp,
        var prodTimer: Int = 0,
        var readyCollect: Boolean = false,
        var atkCooldown: Int = 0,
        var upgradeTicksLeft: Int = 0,
        var upgradeTotalTicks: Int = 0,
        var recoilAnim: Float = 0f,
        var tapBounce: Float = 0f
    ) {
        fun effectiveSpriteKey(): String {
            return if (type == BType.MANOR_HALL) {
                when (level.coerceIn(1, 3)) {
                    1 -> "manor_lv1"
                    2 -> "manor_lv2"
                    else -> "manor_lv3"
                }
            } else {
                type.spriteKey
            }
        }
    }

    // Removable Wild Obstacles on the Untamed Island!
    enum class ObstacleType(val titleAr: String, val spriteKey: String, val clearCostGold: Int, val rewardGems: Int, val rewardElixir: Int) {
        CRYSTAL_ROCK("صخرة الزمرد البرية", "obs_rock", 40, 10, 100),
        HOLLOW_LOG("جذع البلوط القديم", "obs_log", 30, 8, 80),
        ANCIENT_WELL("البئر الحجرية المهجورة", "obs_well", 50, 15, 140)
    }

    data class WildObstacle(
        val id: Int,
        val type: ObstacleType,
        val gx: Int,
        val gy: Int,
        var clearTicksLeft: Int = 0
    )
    private val wildObstacles = CopyOnWriteArrayList<WildObstacle>()

    // 6 Original Non-Pixel 2.5D Farm Wars Units (Starts at ZERO trained units!)
    enum class UType(
        val titleAr: String,
        val spriteKey: String,
        val costElixir: Int,
        val costRations: Int,
        val unlockManorLv: Int,
        val maxHp: Int,
        val damage: Int,
        val speed: Float,
        val range: Float,
        val prefersWalls: Boolean,
        val prefersDefenses: Boolean
    ) {
        VANGUARD("حارس المذراة", "unit_vanguard", 45, 4, 1, 340, 36, 0.055f, 0.9f, false, false),
        RANGER("رامية البلوط", "unit_ranger", 65, 5, 1, 220, 44, 0.058f, 3.6f, false, false),
        BRUISER("عملاق الحظيرة", "unit_bruiser", 150, 12, 2, 1100, 65, 0.038f, 1.0f, false, true),
        SAPPER("مفجر اليقطين", "unit_sapper", 120, 8, 2, 230, 280, 0.075f, 0.85f, true, false),
        ALCHEMIST("حكيم الإكسير", "unit_alchemist", 210, 15, 3, 440, 98, 0.048f, 4.1f, false, false),
        MECH("آلي الحصاد", "unit_mech", 320, 24, 3, 2200, 120, 0.030f, 1.1f, false, true)
    }

    // Starts at 0 troops so the player builds their army from scratch!
    private val armyRoster = mutableMapOf(
        UType.VANGUARD to 0,
        UType.RANGER to 0,
        UType.BRUISER to 0,
        UType.SAPPER to 0,
        UType.ALCHEMIST to 0,
        UType.MECH to 0
    )

    enum class SpellType(val titleAr: String, val icon: String, val costElixir: Int, val color: Int) {
        PEPPER_RAGE("فلفل حار", "🌶️", 140, Color.parseColor("#FF6D00")),
        GREEN_NECTAR("إكسير الشفاء", "🧪", 140, Color.parseColor("#00E676")),
        CORN_BARRAGE("قصف الذرة", "🌽", 170, Color.parseColor("#FFD600"))
    }
    private val spellCounts = mutableMapOf(
        SpellType.PEPPER_RAGE to 0,
        SpellType.GREEN_NECTAR to 0,
        SpellType.CORN_BARRAGE to 0
    )
    private var selectedSpell: SpellType? = null

    data class ActiveSpellZone(val type: SpellType, val gx: Float, val gy: Float, var ticksLeft: Int, val radius: Float = 2.6f)
    private val activeSpells = CopyOnWriteArrayList<ActiveSpellZone>()

    data class VillageWorker(
        var gx: Float,
        var gy: Float,
        var targetGx: Float,
        var targetGy: Float,
        var carryType: Int,
        var animPhase: Float,
        var waitTicks: Int = 0
    )
    private val villageWorkers = CopyOnWriteArrayList<VillageWorker>()

    data class AmbientParticle(
        var sx: Float,
        var sy: Float,
        var vx: Float,
        var vy: Float,
        var radius: Float,
        var color: Int,
        var life: Int,
        var maxLife: Int
    )
    private val ambientParticles = CopyOnWriteArrayList<AmbientParticle>()

    data class ActiveUnit(
        val type: UType,
        var gx: Float,
        var gy: Float,
        var hp: Int,
        val maxHp: Int,
        var cooldown: Int = 0,
        var animTick: Float = 0f,
        var attackSwing: Float = 0f,
        var isGuildAlly: Boolean = false
    )

    data class Projectile(
        var startGx: Float,
        var startGy: Float,
        var curGx: Float,
        var curGy: Float,
        val targetGx: Float,
        val targetGy: Float,
        val color: Int,
        val splash: Boolean,
        val damage: Int,
        val fromDefense: Boolean,
        var progress: Float = 0f
    )

    data class FloatText(var text: String, var sx: Float, var sy: Float, val color: Int, var life: Int = 55)

    private val myBuildings = CopyOnWriteArrayList<PlacedBuilding>()
    private val raidBuildings = CopyOnWriteArrayList<PlacedBuilding>()
    private val activeUnits = CopyOnWriteArrayList<ActiveUnit>()
    private val projectiles = CopyOnWriteArrayList<Projectile>()
    private val floatTexts = CopyOnWriteArrayList<FloatText>()

    private var totalBuilders = 2

    private var inRaidMode = false
    private var raidTargetName = "مزرعة الوادي الحصينة"
    private var raidLootGold = 950
    private var raidLootElixir = 850
    private var raidLootRations = 45
    private var raidTicksLeft = 180 * 30
    private var raidStars = 0
    private var raidDestructionPct = 0
    private var raidTotalStartBuildings = 1
    private var guildReinforcementsCalled = false
    private var hasCompletedFirstRaid = false

    private var selectedUnitType: UType = UType.VANGUARD
    private var selectedBuilding: PlacedBuilding? = null
    private var buildModeType: BType? = null
    private var moveBuildingMode = false

    enum class ModalType { NONE, BUILD_SHOP, KITCHEN_RATIONS, ARMY_TRAIN, TRUCK_ORDERS, GUILD_WAR, GEM_SHOP }
    private var activeModal = ModalType.NONE

    data class FarmContract(
        val title: String,
        val reqWheat: Int,
        val reqMeat: Int,
        val reqBread: Int,
        val rewardGold: Int,
        val rewardElixir: Int,
        val rewardRations: Int
    )
    private val contracts = CopyOnWriteArrayList<FarmContract>()

    private val guildChat = CopyOnWriteArrayList<String>()
    private var warOurStars = 9
    private var warEnemyStars = 7
    private var warAttacksLeft = 2
    private var animTickGlobal = 0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDraggingMap = false

    init {
        holder.addCallback(this)
        isFocusable = true
        bitmapPaint.alpha = 255
        loadAllAssets()
        initSplashEmbers()
        loadOrInitVillage()
        initWorkers()
        resetContracts()
        guildChat.add("الزعيم: ابدأ بتعمير مزرعتك وبناء الأسوار المتصلة حول القاعة!")
        guildChat.add("القائد سيف: أزل الصخور البرية للحصول على الجواهر واطبخ مؤونة الجيش 🍗!")
    }

    private fun initSplashEmbers() {
        splashEmbers.clear()
        for (i in 0 until 26) {
            splashEmbers.add(
                SplashEmber(
                    x = Random.nextFloat() * 1280f,
                    y = Random.nextFloat() * 720f,
                    vx = (Random.nextFloat() - 0.5f) * 1.3f,
                    vy = -1.2f - Random.nextFloat() * 2.0f,
                    size = 4f + Random.nextFloat() * 6f,
                    color = if (i % 2 == 0) Color.parseColor("#00E676") else Color.parseColor("#FFD54F"),
                    alpha = 150 + Random.nextInt(105)
                )
            )
        }
    }

    private fun loadAllAssets() {
        val am = context.assets
        try {
            am.open("iso/farm_wars_splash.jpg").use { splashBg = BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {}

        try {
            am.open("iso/iso_island_bg.jpg").use { islandBg = BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {}

        val files = listOf(
            "manor_lv1", "manor_lv2", "manor_lv3",
            "green_elixir", "field_kitchen", "gold_silo",
            "wheat_field", "ranch_barn", "army_camp",
            "corn_cannon", "emerald_tower", "guild_hall",
            "windmill_bakery", "honey_mortar", "stone_wall",
            "wall_lv1", "wall_lv1_flip", "wall_corner_lv1",
            "wall_gate", "wall_gate_flip",
            "wall_lv2", "wall_lv2_flip", "wall_corner_lv2",
            "wall_lv3", "wall_lv3_flip",
            "obs_rock", "obs_log", "obs_well",
            "unit_vanguard", "unit_ranger", "unit_bruiser",
            "unit_sapper", "unit_alchemist", "unit_mech"
        )
        for (name in files) {
            try {
                am.open("iso/$name.png").use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) bitmaps[name] = bmp
                }
            } catch (_: Exception) {}
        }
    }

    fun getManorHallLevel(): Int {
        val mh = myBuildings.firstOrNull { it.type == BType.MANOR_HALL }
        return (mh?.level ?: 1).coerceIn(1, 3)
    }

    fun getRequiredRationsForArmy(): Int {
        var total = 0
        for ((u, cnt) in armyRoster) {
            total += cnt * (u.costRations / 2).coerceAtLeast(2)
        }
        return total.coerceAtLeast(8)
    }

    private fun loadOrInitVillage() {
        gold = prefs.getInt("gold", 650)
        greenElixir = prefs.getInt("greenElixir", 450)
        armyRations = prefs.getInt("armyRations", 0)
        gems = prefs.getInt("gems", 45)
        trophies = prefs.getInt("trophies", 0)
        wheatCount = prefs.getInt("wheat", 3)
        meatCount = prefs.getInt("meat", 0)
        breadCount = prefs.getInt("bread", 0)
        totalBuilders = prefs.getInt("builders", 2)
        currentQuestStep = prefs.getInt("questStep", 1)
        playerName = prefs.getString("playerName", "LordFarmer") ?: "LordFarmer"
        guildName = prefs.getString("guildName", "Emerald Harvest") ?: "Emerald Harvest"

        for (u in UType.values()) {
            armyRoster[u] = prefs.getInt("unit_${u.name}", 0)
        }

        val savedJson = prefs.getString("buildings_json", null)
        myBuildings.clear()
        if (!savedJson.isNullOrEmpty()) {
            try {
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val bType = BType.valueOf(o.getString("type"))
                    val lv = o.getInt("level").coerceIn(1, 3)
                    val maxH = (bType.maxHp * (1.0 + (lv - 1) * 0.45)).toInt()
                    myBuildings.add(
                        PlacedBuilding(
                            id = o.getInt("id"),
                            type = bType,
                            gx = o.getInt("gx"),
                            gy = o.getInt("gy"),
                            level = lv,
                            hp = maxH,
                            maxHp = maxH
                        )
                    )
                }
            } catch (_: Exception) {}
        }

        val savedObs = prefs.getString("obstacles_json", null)
        wildObstacles.clear()
        if (!savedObs.isNullOrEmpty()) {
            try {
                val arr = JSONArray(savedObs)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val oType = ObstacleType.valueOf(o.getString("type"))
                    wildObstacles.add(WildObstacle(o.getInt("id"), oType, o.getInt("gx"), o.getInt("gy")))
                }
            } catch (_: Exception) {}
        }

        if (myBuildings.isEmpty()) {
            resetToZeroScratchStart()
        }
    }

    private fun resetToZeroScratchStart() {
        myBuildings.clear()
        wildObstacles.clear()
        for (u in UType.values()) armyRoster[u] = 0
        for (s in SpellType.values()) spellCounts[s] = 0
        gold = 650
        greenElixir = 450
        armyRations = 0
        gems = 45
        trophies = 0
        wheatCount = 3
        meatCount = 0
        breadCount = 0
        currentQuestStep = 1
        hasCompletedFirstRaid = false

        // Start with ONLY the Lv.1 Manor Hall in the center (6, 6)!
        myBuildings.add(PlacedBuilding(1, BType.MANOR_HALL, 6, 6, 1, BType.MANOR_HALL.maxHp, BType.MANOR_HALL.maxHp))

        // Populate 8 Wild Obstacles around the untamed island clearing
        wildObstacles.add(WildObstacle(1, ObstacleType.CRYSTAL_ROCK, 2, 3))
        wildObstacles.add(WildObstacle(2, ObstacleType.HOLLOW_LOG, 10, 2))
        wildObstacles.add(WildObstacle(3, ObstacleType.ANCIENT_WELL, 2, 10))
        wildObstacles.add(WildObstacle(4, ObstacleType.CRYSTAL_ROCK, 11, 9))
        wildObstacles.add(WildObstacle(5, ObstacleType.HOLLOW_LOG, 3, 7))
        wildObstacles.add(WildObstacle(6, ObstacleType.CRYSTAL_ROCK, 9, 11))
        wildObstacles.add(WildObstacle(7, ObstacleType.HOLLOW_LOG, 7, 2))
        wildObstacles.add(WildObstacle(8, ObstacleType.ANCIENT_WELL, 11, 5))
        saveVillage()
    }

    // Optional instant fortress builder for testing a full walled castle
    private fun buildSampleWalledFortress() {
        myBuildings.clear()
        wildObstacles.clear()
        var idGen = 1
        fun addB(t: BType, x: Int, y: Int, lv: Int = 2) {
            val mHp = (t.maxHp * (1.0 + (lv - 1) * 0.45)).toInt()
            myBuildings.add(PlacedBuilding(idGen++, t, x, y, lv, mHp, mHp))
        }
        // Center Manor Hall Lv.2 inside a 6x6 Connected Stone/Wood Wall Perimeter (gx 4..9, gy 4..9)
        addB(BType.MANOR_HALL, 6, 6, 2)
        for (x in 4..9) {
            addB(BType.STONE_WALL, x, 4, 2)
            addB(BType.STONE_WALL, x, 9, 2)
        }
        for (y in 5..8) {
            addB(BType.STONE_WALL, 4, y, 2)
            addB(BType.STONE_WALL, 9, y, 2)
        }
        // Defenses & Economy outside/around the walled keep
        addB(BType.CORN_CANNON, 5, 5, 2)
        addB(BType.CORN_CANNON, 8, 5, 2)
        addB(BType.GOLD_SILO, 6, 1, 2)
        addB(BType.FIELD_KITCHEN, 1, 5, 2)
        addB(BType.GREEN_ELIXIR, 10, 5, 2)
        addB(BType.WHEAT_FIELD, 1, 9, 2)
        addB(BType.WHEAT_FIELD, 6, 11, 2)
        addB(BType.ARMY_CAMP, 10, 9, 2)
        addB(BType.RANCH_BARN, 2, 2, 2)
        addB(BType.GUILD_HALL, 10, 2, 2)

        armyRoster[UType.VANGUARD] = 6
        armyRoster[UType.RANGER] = 5
        armyRoster[UType.BRUISER] = 2
        armyRoster[UType.SAPPER] = 2
        gold = 2800
        greenElixir = 2200
        armyRations = 110
        currentQuestStep = 8
        saveVillage()
        showBanner("🏰 تم تشييد القلعة المسوّرة بالكامل مع الأسوار المتصلة والأبراج الركنية!")
    }

    private fun autoSurroundManorWithWalls() {
        val mh = myBuildings.firstOrNull { it.type == BType.MANOR_HALL } ?: return
        val minX = (mh.gx - 1).coerceAtLeast(1)
        val maxX = (mh.gx + mh.type.footprint).coerceAtMost(gridSize - 2)
        val minY = (mh.gy - 1).coerceAtLeast(1)
        val maxY = (mh.gy + mh.type.footprint).coerceAtMost(gridSize - 2)

        var placedCount = 0
        var nextId = (myBuildings.maxOfOrNull { it.id } ?: 0) + 1
        for (x in minX..maxX) {
            for (y in listOf(minY, maxY)) {
                if (isAreaFree(x, y, 1, null) && gold >= BType.STONE_WALL.baseCostGold) {
                    gold -= BType.STONE_WALL.baseCostGold
                    myBuildings.add(PlacedBuilding(nextId++, BType.STONE_WALL, x, y, 1, BType.STONE_WALL.maxHp, BType.STONE_WALL.maxHp, tapBounce = 1f))
                    placedCount++
                }
            }
        }
        for (y in (minY + 1) until maxY) {
            for (x in listOf(minX, maxX)) {
                if (isAreaFree(x, y, 1, null) && gold >= BType.STONE_WALL.baseCostGold) {
                    gold -= BType.STONE_WALL.baseCostGold
                    myBuildings.add(PlacedBuilding(nextId++, BType.STONE_WALL, x, y, 1, BType.STONE_WALL.maxHp, BType.STONE_WALL.maxHp, tapBounce = 1f))
                    placedCount++
                }
            }
        }
        if (placedCount > 0) {
            saveVillage()
            checkQuestProgression()
            showBanner("🧱 تم بناء حزام أسوار متصل ($placedCount قطعة) مع أبراج ركنية وبوابات حول القاعة!")
        } else {
            showBanner("ℹ️ القاعة محاطة بالأسوار بالفعل أو الذهب غير كافٍ!")
        }
    }

    private fun initWorkers() {
        villageWorkers.clear()
        for (i in 0 until 2) {
            villageWorkers.add(
                VillageWorker(
                    gx = 5.5f + i * 1.5f,
                    gy = 6.5f,
                    targetGx = 6f,
                    targetGy = 6f,
                    carryType = i % 3,
                    animPhase = i * 1.5f
                )
            )
        }
    }

    private fun saveVillage() {
        try {
            val arr = JSONArray()
            for (b in myBuildings) {
                val o = JSONObject()
                o.put("id", b.id)
                o.put("type", b.type.name)
                o.put("gx", b.gx)
                o.put("gy", b.gy)
                o.put("level", b.level.coerceIn(1, 3))
                arr.put(o)
            }
            val obsArr = JSONArray()
            for (ob in wildObstacles) {
                val o = JSONObject()
                o.put("id", ob.id)
                o.put("type", ob.type.name)
                o.put("gx", ob.gx)
                o.put("gy", ob.gy)
                obsArr.put(o)
            }
            val ed = prefs.edit()
                .putInt("gold", gold)
                .putInt("greenElixir", greenElixir)
                .putInt("armyRations", armyRations)
                .putInt("gems", gems)
                .putInt("trophies", trophies)
                .putInt("wheat", wheatCount)
                .putInt("meat", meatCount)
                .putInt("bread", breadCount)
                .putInt("builders", totalBuilders)
                .putInt("questStep", currentQuestStep)
                .putString("playerName", playerName)
                .putString("guildName", guildName)
                .putString("buildings_json", arr.toString())
                .putString("obstacles_json", obsArr.toString())
            for ((u, cnt) in armyRoster) {
                ed.putInt("unit_${u.name}", cnt)
            }
            ed.apply()
        } catch (_: Exception) {}
    }

    // Step-by-Step Quest Progression Checker
    private fun checkQuestProgression() {
        var advanced = false
        if (currentQuestStep == 1 && myBuildings.any { it.type == BType.WHEAT_FIELD }) {
            currentQuestStep = 2
            gold += 300
            wheatCount += 4
            showBanner("🎉 اكتملت المهمة 1! مكافأة: +300 ذهب 🪙 و +4 قمح 🌾")
            advanced = true
        }
        if (currentQuestStep == 2 && myBuildings.any { it.type == BType.GREEN_ELIXIR }) {
            currentQuestStep = 3
            greenElixir += 350
            gold += 250
            showBanner("🎉 اكتملت المهمة 2! مكافأة: +350 إكسير أخضر 🧪 و +250 ذهب 🪙")
            advanced = true
        }
        if (currentQuestStep == 3 && myBuildings.any { it.type == BType.FIELD_KITCHEN } && armyRations > 0) {
            currentQuestStep = 4
            armyRations += 45
            gold += 350
            showBanner("🎉 اكتملت المهمة 3! مكافأة: +45 مؤونة جيش 🍗 و +350 ذهب 🪙")
            advanced = true
        }
        if (currentQuestStep == 4 && myBuildings.count { it.type == BType.STONE_WALL } >= 6) {
            currentQuestStep = 5
            gold += 450
            gems += 15
            showBanner("🎉 اكتملت المهمة 4 (تسوير الحصن)! مكافأة: +450 ذهب 🪙 و +15 جوهرة 💎")
            advanced = true
        }
        if (currentQuestStep == 5 && myBuildings.any { it.type == BType.CORN_CANNON }) {
            currentQuestStep = 6
            greenElixir += 400
            showBanner("🎉 اكتملت المهمة 5! مكافأة: +400 إكسير أخضر 🧪")
            advanced = true
        }
        if (currentQuestStep == 6 && myBuildings.any { it.type == BType.ARMY_CAMP } && armyRoster.values.sum() >= 3) {
            currentQuestStep = 7
            armyRations += 50
            gems += 20
            showBanner("🎉 اكتملت المهمة 6 (تجهيز الجيش)! مكافأة: +50 مؤونة 🍗 و +20 جوهرة 💎")
            advanced = true
        }
        if (currentQuestStep == 7 && (hasCompletedFirstRaid || getManorHallLevel() >= 2)) {
            currentQuestStep = 8
            gold += 1200
            greenElixir += 800
            gems += 30
            showBanner("🏆 أكملت جميع مهام التأسيس! +1200 ذهب 🪙 و +800 إكسير 🧪 و +30 جوهرة 💎")
            advanced = true
        }
        if (advanced) saveVillage()
    }

    private fun getCurrentQuestDescription(): String {
        return when (currentQuestStep) {
            1 -> "📜 مهمة 1/7: افتح [🏗️ البناء] وابنِ أول (حقل قمح ذهبي 🌾) لزراعة الغذاء"
            2 -> "📜 مهمة 2/7: ابنِ (مضخة الإكسير الأخضر 🧪) لاستخراج الطاقة الحيوية"
            3 -> "📜 مهمة 3/7: ابنِ (مطبخ مؤونة الجيش 🍗) واطبخ أول وجبة مؤونة من القمح"
            4 -> "📜 مهمة 4/7: حصّن قاعتك! شيّد 6 قطع من (سور الحصن المتصل 🧱) حول القاعة"
            5 -> "📜 مهمة 5/7: ابنِ (مدفع الذرة المدرع 🌽) لحماية مزرعتك"
            6 -> "📜 مهمة 6/7: ابنِ (معسكر التدريب 🛡️) ودرّب أول 3 جنود بيدك!"
            7 -> "📜 مهمة 7/7: شنّ أول [⚔️ هجوم] على الأعداء أو رقِّ قاعة المزرعة للمستوى 2!"
            else -> "👑 مزرعتك مزدهرة! واصل ترقية الأسوار والأبراج وخض حروب التحالفات"
        }
    }

    private fun resetContracts() {
        contracts.clear()
        contracts.add(FarmContract("عقد تموين الحامية الشرقية", 3, 0, 1, 450, 380, 35))
        contracts.add(FarmContract("عقد وليمة فرسان المزرعة", 4, 2, 1, 780, 640, 65))
        contracts.add(FarmContract("شحنة قلعة الزمرد الكبرى", 5, 3, 2, 1200, 950, 95))
    }

    private fun busyBuildersCount(): Int = myBuildings.count { it.upgradeTicksLeft > 0 } + wildObstacles.count { it.clearTicksLeft > 0 }

    private fun gridOriginX(): Float = screenW * 0.50f + camX
    private fun gridOriginY(): Float = screenH * 0.48f - (gridSize * tileH * 0.5f * camZoom) + camY

    private fun isoToScreenX(gx: Float, gy: Float): Float {
        return gridOriginX() + (gx - gy) * (tileW * 0.5f) * camZoom
    }

    private fun isoToScreenY(gx: Float, gy: Float): Float {
        return gridOriginY() + (gx + gy) * (tileH * 0.5f) * camZoom
    }

    private fun screenToIso(sx: Float, sy: Float): Pair<Float, Float> {
        val dx = (sx - gridOriginX()) / (tileW * 0.5f * camZoom)
        val dy = (sy - gridOriginY()) / (tileH * 0.5f * camZoom)
        val gx = (dy + dx) * 0.5f
        val gy = (dy - dx) * 0.5f
        return Pair(gx, gy)
    }

    private fun clampCameraToIsland() {
        val bgScale = 1.28f * camZoom
        val maxPanX = ((screenW * bgScale - screenW) * 0.5f).coerceAtLeast(0f)
        val maxPanY = ((screenH * bgScale - screenH) * 0.5f).coerceAtLeast(0f)
        camX = camX.coerceIn(-maxPanX, maxPanX)
        camY = camY.coerceIn(-maxPanY, maxPanY)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenW = width.toFloat().coerceAtLeast(800f)
        screenH = height.toFloat().coerceAtLeast(480f)
        clampCameraToIsland()
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat().coerceAtLeast(800f)
        screenH = height.toFloat().coerceAtLeast(480f)
        clampCameraToIsland()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

    fun resume() {
        if (!running) {
            running = true
            gameThread = Thread(this, "FarmWars25DLoop").also { it.start() }
        }
    }

    fun pause() {
        running = false
        saveVillage()
        try {
            gameThread?.join(400)
        } catch (_: Exception) {}
    }

    override fun run() {
        while (running) {
            val start = System.currentTimeMillis()
            updateGame()
            drawGame()
            val elapsed = System.currentTimeMillis() - start
            val sleepMs = (33L - elapsed).coerceAtLeast(4L)
            try {
                Thread.sleep(sleepMs)
            } catch (_: Exception) {}
        }
    }

    // ==================== GAME UPDATE LOOP ====================
    private fun updateGame() {
        animTickGlobal += 1f

        if (isLoadingScreen) {
            if (loadingProgress < 100f) {
                loadingProgress = (loadingProgress + 1.6f).coerceAtMost(100f)
                if (loadingProgress.toInt() % 28 == 0) {
                    loadingTipIndex = (loadingTipIndex + 1) % loadingTips.size
                }
            }
            for (e in splashEmbers) {
                e.x += e.vx
                e.y += e.vy
                if (e.y < -20f) {
                    e.y = screenH + 10f
                    e.x = Random.nextFloat() * screenW
                }
            }
            return
        }

        if (statusBannerTimer > 0) statusBannerTimer--

        val ftIter = floatTexts.iterator()
        while (ftIter.hasNext()) {
            val ft = ftIter.next()
            ft.sy -= 1.3f
            ft.life--
            if (ft.life <= 0) floatTexts.remove(ft)
        }

        val apIter = ambientParticles.iterator()
        while (apIter.hasNext()) {
            val p = apIter.next()
            p.sx += p.vx
            p.sy += p.vy
            p.life--
            if (p.life <= 0) ambientParticles.remove(p)
        }

        if (!inRaidMode) {
            // Update Wild Obstacle Clearing
            val obsIter = wildObstacles.iterator()
            while (obsIter.hasNext()) {
                val ob = obsIter.next()
                if (ob.clearTicksLeft > 0) {
                    ob.clearTicksLeft--
                    if (ob.clearTicksLeft == 0) {
                        gems += ob.type.rewardGems
                        greenElixir += ob.type.rewardElixir
                        val sx = isoToScreenX(ob.gx.toFloat(), ob.gy.toFloat())
                        val sy = isoToScreenY(ob.gx.toFloat(), ob.gy.toFloat())
                        floatTexts.add(FloatText("💎 +${ob.type.rewardGems} جوهرة | 🧪 +${ob.type.rewardElixir}", sx, sy - 30f, Color.parseColor("#00E676")))
                        wildObstacles.remove(ob)
                        saveVillage()
                    }
                }
            }

            for (b in myBuildings) {
                if (b.tapBounce > 0f) b.tapBounce = (b.tapBounce - 0.08f).coerceAtLeast(0f)

                if (b.upgradeTicksLeft > 0) {
                    b.upgradeTicksLeft--
                    if (b.upgradeTicksLeft == 0) {
                        b.level = (b.level + 1).coerceAtMost(3)
                        b.maxHp = (b.type.maxHp * (1.0 + (b.level - 1) * 0.45)).toInt()
                        b.hp = b.maxHp
                        b.tapBounce = 1.0f
                        val sx = isoToScreenX(b.gx.toFloat(), b.gy.toFloat())
                        val sy = isoToScreenY(b.gx.toFloat(), b.gy.toFloat())
                        floatTexts.add(FloatText("⭐ تمت الترقية للمستوى ${b.level}!", sx, sy - 50f, Color.parseColor("#00E676")))
                        checkQuestProgression()
                        saveVillage()
                    }
                    continue
                }

                if (b.type in listOf(BType.WHEAT_FIELD, BType.RANCH_BARN, BType.WINDMILL_BAKERY, BType.GREEN_ELIXIR, BType.GOLD_SILO, BType.FIELD_KITCHEN)) {
                    if (!b.readyCollect) {
                        b.prodTimer++
                        val needed = (200 - b.level * 35).coerceAtLeast(85)
                        if (b.prodTimer >= needed) {
                            b.readyCollect = true
                            b.prodTimer = 0
                        }
                    }
                }

                if (animTickGlobal.toInt() % 18 == (b.id % 18)) {
                    val sx = isoToScreenX(b.gx + 0.5f, b.gy + 0.5f)
                    val sy = isoToScreenY(b.gx + 0.5f, b.gy + 0.5f)
                    if (b.type == BType.MANOR_HALL || b.type == BType.FIELD_KITCHEN) {
                        ambientParticles.add(
                            AmbientParticle(
                                sx = sx + (Random.nextFloat() - 0.5f) * 14f,
                                sy = sy - 58f * camZoom,
                                vx = (Random.nextFloat() - 0.3f) * 0.5f,
                                vy = -0.9f - Random.nextFloat() * 0.4f,
                                radius = 5f + Random.nextFloat() * 4f,
                                color = Color.argb(140, 240, 240, 245),
                                life = 36,
                                maxLife = 36
                            )
                        )
                    } else if (b.type == BType.GREEN_ELIXIR || b.type == BType.EMERALD_TOWER) {
                        ambientParticles.add(
                            AmbientParticle(
                                sx = sx + (Random.nextFloat() - 0.5f) * 18f,
                                sy = sy - 30f * camZoom,
                                vx = (Random.nextFloat() - 0.5f) * 0.4f,
                                vy = -1.1f - Random.nextFloat() * 0.5f,
                                radius = 3.5f + Random.nextFloat() * 3.5f,
                                color = Color.parseColor("#00E676"),
                                life = 30,
                                maxLife = 30
                            )
                        )
                    }
                }
            }

            for (w in villageWorkers) {
                w.animPhase += 0.24f
                if (w.waitTicks > 0) {
                    w.waitTicks--
                    continue
                }
                val dx = w.targetGx - w.gx
                val dy = w.targetGy - w.gy
                val dist = hypot(dx, dy)
                if (dist < 0.25f) {
                    w.waitTicks = 40 + Random.nextInt(45)
                    val targetB = myBuildings.randomOrNull()
                    if (targetB != null) {
                        w.targetGx = targetB.gx.toFloat() + 0.5f
                        w.targetGy = targetB.gy.toFloat() + 0.5f
                        w.carryType = (w.carryType + 1) % 3
                    }
                } else {
                    w.gx += (dx / dist) * 0.035f
                    w.gy += (dy / dist) * 0.035f
                }
            }
        } else {
            updateRaidBattle()
        }
    }

    // ==================== RAID BATTLE LOGIC ====================
    private fun startRaidBattle(isGuildWar: Boolean = false) {
        val totalUnits = armyRoster.values.sum()
        if (totalUnits <= 0) {
            showBanner("⚠️ جيشك فارغ (0 جنود)! ابنِ معسكر التدريب ودرّب قواتك أولاً!")
            activeModal = ModalType.ARMY_TRAIN
            return
        }
        val neededRations = getRequiredRationsForArmy()
        if (armyRations < neededRations) {
            showBanner("⚠️ تحتاج إلى $neededRations مؤونة جيش 🍗 للهجوم! اطبخ وجبات في مطبخ الجيش أولاً!")
            activeModal = ModalType.KITCHEN_RATIONS
            return
        }

        armyRations -= neededRations
        saveVillage()

        inRaidMode = true
        activeModal = ModalType.NONE
        selectedBuilding = null
        buildModeType = null
        activeUnits.clear()
        projectiles.clear()
        activeSpells.clear()
        raidBuildings.clear()
        guildReinforcementsCalled = false
        raidTicksLeft = 180 * 30
        raidStars = 0
        raidDestructionPct = 0

        val enemyNames = if (isGuildWar) {
            listOf("قلعة التحالف الخصم #1", "حصن المزارع الحديدية #2", "مملكة الزمرد المعادية #3")
        } else {
            listOf("مزرعة الوادي الأخضر", "حصن السنابل الذهبية", "قلعة التلال المنيعة", "مزرعة البارون المدرعة")
        }
        raidTargetName = enemyNames.random()
        val enemyTier = getManorHallLevel().coerceIn(1, 3)
        raidLootGold = 850 + enemyTier * 450 + Random.nextInt(350)
        raidLootElixir = 800 + enemyTier * 420 + Random.nextInt(350)
        raidLootRations = 40 + enemyTier * 20

        var idG = 100
        fun addRB(t: BType, x: Int, y: Int, lv: Int) {
            val cLv = lv.coerceIn(1, 3)
            val mHp = (t.maxHp * (1.0 + (cLv - 1) * 0.40)).toInt()
            raidBuildings.add(PlacedBuilding(idG++, t, x, y, cLv, mHp, mHp))
        }

        // Enemy fortress with connected wall ring around its Manor Hall!
        addRB(BType.MANOR_HALL, 6, 6, enemyTier)
        for (wx in 4..9) {
            addRB(BType.STONE_WALL, wx, 4, enemyTier)
            addRB(BType.STONE_WALL, wx, 9, enemyTier)
        }
        for (wy in 5..8) {
            addRB(BType.STONE_WALL, 4, wy, enemyTier)
            addRB(BType.STONE_WALL, 9, wy, enemyTier)
        }
        addRB(BType.CORN_CANNON, 5, 5, enemyTier)
        addRB(BType.CORN_CANNON, 8, 5, enemyTier)
        addRB(BType.GOLD_SILO, 6, 1, enemyTier)
        addRB(BType.GREEN_ELIXIR, 10, 6, enemyTier)
        addRB(BType.FIELD_KITCHEN, 2, 6, enemyTier)
        if (enemyTier >= 2) {
            addRB(BType.RANCH_BARN, 2, 9, enemyTier)
            addRB(BType.WINDMILL_BAKERY, 10, 9, enemyTier)
        }
        if (enemyTier >= 3) {
            addRB(BType.EMERALD_TOWER, 6, 8, 3)
            addRB(BType.HONEY_MORTAR, 7, 5, 3)
        }
        raidTotalStartBuildings = raidBuildings.count { it.type != BType.STONE_WALL }.coerceAtLeast(1)
        showBanner("⚔️ انقر على أطراف الجزيرة الخضراء لإنزال جيشك وكسر أسوار العدو!")
    }

    private fun updateRaidBattle() {
        raidTicksLeft--
        if (raidTicksLeft <= 0) {
            endRaidBattle()
            return
        }

        val spIter = activeSpells.iterator()
        while (spIter.hasNext()) {
            val sp = spIter.next()
            sp.ticksLeft--
            if (sp.type == SpellType.GREEN_NECTAR && sp.ticksLeft % 15 == 0) {
                for (u in activeUnits) {
                    if (hypot(u.gx - sp.gx, u.gy - sp.gy) <= sp.radius) {
                        u.hp = (u.hp + 42).coerceAtMost(u.maxHp)
                    }
                }
            }
            if (sp.ticksLeft <= 0) activeSpells.remove(sp)
        }

        val uIter = activeUnits.iterator()
        while (uIter.hasNext()) {
            val u = uIter.next()
            u.animTick += 1f
            if (u.attackSwing > 0f) u.attackSwing = (u.attackSwing - 0.12f).coerceAtLeast(0f)
            if (u.hp <= 0) {
                activeUnits.remove(u)
                continue
            }

            val inRage = activeSpells.any { it.type == SpellType.PEPPER_RAGE && hypot(u.gx - it.gx, u.gy - it.gy) <= it.radius }
            val speedMult = if (inRage) 1.55f else 1.0f
            val dmgMult = (if (inRage) 1.5f else 1.0f) * (1.0f + attackBuffPercent / 100f)

            val target = findBestTargetForUnit(u)
            if (target != null) {
                val tx = target.gx + target.type.footprint * 0.5f
                val ty = target.gy + target.type.footprint * 0.5f
                val dist = hypot(tx - u.gx, ty - u.gy)
                if (dist <= u.type.range) {
                    if (u.cooldown <= 0) {
                        val finalDmg = (u.type.damage * dmgMult).toInt()
                        u.attackSwing = 1.0f
                        if (u.type == UType.SAPPER) {
                            target.hp -= finalDmg
                            u.hp = 0
                            checkBuildingDestroyed(target)
                        } else if (u.type.range > 1.8f) {
                            projectiles.add(
                                Projectile(
                                    u.gx, u.gy, u.gx, u.gy, tx, ty,
                                    Color.parseColor("#00E676"),
                                    u.type == UType.ALCHEMIST,
                                    finalDmg,
                                    false
                                )
                            )
                        } else {
                            target.hp -= finalDmg
                            checkBuildingDestroyed(target)
                        }
                        u.cooldown = 25
                    } else {
                        u.cooldown--
                    }
                } else {
                    val ang = atan2(ty - u.gy, tx - u.gx)
                    u.gx += cos(ang) * u.type.speed * speedMult
                    u.gy += sin(ang) * u.type.speed * speedMult
                }
            }
        }

        for (b in raidBuildings) {
            if (b.recoilAnim > 0f) b.recoilAnim = (b.recoilAnim - 0.1f).coerceAtLeast(0f)
            if (!b.type.isDefense || b.hp <= 0) continue
            if (b.atkCooldown > 0) {
                b.atkCooldown--
                continue
            }
            val bx = b.gx + 0.5f
            val by = b.gy + 0.5f
            val nearestU = activeUnits.minByOrNull { hypot(it.gx - bx, it.gy - by) }
            if (nearestU != null) {
                val d = hypot(nearestU.gx - bx, nearestU.gy - by)
                if (d <= b.type.atkRange) {
                    val dmg = (b.type.atkDamage * (1.0 + (b.level - 1) * 0.35)).toInt()
                    b.recoilAnim = 1.0f
                    projectiles.add(
                        Projectile(
                            bx, by, bx, by, nearestU.gx, nearestU.gy,
                            if (b.type == BType.EMERALD_TOWER) Color.parseColor("#00E676") else Color.parseColor("#FFD54F"),
                            b.type == BType.HONEY_MORTAR,
                            dmg,
                            true
                        )
                    )
                    b.atkCooldown = b.type.atkCooldownMax
                }
            }
        }

        val pIter = projectiles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.progress += 0.13f
            p.curGx = p.startGx + (p.targetGx - p.startGx) * p.progress
            p.curGy = p.startGy + (p.targetGy - p.startGy) * p.progress
            if (p.progress >= 1f) {
                if (p.fromDefense) {
                    for (u in activeUnits) {
                        val hitR = if (p.splash) 1.7f else 0.85f
                        if (hypot(u.gx - p.targetGx, u.gy - p.targetGy) <= hitR) {
                            u.hp -= p.damage
                        }
                    }
                } else {
                    for (b in raidBuildings) {
                        val bx = b.gx + b.type.footprint * 0.5f
                        val by = b.gy + b.type.footprint * 0.5f
                        val hitR = if (p.splash) 1.9f else 1.25f
                        if (hypot(bx - p.targetGx, by - p.targetGy) <= hitR) {
                            b.hp -= p.damage
                            checkBuildingDestroyed(b)
                        }
                    }
                }
                projectiles.remove(p)
            }
        }

        if (raidBuildings.none { it.type != BType.STONE_WALL }) {
            raidDestructionPct = 100
            raidStars = 3
            endRaidBattle()
        }
    }

    private fun findBestTargetForUnit(u: ActiveUnit): PlacedBuilding? {
        if (raidBuildings.isEmpty()) return null
        if (u.type.prefersWalls) {
            val wall = raidBuildings.filter { it.type == BType.STONE_WALL }
                .minByOrNull { hypot((it.gx + 0.5f) - u.gx, (it.gy + 0.5f) - u.gy) }
            if (wall != null) return wall
        }
        if (u.type.prefersDefenses) {
            val def = raidBuildings.filter { it.type.isDefense }
                .minByOrNull { hypot((it.gx + 0.5f) - u.gx, (it.gy + 0.5f) - u.gy) }
            if (def != null) return def
        }
        val nonWalls = raidBuildings.filter { it.type != BType.STONE_WALL }
        val pool = if (nonWalls.isNotEmpty()) nonWalls else raidBuildings
        return pool.minByOrNull { hypot((it.gx + 0.5f) - u.gx, (it.gy + 0.5f) - u.gy) }
    }

    private fun checkBuildingDestroyed(b: PlacedBuilding) {
        if (b.hp > 0 || !raidBuildings.contains(b)) return
        raidBuildings.remove(b)
        val sx = isoToScreenX(b.gx.toFloat(), b.gy.toFloat())
        val sy = isoToScreenY(b.gx.toFloat(), b.gy.toFloat())
        floatTexts.add(FloatText("💥 تدمير ${b.type.titleAr}!", sx, sy - 20f, Color.parseColor("#FFD54F")))

        val remainingNonWall = raidBuildings.count { it.type != BType.STONE_WALL }
        val destroyedCount = (raidTotalStartBuildings - remainingNonWall).coerceAtLeast(0)
        raidDestructionPct = ((destroyedCount * 100f) / raidTotalStartBuildings).toInt().coerceIn(0, 100)

        val mhDestroyed = raidBuildings.none { it.type == BType.MANOR_HALL }
        var stars = 0
        if (raidDestructionPct >= 50) stars++
        if (mhDestroyed) stars++
        if (raidDestructionPct >= 100) stars = 3
        raidStars = stars.coerceAtLeast(raidStars)
    }

    private fun endRaidBattle() {
        inRaidMode = false
        hasCompletedFirstRaid = true
        activeUnits.clear()
        projectiles.clear()
        activeSpells.clear()
        attackBuffPercent = 0

        val ratio = (raidDestructionPct / 100f).coerceAtLeast(if (raidStars > 0) 0.45f else 0.15f)
        val wonGold = (raidLootGold * ratio).toInt()
        val wonElixir = (raidLootElixir * ratio).toInt()
        val wonRations = (raidLootRations * ratio).toInt()
        gold += wonGold
        greenElixir += wonElixir
        armyRations += wonRations

        if (raidStars > 0) {
            trophies += raidStars * 10
            warOurStars += raidStars
            showBanner("🏆 انتصار (${raidStars}⭐)! غنمت +$wonGold ذهب و +$wonElixir إكسير أخضر و +$wonRations مؤونة 🍗")
        } else {
            showBanner("🛡️ انتهت الغارة (${raidDestructionPct}%)! غنمت +$wonGold ذهب و +$wonElixir إكسير")
        }
        checkQuestProgression()
        saveVillage()
    }

    private fun showBanner(msg: String) {
        statusBanner = msg
        statusBannerTimer = 190
    }

    // ==================== RENDERING PIPELINE ====================
    private fun drawGame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            bitmapPaint.alpha = 255
            paint.alpha = 255
            if (isLoadingScreen) {
                drawProfessionalLoadingScreen(canvas)
            } else {
                drawIslandBackground(canvas)
                drawIsoGridAndEntities(canvas)
                drawAmbientAndProjectiles(canvas)
                drawFloatTexts(canvas)
                drawSupercellCornerHUD(canvas)
                if (activeModal != ModalType.NONE) {
                    drawActiveModal(canvas)
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawProfessionalLoadingScreen(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#0D1F12"))
        splashBg?.let { sp ->
            bitmapPaint.alpha = 255
            canvas.drawBitmap(sp, null, RectF(0f, 0f, screenW, screenH), bitmapPaint)
        }

        for (e in splashEmbers) {
            paint.style = Paint.Style.FILL
            paint.color = e.color
            paint.alpha = e.alpha
            canvas.drawCircle(e.x / 1280f * screenW, e.y / 720f * screenH, e.size, paint)
        }
        paint.alpha = 255

        val barPanel = RectF(screenW * 0.18f, screenH * 0.79f, screenW * 0.82f, screenH * 0.96f)
        drawGlossyPanel(canvas, barPanel, Color.parseColor("#DD102818"), Color.parseColor("#00E676"))

        val pct = loadingProgress.toInt().coerceIn(0, 100)
        val stageText = if (pct < 100) "Loading FARM WARS 2.5D World... ($pct%)" else "✅ اضغط في أي مكان لبدء رحلة تأسيس مزرعتك!"
        drawOutlinedText(canvas, stageText, screenW * 0.5f, barPanel.top + 30f, 19f, Color.parseColor("#A5D6A7"))

        val track = RectF(barPanel.left + 36f, barPanel.top + 42f, barPanel.right - 36f, barPanel.top + 72f)
        paint.color = Color.parseColor("#08140C")
        canvas.drawRoundRect(track, 15f, 15f, paint)

        val fillW = (track.width() * (loadingProgress / 100f)).coerceAtLeast(18f)
        val fillRect = RectF(track.left, track.top, track.left + fillW, track.bottom)
        paint.shader = LinearGradient(fillRect.left, fillRect.top, fillRect.left, fillRect.bottom,
            Color.parseColor("#69F0AE"), Color.parseColor("#00C853"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(fillRect, 15f, 15f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.parseColor("#FFD54F")
        canvas.drawRoundRect(track, 15f, 15f, paint)
        paint.style = Paint.Style.FILL

        drawOutlinedText(canvas, loadingTips[loadingTipIndex], screenW * 0.5f, barPanel.bottom - 12f, 16f, Color.WHITE)
    }

    private fun drawIslandBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#1E4620"))
        val bg = islandBg
        if (bg != null) {
            clampCameraToIsland()
            val bgScale = 1.28f * camZoom
            val baseW = screenW * bgScale
            val baseH = screenH * bgScale
            val left = (screenW - baseW) * 0.5f + camX
            val top = (screenH - baseH) * 0.5f + camY
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bg, null, RectF(left, top, left + baseW, top + baseH), bitmapPaint)
        }
    }

    private fun drawIsoGridAndEntities(canvas: Canvas) {
        val showFullGrid = (buildModeType != null || moveBuildingMode)
        if (showFullGrid) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Color.argb(135, 105, 240, 174)
            for (i in 0..gridSize) {
                canvas.drawLine(isoToScreenX(i.toFloat(), 0f), isoToScreenY(i.toFloat(), 0f),
                    isoToScreenX(i.toFloat(), gridSize.toFloat()), isoToScreenY(i.toFloat(), gridSize.toFloat()), paint)
                canvas.drawLine(isoToScreenX(0f, i.toFloat()), isoToScreenY(0f, i.toFloat()),
                    isoToScreenX(gridSize.toFloat(), i.toFloat()), isoToScreenY(gridSize.toFloat(), i.toFloat()), paint)
            }
            paint.style = Paint.Style.FILL
        }

        // Draw active spell rings
        for (sp in activeSpells) {
            val sx = isoToScreenX(sp.gx, sp.gy)
            val sy = isoToScreenY(sp.gx, sp.gy)
            val rx = sp.radius * tileW * 0.45f * camZoom
            val ry = sp.radius * tileH * 0.45f * camZoom
            paint.style = Paint.Style.FILL
            paint.color = (sp.type.color and 0x00FFFFFF) or 0x44000000
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = sp.type.color
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.FILL
        }
        paint.alpha = 255

        val bList = if (inRaidMode) raidBuildings else myBuildings
        // Build fast lookup map of all walls so adjacent walls auto-connect seamlessly!
        val wallMap = HashMap<Pair<Int, Int>, PlacedBuilding>()
        for (b in bList) {
            if (b.type == BType.STONE_WALL) {
                wallMap[Pair(b.gx, b.gy)] = b
            }
        }

        data class RenderItem(
            val depth: Float,
            val kind: Int, // 0=building, 1=unit, 2=worker, 3=obstacle
            val b: PlacedBuilding? = null,
            val u: ActiveUnit? = null,
            val w: VillageWorker? = null,
            val ob: WildObstacle? = null
        )
        val items = ArrayList<RenderItem>(bList.size + activeUnits.size + villageWorkers.size + wildObstacles.size)
        for (b in bList) {
            items.add(RenderItem((b.gx + b.gy + b.type.footprint * 0.6f), 0, b = b))
        }
        if (!inRaidMode) {
            for (ob in wildObstacles) {
                items.add(RenderItem((ob.gx + ob.gy + 0.8f), 3, ob = ob))
            }
            for (w in villageWorkers) {
                items.add(RenderItem(w.gx + w.gy, 2, w = w))
            }
        }
        for (u in activeUnits) {
            items.add(RenderItem(u.gx + u.gy, 1, u = u))
        }
        items.sortBy { it.depth }

        for (item in items) {
            when (item.kind) {
                0 -> item.b?.let {
                    if (it.type == BType.STONE_WALL) {
                        drawConnectedWall25D(canvas, it, wallMap)
                    } else {
                        drawBuilding25D(canvas, it)
                    }
                }
                1 -> item.u?.let { drawUnit25D(canvas, it) }
                2 -> item.w?.let { drawVillageWorker25D(canvas, it) }
                3 -> item.ob?.let { drawWildObstacle25D(canvas, it) }
            }
        }
    }

    // ==================== AUTO-CONNECTING 2.5D ISOMETRIC WALL SYSTEM ====================
    private fun drawConnectedWall25D(canvas: Canvas, b: PlacedBuilding, wallMap: Map<Pair<Int, Int>, PlacedBuilding>) {
        val sx = isoToScreenX(b.gx + 0.5f, b.gy + 0.5f)
        val sy = isoToScreenY(b.gx + 0.5f, b.gy + 0.5f)

        val hasXPrev = wallMap.containsKey(Pair(b.gx - 1, b.gy))
        val hasXNext = wallMap.containsKey(Pair(b.gx + 1, b.gy))
        val hasYPrev = wallMap.containsKey(Pair(b.gx, b.gy - 1))
        val hasYNext = wallMap.containsKey(Pair(b.gx, b.gy + 1))

        val connectsX = hasXPrev || hasXNext
        val connectsY = hasYPrev || hasYNext
        val isCornerOrIntersection = (connectsX && connectsY) || (!connectsX && !connectsY)

        // 1. Draw seamless 3D isometric connecting wall beams to (gx+1, gy) and (gx, gy+1) so there are ZERO gaps!
        val wallHeight = (22f + b.level * 4f) * camZoom
        val wallThick = 9f * camZoom
        val bodyColor = when (b.level) {
            1 -> Color.parseColor("#6D4C41")
            2 -> Color.parseColor("#607D8B")
            else -> Color.parseColor("#1B5E20")
        }
        val topColor = when (b.level) {
            1 -> Color.parseColor("#A1887F")
            2 -> Color.parseColor("#B0BEC5")
            else -> Color.parseColor("#00E676")
        }

        if (hasXNext) {
            val nx = isoToScreenX(b.gx + 1.5f, b.gy + 0.5f)
            val ny = isoToScreenY(b.gx + 1.5f, b.gy + 0.5f)
            drawIsometricWallBeam(canvas, sx, sy, nx, ny, wallHeight, wallThick, bodyColor, topColor)
        }
        if (hasYNext) {
            val nx = isoToScreenX(b.gx + 0.5f, b.gy + 1.5f)
            val ny = isoToScreenY(b.gx + 0.5f, b.gy + 1.5f)
            drawIsometricWallBeam(canvas, sx, sy, nx, ny, wallHeight, wallThick, bodyColor, topColor)
        }

        // 2. Choose the exact 2.5D sprite for this wall tile (Corner Watchtower, Gate, or Directional Wall!)
        val isGateSpot = (b.gx == 6 && connectsX && !connectsY) || (b.gy == 6 && connectsY && !connectsX)
        val spriteKey = when {
            isCornerOrIntersection -> if (b.level == 1) "wall_corner_lv1" else "wall_corner_lv2"
            isGateSpot && connectsX -> "wall_gate_flip"
            isGateSpot && connectsY -> "wall_gate"
            connectsX && !connectsY -> when (b.level) {
                1 -> "wall_lv1_flip"
                2 -> "wall_lv2_flip"
                else -> "wall_lv3_flip"
            }
            else -> when (b.level) {
                1 -> "wall_lv1"
                2 -> "wall_lv2"
                else -> "wall_lv3"
            }
        }

        val bmp = bitmaps[spriteKey] ?: bitmaps["stone_wall"]
        if (bmp != null) {
            val scale = (if (isCornerOrIntersection) 0.92f else 1.08f) * camZoom * (1.0f + b.tapBounce * 0.12f)
            val drawW = tileW * scale
            val drawH = drawW * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(
                sx - drawW * 0.5f,
                sy - drawH * 0.78f + tileH * 0.22f * camZoom,
                sx + drawW * 0.5f,
                sy + tileH * 0.22f * camZoom
            )
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }

        if (selectedBuilding == b) {
            drawIsoDiamondTile(canvas, b.gx.toFloat(), b.gy.toFloat(), 1,
                Color.argb(95, 0, 230, 118), Color.parseColor("#00E676"))
        }
    }

    private fun drawIsometricWallBeam(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        wallH: Float,
        thick: Float,
        bodyColor: Int,
        topColor: Int
    ) {
        // Vertical wall face between (x1, y1) and (x2, y2)
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x2, y2 - wallH)
        path.lineTo(x1, y1 - wallH)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = bodyColor
        canvas.drawPath(path, paint)

        // Top battlement walkway
        path.reset()
        path.moveTo(x1 - thick * 0.5f, y1 - wallH)
        path.lineTo(x2 - thick * 0.5f, y2 - wallH)
        path.lineTo(x2 + thick * 0.5f, y2 - wallH - thick * 0.4f)
        path.lineTo(x1 + thick * 0.5f, y1 - wallH - thick * 0.4f)
        path.close()
        paint.color = topColor
        canvas.drawPath(path, paint)
        paint.alpha = 255
    }

    private fun drawWildObstacle25D(canvas: Canvas, ob: WildObstacle) {
        val sx = isoToScreenX(ob.gx + 0.5f, ob.gy + 0.5f)
        val sy = isoToScreenY(ob.gx + 0.5f, ob.gy + 0.5f)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 0, 0, 0)
        canvas.drawOval(RectF(sx - 24f * camZoom, sy - 10f * camZoom, sx + 24f * camZoom, sy + 12f * camZoom), paint)
        paint.alpha = 255

        bitmaps[ob.type.spriteKey]?.let { bmp ->
            val drawW = tileW * 1.15f * camZoom
            val drawH = drawW * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(sx - drawW * 0.5f, sy - drawH * 0.72f + 8f * camZoom, sx + drawW * 0.5f, sy + 8f * camZoom)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        }

        if (ob.clearTicksLeft > 0) {
            val sec = (ob.clearTicksLeft / 30) + 1
            drawOutlinedText(canvas, "⛏️ إزالة (${sec}ث)...", sx, sy - 38f * camZoom, 12.5f * camZoom, Color.parseColor("#FFD54F"))
        }
    }

    private fun drawIsoDiamondTile(canvas: Canvas, gx: Float, gy: Float, fp: Int, fillColor: Int, strokeColor: Int) {
        val topX = isoToScreenX(gx, gy)
        val topY = isoToScreenY(gx, gy)
        val rightX = isoToScreenX(gx + fp, gy)
        val rightY = isoToScreenY(gx + fp, gy)
        val botX = isoToScreenX(gx + fp, gy + fp)
        val botY = isoToScreenY(gx + fp, gy + fp)
        val leftX = isoToScreenX(gx, gy + fp)
        val leftY = isoToScreenY(gx, gy + fp)

        path.reset()
        path.moveTo(topX, topY)
        path.lineTo(rightX, rightY)
        path.lineTo(botX, botY)
        path.lineTo(leftX, leftY)
        path.close()

        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawPath(path, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * camZoom
        paint.color = strokeColor
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawBuilding25D(canvas: Canvas, b: PlacedBuilding) {
        val fp = b.type.footprint
        val centerGx = b.gx + fp * 0.5f
        val centerGy = b.gy + fp * 0.5f
        val sx = isoToScreenX(centerGx, centerGy)
        val sy = isoToScreenY(centerGx, centerGy)

        if (selectedBuilding == b) {
            drawIsoDiamondTile(canvas, b.gx.toFloat(), b.gy.toFloat(), fp,
                Color.argb(95, 0, 230, 118), Color.parseColor("#00E676"))
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 0, 0, 0)
        val shW = fp * tileW * 0.34f * camZoom
        val shH = fp * tileH * 0.30f * camZoom
        canvas.drawOval(RectF(sx - shW, sy - shH * 0.3f, sx + shW, sy + shH * 0.9f), paint)
        paint.alpha = 255

        val breathScale = 1.0f + sin(animTickGlobal * 0.06f + b.id) * 0.012f + b.tapBounce * 0.12f
        val levelScale = 1.0f + (b.level - 1) * 0.07f
        val recoilOffset = b.recoilAnim * 7f * camZoom
        val swaySkewX = if (b.type == BType.WHEAT_FIELD) sin(animTickGlobal * 0.08f + b.id) * 3.5f * camZoom else 0f

        val spriteKey = b.effectiveSpriteKey()
        val bmp = bitmaps[spriteKey]
        if (bmp != null) {
            val scale = when {
                b.type == BType.MANOR_HALL && b.level == 3 -> 1.95f
                b.type == BType.MANOR_HALL && b.level == 2 -> 1.80f
                b.type == BType.MANOR_HALL -> 1.65f
                fp == 1 -> 1.15f
                else -> 1.50f
            } * camZoom * breathScale * levelScale

            val drawW = tileW * scale
            val drawH = drawW * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(
                sx - drawW * 0.5f + swaySkewX - recoilOffset,
                sy - drawH * 0.76f + fp * tileH * 0.20f * camZoom,
                sx + drawW * 0.5f + swaySkewX - recoilOffset,
                sy + fp * tileH * 0.24f * camZoom
            )
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)

            if (b.type == BType.WINDMILL_BAKERY) {
                drawSpinningWindmillBlades(canvas, sx, dst.top + drawH * 0.36f, 30f * camZoom)
            }
            if (b.type == BType.EMERALD_TOWER || (b.type == BType.MANOR_HALL && b.level == 3)) {
                val bobY = sin(animTickGlobal * 0.12f + b.id) * 5f * camZoom
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(110, 0, 230, 118)
                canvas.drawCircle(sx, dst.top + 6f * camZoom + bobY, 13f * camZoom, paint)
                paint.color = Color.parseColor("#69F0AE")
                canvas.drawCircle(sx, dst.top + 6f * camZoom + bobY, 5.5f * camZoom, paint)
                paint.alpha = 255
            }
        }

        if (selectedBuilding == b) {
            val starsStr = "⭐".repeat(b.level.coerceIn(1, 3))
            val badgeY = sy - fp * tileH * 0.96f * camZoom
            drawOutlinedText(canvas, "${b.type.titleAr} $starsStr", sx, badgeY, 15f * camZoom, Color.parseColor("#69F0AE"))
        } else if (b.level >= 2) {
            val starsStr = "⭐".repeat(b.level.coerceIn(1, 3))
            drawOutlinedText(canvas, starsStr, sx, sy + fp * tileH * 0.30f * camZoom, 11f * camZoom, Color.parseColor("#FFD54F"))
        }

        if (b.upgradeTicksLeft > 0 && b.upgradeTotalTicks > 0) {
            val secLeft = (b.upgradeTicksLeft / 30) + 1
            val pct = 1f - (b.upgradeTicksLeft.toFloat() / b.upgradeTotalTicks.toFloat())
            val barW = 72f * camZoom
            val barH = 10f * camZoom
            val by = sy - fp * tileH * 0.90f * camZoom
            paint.color = Color.parseColor("#CC000000")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 5f, 5f, paint)
            paint.color = Color.parseColor("#00E676")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * pct, by + barH), 5f, 5f, paint)
            paint.alpha = 255
            drawOutlinedText(canvas, "🔨 ${secLeft}ث", sx, by - 4f, 12f * camZoom, Color.WHITE)
        }

        if (!inRaidMode && b.readyCollect) {
            val bob = sin(animTickGlobal * 0.14f + b.id) * 5f
            val by = sy - fp * tileH * 0.85f * camZoom + bob
            val bubbleRect = RectF(sx - 20f * camZoom, by - 22f * camZoom, sx + 20f * camZoom, by + 10f * camZoom)
            drawGlossyPanel(canvas, bubbleRect, Color.parseColor("#EE1B5E20"), Color.parseColor("#FFD54F"), 12f)
            val icon = when (b.type) {
                BType.WHEAT_FIELD -> "🌾"
                BType.RANCH_BARN -> "🥩"
                BType.WINDMILL_BAKERY -> "🥖"
                BType.GREEN_ELIXIR -> "🧪"
                BType.GOLD_SILO -> "🪙"
                BType.FIELD_KITCHEN -> "🍗"
                else -> "✨"
            }
            drawOutlinedText(canvas, icon, sx, by, 16f * camZoom, Color.WHITE)
        }

        if (inRaidMode && b.hp < b.maxHp) {
            val barW = 56f * camZoom
            val barH = 8f * camZoom
            val by = sy - fp * tileH * 0.65f * camZoom
            val ratio = (b.hp.toFloat() / b.maxHp.toFloat()).coerceIn(0f, 1f)
            paint.color = Color.parseColor("#CC000000")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 4f, 4f, paint)
            paint.color = if (ratio > 0.5f) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * ratio, by + barH), 4f, 4f, paint)
            paint.alpha = 255
        }
    }

    private fun drawSpinningWindmillBlades(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val baseAngle = animTickGlobal * 0.045f
        paint.strokeWidth = 3.5f * camZoom
        for (i in 0 until 4) {
            val ang = baseAngle + i * (PI.toFloat() * 0.5f)
            val ex = cx + cos(ang) * radius
            val ey = cy + sin(ang) * radius
            paint.color = Color.parseColor("#5D4037")
            canvas.drawLine(cx, cy, ex, ey, paint)
            paint.color = Color.parseColor("#FFF8E1")
            canvas.drawCircle(cx + cos(ang) * (radius * 0.65f), cy + sin(ang) * (radius * 0.65f), 4.5f * camZoom, paint)
        }
        paint.color = Color.parseColor("#FFD54F")
        canvas.drawCircle(cx, cy, 4.5f * camZoom, paint)
        paint.alpha = 255
    }

    private fun drawVillageWorker25D(canvas: Canvas, w: VillageWorker) {
        val sx = isoToScreenX(w.gx, w.gy)
        val sy = isoToScreenY(w.gx, w.gy)
        val bob = abs(sin(w.animPhase)) * 3.5f * camZoom

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(60, 0, 0, 0)
        canvas.drawOval(RectF(sx - 8f * camZoom, sy - 3f * camZoom, sx + 8f * camZoom, sy + 4f * camZoom), paint)
        paint.alpha = 255

        paint.color = Color.parseColor("#2E7D32")
        canvas.drawRoundRect(RectF(sx - 5.5f * camZoom, sy - 17f * camZoom - bob, sx + 5.5f * camZoom, sy - 4f * camZoom - bob), 4f, 4f, paint)
        paint.color = Color.parseColor("#FFCC80")
        canvas.drawCircle(sx, sy - 22f * camZoom - bob, 5f * camZoom, paint)
        paint.color = Color.parseColor("#FFD54F")
        canvas.drawOval(RectF(sx - 8f * camZoom, sy - 27f * camZoom - bob, sx + 8f * camZoom, sy - 23f * camZoom - bob), paint)
    }

    private fun drawUnit25D(canvas: Canvas, u: ActiveUnit) {
        val sx = isoToScreenX(u.gx, u.gy)
        val sy = isoToScreenY(u.gx, u.gy)
        val bounce = abs(sin(u.animTick * 0.35f)) * 5f * camZoom
        val swingOffset = u.attackSwing * 8f * camZoom

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(65, 0, 0, 0)
        canvas.drawOval(RectF(sx - 14f * camZoom, sy - 4f * camZoom, sx + 14f * camZoom, sy + 6f * camZoom), paint)
        paint.alpha = 255

        val bmp = bitmaps[u.type.spriteKey]
        if (bmp != null) {
            val w = 64f * camZoom * (1.0f + u.attackSwing * 0.14f)
            val h = w * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(sx - w * 0.5f + swingOffset, sy - h * 0.86f - bounce, sx + w * 0.5f + swingOffset, sy + h * 0.14f - bounce)
            bitmapPaint.alpha = 255
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        } else {
            paint.color = Color.parseColor("#00E676")
            canvas.drawCircle(sx, sy - 15f * camZoom - bounce, 14f * camZoom, paint)
        }

        val barW = 38f * camZoom
        val barH = 6f * camZoom
        val ratio = (u.hp.toFloat() / u.maxHp.toFloat()).coerceIn(0f, 1f)
        val by = sy - 58f * camZoom - bounce
        paint.color = Color.parseColor("#CC000000")
        canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 3f, 3f, paint)
        paint.color = if (u.isGuildAlly) Color.parseColor("#40C4FF") else Color.parseColor("#00E676")
        canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * ratio, by + barH), 3f, 3f, paint)
        paint.alpha = 255
    }

    private fun drawAmbientAndProjectiles(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        for (p in ambientParticles) {
            paint.color = p.color
            paint.alpha = ((p.life.toFloat() / p.maxLife.toFloat()) * 170).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.sx, p.sy, p.radius * camZoom, paint)
        }
        paint.alpha = 255

        for (p in projectiles) {
            val sx = isoToScreenX(p.curGx, p.curGy)
            val arcLift = sin(p.progress * PI).toFloat() * (if (p.splash) 65f else 24f) * camZoom
            val sy = isoToScreenY(p.curGx, p.curGy) - 20f * camZoom - arcLift
            paint.color = p.color
            canvas.drawCircle(sx, sy, if (p.splash) 9.5f * camZoom else 6.5f * camZoom, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawCircle(sx, sy, if (p.splash) 9.5f * camZoom else 6.5f * camZoom, paint)
            paint.style = Paint.Style.FILL
        }
        paint.alpha = 255
    }

    private fun drawFloatTexts(canvas: Canvas) {
        for (ft in floatTexts) {
            drawOutlinedText(canvas, ft.text, ft.sx, ft.sy, 17f, ft.color)
        }
    }

    // ==================== SUPERCELL CORNER HUD + INTERACTIVE QUEST BANNER ====================
    private fun drawGlossyPanel(canvas: Canvas, rect: RectF, bgColor: Int, borderColor: Int, radius: Float = 16f) {
        paint.style = Paint.Style.FILL
        paint.color = bgColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        val highlightRect = RectF(rect.left + 4f, rect.top + 3f, rect.right - 4f, rect.top + rect.height() * 0.40f)
        paint.color = Color.argb(36, 255, 255, 255)
        canvas.drawRoundRect(highlightRect, radius * 0.8f, radius * 0.8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.alpha = 255
    }

    private fun drawGlossyButton(canvas: Canvas, rect: RectF, text: String, topColor: Int, botColor: Int, borderColor: Int = Color.parseColor("#FFF8E1"), textSize: Float = 16f) {
        val shadowRect = RectF(rect.left, rect.top + 4f, rect.right, rect.bottom + 4f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(shadowRect, 16f, 16f, paint)
        paint.alpha = 255

        paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, topColor, botColor, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.shader = null

        val shine = RectF(rect.left + 5f, rect.top + 4f, rect.right - 5f, rect.centerY())
        paint.color = Color.argb(65, 255, 255, 255)
        canvas.drawRoundRect(shine, 12f, 12f, paint)
        paint.alpha = 255

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = borderColor
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.style = Paint.Style.FILL

        drawOutlinedText(canvas, text, rect.centerX(), rect.centerY() + textSize * 0.34f, textSize, Color.WHITE)
    }

    private fun drawOutlinedText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.CENTER) {
        textPaint.textSize = size
        textPaint.textAlign = align
        textPaint.typeface = Typeface.DEFAULT_BOLD

        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = (size * 0.22f).coerceAtLeast(3f)
        textPaint.color = Color.parseColor("#140B04")
        canvas.drawText(text, cx, cy, textPaint)

        textPaint.style = Paint.Style.FILL
        textPaint.color = color
        canvas.drawText(text, cx, cy, textPaint)
    }

    private fun drawSupercellCornerHUD(canvas: Canvas) {
        val pad = 12f
        val mhLv = getManorHallLevel()
        val freeBuilders = (totalBuilders - busyBuildersCount()).coerceAtLeast(0)
        val neededRations = getRequiredRationsForArmy()

        // TOP-LEFT COMPACT STACK
        val tlW = 240f
        val tlH = 38f
        val p1 = RectF(pad, pad, pad + tlW, pad + tlH)
        drawGlossyPanel(canvas, p1, Color.parseColor("#D8142C1A"), Color.parseColor("#FFD54F"), 12f)
        drawOutlinedText(canvas, "🏰 قاعة Lv.$mhLv  |  🏆 $trophies", p1.centerX(), p1.centerY() + 5f, 14.5f, Color.parseColor("#FFD54F"))

        val p2 = RectF(pad, p1.bottom + 6f, pad + tlW, p1.bottom + 6f + tlH)
        val rationColor = if (armyRations >= neededRations && armyRations > 0) Color.parseColor("#FFAB40") else Color.parseColor("#FF5252")
        drawGlossyPanel(canvas, p2, Color.parseColor("#D82E1C14"), rationColor, 12f)
        drawOutlinedText(canvas, "🍗 مؤونة: $armyRations/$neededRations  |  🔨 $freeBuilders/$totalBuilders", p2.centerX(), p2.centerY() + 5f, 13.5f, Color.parseColor("#FFCC80"))

        val p3 = RectF(pad, p2.bottom + 6f, pad + tlW, p2.bottom + 36f)
        drawGlossyPanel(canvas, p3, Color.parseColor("#BF102416"), Color.parseColor("#69F0AE"), 10f)
        val totalTroops = armyRoster.values.sum()
        drawOutlinedText(canvas, "🌾$wheatCount  🥩$meatCount  🥖$breadCount  |  🛡️جيش:$totalTroops", p3.centerX(), p3.centerY() + 5f, 12.5f, Color.WHITE)

        // TOP-RIGHT COMPACT STACK
        val trW = 225f
        val rGold = RectF(screenW - pad - trW, pad, screenW - pad, pad + tlH)
        drawGlossyPanel(canvas, rGold, Color.parseColor("#D83E2723"), Color.parseColor("#FFD54F"), 12f)
        drawOutlinedText(canvas, "🪙 ذهب: $gold", rGold.centerX(), rGold.centerY() + 5f, 14.5f, Color.parseColor("#FFE082"))

        val rElixir = RectF(screenW - pad - trW, rGold.bottom + 6f, screenW - pad, rGold.bottom + 6f + tlH)
        drawGlossyPanel(canvas, rElixir, Color.parseColor("#D80D3B1E"), Color.parseColor("#00E676"), 12f)
        drawOutlinedText(canvas, "🧪 إكسير أخضر: $greenElixir", rElixir.centerX(), rElixir.centerY() + 5f, 14.5f, Color.parseColor("#69F0AE"))

        val rGems = RectF(screenW - pad - 155f, rElixir.bottom + 6f, screenW - pad, rElixir.bottom + 36f)
        drawGlossyPanel(canvas, rGems, Color.parseColor("#D8004D40"), Color.parseColor("#1DE9B6"), 10f)
        drawOutlinedText(canvas, "💎 $gems [+]", rGems.centerX(), rGems.centerY() + 5f, 13.5f, Color.parseColor("#A7FFEB"))

        // TOP-CENTER: Interactive Quest Guide Banner (plus 1-tap Demo Fortress toggle)
        if (!inRaidMode) {
            val qBar = RectF(screenW * 0.25f, pad, screenW * 0.76f, pad + 44f)
            drawGlossyPanel(canvas, qBar, Color.parseColor("#E6142E1B"), Color.parseColor("#00E676"), 14f)
            val msg = if (statusBannerTimer > 0 && statusBanner.isNotEmpty()) statusBanner else getCurrentQuestDescription()
            drawOutlinedText(canvas, msg, qBar.centerX(), qBar.centerY() + 5f, 13.5f, Color.parseColor("#FFF59D"))

            // Quick Mode Buttons under Quest Bar: [🧱 تسوير القاعة] & [⚡ تجربة قلعة كاملة / تصفير]
            val autoWallBtn = RectF(screenW * 0.31f, qBar.bottom + 6f, screenW * 0.50f, qBar.bottom + 38f)
            drawGlossyButton(canvas, autoWallBtn, "🧱 تسوير القاعة تلقائياً", Color.parseColor("#6D4C41"), Color.parseColor("#3E2723"), textSize = 12f)

            val demoBtn = RectF(screenW * 0.51f, qBar.bottom + 6f, screenW * 0.69f, qBar.bottom + 38f)
            val demoLabel = if (myBuildings.size <= 3) "⚡ تجربة قلعة مسوّرة كاملة" else "🔄 بدء اللعبة من الصفر"
            drawGlossyButton(canvas, demoBtn, demoLabel, Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 12f)
        }

        if (!inRaidMode) {
            // BOTTOM-LEFT ACTION BUTTONS: Iconic Attack Shield + Guild War
            val atkRect = RectF(pad, screenH - pad - 84f, pad + 148f, screenH - pad)
            drawGlossyButton(canvas, atkRect, "⚔️ هجوم!", Color.parseColor("#FF8F00"), Color.parseColor("#E65100"), Color.parseColor("#FFE082"), 19f)

            val guildRect = RectF(atkRect.right + 10f, screenH - pad - 68f, atkRect.right + 142f, screenH - pad)
            drawGlossyButton(canvas, guildRect, "🏰 التحالف", Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 15f)

            // BOTTOM-RIGHT 4 COMPACT ACTION CARDS (Build, Kitchen, Army, Contracts)
            val cardW = 112f
            val cardH = 68f
            val gap = 8f
            val rightLabels = listOf("🏗️ البناء", "🍗 المطبخ", "🛡️ الجيش", "🚚 العقود")
            val topCols = listOf("#43A047", "#F4511E", "#00C853", "#1E88E5")
            val botCols = listOf("#1B5E20", "#BF360C", "#00695C", "#0D47A1")

            val startRightX = screenW - pad - (cardW * 4 + gap * 3)
            for (i in 0..3) {
                val cx = startRightX + i * (cardW + gap)
                val r = RectF(cx, screenH - pad - cardH, cx + cardW, screenH - pad)
                drawGlossyButton(canvas, r, rightLabels[i], Color.parseColor(topCols[i]), Color.parseColor(botCols[i]), textSize = 14.5f)
            }

            // Animated Tutorial Pointer Arrow bouncing over the recommended button!
            val arrowBob = sin(animTickGlobal * 0.18f) * 8f
            val targetBtnX: Float? = when (currentQuestStep) {
                1, 2, 4, 5 -> startRightX + cardW * 0.5f // Point to Build button
                3 -> if (myBuildings.any { it.type == BType.FIELD_KITCHEN }) startRightX + (cardW + gap) * 1 + cardW * 0.5f else startRightX + cardW * 0.5f
                6 -> if (myBuildings.any { it.type == BType.ARMY_CAMP }) startRightX + (cardW + gap) * 2 + cardW * 0.5f else startRightX + cardW * 0.5f
                7 -> atkRect.centerX()
                else -> null
            }
            if (targetBtnX != null && buildModeType == null && activeModal == ModalType.NONE) {
                drawOutlinedText(canvas, "👇", targetBtnX, screenH - pad - cardH - 16f + arrowBob, 28f, Color.parseColor("#FFD54F"))
            }

            // Active Wall / Building Placement Mode Banner at Bottom-Center
            buildModeType?.let { bt ->
                val bCard = RectF(screenW * 0.32f, screenH - pad - 76f, screenW * 0.62f, screenH - pad - 12f)
                drawGlossyPanel(canvas, bCard, Color.parseColor("#EE1B5E20"), Color.parseColor("#FFD54F"), 16f)
                val hint = if (bt == BType.STONE_WALL) "🧱 اسحب إصبعك لرسم خط أسوار متصل | [إنهاء ✖]" else "🏗️ انقر على العشب لوضع ${bt.titleAr} | [إلغاء ✖]"
                drawOutlinedText(canvas, hint, bCard.centerX(), bCard.centerY() + 5f, 13.5f, Color.WHITE)
            }

            // BOTTOM-CENTER SELECTED BUILDING INSPECTOR CARD
            if (buildModeType == null) {
                selectedBuilding?.let { b ->
                    val infoW = 420f
                    val infoRect = RectF(screenW * 0.5f - infoW * 0.5f, screenH - pad - 146f, screenW * 0.5f + infoW * 0.5f, screenH - pad - 82f)
                    drawGlossyPanel(canvas, infoRect, Color.parseColor("#F0102818"), Color.parseColor("#00E676"), 16f)

                    val nextLv = b.level + 1
                    val upCost = (b.type.baseCostGold + b.type.baseCostElixir + 160) * b.level
                    val upLabel = when {
                        b.level >= 3 -> "✅ ماكس Lv.3"
                        b.type != BType.MANOR_HALL && nextLv > mhLv -> "🔒 قاعة Lv.$nextLv"
                        b.upgradeTicksLeft > 0 -> "⚡ إنهاء (10💎)"
                        else -> "⬆️ ترقية ($upCost🪙)"
                    }

                    drawOutlinedText(canvas, "${b.type.titleAr} (Lv.${b.level}/3)", infoRect.left + 115f, infoRect.centerY() + 5f, 14f, Color.parseColor("#FFD54F"))

                    val upBtn = RectF(infoRect.right - 205f, infoRect.top + 10f, infoRect.right - 84f, infoRect.bottom - 10f)
                    drawGlossyButton(canvas, upBtn, upLabel, Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 12.5f)

                    val moveBtn = RectF(infoRect.right - 78f, infoRect.top + 10f, infoRect.right - 10f, infoRect.bottom - 10f)
                    drawGlossyButton(canvas, moveBtn, if (moveBuildingMode) "تثبيت" else "نقل ✥", Color.parseColor("#1E88E5"), Color.parseColor("#0D47A1"), textSize = 13f)
                }
            }
        } else {
            // RAID MODE HUD
            val raidCard = RectF(screenW * 0.32f, pad, screenW * 0.68f, pad + 58f)
            drawGlossyPanel(canvas, raidCard, Color.parseColor("#E0263238"), Color.parseColor("#FFD54F"), 14f)
            val sec = (raidTicksLeft / 30).coerceAtLeast(0)
            val starStr = "⭐".repeat(raidStars) + "☆".repeat(3 - raidStars)
            drawOutlinedText(canvas, "$raidTargetName | $starStr ($raidDestructionPct%)", raidCard.centerX(), raidCard.top + 24f, 15f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "⏱️ ${sec}ث | الغنيمة: 🪙$raidLootGold 🧪$raidLootElixir 🍗$raidLootRations", raidCard.centerX(), raidCard.top + 46f, 13f, Color.WHITE)

            val totalSlots = 11
            val gap = 6f
            val barH = 76f
            val topY = screenH - barH - pad
            val slotW = (screenW - pad * 2 - gap * (totalSlots - 1)) / totalSlots

            val uTypes = UType.values()
            for (i in uTypes.indices) {
                val u = uTypes[i]
                val cnt = armyRoster[u] ?: 0
                val sx = pad + i * (slotW + gap)
                val r = RectF(sx, topY, sx + slotW, screenH - pad)
                val isSel = (selectedSpell == null && selectedUnitType == u)
                drawGlossyPanel(canvas, r, if (cnt > 0) Color.parseColor("#E6102818") else Color.parseColor("#99263238"),
                    if (isSel) Color.parseColor("#00E676") else Color.parseColor("#90A4AE"), 12f)

                bitmaps[u.spriteKey]?.let { bmp ->
                    bitmapPaint.alpha = 255
                    val iconS = 36f
                    canvas.drawBitmap(bmp, null, RectF(r.centerX() - iconS / 2, r.top + 4f, r.centerX() + iconS / 2, r.top + 4f + iconS), bitmapPaint)
                }
                drawOutlinedText(canvas, u.titleAr, r.centerX(), r.bottom - 20f, 10.5f, Color.WHITE)
                drawOutlinedText(canvas, "x$cnt", r.centerX(), r.bottom - 5f, 12.5f, Color.parseColor("#69F0AE"))
            }

            val sTypes = SpellType.values()
            for (j in sTypes.indices) {
                val sp = sTypes[j]
                val cnt = spellCounts[sp] ?: 0
                val idx = 6 + j
                val sx = pad + idx * (slotW + gap)
                val r = RectF(sx, topY, sx + slotW, screenH - pad)
                val isSel = (selectedSpell == sp)
                drawGlossyPanel(canvas, r, Color.parseColor("#E61B3B22"), if (isSel) Color.parseColor("#FFD54F") else sp.color, 12f)
                drawOutlinedText(canvas, sp.icon, r.centerX(), r.top + 26f, 18f, Color.WHITE)
                drawOutlinedText(canvas, sp.titleAr, r.centerX(), r.bottom - 20f, 10.5f, Color.WHITE)
                drawOutlinedText(canvas, "x$cnt", r.centerX(), r.bottom - 5f, 12.5f, Color.parseColor("#FFD54F"))
            }

            val ccX = pad + 9 * (slotW + gap)
            drawGlossyButton(canvas, RectF(ccX, topY, ccX + slotW, screenH - pad),
                if (guildReinforcementsCalled) "✅ تم" else "🏰 دعم",
                Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 12f)

            val endX = pad + 10 * (slotW + gap)
            drawGlossyButton(canvas, RectF(endX, topY, endX + slotW, screenH - pad),
                "🏳️ إنهاء", Color.parseColor("#E53935"), Color.parseColor("#B71C1C"), textSize = 13f)
        }
    }

    // ==================== MODAL DIALOGS ====================
    private fun drawActiveModal(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(165, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
        paint.alpha = 255

        val panel = RectF(screenW * 0.08f, screenH * 0.11f, screenW * 0.92f, screenH * 0.87f)
        drawGlossyPanel(canvas, panel, Color.parseColor("#F5122A1A"), Color.parseColor("#00E676"), 22f)

        val closeBtn = RectF(panel.right - 56f, panel.top + 12f, panel.right - 12f, panel.top + 54f)
        drawGlossyButton(canvas, closeBtn, "✖", Color.parseColor("#E53935"), Color.parseColor("#B71C1C"), textSize = 18f)

        when (activeModal) {
            ModalType.BUILD_SHOP -> drawBuildShopModal(canvas, panel)
            ModalType.KITCHEN_RATIONS -> drawKitchenRationsModal(canvas, panel)
            ModalType.ARMY_TRAIN -> drawArmyTrainModal(canvas, panel)
            ModalType.TRUCK_ORDERS -> drawContractsModal(canvas, panel)
            ModalType.GUILD_WAR -> drawGuildWarModal(canvas, panel)
            ModalType.GEM_SHOP -> drawGemShopModal(canvas, panel)
            else -> {}
        }
    }

    private fun drawBuildShopModal(canvas: Canvas, panel: RectF) {
        val mhLv = getManorHallLevel()
        drawOutlinedText(canvas, "🏗️ متجر المباني والأسوار المتصلة (قاعة المزرعة: المستوى $mhLv/3)", panel.centerX(), panel.top + 40f, 20f, Color.parseColor("#69F0AE"))

        val buildable = BType.values().filter { it != BType.MANOR_HALL }
        val cols = 4
        val rows = 3
        val pad = 12f
        val gridTop = panel.top + 62f
        val cellW = (panel.width() - pad * (cols + 1)) / cols
        val cellH = (panel.height() - 78f - pad * (rows + 1)) / rows

        for (i in buildable.indices) {
            val t = buildable[i]
            val c = i % cols
            val r = i / cols
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            val unlocked = mhLv >= t.unlockManorLv

            drawGlossyPanel(canvas, card,
                if (unlocked) Color.parseColor("#DD1B3B22") else Color.parseColor("#AA263238"),
                if (unlocked) Color.parseColor("#00E676") else Color.parseColor("#78909C"), 14f)

            bitmaps[t.spriteKey]?.let { bmp ->
                bitmapPaint.alpha = 255
                val iconS = min(cellW * 0.36f, cellH * 0.54f)
                val dst = RectF(card.left + 10f, card.centerY() - iconS * 0.5f, card.left + 10f + iconS, card.centerY() + iconS * 0.5f)
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }

            drawOutlinedText(canvas, t.titleAr, card.centerX() + 26f, card.top + 26f, 13.5f, Color.WHITE)
            val costTxt = if (!unlocked) {
                "🔒 قاعة مستوى ${t.unlockManorLv}"
            } else if (t.baseCostGold > 0 && t.baseCostElixir > 0) {
                "🪙${t.baseCostGold} + 🧪${t.baseCostElixir}"
            } else if (t.baseCostGold > 0) {
                "🪙 ${t.baseCostGold} ذهب"
            } else {
                "🧪 ${t.baseCostElixir} إكسير"
            }
            drawOutlinedText(canvas, costTxt, card.centerX() + 26f, card.top + 48f, 12f, Color.parseColor("#FFD54F"))

            val buyR = RectF(card.left + cellW * 0.38f, card.bottom - 36f, card.right - 10f, card.bottom - 8f)
            drawGlossyButton(canvas, buyR,
                if (unlocked) "بناء الآن" else "مغلق 🔒",
                if (unlocked) Color.parseColor("#43A047") else Color.parseColor("#546E7A"),
                if (unlocked) Color.parseColor("#1B5E20") else Color.parseColor("#37474F"),
                textSize = 12.5f)
        }
    }

    private fun drawKitchenRationsModal(canvas: Canvas, panel: RectF) {
        val neededRations = getRequiredRationsForArmy()
        drawOutlinedText(canvas, "🍗 مطبخ تجهيز مؤونة الجيش (الرصيد: $armyRations🍗 | المطلوب للهجوم: $neededRations🍗)", panel.centerX(), panel.top + 40f, 19.5f, Color.parseColor("#FFAB40"))
        drawOutlinedText(canvas, "المخزون الزراعي: 🌾 قمح ($wheatCount)  |  🥩 لحوم الماشية ($meatCount)  |  🥖 خبز الطاحونة ($breadCount)", panel.centerX(), panel.top + 70f, 15f, Color.WHITE)

        val meals = listOf(
            Triple("🥖 وجبة المزارع السريعة (+35 مؤونة 🍗)", "التكلفة: 2 قمح 🌾", "تجهيز سريع لتدريب الجنود والغارات"),
            Triple("🍗 وليمة الشواء المحارب (+85 مؤونة 🍗)", "التكلفة: 2 قمح 🌾 + 1 لحوم 🥩", "تموين مشبع لجيش المزرعة"),
            Triple("🔥 وليمة الزمرد الملكية (+160 مؤونة 🍗 + 25% قوة هجوم!)", "التكلفة: 3 قمح 🌾 + 2 لحوم 🥩 + 150 إكسير أخضر 🧪", "تمنح جيشك +25% ضرر إضافي في المعركة القادمة!")
        )

        val startY = panel.top + 94f
        val rowH = (panel.height() - 120f) / 3f
        for (i in meals.indices) {
            val ry = startY + i * rowH
            val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
            drawGlossyPanel(canvas, rowRect, Color.parseColor("#DD2E1C14"), Color.parseColor("#FFAB40"), 16f)

            bitmaps["field_kitchen"]?.let { bmp ->
                bitmapPaint.alpha = 255
                val s = rowH * 0.65f
                canvas.drawBitmap(bmp, null, RectF(rowRect.left + 16f, rowRect.centerY() - s / 2, rowRect.left + 16f + s, rowRect.centerY() + s / 2), bitmapPaint)
            }

            drawOutlinedText(canvas, meals[i].first, rowRect.centerX() - 20f, rowRect.top + 30f, 16.5f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "${meals[i].second}  —  ${meals[i].third}", rowRect.centerX() - 20f, rowRect.top + 58f, 13f, Color.WHITE)

            val cookBtn = RectF(rowRect.right - 185f, rowRect.centerY() - 22f, rowRect.right - 18f, rowRect.centerY() + 22f)
            drawGlossyButton(canvas, cookBtn, "🍳 اطبخ للجيش", Color.parseColor("#F4511E"), Color.parseColor("#BF360C"), textSize = 14.5f)
        }
    }

    private fun drawArmyTrainModal(canvas: Canvas, panel: RectF) {
        val mhLv = getManorHallLevel()
        drawOutlinedText(canvas, "🛡️ معسكر تدريب جيش المزرعة والتميمة الخضراء (قاعة مستوى $mhLv)", panel.centerX(), panel.top + 40f, 19.5f, Color.parseColor("#69F0AE"))

        val units = UType.values()
        val cols = 3
        val rows = 2
        val pad = 14f
        val gridTop = panel.top + 62f
        val cellW = (panel.width() - pad * (cols + 1)) / cols
        val cellH = (panel.height() - 148f - pad * (rows + 1)) / rows

        for (i in units.indices) {
            val u = units[i]
            val c = i % cols
            val r = i / cols
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            val unlocked = mhLv >= u.unlockManorLv

            drawGlossyPanel(canvas, card,
                if (unlocked) Color.parseColor("#DD102818") else Color.parseColor("#AA263238"),
                if (unlocked) Color.parseColor("#00E676") else Color.parseColor("#78909C"), 16f)

            bitmaps[u.spriteKey]?.let { bmp ->
                bitmapPaint.alpha = 255
                val s = min(cellW * 0.34f, cellH * 0.62f)
                val dst = RectF(card.left + 12f, card.centerY() - s * 0.5f, card.left + 12f + s, card.centerY() + s * 0.5f)
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }

            val cnt = armyRoster[u] ?: 0
            drawOutlinedText(canvas, "${u.titleAr} (x$cnt)", card.centerX() + 34f, card.top + 28f, 15f, Color.WHITE)
            drawOutlinedText(canvas, "❤️${u.maxHp} | ⚔️${u.damage}", card.centerX() + 34f, card.top + 50f, 12.5f, Color.parseColor("#A5D6A7"))

            val btnR = RectF(card.left + cellW * 0.36f, card.bottom - 40f, card.right - 12f, card.bottom - 8f)
            val label = if (unlocked) "تدريب (🧪${u.costElixir} + 🍗${u.costRations})" else "🔒 قاعة مستوى ${u.unlockManorLv}"
            drawGlossyButton(canvas, btnR, label,
                if (unlocked) Color.parseColor("#00C853") else Color.parseColor("#546E7A"),
                if (unlocked) Color.parseColor("#00695C") else Color.parseColor("#37474F"),
                textSize = 12f)
        }

        val spellBarY = panel.bottom - 70f
        val spells = SpellType.values()
        val spW = (panel.width() - pad * 4) / 3f
        for (j in spells.indices) {
            val sp = spells[j]
            val sx = panel.left + pad + j * (spW + pad)
            val sr = RectF(sx, spellBarY, sx + spW, panel.bottom - 14f)
            val cnt = spellCounts[sp] ?: 0
            drawGlossyButton(canvas, sr, "${sp.icon} ${sp.titleAr} (x$cnt) - 🧪${sp.costElixir}", Color.parseColor("#2E7D32"), Color.parseColor("#1B5E20"), textSize = 13f)
        }
    }

    private fun drawContractsModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "🚚 عقود تموين المزارع وتجارة المحاصيل", panel.centerX(), panel.top + 40f, 20f, Color.parseColor("#FFD54F"))
        drawOutlinedText(canvas, "مخزنك: 🌾 قمح ($wheatCount)  |  🥩 لحوم ($meatCount)  |  🥖 خبز ($breadCount)", panel.centerX(), panel.top + 70f, 15.5f, Color.WHITE)

        val startY = panel.top + 94f
        val rowH = (panel.height() - 118f) / 3f
        for (i in contracts.indices) {
            val o = contracts[i]
            val ry = startY + i * rowH
            val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
            drawGlossyPanel(canvas, rowRect, Color.parseColor("#DD1B3B22"), Color.parseColor("#00E676"), 16f)

            drawOutlinedText(canvas, "📜 ${o.title}", rowRect.left + 180f, rowRect.top + 28f, 16f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "المطلوب: 🌾${o.reqWheat}  🥩${o.reqMeat}  🥖${o.reqBread}", rowRect.left + 180f, rowRect.top + 56f, 14f, Color.WHITE)
            drawOutlinedText(canvas, "المكافأة: +${o.rewardGold}🪙  +${o.rewardElixir}🧪  +${o.rewardRations}🍗", rowRect.centerX() + 45f, rowRect.centerY() + 6f, 15f, Color.parseColor("#69F0AE"))

            val sendBtn = RectF(rowRect.right - 175f, rowRect.centerY() - 22f, rowRect.right - 16f, rowRect.centerY() + 22f)
            drawGlossyButton(canvas, sendBtn, "🚚 إرسال الشحنة", Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 14f)
        }
    }

    private fun drawGuildWarModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "🏰 تحالف [$guildName] — حرب المزارع 5v5 والسحابة", panel.centerX(), panel.top + 40f, 19.5f, Color.parseColor("#69F0AE"))

        val leftBox = RectF(panel.left + 20f, panel.top + 64f, panel.centerX() - 10f, panel.bottom - 20f)
        drawGlossyPanel(canvas, leftBox, Color.parseColor("#DD102818"), Color.parseColor("#FFD54F"), 16f)
        drawOutlinedText(canvas, "⚔️ حرب التحالفات (5 ضد 5)", leftBox.centerX(), leftBox.top + 32f, 17.5f, Color.parseColor("#FFD54F"))
        drawOutlinedText(canvas, "تحالفنا ⭐ $warOurStars  —  $warEnemyStars ⭐ الخصم", leftBox.centerX(), leftBox.top + 66f, 18.5f, Color.WHITE)
        drawOutlinedText(canvas, "هجمات الحرب المتبقية: $warAttacksLeft / 2", leftBox.centerX(), leftBox.top + 96f, 14f, Color.parseColor("#A5D6A7"))

        val warAtkBtn = RectF(leftBox.left + 24f, leftBox.top + 116f, leftBox.right - 24f, leftBox.top + 164f)
        drawGlossyButton(canvas, warAtkBtn, "🔥 هجوم حرب التحالفات (+غنائم مضاعفة)", Color.parseColor("#FF6F00"), Color.parseColor("#E65100"), textSize = 14.5f)

        val syncBtn = RectF(leftBox.left + 24f, leftBox.top + 178f, leftBox.right - 24f, leftBox.top + 226f)
        drawGlossyButton(canvas, syncBtn, "☁️ مزامنة مع apk3rade.mangoad.online", Color.parseColor("#1E88E5"), Color.parseColor("#0D47A1"), textSize = 13.5f)

        val editGuildBtn = RectF(leftBox.left + 24f, leftBox.top + 240f, leftBox.right - 24f, leftBox.top + 288f)
        drawGlossyButton(canvas, editGuildBtn, "✏️ تغيير اسم اللاعب / التحالف", Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 13.5f)

        val rightBox = RectF(panel.centerX() + 10f, panel.top + 64f, panel.right - 20f, panel.bottom - 20f)
        drawGlossyPanel(canvas, rightBox, Color.parseColor("#DD102818"), Color.parseColor("#00E676"), 16f)
        drawOutlinedText(canvas, "💬 دردشة التحالف وطلب المؤونة", rightBox.centerX(), rightBox.top + 32f, 16.5f, Color.parseColor("#80CBC4"))

        var chatY = rightBox.top + 64f
        for (msg in guildChat.takeLast(6)) {
            drawOutlinedText(canvas, msg, rightBox.centerX(), chatY, 12.5f, Color.WHITE)
            chatY += 30f
        }

        val donateBtn = RectF(rightBox.left + 20f, rightBox.bottom - 60f, rightBox.centerX() - 6f, rightBox.bottom - 14f)
        drawGlossyButton(canvas, donateBtn, "🎁 طلب مؤونة ودعم", Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 13f)

        val chatBtn = RectF(rightBox.centerX() + 6f, rightBox.bottom - 60f, rightBox.right - 20f, rightBox.bottom - 14f)
        drawGlossyButton(canvas, chatBtn, "💬 إرسال رسالة", Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 13f)
    }

    private fun drawGemShopModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "💎 خزانة الزمرد والعمال الإضافيين", panel.centerX(), panel.top + 40f, 21f, Color.parseColor("#A7FFEB"))

        val items = listOf(
            Triple("🔨 تعيين عامل بناء إضافي (#${totalBuilders + 1})", "يبني ويطور عدة مباني في نفس الوقت", "50 💎"),
            Triple("🍗 شحنة مؤونة جيش فورية (+150 مؤونة)", "تملأ مخزن طعام الجيش فوراً للهجوم", "20 💎"),
            Triple("🧪 خزان إكسير أخضر مركّز (+2500 إكسير)", "يمد مزرعتك بالإكسير الأخضر النقي", "25 💎"),
            Triple("🪙 صندوق الذهب الملكي (+3000 ذهب)", "عملات ذهبية لترقية قاعة المزرعة والدفاعات", "25 💎")
        )

        val pad = 18f
        val gridTop = panel.top + 68f
        val cellW = (panel.width() - pad * 3) / 2f
        val cellH = (panel.height() - 92f - pad * 3) / 2f

        for (i in items.indices) {
            val c = i % 2
            val r = i / 2
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            drawGlossyPanel(canvas, card, Color.parseColor("#DD004D40"), Color.parseColor("#1DE9B6"), 16f)

            drawOutlinedText(canvas, items[i].first, card.centerX(), card.top + 34f, 16f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, items[i].second, card.centerX(), card.top + 64f, 13f, Color.WHITE)

            val buyBtn = RectF(card.centerX() - 90f, card.bottom - 50f, card.centerX() + 90f, card.bottom - 12f)
            drawGlossyButton(canvas, buyBtn, "شراء (${items[i].third})", Color.parseColor("#00BFA5"), Color.parseColor("#00695C"), textSize = 14.5f)
        }
    }

    // ==================== TOUCH & INPUT HANDLING ====================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x
        val ty = event.y

        if (isLoadingScreen) {
            if (event.action == MotionEvent.ACTION_UP) {
                isLoadingScreen = false
                showBanner("🌱 ابدأ من الصفر! اتبع شريط المهام بأعلى الشاشة لتعمير مزرعتك وتسويرها")
            }
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = tx
                lastTouchY = ty
                touchDownX = tx
                touchDownY = ty
                isDraggingMap = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = tx - lastTouchX
                val dy = ty - lastTouchY
                // Continuous Finger-Drag Wall Painting when in Wall Build Mode!
                if (buildModeType == BType.STONE_WALL && activeModal == ModalType.NONE && ty in 110f..(screenH - 100f)) {
                    val (gxF, gyF) = screenToIso(tx, ty)
                    val gx = gxF.toInt()
                    val gy = gyF.toInt()
                    if (gx in 1..(gridSize - 1) && gy in 1..(gridSize - 1) && isAreaFree(gx, gy, 1, null)) {
                        if (gold >= BType.STONE_WALL.baseCostGold) {
                            gold -= BType.STONE_WALL.baseCostGold
                            val newId = (myBuildings.maxOfOrNull { it.id } ?: 0) + 1
                            myBuildings.add(PlacedBuilding(newId, BType.STONE_WALL, gx, gy, 1, BType.STONE_WALL.maxHp, BType.STONE_WALL.maxHp, tapBounce = 1f))
                            checkQuestProgression()
                            saveVillage()
                        }
                    }
                    lastTouchX = tx
                    lastTouchY = ty
                    return true
                }

                if (hypot(tx - touchDownX, ty - touchDownY) > 14f && activeModal == ModalType.NONE) {
                    isDraggingMap = true
                    camX += dx
                    camY += dy
                    clampCameraToIsland()
                }
                lastTouchX = tx
                lastTouchY = ty
            }
            MotionEvent.ACTION_UP -> {
                if (!isDraggingMap) {
                    handleTap(tx, ty)
                }
            }
        }
        return true
    }

    private fun handleTap(tx: Float, ty: Float) {
        if (activeModal != ModalType.NONE) {
            handleModalTap(tx, ty)
            return
        }

        val pad = 12f
        if (tx <= 260f && ty in 52f..96f) {
            activeModal = ModalType.KITCHEN_RATIONS
            return
        }
        if (tx >= screenW - 180f && ty in 92f..136f) {
            activeModal = ModalType.GEM_SHOP
            return
        }

        // Top-Center Quick Wall Ring & Demo Fortress Toggle Buttons
        if (!inRaidMode && ty in 58f..98f) {
            val autoWallBtn = RectF(screenW * 0.31f, 62f, screenW * 0.50f, 96f)
            val demoBtn = RectF(screenW * 0.51f, 62f, screenW * 0.69f, 96f)
            if (autoWallBtn.contains(tx, ty)) {
                autoSurroundManorWithWalls()
                return
            }
            if (demoBtn.contains(tx, ty)) {
                if (myBuildings.size <= 3) {
                    buildSampleWalledFortress()
                } else {
                    resetToZeroScratchStart()
                    showBanner("🔄 تمت إعادة اللعبة للبداية من الصفر (قاعة المزرعة فقط + الأرض البرية)!")
                }
                return
            }
        }

        // Cancel active buildModeType banner tap
        if (!inRaidMode && buildModeType != null) {
            val bCard = RectF(screenW * 0.32f, screenH - pad - 76f, screenW * 0.62f, screenH - pad - 12f)
            if (bCard.contains(tx, ty)) {
                buildModeType = null
                showBanner("✅ تم إنهاء وضع البناء")
                return
            }
        }

        // Selected Building Contextual Inspector Card
        if (!inRaidMode && buildModeType == null && selectedBuilding != null) {
            val infoW = 420f
            val infoRect = RectF(screenW * 0.5f - infoW * 0.5f, screenH - pad - 146f, screenW * 0.5f + infoW * 0.5f, screenH - pad - 82f)
            if (infoRect.contains(tx, ty)) {
                val b = selectedBuilding!!
                val upBtn = RectF(infoRect.right - 205f, infoRect.top + 10f, infoRect.right - 84f, infoRect.bottom - 10f)
                val moveBtn = RectF(infoRect.right - 78f, infoRect.top + 10f, infoRect.right - 10f, infoRect.bottom - 10f)
                if (upBtn.contains(tx, ty)) {
                    triggerBuildingUpgrade(b)
                    return
                }
                if (moveBtn.contains(tx, ty)) {
                    moveBuildingMode = !moveBuildingMode
                    showBanner(if (moveBuildingMode) "✥ انقر على أي مربع فارغ لنقل ${b.type.titleAr}" else "✅ تم تثبيت موقع المبنى")
                    return
                }
                return
            }
        }

        // Bottom Corner Buttons
        if (!inRaidMode) {
            val atkRect = RectF(pad, screenH - pad - 84f, pad + 148f, screenH - pad)
            if (atkRect.contains(tx, ty)) {
                startRaidBattle(false)
                return
            }
            val guildRect = RectF(atkRect.right + 10f, screenH - pad - 68f, atkRect.right + 142f, screenH - pad)
            if (guildRect.contains(tx, ty)) {
                activeModal = ModalType.GUILD_WAR
                return
            }

            val cardW = 112f
            val cardH = 68f
            val gap = 8f
            val startRightX = screenW - pad - (cardW * 4 + gap * 3)
            if (ty >= screenH - pad - cardH && tx >= startRightX) {
                val idx = ((tx - startRightX) / (cardW + gap)).toInt().coerceIn(0, 3)
                when (idx) {
                    0 -> activeModal = ModalType.BUILD_SHOP
                    1 -> activeModal = ModalType.KITCHEN_RATIONS
                    2 -> activeModal = ModalType.ARMY_TRAIN
                    3 -> activeModal = ModalType.TRUCK_ORDERS
                }
                return
            }
        } else {
            val barH = 76f
            val topY = screenH - barH - pad
            if (ty >= topY) {
                val totalSlots = 11
                val gap = 6f
                val slotW = (screenW - pad * 2 - gap * (totalSlots - 1)) / totalSlots
                val idx = ((tx - pad) / (slotW + gap)).toInt().coerceIn(0, totalSlots - 1)
                when (idx) {
                    in 0..5 -> {
                        selectedSpell = null
                        selectedUnitType = UType.values()[idx]
                    }
                    in 6..8 -> {
                        selectedSpell = SpellType.values()[idx - 6]
                    }
                    9 -> deployGuildReinforcements()
                    10 -> endRaidBattle()
                }
                return
            }
        }

        // Tap on Isometric World
        val (gxF, gyF) = screenToIso(tx, ty)
        val gx = gxF.toInt()
        val gy = gyF.toInt()

        if (inRaidMode) {
            if (gxF in -1.5f..(gridSize + 1.5f) && gyF in -1.5f..(gridSize + 1.5f)) {
                val sp = selectedSpell
                if (sp != null) {
                    val cnt = spellCounts[sp] ?: 0
                    if (cnt > 0) {
                        spellCounts[sp] = cnt - 1
                        if (sp == SpellType.CORN_BARRAGE) {
                            for (b in raidBuildings) {
                                val bx = b.gx + b.type.footprint * 0.5f
                                val by = b.gy + b.type.footprint * 0.5f
                                if (hypot(bx - gxF, by - gyF) <= 2.8f) {
                                    b.hp -= 380
                                    checkBuildingDestroyed(b)
                                }
                            }
                            floatTexts.add(FloatText("🌽💥 قصف الذرة الذهبية!", tx, ty, Color.parseColor("#FFD600")))
                        } else {
                            activeSpells.add(ActiveSpellZone(sp, gxF, gyF, 180))
                            floatTexts.add(FloatText("${sp.icon} ${sp.titleAr}!", tx, ty, sp.color))
                        }
                    } else {
                        showBanner("⚠️ نفدت هذه التميمة!")
                    }
                } else {
                    val cnt = armyRoster[selectedUnitType] ?: 0
                    if (cnt > 0) {
                        armyRoster[selectedUnitType] = cnt - 1
                        val uHp = selectedUnitType.maxHp
                        activeUnits.add(ActiveUnit(selectedUnitType, gxF, gyF, uHp, uHp))
                    } else {
                        showBanner("⚠️ نفدت وحدات ${selectedUnitType.titleAr}!")
                    }
                }
            }
            return
        }

        // Placing New Building or Wall
        buildModeType?.let { bType ->
            if (gx in 1..(gridSize - bType.footprint) && gy in 1..(gridSize - bType.footprint)) {
                if (isAreaFree(gx, gy, bType.footprint, null)) {
                    if (gold >= bType.baseCostGold && greenElixir >= bType.baseCostElixir) {
                        gold -= bType.baseCostGold
                        greenElixir -= bType.baseCostElixir
                        val newId = (myBuildings.maxOfOrNull { it.id } ?: 0) + 1
                        val placed = PlacedBuilding(newId, bType, gx, gy, 1, bType.maxHp, bType.maxHp, tapBounce = 1.0f)
                        myBuildings.add(placed)
                        selectedBuilding = placed
                        if (bType != BType.STONE_WALL) buildModeType = null
                        checkQuestProgression()
                        saveVillage()
                        showBanner("✅ تم بناء ${bType.titleAr}!")
                    } else {
                        showBanner("⚠️ الموارد غير كافية لبناء ${bType.titleAr}!")
                        buildModeType = null
                    }
                } else {
                    showBanner("⚠️ الموقع مشغول!")
                }
            }
            return
        }

        // Moving Selected Building
        if (moveBuildingMode && selectedBuilding != null) {
            val b = selectedBuilding!!
            if (gx in 1..(gridSize - b.type.footprint) && gy in 1..(gridSize - b.type.footprint)) {
                if (isAreaFree(gx, gy, b.type.footprint, b)) {
                    b.gx = gx
                    b.gy = gy
                    b.tapBounce = 1.0f
                    moveBuildingMode = false
                    saveVillage()
                    showBanner("✅ تم نقل ${b.type.titleAr}!")
                } else {
                    showBanner("⚠️ الموقع مشغول!")
                }
            }
            return
        }

        // Tap Wild Obstacle to Clear it for Gems!
        val tappedObs = wildObstacles.firstOrNull { ob -> gx == ob.gx && gy == ob.gy }
        if (tappedObs != null) {
            if (tappedObs.clearTicksLeft == 0) {
                if (gold >= tappedObs.type.clearCostGold) {
                    gold -= tappedObs.type.clearCostGold
                    tappedObs.clearTicksLeft = 90 // 3 seconds
                    showBanner("⛏️ جاري إزالة ${tappedObs.type.titleAr} لاستخراج الجواهر 💎!")
                } else {
                    showBanner("⚠️ تحتاج ${tappedObs.type.clearCostGold} ذهب 🪙 لإزالة هذا العائق!")
                }
            }
            return
        }

        // Tap Existing Building
        val tapped = myBuildings.firstOrNull { b ->
            val fp = b.type.footprint
            gx >= b.gx && gx < b.gx + fp && gy >= b.gy && gy < b.gy + fp
        }
        if (tapped != null) {
            tapped.tapBounce = 0.85f
            if (tapped.readyCollect) {
                tapped.readyCollect = false
                val sx = isoToScreenX(tapped.gx.toFloat(), tapped.gy.toFloat())
                val sy = isoToScreenY(tapped.gx.toFloat(), tapped.gy.toFloat())
                when (tapped.type) {
                    BType.WHEAT_FIELD -> {
                        val amt = 2 + tapped.level
                        wheatCount += amt
                        floatTexts.add(FloatText("+$amt قمح 🌾", sx, sy, Color.parseColor("#FFD54F")))
                    }
                    BType.RANCH_BARN -> {
                        val amt = 1 + tapped.level
                        meatCount += amt
                        floatTexts.add(FloatText("+$amt لحوم 🥩", sx, sy, Color.parseColor("#FFAB91")))
                    }
                    BType.WINDMILL_BAKERY -> {
                        val amt = 1 + tapped.level
                        breadCount += amt
                        armyRations += amt * 12
                        floatTexts.add(FloatText("+$amt خبز 🥖 (+${amt * 12} مؤونة 🍗)", sx, sy, Color.parseColor("#FFE082")))
                    }
                    BType.GREEN_ELIXIR -> {
                        val amt = 180 * tapped.level
                        greenElixir += amt
                        floatTexts.add(FloatText("+$amt إكسير أخضر 🧪", sx, sy, Color.parseColor("#00E676")))
                    }
                    BType.GOLD_SILO -> {
                        val amt = 190 * tapped.level
                        gold += amt
                        floatTexts.add(FloatText("+$amt ذهب 🪙", sx, sy, Color.parseColor("#FFD54F")))
                    }
                    BType.FIELD_KITCHEN -> {
                        val amt = 25 * tapped.level
                        armyRations += amt
                        floatTexts.add(FloatText("+$amt مؤونة جيش 🍗", sx, sy, Color.parseColor("#FFAB40")))
                    }
                    else -> {}
                }
                checkQuestProgression()
                saveVillage()
            } else if (tapped.type == BType.FIELD_KITCHEN && selectedBuilding == tapped) {
                activeModal = ModalType.KITCHEN_RATIONS
            }
            selectedBuilding = tapped
        } else {
            selectedBuilding = null
            moveBuildingMode = false
        }
    }

    private fun triggerBuildingUpgrade(b: PlacedBuilding) {
        if (b.upgradeTicksLeft > 0) {
            if (gems >= 10) {
                gems -= 10
                b.upgradeTicksLeft = 1
                saveVillage()
            } else {
                showBanner("⚠️ تحتاج 10 جواهر 💎 للإنهاء الفوري!")
            }
            return
        }
        if (b.level >= 3) {
            showBanner("✅ هذا المبنى وصل لأقصى مستوى (Lv.3)!")
            return
        }
        val mhLv = getManorHallLevel()
        if (b.type != BType.MANOR_HALL && b.level + 1 > mhLv) {
            showBanner("⚠️ يجب ترقية قاعة المزرعة الكبرى للمستوى ${b.level + 1} أولاً!")
            return
        }
        if (busyBuildersCount() >= totalBuilders) {
            showBanner("⚠️ جميع العمال مشغولون!")
            return
        }
        val upCost = (b.type.baseCostGold + b.type.baseCostElixir + 160) * b.level
        if (gold >= upCost) {
            gold -= upCost
            b.upgradeTotalTicks = 10 * 30
            b.upgradeTicksLeft = b.upgradeTotalTicks
            saveVillage()
            showBanner("🔨 بدأت ترقية ${b.type.titleAr} إلى المستوى ${b.level + 1}!")
        } else {
            showBanner("⚠️ تحتاج $upCost ذهب 🪙 للترقية!")
        }
    }

    private fun deployGuildReinforcements() {
        if (guildReinforcementsCalled) {
            showBanner("⚠️ تم استدعاء دعم التحالف بالفعل!")
            return
        }
        guildReinforcementsCalled = true
        activeUnits.add(ActiveUnit(UType.MECH, 1.2f, 6.5f, UType.MECH.maxHp, UType.MECH.maxHp, isGuildAlly = true))
        activeUnits.add(ActiveUnit(UType.ALCHEMIST, 1.5f, 5.8f, UType.ALCHEMIST.maxHp, UType.ALCHEMIST.maxHp, isGuildAlly = true))
        activeUnits.add(ActiveUnit(UType.BRUISER, 1.5f, 7.2f, UType.BRUISER.maxHp, UType.BRUISER.maxHp, isGuildAlly = true))
        showBanner("🏰 وصلت قوات دعم التحالف [$guildName]!")
    }

    private fun isAreaFree(gx: Int, gy: Int, fp: Int, ignore: PlacedBuilding?): Boolean {
        for (b in myBuildings) {
            if (b == ignore) continue
            val bFp = b.type.footprint
            val overlapX = gx < b.gx + bFp && gx + fp > b.gx
            val overlapY = gy < b.gy + bFp && gy + fp > b.gy
            if (overlapX && overlapY) return false
        }
        for (ob in wildObstacles) {
            val overlapX = gx < ob.gx + 1 && gx + fp > ob.gx
            val overlapY = gy < ob.gy + 1 && gy + fp > ob.gy
            if (overlapX && overlapY) return false
        }
        return true
    }

    private fun handleModalTap(tx: Float, ty: Float) {
        val panel = RectF(screenW * 0.08f, screenH * 0.11f, screenW * 0.92f, screenH * 0.87f)
        val closeBtn = RectF(panel.right - 56f, panel.top + 12f, panel.right - 12f, panel.top + 54f)
        if (closeBtn.contains(tx, ty) || !panel.contains(tx, ty)) {
            activeModal = ModalType.NONE
            return
        }

        when (activeModal) {
            ModalType.BUILD_SHOP -> {
                val mhLv = getManorHallLevel()
                val buildable = BType.values().filter { it != BType.MANOR_HALL }
                val cols = 4
                val rows = 3
                val pad = 12f
                val gridTop = panel.top + 62f
                val cellW = (panel.width() - pad * (cols + 1)) / cols
                val cellH = (panel.height() - 78f - pad * (rows + 1)) / rows
                for (i in buildable.indices) {
                    val c = i % cols
                    val r = i / cols
                    val cx = panel.left + pad + c * (cellW + pad)
                    val cy = gridTop + r * (cellH + pad)
                    val card = RectF(cx, cy, cx + cellW, cy + cellH)
                    if (card.contains(tx, ty)) {
                        val t = buildable[i]
                        if (mhLv < t.unlockManorLv) {
                            showBanner("🔒 يفتح مبنى ${t.titleAr} عند ترقية قاعة المزرعة للمستوى ${t.unlockManorLv}!")
                        } else {
                            buildModeType = t
                            activeModal = ModalType.NONE
                            showBanner(if (t == BType.STONE_WALL) "🧱 انقر أو اسحب إصبعك لرسم خط أسوار متصل حول قاعتك!" else "🏗️ انقر على أي مربع أخضر في المزرعة لبناء ${t.titleAr}")
                        }
                        return
                    }
                }
            }

            ModalType.KITCHEN_RATIONS -> {
                val startY = panel.top + 94f
                val rowH = (panel.height() - 120f) / 3f
                for (i in 0..2) {
                    val ry = startY + i * rowH
                    val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
                    if (rowRect.contains(tx, ty)) {
                        when (i) {
                            0 -> if (wheatCount >= 2) { wheatCount -= 2; armyRations += 35; breadCount += 1; showBanner("🥖 تم خبز وجبة المزارع (+35 مؤونة 🍗)!") } else showBanner("⚠️ تحتاج 2 قمح 🌾!")
                            1 -> if (wheatCount >= 2 && meatCount >= 1) { wheatCount -= 2; meatCount -= 1; armyRations += 85; showBanner("🍗 تم طهي وليمة الشواء (+85 مؤونة 🍗)!") } else showBanner("⚠️ تحتاج 2 قمح 🌾 و 1 لحوم 🥩!")
                            2 -> if (wheatCount >= 3 && meatCount >= 2 && greenElixir >= 150) { wheatCount -= 3; meatCount -= 2; greenElixir -= 150; armyRations += 160; attackBuffPercent = 25; showBanner("🔥 تم تجهيز وليمة الزمرد (+160 مؤونة + 25% هجوم)!") } else showBanner("⚠️ تحتاج 3 قمح و 2 لحوم و 150 إكسير أخضر!")
                        }
                        checkQuestProgression()
                        saveVillage()
                        return
                    }
                }
            }

            ModalType.ARMY_TRAIN -> {
                val mhLv = getManorHallLevel()
                val units = UType.values()
                val cols = 3
                val rows = 2
                val pad = 14f
                val gridTop = panel.top + 62f
                val cellW = (panel.width() - pad * (cols + 1)) / cols
                val cellH = (panel.height() - 148f - pad * (rows + 1)) / rows
                for (i in units.indices) {
                    val c = i % cols
                    val r = i / cols
                    val cx = panel.left + pad + c * (cellW + pad)
                    val cy = gridTop + r * (cellH + pad)
                    val card = RectF(cx, cy, cx + cellW, cy + cellH)
                    if (card.contains(tx, ty)) {
                        val u = units[i]
                        if (mhLv < u.unlockManorLv) {
                            showBanner("🔒 تفتح وحدة ${u.titleAr} في قاعة مستوى ${u.unlockManorLv}!")
                            return
                        }
                        if (greenElixir >= u.costElixir && armyRations >= u.costRations) {
                            greenElixir -= u.costElixir
                            armyRations -= u.costRations
                            armyRoster[u] = (armyRoster[u] ?: 0) + 1
                            checkQuestProgression()
                            saveVillage()
                            showBanner("✅ تم تدريب ${u.titleAr}!")
                        } else {
                            showBanner("⚠️ تحتاج 🧪${u.costElixir} إكسير و 🍗${u.costRations} مؤونة لتدريب الجندي!")
                        }
                        return
                    }
                }

                val spellBarY = panel.bottom - 70f
                val spells = SpellType.values()
                val spW = (panel.width() - pad * 4) / 3f
                for (j in spells.indices) {
                    val sp = spells[j]
                    val sx = panel.left + pad + j * (spW + pad)
                    val sr = RectF(sx, spellBarY, sx + spW, panel.bottom - 14f)
                    if (sr.contains(tx, ty)) {
                        if (greenElixir >= sp.costElixir) {
                            greenElixir -= sp.costElixir
                            spellCounts[sp] = (spellCounts[sp] ?: 0) + 1
                            saveVillage()
                            showBanner("${sp.icon} تم تحضير ${sp.titleAr}!")
                        } else {
                            showBanner("⚠️ الإكسير الأخضر 🧪 غير كافٍ!")
                        }
                        return
                    }
                }
            }

            ModalType.TRUCK_ORDERS -> {
                val startY = panel.top + 94f
                val rowH = (panel.height() - 118f) / 3f
                for (i in contracts.indices) {
                    val ry = startY + i * rowH
                    val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
                    if (rowRect.contains(tx, ty)) {
                        val o = contracts[i]
                        if (wheatCount >= o.reqWheat && meatCount >= o.reqMeat && breadCount >= o.reqBread) {
                            wheatCount -= o.reqWheat
                            meatCount -= o.reqMeat
                            breadCount -= o.reqBread
                            gold += o.rewardGold
                            greenElixir += o.rewardElixir
                            armyRations += o.rewardRations
                            contracts[i] = FarmContract(
                                "عقد تموين متجدد #${Random.nextInt(10, 99)}",
                                Random.nextInt(2, 5), Random.nextInt(0, 3), Random.nextInt(0, 2),
                                550 + Random.nextInt(450), 480 + Random.nextInt(400), 45 + Random.nextInt(40)
                            )
                            saveVillage()
                            showBanner("🚚 تم تسليم الشحنة واستلام المكافأة!")
                        } else {
                            showBanner("⚠️ المنتجات الزراعية غير كافية!")
                        }
                        return
                    }
                }
            }

            ModalType.GUILD_WAR -> {
                val leftBox = RectF(panel.left + 20f, panel.top + 64f, panel.centerX() - 10f, panel.bottom - 20f)
                val warAtkBtn = RectF(leftBox.left + 24f, leftBox.top + 116f, leftBox.right - 24f, leftBox.top + 164f)
                val syncBtn = RectF(leftBox.left + 24f, leftBox.top + 178f, leftBox.right - 24f, leftBox.top + 226f)
                val editGuildBtn = RectF(leftBox.left + 24f, leftBox.top + 240f, leftBox.right - 24f, leftBox.top + 288f)

                if (warAtkBtn.contains(tx, ty)) {
                    if (warAttacksLeft > 0) {
                        warAttacksLeft--
                        startRaidBattle(true)
                    } else {
                        showBanner("⚠️ استنفدت هجمات الحرب اليوم!")
                    }
                    return
                }
                if (syncBtn.contains(tx, ty)) {
                    syncWithInfinityFreeServer()
                    return
                }
                if (editGuildBtn.contains(tx, ty)) {
                    promptEditGuildDialog()
                    return
                }

                val rightBox = RectF(panel.centerX() + 10f, panel.top + 64f, panel.right - 20f, panel.bottom - 20f)
                val donateBtn = RectF(rightBox.left + 20f, rightBox.bottom - 60f, rightBox.centerX() - 6f, rightBox.bottom - 14f)
                val chatBtn = RectF(rightBox.centerX() + 6f, rightBox.bottom - 60f, rightBox.right - 20f, rightBox.bottom - 14f)

                if (donateBtn.contains(tx, ty)) {
                    armyRoster[UType.MECH] = (armyRoster[UType.MECH] ?: 0) + 1
                    armyRations += 40
                    guildChat.add("🎁 التحالف دعم $playerName بآلي حصاد + 40 مؤونة 🍗!")
                    showBanner("🎁 استلمت آلي حصاد + 40 مؤونة جيش!")
                    saveVillage()
                    return
                }
                if (chatBtn.contains(tx, ty)) {
                    promptGuildChatMessage()
                    return
                }
            }

            ModalType.GEM_SHOP -> {
                val pad = 18f
                val gridTop = panel.top + 68f
                val cellW = (panel.width() - pad * 3) / 2f
                val cellH = (panel.height() - 92f - pad * 3) / 2f
                for (i in 0..3) {
                    val c = i % 2
                    val r = i / 2
                    val cx = panel.left + pad + c * (cellW + pad)
                    val cy = gridTop + r * (cellH + pad)
                    val card = RectF(cx, cy, cx + cellW, cy + cellH)
                    if (card.contains(tx, ty)) {
                        when (i) {
                            0 -> if (gems >= 50) { gems -= 50; totalBuilders++; showBanner("🔨 تم تعيين عامل بناء إضافي!") } else showBanner("⚠️ تحتاج 50 جوهرة 💎!")
                            1 -> if (gems >= 20) { gems -= 20; armyRations += 150; showBanner("🍗 +150 مؤونة جيش!") } else showBanner("⚠️ تحتاج 20 جوهرة 💎!")
                            2 -> if (gems >= 25) { gems -= 25; greenElixir += 2500; showBanner("🧪 +2500 إكسير أخضر!") } else showBanner("⚠️ تحتاج 25 جوهرة 💎!")
                            3 -> if (gems >= 25) { gems -= 25; gold += 3000; showBanner("🪙 +3000 ذهب!") } else showBanner("⚠️ تحتاج 25 جوهرة 💎!")
                        }
                        saveVillage()
                        return
                    }
                }
            }
            else -> {}
        }
    }

    private fun promptEditGuildDialog() {
        post {
            val layout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 24, 40, 16)
            }
            val nameIn = EditText(context).apply {
                hint = "اسم القائد"
                setText(playerName)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            val guildIn = EditText(context).apply {
                hint = "اسم التحالف"
                setText(guildName)
                inputType = InputType.TYPE_CLASS_TEXT
            }
            layout.addView(nameIn)
            layout.addView(guildIn)
            AlertDialog.Builder(context)
                .setTitle("تعديل بيانات القائد والتحالف")
                .setView(layout)
                .setPositiveButton("حفظ") { _, _ ->
                    val n = nameIn.text.toString().trim()
                    val c = guildIn.text.toString().trim()
                    if (n.isNotEmpty()) playerName = n
                    if (c.isNotEmpty()) guildName = c
                    saveVillage()
                    showBanner("✅ تم تحديث الاسم إلى $playerName [$guildName]")
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun promptGuildChatMessage() {
        post {
            val input = EditText(context).apply {
                hint = "اكتب رسالتك لأعضاء التحالف..."
                inputType = InputType.TYPE_CLASS_TEXT
            }
            AlertDialog.Builder(context)
                .setTitle("💬 دردشة التحالف [$guildName]")
                .setView(input)
                .setPositiveButton("إرسال") { _, _ ->
                    val txt = input.text.toString().trim()
                    if (txt.isNotEmpty()) {
                        guildChat.add("$playerName: $txt")
                        syncWithInfinityFreeServer(txt)
                    }
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }
    }

    private fun syncWithInfinityFreeServer(chatMsg: String = "") {
        showBanner("☁️ جاري المزامنة مع apk3rade.mangoad.online ...")
        thread {
            try {
                val params = "action=sync_clan" +
                    "&player=" + URLEncoder.encode(playerName, "UTF-8") +
                    "&clan=" + URLEncoder.encode(guildName, "UTF-8") +
                    "&trophies=" + trophies +
                    "&msg=" + URLEncoder.encode(chatMsg, "UTF-8")
                val resp = requestCloud(serverUrl, params)
                if (resp.isNotEmpty()) {
                    guildChat.add("☁️ السحابة: تمت المزامنة مع السيرفر بنجاح!")
                    showBanner("✅ تمت المزامنة مع السيرفر بنجاح!")
                } else {
                    showBanner("ℹ️ تم الحفظ محلياً")
                }
            } catch (_: Exception) {
                showBanner("ℹ️ تم الحفظ محلياً")
            }
        }
    }

    private fun requestCloud(urlStr: String, postBody: String): String {
        var conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 7000
            readTimeout = 7000
            doOutput = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
            ifCookie?.let { setRequestProperty("Cookie", it) }
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(postBody) }
        var text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }

        if (text.contains("slowAES.decrypt")) {
            val solved = solveInfinityFreeChallenge(text)
            if (solved != null) {
                ifCookie = "__test=$solved"
                val sep = if (urlStr.contains("?")) "&" else "?"
                conn = (URL("${urlStr}${sep}i=1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile)")
                    setRequestProperty("Cookie", ifCookie)
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(postBody) }
                text = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            }
        }
        return text
    }

    private fun solveInfinityFreeChallenge(html: String): String? {
        return try {
            val hexRegex = Regex("toNumbers\\(\"([0-9a-fA-F]{32})\"\\)")
            val matches = hexRegex.findAll(html).map { it.groupValues[1] }.toList()
            if (matches.size >= 3) {
                val key = hexToBytes(matches[0])
                val iv = hexToBytes(matches[1])
                val cipherBytes = hexToBytes(matches[2])
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                bytesToHex(cipher.doFinal(cipherBytes))
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}

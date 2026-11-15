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
 * FARM WARS (حروب المزارع) - Standalone 2.5D Stylized Strategy & Farming Engine
 * - Non-pixel 3D/2.5D hand-painted artwork & animated splash screen
 * - 3 Farm Manor Hall Tiers (Lv.1 Homestead, Lv.2 Stone Keep, Lv.3 Grand Emerald Citadel)
 * - 3 Distinct Upgrade Levels per building with new unlocks at each Manor Hall tier
 * - Green Bio-Elixir (#00E676) + Army Rations/Food preparation system required to train & raid
 * - Living 2.5D animations: chimney smoke, spinning windmills, bubbling green elixir tanks,
 *   swaying wheat fields, levitating emerald crystals, roaming farm workers, and recoiling turrets
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    @Volatile private var running = false
    private var gameThread: Thread? = null
    private val prefs = context.getSharedPreferences("farm_wars_25d_save_v3", Context.MODE_PRIVATE)

    // Screen & Camera
    private var screenW = 1280f
    private var screenH = 720f
    private var camX = 0f
    private var camY = 0f
    private var camZoom = 1.0f

    // Isometric 14x14 Farm Grid
    private val gridSize = 14
    private val tileW = 106f
    private val tileH = 54f

    // Assets
    private val bitmaps = HashMap<String, Bitmap>()
    private var splashBg: Bitmap? = null
    private var islandBg: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Professional Loading Screen State
    private var isLoadingScreen = true
    private var loadingProgress = 0f
    private var loadingReadyToEnter = false
    private var loadingTipIndex = 0
    private val loadingTips = listOf(
        "نصيحة: اجمع القمح واللحوم واطبخ مؤونة الجيش 🍗 في مطبخ المزرعة قبل شن أي هجوم!",
        "نصيحة: الإكسير الأخضر 🧪 يُستخرج من مضخات الطاقة الحيوية ويُستخدم لتدريب جيش المزارع.",
        "نصيحة: ترقية قاعة المزرعة (Manor Hall) للمستوى 2 و 3 يفتح دفاعات وجنوداً وترقيات جديدة!",
        "نصيحة: لكل مبنى 3 مستويات ترقية تزيد الإنتاج والقوة وتضيف تأثيرات زمردية وذهبية."
    )

    data class SplashEmber(var x: Float, var y: Float, var vx: Float, var vy: Float, var size: Float, var color: Int, var alpha: Int)
    private val splashEmbers = CopyOnWriteArrayList<SplashEmber>()

    // Core Player Resources
    private var gold = 2500
    private var greenElixir = 2000     // Green Bio-Elixir (#00E676)
    private var armyRations = 90       // Prepared Food/Rations required to train & raid!
    private var gems = 150
    private var trophies = 120
    private var wheatCount = 12
    private var meatCount = 6
    private var breadCount = 5
    private var attackBuffPercent = 0  // Earned by cooking Royal Banquet in Field Kitchen!

    private var playerName = "LordFarmer"
    private var guildName = "Emerald Harvest"
    private var guildRole = "Leader"
    private var serverUrl = "https://apk3rade.mangoad.online/server_api.php"
    private var ifCookie: String? = null
    private var statusBanner = "مرحباً بك في FARM WARS! جهّز مؤونة الجيش 🍗 من المطبخ وطور قاعة المزرعة!"
    private var statusBannerTimer = 240

    // Building Catalog (13 Non-Pixel 2.5D Buildings with Manor Hall Unlock Requirements & Max Level 3)
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
        val atkCooldownMax: Int,
        val descriptionAr: String
    ) {
        MANOR_HALL("قاعة المزرعة الكبرى", "Manor Hall", "manor_lv1", 2, 1000, 0, 2600, 1, false, 0f, 0, 0, "قلب المزرعة؛ ترقيتها للمستوى 2 و 3 تفتح مباني وجنوداً وترقيات جديدة"),
        WHEAT_FIELD("حقل القمح الذهبي", "Wheat Field", "wheat_field", 2, 180, 0, 750, 1, false, 0f, 0, 0, "ينتج القمح الطازج 🌾 اللازم لطبخ مؤونة الجيش"),
        GREEN_ELIXIR("مضخة الإكسير الأخضر", "Bio-Elixir Pump", "green_elixir", 2, 220, 0, 850, 1, false, 0f, 0, 0, "تستخرج الإكسير الأخضر 🧪 لتدريب الجنود وتطوير المعسكرات"),
        GOLD_SILO("صومعة الذهب والحبوب", "Gold Silo", "gold_silo", 2, 0, 220, 950, 1, false, 0f, 0, 0, "تنتج وتخزن العملات الذهبية 🪙 لترقية القلعة والدفاعات"),
        FIELD_KITCHEN("مطبخ مؤونة الجيش", "Field Kitchen", "field_kitchen", 2, 260, 150, 900, 1, false, 0f, 0, 0, "يطبخ القمح واللحوم لتحويلها إلى مؤونة جيش 🍗 ضرورية للهجوم!"),
        ARMY_CAMP("معسكر تدريب المزارعين", "Army Camp", "army_camp", 2, 200, 240, 950, 1, false, 0f, 0, 0, "يدرب وحدات جيش المزرعة باستخدام الإكسير الأخضر والمؤونة"),
        CORN_CANNON("مدفع الذرة المدرع", "Corn Cannon", "corn_cannon", 1, 320, 0, 980, 1, true, 4.6f, 46, 36, "يطلق قذائف ذرة ذهبية متفجرة على المهاجمين الأرضيين"),
        STONE_WALL("سور الحماية الصخري", "Stone Barricade", "stone_wall", 1, 60, 0, 1500, 1, false, 0f, 0, 0, "سور دفاعي متين يعيق تقدم قوات العدو"),
        RANCH_BARN("حظيرة الماشية واللحوم", "Ranch Barn", "ranch_barn", 2, 380, 180, 1050, 2, false, 0f, 0, 0, "(تفتح في قاعة مستوى 2) تنتج اللحوم الفاخرة 🥩 لولائم الجيش"),
        WINDMILL_BAKERY("طاحونة المخبز الآلي", "Windmill Bakery", "windmill_bakery", 2, 420, 220, 1100, 2, false, 0f, 0, 0, "(تفتح في قاعة مستوى 2) تطحن القمح وتنتج الخبز ومؤونة إضافية تلقائياً"),
        GUILD_HALL("قلعة تحالف المزارعين", "Guild Hall", "guild_hall", 2, 550, 350, 1400, 2, false, 0f, 0, 0, "(تفتح في قاعة مستوى 2) تتيح حروب المزارع 5 ضد 5 وطلب الدعم"),
        EMERALD_TOWER("برج الزمرد الأخضر", "Emerald Tower", "emerald_tower", 1, 750, 450, 1350, 3, true, 5.6f, 68, 32, "(يفتح في قاعة مستوى 3) يطلق صواعق إكسير أخضر متوهجة بعيدة المدى"),
        HONEY_MORTAR("هاون قذائف العسل", "Honey Mortar", "honey_mortar", 1, 820, 500, 1450, 3, true, 6.4f, 85, 65, "(يفتح في قاعة مستوى 3) يقصف كرات عسل متفجرة تبطئ وتدمر تجمعات العدو")
    }

    data class PlacedBuilding(
        var id: Int,
        val type: BType,
        var gx: Int,
        var gy: Int,
        var level: Int = 1,            // Strictly 1..3 (3 upgrades per building)
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

    // 6 Original Non-Pixel 2.5D Farm Wars Units (with Food/Ration requirement!)
    enum class UType(
        val titleAr: String,
        val titleEn: String,
        val spriteKey: String,
        val costElixir: Int,
        val costRations: Int,   // Food required to train & feed!
        val unlockManorLv: Int,
        val maxHp: Int,
        val damage: Int,
        val speed: Float,
        val range: Float,
        val prefersWalls: Boolean,
        val prefersDefenses: Boolean
    ) {
        VANGUARD("حارس المذراة", "Pitchfork Vanguard", "unit_vanguard", 50, 4, 1, 320, 34, 0.052f, 0.9f, false, false),
        RANGER("رامية البلوط", "Acorn Ranger", "unit_ranger", 70, 5, 1, 210, 42, 0.056f, 3.6f, false, false),
        BRUISER("عملاق الحظيرة", "Barn Bruiser", "unit_bruiser", 160, 12, 2, 1050, 62, 0.036f, 1.0f, false, true),
        SAPPER("مفجر اليقطين", "Pumpkin Sapper", "unit_sapper", 130, 8, 2, 220, 260, 0.072f, 0.85f, true, false),
        ALCHEMIST("حكيم الإكسير الأخضر", "Emerald Alchemist", "unit_alchemist", 220, 15, 3, 420, 96, 0.046f, 4.1f, false, false),
        MECH("آلي الحصاد المدرع", "Iron Harvest Mech", "unit_mech", 340, 24, 3, 2100, 115, 0.028f, 1.1f, false, true)
    }

    private val armyRoster = mutableMapOf(
        UType.VANGUARD to 8,
        UType.RANGER to 6,
        UType.BRUISER to 2,
        UType.SAPPER to 2,
        UType.ALCHEMIST to 1,
        UType.MECH to 1
    )

    // Tactical Farm Spells (Green Overgrowth Heal, Spicy Pepper Rage, Acorn Barrage)
    enum class SpellType(val titleAr: String, val icon: String, val costElixir: Int, val color: Int) {
        PEPPER_RAGE("حساء الفلفل الحار", "🌶️", 160, Color.parseColor("#FF6D00")),
        GREEN_NECTAR("مطر الإكسير الأخضر", "🧪", 160, Color.parseColor("#00E676")),
        CORN_BARRAGE("قصف الذرة الذهبية", "🌽", 190, Color.parseColor("#FFD600"))
    }
    private val spellCounts = mutableMapOf(
        SpellType.PEPPER_RAGE to 2,
        SpellType.GREEN_NECTAR to 2,
        SpellType.CORN_BARRAGE to 1
    )
    private var selectedSpell: SpellType? = null

    data class ActiveSpellZone(
        val type: SpellType,
        val gx: Float,
        val gy: Float,
        var ticksLeft: Int,
        val radius: Float = 2.6f
    )
    private val activeSpells = CopyOnWriteArrayList<ActiveSpellZone>()

    // Living Village Workers (Roaming between Houses, Fields, and Kitchen!)
    data class VillageWorker(
        var gx: Float,
        var gy: Float,
        var targetGx: Float,
        var targetGy: Float,
        var carryType: Int, // 0=wheat, 1=green_elixir, 2=rations
        var animPhase: Float,
        var waitTicks: Int = 0
    )
    private val villageWorkers = CopyOnWriteArrayList<VillageWorker>()

    // Ambient Chimney Smoke / Green Elixir Bubbles / Sparkles
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

    // Active Battle Units
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

    data class FloatText(
        var text: String,
        var sx: Float,
        var sy: Float,
        val color: Int,
        var life: Int = 50
    )

    // Lists
    private val myBuildings = CopyOnWriteArrayList<PlacedBuilding>()
    private val raidBuildings = CopyOnWriteArrayList<PlacedBuilding>()
    private val activeUnits = CopyOnWriteArrayList<ActiveUnit>()
    private val projectiles = CopyOnWriteArrayList<Projectile>()
    private val floatTexts = CopyOnWriteArrayList<FloatText>()

    // Builders
    private var totalBuilders = 2

    // Modes & Selection
    private var inRaidMode = false
    private var raidTargetName = "مزرعة الجبل الحصينة"
    private var raidLootGold = 950
    private var raidLootElixir = 850
    private var raidLootRations = 45
    private var raidTicksLeft = 180 * 30
    private var raidStars = 0
    private var raidDestructionPct = 0
    private var raidTotalStartBuildings = 1
    private var guildReinforcementsCalled = false

    private var selectedUnitType: UType = UType.VANGUARD
    private var selectedBuilding: PlacedBuilding? = null
    private var buildModeType: BType? = null
    private var moveBuildingMode = false

    // Active Modal Dialog
    enum class ModalType { NONE, BUILD_SHOP, KITCHEN_RATIONS, ARMY_TRAIN, TRUCK_ORDERS, GUILD_WAR, GEM_SHOP }
    private var activeModal = ModalType.NONE

    // Farm Delivery Contracts
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

    // Guild Chat & 5v5 Farm War
    private val guildChat = CopyOnWriteArrayList<String>()
    private var warOurStars = 9
    private var warEnemyStars = 7
    private var warAttacksLeft = 2
    private var animTickGlobal = 0f

    // Touch Gesture State
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var isDraggingMap = false

    init {
        holder.addCallback(this)
        isFocusable = true
        loadAllAssets()
        initSplashEmbers()
        loadOrInitVillage()
        initWorkers()
        resetContracts()
        guildChat.add("الزعيم: لا تنسوا طبخ مؤونة الجيش 🍗 في المطبخ قبل الهجوم في حرب المزارع!")
        guildChat.add("القائد سيف: الإكسير الأخضر 🧪 جاهز لدعم أعضاء التحالف بآلي الحصاد المدرع!")
    }

    private fun initSplashEmbers() {
        splashEmbers.clear()
        for (i in 0 until 28) {
            splashEmbers.add(
                SplashEmber(
                    x = Random.nextFloat() * 1280f,
                    y = Random.nextFloat() * 720f,
                    vx = (Random.nextFloat() - 0.5f) * 1.4f,
                    vy = -1.2f - Random.nextFloat() * 2.2f,
                    size = 4f + Random.nextFloat() * 7f,
                    color = if (i % 2 == 0) Color.parseColor("#00E676") else Color.parseColor("#FFD54F"),
                    alpha = 140 + Random.nextInt(115)
                )
            )
        }
    }

    private fun loadAllAssets() {
        val am = context.assets
        try {
            am.open("iso/farm_wars_splash.jpg").use {
                splashBg = BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {}

        try {
            am.open("iso/iso_island_bg.jpg").use {
                islandBg = BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {}

        val files = listOf(
            "manor_lv1", "manor_lv2", "manor_lv3",
            "green_elixir", "field_kitchen", "gold_silo",
            "wheat_field", "ranch_barn", "army_camp",
            "corn_cannon", "emerald_tower", "guild_hall",
            "windmill_bakery", "honey_mortar", "stone_wall",
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
        return total.coerceAtLeast(10)
    }

    private fun loadOrInitVillage() {
        gold = prefs.getInt("gold", 2500)
        greenElixir = prefs.getInt("greenElixir", 2000)
        armyRations = prefs.getInt("armyRations", 95)
        gems = prefs.getInt("gems", 150)
        trophies = prefs.getInt("trophies", 120)
        wheatCount = prefs.getInt("wheat", 12)
        meatCount = prefs.getInt("meat", 6)
        breadCount = prefs.getInt("bread", 5)
        totalBuilders = prefs.getInt("builders", 2)
        playerName = prefs.getString("playerName", "LordFarmer") ?: "LordFarmer"
        guildName = prefs.getString("guildName", "Emerald Harvest") ?: "Emerald Harvest"

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

        if (myBuildings.isEmpty()) {
            var idGen = 1
            fun addB(t: BType, x: Int, y: Int, lv: Int = 1) {
                val mHp = (t.maxHp * (1.0 + (lv - 1) * 0.45)).toInt()
                myBuildings.add(PlacedBuilding(idGen++, t, x, y, lv, mHp, mHp))
            }
            // Default starting layout (Manor Hall Lv.1 + Starter Farm & Defenses)
            addB(BType.MANOR_HALL, 6, 6, 1)
            addB(BType.FIELD_KITCHEN, 4, 6, 1)
            addB(BType.GREEN_ELIXIR, 8, 6, 1)
            addB(BType.GOLD_SILO, 6, 4, 1)
            addB(BType.WHEAT_FIELD, 3, 8, 1)
            addB(BType.WHEAT_FIELD, 5, 9, 1)
            addB(BType.ARMY_CAMP, 9, 8, 1)
            addB(BType.CORN_CANNON, 5, 5, 1)
            addB(BType.CORN_CANNON, 8, 5, 1)
            addB(BType.STONE_WALL, 4, 4, 1)
            addB(BType.STONE_WALL, 5, 4, 1)
            addB(BType.STONE_WALL, 7, 4, 1)
            addB(BType.STONE_WALL, 8, 4, 1)
            saveVillage()
        }
    }

    private fun initWorkers() {
        villageWorkers.clear()
        for (i in 0 until 5) {
            villageWorkers.add(
                VillageWorker(
                    gx = 5f + (i % 3) * 1.5f,
                    gy = 6f + (i / 2) * 1.5f,
                    targetGx = 4f + Random.nextFloat() * 5f,
                    targetGy = 4f + Random.nextFloat() * 5f,
                    carryType = i % 3,
                    animPhase = i * 1.3f
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
            prefs.edit()
                .putInt("gold", gold)
                .putInt("greenElixir", greenElixir)
                .putInt("armyRations", armyRations)
                .putInt("gems", gems)
                .putInt("trophies", trophies)
                .putInt("wheat", wheatCount)
                .putInt("meat", meatCount)
                .putInt("bread", breadCount)
                .putInt("builders", totalBuilders)
                .putString("playerName", playerName)
                .putString("guildName", guildName)
                .putString("buildings_json", arr.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun resetContracts() {
        contracts.clear()
        contracts.add(FarmContract("عقد تموين الحامية الشرقية", 3, 0, 1, 450, 380, 35))
        contracts.add(FarmContract("عقد وليمة فرسان المزرعة", 4, 2, 1, 780, 640, 65))
        contracts.add(FarmContract("شحنة قلعة الزمرد الكبرى", 5, 3, 2, 1200, 950, 95))
    }

    private fun busyBuildersCount(): Int = myBuildings.count { it.upgradeTicksLeft > 0 }

    // Isometric Coordinate Conversions
    private fun isoToScreenX(gx: Float, gy: Float): Float {
        val originX = screenW * 0.50f + camX
        return originX + (gx - gy) * (tileW * 0.5f) * camZoom
    }

    private fun isoToScreenY(gx: Float, gy: Float): Float {
        val originY = screenH * 0.20f + camY
        return originY + (gx + gy) * (tileH * 0.5f) * camZoom
    }

    private fun screenToIso(sx: Float, sy: Float): Pair<Float, Float> {
        val originX = screenW * 0.50f + camX
        val originY = screenH * 0.20f + camY
        val dx = (sx - originX) / (tileW * 0.5f * camZoom)
        val dy = (sy - originY) / (tileH * 0.5f * camZoom)
        val gx = (dy + dx) * 0.5f
        val gy = (dy - dx) * 0.5f
        return Pair(gx, gy)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        screenW = width.toFloat().coerceAtLeast(800f)
        screenH = height.toFloat().coerceAtLeast(480f)
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat().coerceAtLeast(800f)
        screenH = height.toFloat().coerceAtLeast(480f)
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

        // 1. Update Loading Screen
        if (isLoadingScreen) {
            if (loadingProgress < 100f) {
                loadingProgress = (loadingProgress + 1.45f).coerceAtMost(100f)
                if (loadingProgress.toInt() % 28 == 0) {
                    loadingTipIndex = (loadingTipIndex + 1) % loadingTips.size
                }
            } else {
                loadingReadyToEnter = true
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

        // 2. Update Floating Texts & Ambient Particles
        val ftIter = floatTexts.iterator()
        while (ftIter.hasNext()) {
            val ft = ftIter.next()
            ft.sy -= 1.25f
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

        // 3. Home Farm Logic & Living Animations
        if (!inRaidMode) {
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
                        floatTexts.add(FloatText("⭐ اكتملت الترقية للمستوى ${b.level}!", sx, sy - 45f, Color.parseColor("#00E676")))
                        saveVillage()
                    }
                    continue
                }

                // Production timers
                if (b.type in listOf(BType.WHEAT_FIELD, BType.RANCH_BARN, BType.WINDMILL_BAKERY, BType.GREEN_ELIXIR, BType.GOLD_SILO, BType.FIELD_KITCHEN)) {
                    if (!b.readyCollect) {
                        b.prodTimer++
                        val needed = (230 - b.level * 35).coerceAtLeast(100)
                        if (b.prodTimer >= needed) {
                            b.readyCollect = true
                            b.prodTimer = 0
                        }
                    }
                }

                // Spawn ambient particles (Chimney smoke for Manor/Kitchen, Green Bubbles for Bio-Elixir!)
                if (animTickGlobal.toInt() % 14 == (b.id % 14)) {
                    val sx = isoToScreenX(b.gx + 0.5f, b.gy + 0.5f)
                    val sy = isoToScreenY(b.gx + 0.5f, b.gy + 0.5f)
                    if (b.type == BType.MANOR_HALL || b.type == BType.FIELD_KITCHEN) {
                        ambientParticles.add(
                            AmbientParticle(
                                sx = sx + (Random.nextFloat() - 0.5f) * 16f,
                                sy = sy - 58f * camZoom,
                                vx = (Random.nextFloat() - 0.3f) * 0.6f,
                                vy = -0.9f - Random.nextFloat() * 0.5f,
                                radius = 6f + Random.nextFloat() * 5f,
                                color = Color.argb(150, 235, 235, 240),
                                life = 40,
                                maxLife = 40
                            )
                        )
                    } else if (b.type == BType.GREEN_ELIXIR || b.type == BType.EMERALD_TOWER) {
                        ambientParticles.add(
                            AmbientParticle(
                                sx = sx + (Random.nextFloat() - 0.5f) * 24f,
                                sy = sy - 26f * camZoom,
                                vx = (Random.nextFloat() - 0.5f) * 0.5f,
                                vy = -1.1f - Random.nextFloat() * 0.6f,
                                radius = 4f + Random.nextFloat() * 4f,
                                color = Color.parseColor("#00E676"),
                                life = 34,
                                maxLife = 34
                            )
                        )
                    }
                }
            }

            // Update Roaming Farm Workers
            for (w in villageWorkers) {
                w.animPhase += 0.25f
                if (w.waitTicks > 0) {
                    w.waitTicks--
                    continue
                }
                val dx = w.targetGx - w.gx
                val dy = w.targetGy - w.gy
                val dist = hypot(dx, dy)
                if (dist < 0.25f) {
                    w.waitTicks = 35 + Random.nextInt(45)
                    val targetB = myBuildings.randomOrNull()
                    if (targetB != null) {
                        w.targetGx = targetB.gx.toFloat() + 0.5f
                        w.targetGy = targetB.gy.toFloat() + 0.5f
                        w.carryType = (w.carryType + 1) % 3
                    } else {
                        w.targetGx = 3f + Random.nextFloat() * 8f
                        w.targetGy = 3f + Random.nextFloat() * 8f
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
        val neededRations = getRequiredRationsForArmy()
        if (armyRations < neededRations) {
            showBanner("⚠️ تحتاج إلى $neededRations مؤونة جيش 🍗 للهجوم! اطبخ وجبات في مطبخ المزرعة أولاً!")
            activeModal = ModalType.KITCHEN_RATIONS
            return
        }
        val totalUnits = armyRoster.values.sum()
        if (totalUnits <= 0) {
            showBanner("⚠️ جيشك فارغ! درّب وحدات في معسكر المزارعين أولاً!")
            activeModal = ModalType.ARMY_TRAIN
            return
        }

        // Deduct marching rations
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
        raidLootGold = 800 + enemyTier * 450 + Random.nextInt(350)
        raidLootElixir = 750 + enemyTier * 420 + Random.nextInt(350)
        raidLootRations = 35 + enemyTier * 20

        var idG = 100
        fun addRB(t: BType, x: Int, y: Int, lv: Int) {
            val cLv = lv.coerceIn(1, 3)
            val mHp = (t.maxHp * (1.0 + (cLv - 1) * 0.40)).toInt()
            raidBuildings.add(PlacedBuilding(idG++, t, x, y, cLv, mHp, mHp))
        }

        addRB(BType.MANOR_HALL, 6, 6, enemyTier)
        addRB(BType.GOLD_SILO, 4, 6, enemyTier)
        addRB(BType.GREEN_ELIXIR, 8, 6, enemyTier)
        addRB(BType.FIELD_KITCHEN, 6, 4, enemyTier)
        addRB(BType.CORN_CANNON, 4, 4, enemyTier)
        addRB(BType.CORN_CANNON, 8, 4, enemyTier)
        if (enemyTier >= 2) {
            addRB(BType.RANCH_BARN, 4, 8, enemyTier)
            addRB(BType.WINDMILL_BAKERY, 8, 8, enemyTier)
        }
        if (enemyTier >= 3) {
            addRB(BType.EMERALD_TOWER, 6, 8, 3)
            addRB(BType.HONEY_MORTAR, 6, 5, 3)
        } else {
            addRB(BType.CORN_CANNON, 6, 8, enemyTier)
        }
        for (wx in 3..9) {
            addRB(BType.STONE_WALL, wx, 3, enemyTier)
            addRB(BType.STONE_WALL, wx, 9, enemyTier)
        }
        raidTotalStartBuildings = raidBuildings.count { it.type != BType.STONE_WALL }.coerceAtLeast(1)
        val buffMsg = if (attackBuffPercent > 0) " (+$attackBuffPercent% قوة هجوم من وليمة المطبخ!)" else ""
        showBanner("⚔️ استهلك الجيش $neededRations مؤونة 🍗! انقر على أطراف الجزيرة لإنزال قواتك$buffMsg")
    }

    private fun updateRaidBattle() {
        raidTicksLeft--
        if (raidTicksLeft <= 0) {
            endRaidBattle()
            return
        }

        // Update Spells
        val spIter = activeSpells.iterator()
        while (spIter.hasNext()) {
            val sp = spIter.next()
            sp.ticksLeft--
            if (sp.type == SpellType.GREEN_NECTAR && sp.ticksLeft % 15 == 0) {
                for (u in activeUnits) {
                    if (hypot(u.gx - sp.gx, u.gy - sp.gy) <= sp.radius) {
                        u.hp = (u.hp + 40).coerceAtMost(u.maxHp)
                    }
                }
            }
            if (sp.ticksLeft <= 0) activeSpells.remove(sp)
        }

        // Update Active Units
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
                        u.cooldown = 26
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

        // Update Enemy Defenses
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

        // Update Projectiles
        val pIter = projectiles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.progress += 0.12f
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
        activeUnits.clear()
        projectiles.clear()
        activeSpells.clear()
        attackBuffPercent = 0

        val ratio = (raidDestructionPct / 100f).coerceAtLeast(if (raidStars > 0) 0.45f else 0.1f)
        val wonGold = (raidLootGold * ratio).toInt()
        val wonElixir = (raidLootElixir * ratio).toInt()
        val wonRations = (raidLootRations * ratio).toInt()
        gold += wonGold
        greenElixir += wonElixir
        armyRations += wonRations

        if (raidStars > 0) {
            val gainedTrophies = raidStars * 10
            trophies += gainedTrophies
            warOurStars += raidStars
            showBanner("🏆 انتصار (${raidStars}⭐)! غنمت +$wonGold ذهب و +$wonElixir إكسير أخضر و +$wonRations مؤونة 🍗")
        } else {
            trophies = (trophies - 5).coerceAtLeast(0)
            showBanner("🛡️ انتهت الغارة (${raidDestructionPct}%)! غنمت +$wonGold ذهب و +$wonElixir إكسير أخضر")
        }
        saveVillage()
    }

    private fun showBanner(msg: String) {
        statusBanner = msg
        statusBannerTimer = 200
    }

    // ==================== RENDERING PIPELINE ====================
    private fun drawGame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            if (isLoadingScreen) {
                drawProfessionalLoadingScreen(canvas)
            } else {
                drawIslandBackground(canvas)
                drawIsoGridAndEntities(canvas)
                drawAmbientAndProjectiles(canvas)
                drawFloatTexts(canvas)
                drawTopSupercellHUD(canvas)
                drawBottomActionBar(canvas)
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
        val sp = splashBg
        if (sp != null) {
            val dst = RectF(0f, 0f, screenW, screenH)
            canvas.drawBitmap(sp, null, dst, paint)
        }

        // Animated floating glowing emerald & gold embers
        for (e in splashEmbers) {
            paint.style = Paint.Style.FILL
            paint.color = e.color
            paint.alpha = e.alpha
            canvas.drawCircle(e.x / 1280f * screenW, e.y / 720f * screenH, e.size, paint)
        }
        paint.alpha = 255

        // Dark translucent bottom overlay for custom interactive loading bar & tips
        val barPanel = RectF(screenW * 0.16f, screenH * 0.78f, screenW * 0.84f, screenH * 0.96f)
        drawGlossyPanel(canvas, barPanel, Color.parseColor("#DD102818"), Color.parseColor("#00E676"))

        val pct = loadingProgress.toInt().coerceIn(0, 100)
        val stageText = when {
            pct < 30 -> "Loading 2.5D Isometric Farm Assets... ($pct%)"
            pct < 65 -> "Initializing Green Bio-Elixir Pumps & Field Kitchen... ($pct%)"
            pct < 99 -> "Preparing Manor Hall Tiers & Guild Servers... ($pct%)"
            else -> "✅ FARM WARS Ready! اضغط في أي مكان لدخول المزرعة"
        }

        drawOutlinedText(canvas, stageText, screenW * 0.5f, barPanel.top + 32f, 21f, Color.parseColor("#A5D6A7"))

        // Progress Bar Track
        val track = RectF(barPanel.left + 36f, barPanel.top + 46f, barPanel.right - 36f, barPanel.top + 78f)
        paint.color = Color.parseColor("#08140C")
        canvas.drawRoundRect(track, 16f, 16f, paint)

        val fillW = (track.width() * (loadingProgress / 100f)).coerceAtLeast(18f)
        val fillRect = RectF(track.left, track.top, track.left + fillW, track.bottom)
        val barGrad = LinearGradient(fillRect.left, fillRect.top, fillRect.left, fillRect.bottom,
            Color.parseColor("#69F0AE"), Color.parseColor("#00C853"), Shader.TileMode.CLAMP)
        paint.shader = barGrad
        canvas.drawRoundRect(fillRect, 16f, 16f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.parseColor("#FFD54F")
        canvas.drawRoundRect(track, 16f, 16f, paint)
        paint.style = Paint.Style.FILL

        // Tip text
        drawOutlinedText(canvas, loadingTips[loadingTipIndex], screenW * 0.5f, barPanel.bottom - 14f, 18f, Color.WHITE)
    }

    private fun drawIslandBackground(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#147A85"))
        val bg = islandBg
        if (bg != null) {
            val baseW = screenW * 1.28f * camZoom
            val baseH = screenH * 1.28f * camZoom
            val left = (screenW - baseW) * 0.5f + camX
            val top = (screenH - baseH) * 0.44f + camY
            val dst = RectF(left, top, left + baseW, top + baseH)
            canvas.drawBitmap(bg, null, dst, paint)
        }
    }

    private fun drawIsoGridAndEntities(canvas: Canvas) {
        // Draw subtle isometric grid lines on the central clearing
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        paint.color = if (buildModeType != null || moveBuildingMode) Color.argb(115, 105, 240, 174) else Color.argb(36, 255, 255, 255)

        for (i in 0..gridSize) {
            val x1 = isoToScreenX(i.toFloat(), 0f)
            val y1 = isoToScreenY(i.toFloat(), 0f)
            val x2 = isoToScreenX(i.toFloat(), gridSize.toFloat())
            val y2 = isoToScreenY(i.toFloat(), gridSize.toFloat())
            canvas.drawLine(x1, y1, x2, y2, paint)

            val x3 = isoToScreenX(0f, i.toFloat())
            val y3 = isoToScreenY(0f, i.toFloat())
            val x4 = isoToScreenX(gridSize.toFloat(), i.toFloat())
            val y4 = isoToScreenY(gridSize.toFloat(), i.toFloat())
            canvas.drawLine(x3, y3, x4, y4, paint)
        }
        paint.style = Paint.Style.FILL

        // Draw active spell rings
        for (sp in activeSpells) {
            val sx = isoToScreenX(sp.gx, sp.gy)
            val sy = isoToScreenY(sp.gx, sp.gy)
            val rx = sp.radius * tileW * 0.45f * camZoom
            val ry = sp.radius * tileH * 0.45f * camZoom
            paint.color = (sp.type.color and 0x00FFFFFF) or 0x55000000
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            paint.color = sp.type.color
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.FILL
        }

        // Depth-sort buildings, roaming workers, and battle units by (gx + gy)
        val bList = if (inRaidMode) raidBuildings else myBuildings
        data class RenderItem(val depth: Float, val kind: Int, val b: PlacedBuilding?, val u: ActiveUnit?, val w: VillageWorker?)
        val items = ArrayList<RenderItem>(bList.size + activeUnits.size + villageWorkers.size)
        for (b in bList) {
            items.add(RenderItem((b.gx + b.gy + b.type.footprint).toFloat(), 0, b, null, null))
        }
        if (!inRaidMode) {
            for (w in villageWorkers) {
                items.add(RenderItem(w.gx + w.gy, 2, null, null, w))
            }
        }
        for (u in activeUnits) {
            items.add(RenderItem(u.gx + u.gy, 1, null, u, null))
        }
        items.sortBy { it.depth }

        for (item in items) {
            when (item.kind) {
                0 -> item.b?.let { drawBuilding25D(canvas, it) }
                1 -> item.u?.let { drawUnit25D(canvas, it) }
                2 -> item.w?.let { drawVillageWorker25D(canvas, it) }
            }
        }
    }

    private fun drawBuilding25D(canvas: Canvas, b: PlacedBuilding) {
        val fp = b.type.footprint
        val centerGx = b.gx + fp * 0.5f
        val centerGy = b.gy + fp * 0.5f
        val sx = isoToScreenX(centerGx, centerGy)
        val sy = isoToScreenY(centerGx, centerGy)

        // Selection or Upgrade Level Base Platform Ring
        if (selectedBuilding == b) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.parseColor("#00E676")
            val rx = fp * tileW * 0.48f * camZoom
            val ry = fp * tileH * 0.48f * camZoom
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.FILL
        } else if (b.level >= 2 && b.type != BType.STONE_WALL) {
            // Level 2 (Silver/Stone) & Level 3 (Golden-Emerald) glowing aura ring under upgraded buildings!
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * camZoom
            paint.color = if (b.level == 3) Color.argb(145, 0, 230, 118) else Color.argb(110, 255, 213, 79)
            val rx = fp * tileW * 0.42f * camZoom
            val ry = fp * tileH * 0.42f * camZoom
            canvas.drawOval(RectF(sx - rx, sy - ry, sx + rx, sy + ry), paint)
            paint.style = Paint.Style.FILL
        }

        // Ground shadow
        paint.color = Color.argb(75, 0, 0, 0)
        val shW = fp * tileW * 0.42f * camZoom
        val shH = fp * tileH * 0.40f * camZoom
        canvas.drawOval(RectF(sx - shW, sy - shH * 0.5f, sx + shW, sy + shH * 1.1f), paint)

        // Living animations: subtle breathing pulse + tap bounce + turret recoil + crop sway
        val breathScale = 1.0f + sin(animTickGlobal * 0.06f + b.id) * 0.014f + b.tapBounce * 0.12f
        val levelScale = 1.0f + (b.level - 1) * 0.06f
        val recoilOffset = b.recoilAnim * 8f * camZoom
        val swaySkewX = if (b.type == BType.WHEAT_FIELD) sin(animTickGlobal * 0.09f + b.id) * 4f * camZoom else 0f

        val spriteKey = b.effectiveSpriteKey()
        val bmp = bitmaps[spriteKey]
        if (bmp != null) {
            val scale = when {
                b.type == BType.MANOR_HALL && b.level == 3 -> 1.55f
                b.type == BType.MANOR_HALL && b.level == 2 -> 1.42f
                b.type == BType.MANOR_HALL -> 1.28f
                b.type == BType.STONE_WALL -> 0.58f
                fp == 1 -> 0.86f
                else -> 1.18f
            } * camZoom * breathScale * levelScale

            val drawW = tileW * scale
            val drawH = drawW * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(
                sx - drawW * 0.5f + swaySkewX - recoilOffset,
                sy - drawH * 0.74f + fp * tileH * 0.18f * camZoom,
                sx + drawW * 0.5f + swaySkewX - recoilOffset,
                sy + fp * tileH * 0.26f * camZoom
            )
            canvas.drawBitmap(bmp, null, dst, paint)

            // Extra Animated Details on Houses & Buildings:
            // 1. Spinning Windmill Sails on WINDMILL_BAKERY
            if (b.type == BType.WINDMILL_BAKERY) {
                drawSpinningWindmillBlades(canvas, sx, dst.top + drawH * 0.36f, 28f * camZoom)
            }
            // 2. Floating Emerald Crystal on EMERALD_TOWER and MANOR_HALL Lv.3
            if (b.type == BType.EMERALD_TOWER || (b.type == BType.MANOR_HALL && b.level == 3)) {
                val bobY = sin(animTickGlobal * 0.12f + b.id) * 6f * camZoom
                paint.color = Color.argb(120, 0, 230, 118)
                canvas.drawCircle(sx, dst.top + 8f * camZoom + bobY, 14f * camZoom, paint)
                paint.color = Color.parseColor("#69F0AE")
                canvas.drawCircle(sx, dst.top + 8f * camZoom + bobY, 6f * camZoom, paint)
            }
            // 3. Pulsing Green Glow on GREEN_ELIXIR Pump
            if (b.type == BType.GREEN_ELIXIR) {
                val pulseA = (90 + (sin(animTickGlobal * 0.15f + b.id) * 55).toInt()).coerceIn(30, 180)
                paint.color = Color.argb(pulseA, 0, 230, 118)
                canvas.drawCircle(sx + 4f * camZoom, dst.centerY(), 18f * camZoom, paint)
            }
            // 4. Warm Flickering Oven Glow on FIELD_KITCHEN
            if (b.type == BType.FIELD_KITCHEN) {
                val fireA = (80 + Random.nextInt(55))
                paint.color = Color.argb(fireA, 255, 145, 0)
                canvas.drawCircle(sx - 6f * camZoom, dst.centerY() + 4f * camZoom, 15f * camZoom, paint)
            }
        }

        // Level Stars & Badge (Lv.1 ⭐, Lv.2 ⭐⭐, Lv.3 ⭐⭐⭐)
        if (b.type != BType.STONE_WALL) {
            val starsStr = "⭐".repeat(b.level.coerceIn(1, 3))
            val badgeY = sy - fp * tileH * 0.78f * camZoom
            drawOutlinedText(canvas, "${b.type.titleAr} $starsStr", sx, badgeY, 13f * camZoom,
                if (b.level == 3) Color.parseColor("#69F0AE") else Color.parseColor("#FFF59D"))
        }

        // Upgrade Progress Bar
        if (b.upgradeTicksLeft > 0 && b.upgradeTotalTicks > 0) {
            val secLeft = (b.upgradeTicksLeft / 30) + 1
            val pct = 1f - (b.upgradeTicksLeft.toFloat() / b.upgradeTotalTicks.toFloat())
            val barW = 74f * camZoom
            val barH = 11f * camZoom
            val by = sy - fp * tileH * 0.98f * camZoom
            paint.color = Color.parseColor("#AA000000")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 5f, 5f, paint)
            paint.color = Color.parseColor("#00E676")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * pct, by + barH), 5f, 5f, paint)
            drawOutlinedText(canvas, "🔨 ترقية ${secLeft}ث", sx, by - 4f, 12f * camZoom, Color.WHITE)
        }

        // Floating Ready-to-Harvest Bubble
        if (!inRaidMode && b.readyCollect) {
            val bob = sin(animTickGlobal * 0.14f + b.id) * 6f
            val by = sy - fp * tileH * 0.95f * camZoom + bob
            val bubbleRect = RectF(sx - 24f * camZoom, by - 24f * camZoom, sx + 24f * camZoom, by + 12f * camZoom)
            drawGlossyPanel(canvas, bubbleRect, Color.parseColor("#E61B5E20"), Color.parseColor("#00E676"))
            val icon = when (b.type) {
                BType.WHEAT_FIELD -> "🌾"
                BType.RANCH_BARN -> "🥩"
                BType.WINDMILL_BAKERY -> "🥖"
                BType.GREEN_ELIXIR -> "🧪"
                BType.GOLD_SILO -> "🪙"
                BType.FIELD_KITCHEN -> "🍗"
                else -> "✨"
            }
            drawOutlinedText(canvas, icon, sx, by, 18f * camZoom, Color.WHITE)
        }

        // Battle HP bar
        if (inRaidMode && b.hp < b.maxHp) {
            val barW = 58f * camZoom
            val barH = 8f * camZoom
            val by = sy - fp * tileH * 0.62f * camZoom
            val ratio = (b.hp.toFloat() / b.maxHp.toFloat()).coerceIn(0f, 1f)
            paint.color = Color.parseColor("#AA000000")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 4f, 4f, paint)
            paint.color = if (ratio > 0.5f) Color.parseColor("#00E676") else Color.parseColor("#FF5252")
            canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * ratio, by + barH), 4f, 4f, paint)
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
    }

    private fun drawVillageWorker25D(canvas: Canvas, w: VillageWorker) {
        val sx = isoToScreenX(w.gx, w.gy)
        val sy = isoToScreenY(w.gx, w.gy)
        val bob = abs(sin(w.animPhase)) * 4f * camZoom

        // Shadow
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawOval(RectF(sx - 9f * camZoom, sy - 3f * camZoom, sx + 9f * camZoom, sy + 5f * camZoom), paint)

        // Worker body & straw hat
        paint.color = Color.parseColor("#2E7D32")
        canvas.drawRoundRect(RectF(sx - 6f * camZoom, sy - 18f * camZoom - bob, sx + 6f * camZoom, sy - 4f * camZoom - bob), 4f, 4f, paint)
        paint.color = Color.parseColor("#FFCC80")
        canvas.drawCircle(sx, sy - 23f * camZoom - bob, 5.5f * camZoom, paint)
        paint.color = Color.parseColor("#FFD54F")
        canvas.drawOval(RectF(sx - 9f * camZoom, sy - 29f * camZoom - bob, sx + 9f * camZoom, sy - 24f * camZoom - bob), paint)

        // Carried item icon (Wheat / Green Elixir / Rations)
        val itemIcon = when (w.carryType) {
            0 -> "🌾"
            1 -> "🧪"
            else -> "🍗"
        }
        drawOutlinedText(canvas, itemIcon, sx + 8f * camZoom, sy - 14f * camZoom - bob, 11f * camZoom, Color.WHITE)
    }

    private fun drawUnit25D(canvas: Canvas, u: ActiveUnit) {
        val sx = isoToScreenX(u.gx, u.gy)
        val sy = isoToScreenY(u.gx, u.gy)
        val bounce = abs(sin(u.animTick * 0.35f)) * 5.5f * camZoom
        val swingOffset = u.attackSwing * 9f * camZoom

        // Ground shadow
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(RectF(sx - 15f * camZoom, sy - 5f * camZoom, sx + 15f * camZoom, sy + 7f * camZoom), paint)

        val bmp = bitmaps[u.type.spriteKey]
        if (bmp != null) {
            val w = 56f * camZoom * (1.0f + u.attackSwing * 0.14f)
            val h = w * (bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1f))
            val dst = RectF(sx - w * 0.5f + swingOffset, sy - h * 0.85f - bounce, sx + w * 0.5f + swingOffset, sy + h * 0.15f - bounce)
            canvas.drawBitmap(bmp, null, dst, paint)
        } else {
            paint.color = Color.parseColor("#00E676")
            canvas.drawCircle(sx, sy - 15f * camZoom - bounce, 14f * camZoom, paint)
        }

        // HP bar
        val barW = 38f * camZoom
        val barH = 6f * camZoom
        val ratio = (u.hp.toFloat() / u.maxHp.toFloat()).coerceIn(0f, 1f)
        val by = sy - 54f * camZoom - bounce
        paint.color = Color.parseColor("#AA000000")
        canvas.drawRoundRect(RectF(sx - barW / 2, by, sx + barW / 2, by + barH), 3f, 3f, paint)
        paint.color = if (u.isGuildAlly) Color.parseColor("#40C4FF") else Color.parseColor("#00E676")
        canvas.drawRoundRect(RectF(sx - barW / 2, by, sx - barW / 2 + barW * ratio, by + barH), 3f, 3f, paint)
    }

    private fun drawAmbientAndProjectiles(canvas: Canvas) {
        for (p in ambientParticles) {
            paint.color = p.color
            paint.alpha = ((p.life.toFloat() / p.maxLife.toFloat()) * 185).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.sx, p.sy, p.radius * camZoom, paint)
        }
        paint.alpha = 255

        for (p in projectiles) {
            val sx = isoToScreenX(p.curGx, p.curGy)
            val arcLift = sin(p.progress * PI).toFloat() * (if (p.splash) 68f else 26f) * camZoom
            val sy = isoToScreenY(p.curGx, p.curGy) - 20f * camZoom - arcLift
            paint.color = p.color
            canvas.drawCircle(sx, sy, if (p.splash) 10f * camZoom else 6.5f * camZoom, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.WHITE
            canvas.drawCircle(sx, sy, if (p.splash) 10f * camZoom else 6.5f * camZoom, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawFloatTexts(canvas: Canvas) {
        for (ft in floatTexts) {
            drawOutlinedText(canvas, ft.text, ft.sx, ft.sy, 18f, ft.color)
        }
    }

    // ==================== GLOSSY FARM WARS HUD ====================
    private fun drawGlossyPanel(canvas: Canvas, rect: RectF, bgColor: Int, borderColor: Int, radius: Float = 18f) {
        paint.style = Paint.Style.FILL
        paint.color = bgColor
        canvas.drawRoundRect(rect, radius, radius, paint)

        val highlightRect = RectF(rect.left + 4f, rect.top + 4f, rect.right - 4f, rect.top + rect.height() * 0.42f)
        paint.color = Color.argb(40, 255, 255, 255)
        canvas.drawRoundRect(highlightRect, radius * 0.8f, radius * 0.8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawGlossyButton(canvas: Canvas, rect: RectF, text: String, topColor: Int, botColor: Int, borderColor: Int = Color.parseColor("#FFF8E1"), textSize: Float = 17f) {
        val shadowRect = RectF(rect.left, rect.top + 4f, rect.right, rect.bottom + 4f)
        paint.color = Color.argb(115, 0, 0, 0)
        canvas.drawRoundRect(shadowRect, 16f, 16f, paint)

        val grad = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, topColor, botColor, Shader.TileMode.CLAMP)
        paint.shader = grad
        canvas.drawRoundRect(rect, 16f, 16f, paint)
        paint.shader = null

        val shine = RectF(rect.left + 5f, rect.top + 4f, rect.right - 5f, rect.centerY())
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(shine, 12f, 12f, paint)

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

    private fun drawTopSupercellHUD(canvas: Canvas) {
        val pad = 12f
        val pillW = (screenW - pad * 7) / 6f
        val pillH = 46f

        val freeBuilders = (totalBuilders - busyBuildersCount()).coerceAtLeast(0)
        val mhLv = getManorHallLevel()

        // 1. Manor Hall Tier & Trophies
        val p1 = RectF(pad, pad, pad + pillW, pad + pillH)
        drawGlossyPanel(canvas, p1, Color.parseColor("#CC1B3B22"), Color.parseColor("#FFD54F"), 14f)
        drawOutlinedText(canvas, "🏰 TH$mhLv | 🏆 $trophies", p1.centerX(), p1.centerY() + 6f, 15f, Color.parseColor("#FFD54F"))

        // 2. Builders
        val p2 = RectF(p1.right + pad, pad, p1.right + pad + pillW, pad + pillH)
        drawGlossyPanel(canvas, p2, Color.parseColor("#CC1B3B22"), Color.parseColor("#4FC3F7"), 14f)
        drawOutlinedText(canvas, "🔨 العمال $freeBuilders/$totalBuilders", p2.centerX(), p2.centerY() + 6f, 15f, Color.parseColor("#81D4FA"))

        // 3. Army Rations / Food (Required to Attack!)
        val p3 = RectF(p2.right + pad, pad, p2.right + pad + pillW, pad + pillH)
        val neededRations = getRequiredRationsForArmy()
        val rationBorder = if (armyRations >= neededRations) Color.parseColor("#FFAB40") else Color.parseColor("#FF5252")
        drawGlossyPanel(canvas, p3, Color.parseColor("#CC3E2723"), rationBorder, 14f)
        drawOutlinedText(canvas, "🍗 المؤونة $armyRations/$neededRations", p3.centerX(), p3.centerY() + 6f, 15f, Color.parseColor("#FFCC80"))

        // 4. Green Bio-Elixir (#00E676 instead of purple!)
        val p4 = RectF(p3.right + pad, pad, p3.right + pad + pillW, pad + pillH)
        drawGlossyPanel(canvas, p4, Color.parseColor("#CC0D3B1E"), Color.parseColor("#00E676"), 14f)
        drawOutlinedText(canvas, "🧪 إكسير أخضر $greenElixir", p4.centerX(), p4.centerY() + 6f, 15f, Color.parseColor("#69F0AE"))

        // 5. Gold Coins
        val p5 = RectF(p4.right + pad, pad, p4.right + pad + pillW, pad + pillH)
        drawGlossyPanel(canvas, p5, Color.parseColor("#CC3E2723"), Color.parseColor("#FFD54F"), 14f)
        drawOutlinedText(canvas, "🪙 ذهب $gold", p5.centerX(), p5.centerY() + 6f, 15f, Color.parseColor("#FFE082"))

        // 6. Emerald Gems (+)
        val p6 = RectF(p5.right + pad, pad, p5.right + pad + pillW, pad + pillH)
        drawGlossyPanel(canvas, p6, Color.parseColor("#CC004D40"), Color.parseColor("#1DE9B6"), 14f)
        drawOutlinedText(canvas, "💎 $gems [+]", p6.centerX(), p6.centerY() + 6f, 15f, Color.parseColor("#A7FFEB"))

        // Raw Ingredients Sub-bar (Wheat, Meat, Bread + Attack Buff)
        val subBar = RectF(pad, pad + pillH + 6f, screenW * 0.44f, pad + pillH + 38f)
        drawGlossyPanel(canvas, subBar, Color.parseColor("#AA102818"), Color.parseColor("#69F0AE"), 10f)
        val buffStr = if (attackBuffPercent > 0) " | 🔥 هجوم +$attackBuffPercent%" else ""
        drawOutlinedText(
            canvas,
            "🌾 قمح: $wheatCount   🥩 لحوم: $meatCount   🥖 خبز: $breadCount$buffStr",
            subBar.centerX(), subBar.centerY() + 5f, 14f, Color.WHITE
        )

        // Status Toast Banner
        if (statusBannerTimer > 0) {
            val bannerR = RectF(screenW * 0.46f, pad + pillH + 6f, screenW - pad, pad + pillH + 38f)
            drawGlossyPanel(canvas, bannerR, Color.parseColor("#DD1B5E20"), Color.parseColor("#FFD54F"), 10f)
            drawOutlinedText(canvas, statusBanner, bannerR.centerX(), bannerR.centerY() + 5f, 13.5f, Color.WHITE)
        }

        // Raid Battle Top Progress Overlay
        if (inRaidMode) {
            val raidCard = RectF(screenW * 0.30f, pad + pillH + 44f, screenW * 0.70f, pad + pillH + 104f)
            drawGlossyPanel(canvas, raidCard, Color.parseColor("#DD263238"), Color.parseColor("#FFD54F"), 16f)
            val sec = (raidTicksLeft / 30).coerceAtLeast(0)
            val starStr = "⭐".repeat(raidStars) + "☆".repeat(3 - raidStars)
            drawOutlinedText(canvas, "$raidTargetName | $starStr ($raidDestructionPct%)", raidCard.centerX(), raidCard.top + 26f, 16f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "⏱️ الوقت: ${sec}ث | الغنيمة: 🪙$raidLootGold 🧪$raidLootElixir 🍗$raidLootRations", raidCard.centerX(), raidCard.top + 50f, 14f, Color.WHITE)
        }
    }

    private fun drawBottomActionBar(canvas: Canvas) {
        val pad = 12f
        val barH = 82f
        val topY = screenH - barH - pad

        if (!inRaidMode) {
            // Selected Building Contextual Bar
            selectedBuilding?.let { b ->
                val infoW = 500f
                val infoRect = RectF(screenW * 0.5f - infoW * 0.5f, topY - 70f, screenW * 0.5f + infoW * 0.5f, topY - 8f)
                drawGlossyPanel(canvas, infoRect, Color.parseColor("#EE102818"), Color.parseColor("#00E676"), 16f)

                val mhLv = getManorHallLevel()
                val nextLv = b.level + 1
                val upCost = (b.type.baseCostGold + b.type.baseCostElixir + 240) * b.level
                val upLabel = when {
                    b.level >= 3 -> "✅ أقصى مستوى (Lv.3)"
                    b.type != BType.MANOR_HALL && nextLv > mhLv -> "🔒 رقِّ القاعة لـ Lv.$nextLv"
                    b.upgradeTicksLeft > 0 -> "⚡ إنهاء فوري (10💎)"
                    else -> "⬆️ ترقية Lv.$nextLv ($upCost🪙)"
                }

                drawOutlinedText(canvas, "${b.type.titleAr} (Lv.${b.level}/3)", infoRect.left + 135f, infoRect.centerY() + 6f, 15f, Color.parseColor("#FFD54F"))

                val upBtn = RectF(infoRect.right - 235f, infoRect.top + 10f, infoRect.right - 95f, infoRect.bottom - 10f)
                drawGlossyButton(canvas, upBtn, upLabel, Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 13f)

                val moveBtn = RectF(infoRect.right - 88f, infoRect.top + 10f, infoRect.right - 10f, infoRect.bottom - 10f)
                drawGlossyButton(
                    canvas, moveBtn,
                    if (moveBuildingMode) "ضع هنا" else "نقل ✥",
                    Color.parseColor("#1E88E5"), Color.parseColor("#0D47A1"), textSize = 13.5f
                )
            }

            // 6 Glossy Bottom Buttons (Attack, Build, Kitchen/Food, Army, Contracts, Guild)
            val btnCount = 6
            val gap = 10f
            val btnW = (screenW - pad * 2 - gap * (btnCount - 1)) / btnCount

            val labels = listOf(
                "⚔️ هجوم!",
                "🏗️ البناء",
                "🍗 مطبخ الجيش",
                "🛡️ تدريب الجيش",
                "🚚 عقود التموين",
                "🏰 التحالف والحرب"
            )
            val topColors = listOf("#FF8F00", "#43A047", "#F4511E", "#00C853", "#1E88E5", "#00897B")
            val botColors = listOf("#E65100", "#1B5E20", "#BF360C", "#00695C", "#0D47A1", "#004D40")

            for (i in 0 until btnCount) {
                val bx = pad + i * (btnW + gap)
                val r = RectF(bx, topY, bx + btnW, screenH - pad)
                drawGlossyButton(canvas, r, labels[i], Color.parseColor(topColors[i]), Color.parseColor(botColors[i]), textSize = 15.5f)
            }
        } else {
            // RAID MODE: 6 Units + 3 Spells + Guild Support + End Raid
            val totalSlots = 11
            val gap = 6f
            val slotW = (screenW - pad * 2 - gap * (totalSlots - 1)) / totalSlots

            val uTypes = UType.values()
            for (i in uTypes.indices) {
                val u = uTypes[i]
                val cnt = armyRoster[u] ?: 0
                val sx = pad + i * (slotW + gap)
                val r = RectF(sx, topY, sx + slotW, screenH - pad)
                val isSel = (selectedSpell == null && selectedUnitType == u)
                val border = if (isSel) Color.parseColor("#00E676") else Color.parseColor("#90A4AE")
                drawGlossyPanel(canvas, r, if (cnt > 0) Color.parseColor("#DD102818") else Color.parseColor("#88263238"), border, 12f)

                bitmaps[u.spriteKey]?.let { bmp ->
                    val iconS = 38f
                    val dst = RectF(r.centerX() - iconS / 2, r.top + 4f, r.centerX() + iconS / 2, r.top + 4f + iconS)
                    canvas.drawBitmap(bmp, null, dst, paint)
                }
                drawOutlinedText(canvas, u.titleAr, r.centerX(), r.bottom - 22f, 11f, Color.WHITE)
                drawOutlinedText(canvas, "x$cnt", r.centerX(), r.bottom - 6f, 13f, Color.parseColor("#69F0AE"))
            }

            // 3 Spells
            val sTypes = SpellType.values()
            for (j in sTypes.indices) {
                val sp = sTypes[j]
                val cnt = spellCounts[sp] ?: 0
                val idx = 6 + j
                val sx = pad + idx * (slotW + gap)
                val r = RectF(sx, topY, sx + slotW, screenH - pad)
                val isSel = (selectedSpell == sp)
                drawGlossyPanel(canvas, r, Color.parseColor("#DD1B3B22"), if (isSel) Color.parseColor("#FFD54F") else sp.color, 12f)
                drawOutlinedText(canvas, sp.icon, r.centerX(), r.top + 28f, 20f, Color.WHITE)
                drawOutlinedText(canvas, sp.titleAr, r.centerX(), r.bottom - 22f, 10.5f, Color.WHITE)
                drawOutlinedText(canvas, "x$cnt", r.centerX(), r.bottom - 6f, 13f, Color.parseColor("#FFD54F"))
            }

            // Slot 9: Guild Reinforcements
            val ccX = pad + 9 * (slotW + gap)
            val ccRect = RectF(ccX, topY, ccX + slotW, screenH - pad)
            drawGlossyButton(
                canvas, ccRect,
                if (guildReinforcementsCalled) "✅ تم الدعم" else "🏰 دعم التحالف",
                Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 12f
            )

            // Slot 10: End Battle
            val endX = pad + 10 * (slotW + gap)
            val endRect = RectF(endX, topY, endX + slotW, screenH - pad)
            drawGlossyButton(canvas, endRect, "🏳️ إنهاء", Color.parseColor("#E53935"), Color.parseColor("#B71C1C"), textSize = 13.5f)
        }
    }

    // ==================== MODAL DIALOGS ====================
    private fun drawActiveModal(canvas: Canvas) {
        paint.color = Color.argb(165, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)

        val panel = RectF(screenW * 0.08f, screenH * 0.11f, screenW * 0.92f, screenH * 0.87f)
        drawGlossyPanel(canvas, panel, Color.parseColor("#F5122A1A"), Color.parseColor("#00E676"), 24f)

        val closeBtn = RectF(panel.right - 58f, panel.top + 12f, panel.right - 12f, panel.top + 56f)
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
        drawOutlinedText(canvas, "🏗️ متجر مباني المزرعة والدفاعات (قاعة المزرعة الحالية: المستوى $mhLv/3)", panel.centerX(), panel.top + 42f, 21f, Color.parseColor("#69F0AE"))

        val buildable = BType.values().filter { it != BType.MANOR_HALL }
        val cols = 4
        val rows = 3
        val pad = 12f
        val gridTop = panel.top + 64f
        val cellW = (panel.width() - pad * (cols + 1)) / cols
        val cellH = (panel.height() - 80f - pad * (rows + 1)) / rows

        for (i in buildable.indices) {
            val t = buildable[i]
            val c = i % cols
            val r = i / cols
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            val unlocked = mhLv >= t.unlockManorLv

            drawGlossyPanel(
                canvas, card,
                if (unlocked) Color.parseColor("#DD1B3B22") else Color.parseColor("#AA263238"),
                if (unlocked) Color.parseColor("#00E676") else Color.parseColor("#78909C"), 14f
            )

            bitmaps[t.spriteKey]?.let { bmp ->
                val iconS = min(cellW * 0.36f, cellH * 0.50f)
                val dst = RectF(card.left + 10f, card.centerY() - iconS * 0.5f, card.left + 10f + iconS, card.centerY() + iconS * 0.5f)
                canvas.drawBitmap(bmp, null, dst, paint)
            }

            drawOutlinedText(canvas, t.titleAr, card.centerX() + 26f, card.top + 26f, 14f, Color.WHITE)
            val costTxt = if (!unlocked) {
                "🔒 تفتح في قاعة مستوى ${t.unlockManorLv}"
            } else if (t.baseCostGold > 0 && t.baseCostElixir > 0) {
                "🪙${t.baseCostGold} + 🧪${t.baseCostElixir}"
            } else if (t.baseCostGold > 0) {
                "🪙 ${t.baseCostGold} ذهب"
            } else {
                "🧪 ${t.baseCostElixir} إكسير أخضر"
            }
            drawOutlinedText(canvas, costTxt, card.centerX() + 26f, card.top + 50f, 12.5f, Color.parseColor("#FFD54F"))

            val buyR = RectF(card.left + cellW * 0.38f, card.bottom - 38f, card.right - 10f, card.bottom - 8f)
            drawGlossyButton(
                canvas, buyR,
                if (unlocked) "بناء الآن" else "مغلق 🔒",
                if (unlocked) Color.parseColor("#43A047") else Color.parseColor("#546E7A"),
                if (unlocked) Color.parseColor("#1B5E20") else Color.parseColor("#37474F"),
                textSize = 13f
            )
        }
    }

    private fun drawKitchenRationsModal(canvas: Canvas, panel: RectF) {
        val neededRations = getRequiredRationsForArmy()
        drawOutlinedText(canvas, "🍗 مطبخ تجهيز مؤونة الجيش (الرصيد: $armyRations🍗 | المطلوب للهجوم: $neededRations🍗)", panel.centerX(), panel.top + 42f, 20f, Color.parseColor("#FFAB40"))
        drawOutlinedText(canvas, "المخزون الزراعي: 🌾 قمح ($wheatCount)  |  🥩 لحوم الماشية ($meatCount)  |  🥖 خبز الطاحونة ($breadCount)", panel.centerX(), panel.top + 72f, 15.5f, Color.WHITE)

        val meals = listOf(
            Triple("🥖 وجبة المزارع السريعة (+35 مؤونة 🍗)", "التكلفة: 2 قمح 🌾", "تجهيز سريع للغارات الخفيفة"),
            Triple("🍗 وليمة الشواء المحارب (+85 مؤونة 🍗)", "التكلفة: 2 قمح 🌾 + 1 لحوم 🥩", "تموين مشبع لجيش المزرعة بالكامل"),
            Triple("🔥 وليمة الزمرد الملكية (+160 مؤونة 🍗 + 25% قوة هجوم!)", "التكلفة: 3 قمح 🌾 + 2 لحوم 🥩 + 150 إكسير أخضر 🧪", "تمنح جيشك بالكامل +25% ضرر إضافي في الهجوم القادم!")
        )

        val startY = panel.top + 96f
        val rowH = (panel.height() - 125f) / 3f
        for (i in meals.indices) {
            val ry = startY + i * rowH
            val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
            drawGlossyPanel(canvas, rowRect, Color.parseColor("#DD2E1C14"), Color.parseColor("#FFAB40"), 16f)

            bitmaps["field_kitchen"]?.let { bmp ->
                val s = rowH * 0.65f
                canvas.drawBitmap(bmp, null, RectF(rowRect.left + 16f, rowRect.centerY() - s / 2, rowRect.left + 16f + s, rowRect.centerY() + s / 2), paint)
            }

            drawOutlinedText(canvas, meals[i].first, rowRect.centerX() - 20f, rowRect.top + 32f, 17f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "${meals[i].second}  —  ${meals[i].third}", rowRect.centerX() - 20f, rowRect.top + 62f, 13.5f, Color.WHITE)

            val cookBtn = RectF(rowRect.right - 190f, rowRect.centerY() - 24f, rowRect.right - 18f, rowRect.centerY() + 24f)
            drawGlossyButton(canvas, cookBtn, "🍳 اطبخ للـجيش", Color.parseColor("#F4511E"), Color.parseColor("#BF360C"), textSize = 15f)
        }
    }

    private fun drawArmyTrainModal(canvas: Canvas, panel: RectF) {
        val mhLv = getManorHallLevel()
        drawOutlinedText(canvas, "🛡️ معسكر تدريب جيش المزرعة والتميمة الخضراء (قاعة مستوى $mhLv)", panel.centerX(), panel.top + 42f, 20f, Color.parseColor("#69F0AE"))

        val units = UType.values()
        val cols = 3
        val rows = 2
        val pad = 14f
        val gridTop = panel.top + 64f
        val cellW = (panel.width() - pad * (cols + 1)) / cols
        val cellH = (panel.height() - 150f - pad * (rows + 1)) / rows

        for (i in units.indices) {
            val u = units[i]
            val c = i % cols
            val r = i / cols
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            val unlocked = mhLv >= u.unlockManorLv

            drawGlossyPanel(
                canvas, card,
                if (unlocked) Color.parseColor("#DD102818") else Color.parseColor("#AA263238"),
                if (unlocked) Color.parseColor("#00E676") else Color.parseColor("#78909C"), 16f
            )

            bitmaps[u.spriteKey]?.let { bmp ->
                val s = min(cellW * 0.34f, cellH * 0.62f)
                val dst = RectF(card.left + 12f, card.centerY() - s * 0.5f, card.left + 12f + s, card.centerY() + s * 0.5f)
                canvas.drawBitmap(bmp, null, dst, paint)
            }

            val cnt = armyRoster[u] ?: 0
            drawOutlinedText(canvas, "${u.titleAr} (x$cnt)", card.centerX() + 34f, card.top + 28f, 15.5f, Color.WHITE)
            drawOutlinedText(canvas, "❤️${u.maxHp} | ⚔️${u.damage}", card.centerX() + 34f, card.top + 52f, 13f, Color.parseColor("#A5D6A7"))

            val btnR = RectF(card.left + cellW * 0.36f, card.bottom - 42f, card.right - 12f, card.bottom - 8f)
            val label = if (unlocked) "تدريب (🧪${u.costElixir} + 🍗${u.costRations})" else "🔒 قاعة مستوى ${u.unlockManorLv}"
            drawGlossyButton(
                canvas, btnR, label,
                if (unlocked) Color.parseColor("#00C853") else Color.parseColor("#546E7A"),
                if (unlocked) Color.parseColor("#00695C") else Color.parseColor("#37474F"),
                textSize = 12.5f
            )
        }

        // Bottom Spell Brewing Bar
        val spellBarY = panel.bottom - 72f
        val spells = SpellType.values()
        val spW = (panel.width() - pad * 4) / 3f
        for (j in spells.indices) {
            val sp = spells[j]
            val sx = panel.left + pad + j * (spW + pad)
            val sr = RectF(sx, spellBarY, sx + spW, panel.bottom - 14f)
            val cnt = spellCounts[sp] ?: 0
            drawGlossyButton(canvas, sr, "${sp.icon} ${sp.titleAr} (x$cnt) - 🧪${sp.costElixir}", Color.parseColor("#2E7D32"), Color.parseColor("#1B5E20"), textSize = 13.5f)
        }
    }

    private fun drawContractsModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "🚚 عقود تموين المزارع وتجارة المحاصيل", panel.centerX(), panel.top + 42f, 21f, Color.parseColor("#FFD54F"))
        drawOutlinedText(canvas, "مخزنك: 🌾 قمح ($wheatCount)  |  🥩 لحوم ($meatCount)  |  🥖 خبز ($breadCount)", panel.centerX(), panel.top + 74f, 16f, Color.WHITE)

        val startY = panel.top + 96f
        val rowH = (panel.height() - 120f) / 3f
        for (i in contracts.indices) {
            val o = contracts[i]
            val ry = startY + i * rowH
            val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
            drawGlossyPanel(canvas, rowRect, Color.parseColor("#DD1B3B22"), Color.parseColor("#00E676"), 16f)

            drawOutlinedText(canvas, "📜 ${o.title}", rowRect.left + 180f, rowRect.top + 30f, 16.5f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, "المطلوب: 🌾${o.reqWheat}  🥩${o.reqMeat}  🥖${o.reqBread}", rowRect.left + 180f, rowRect.top + 58f, 14.5f, Color.WHITE)
            drawOutlinedText(canvas, "المكافأة: +${o.rewardGold}🪙  +${o.rewardElixir}🧪  +${o.rewardRations}🍗", rowRect.centerX() + 45f, rowRect.centerY() + 6f, 15.5f, Color.parseColor("#69F0AE"))

            val sendBtn = RectF(rowRect.right - 175f, rowRect.centerY() - 22f, rowRect.right - 16f, rowRect.centerY() + 22f)
            drawGlossyButton(canvas, sendBtn, "🚚 إرسال الشحنة", Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 14.5f)
        }
    }

    private fun drawGuildWarModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "🏰 تحالف [$guildName] — حرب المزارع 5v5 والسحابة", panel.centerX(), panel.top + 42f, 20f, Color.parseColor("#69F0AE"))

        val leftBox = RectF(panel.left + 20f, panel.top + 66f, panel.centerX() - 10f, panel.bottom - 20f)
        drawGlossyPanel(canvas, leftBox, Color.parseColor("#DD102818"), Color.parseColor("#FFD54F"), 16f)
        drawOutlinedText(canvas, "⚔️ حرب التحالفات (5 ضد 5)", leftBox.centerX(), leftBox.top + 32f, 18f, Color.parseColor("#FFD54F"))
        drawOutlinedText(canvas, "تحالفنا ⭐ $warOurStars  —  $warEnemyStars ⭐ الخصم", leftBox.centerX(), leftBox.top + 68f, 19f, Color.WHITE)
        drawOutlinedText(canvas, "هجمات الحرب المتبقية: $warAttacksLeft / 2", leftBox.centerX(), leftBox.top + 98f, 14.5f, Color.parseColor("#A5D6A7"))

        val warAtkBtn = RectF(leftBox.left + 24f, leftBox.top + 118f, leftBox.right - 24f, leftBox.top + 168f)
        drawGlossyButton(canvas, warAtkBtn, "🔥 هجوم حرب التحالفات (+غنائم مضاعفة)", Color.parseColor("#FF6F00"), Color.parseColor("#E65100"), textSize = 15f)

        val syncBtn = RectF(leftBox.left + 24f, leftBox.top + 182f, leftBox.right - 24f, leftBox.top + 230f)
        drawGlossyButton(canvas, syncBtn, "☁️ مزامنة مع apk3rade.mangoad.online", Color.parseColor("#1E88E5"), Color.parseColor("#0D47A1"), textSize = 14f)

        val editGuildBtn = RectF(leftBox.left + 24f, leftBox.top + 244f, leftBox.right - 24f, leftBox.top + 292f)
        drawGlossyButton(canvas, editGuildBtn, "✏️ تغيير اسم اللاعب / التحالف", Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 14f)

        val rightBox = RectF(panel.centerX() + 10f, panel.top + 66f, panel.right - 20f, panel.bottom - 20f)
        drawGlossyPanel(canvas, rightBox, Color.parseColor("#DD102818"), Color.parseColor("#00E676"), 16f)
        drawOutlinedText(canvas, "💬 دردشة التحالف وطلب المؤونة", rightBox.centerX(), rightBox.top + 32f, 17f, Color.parseColor("#80CBC4"))

        var chatY = rightBox.top + 66f
        val recent = guildChat.takeLast(6)
        for (msg in recent) {
            drawOutlinedText(canvas, msg, rightBox.centerX(), chatY, 13f, Color.WHITE)
            chatY += 32f
        }

        val donateBtn = RectF(rightBox.left + 20f, rightBox.bottom - 62f, rightBox.centerX() - 6f, rightBox.bottom - 14f)
        drawGlossyButton(canvas, donateBtn, "🎁 طلب مؤونة ودعم", Color.parseColor("#43A047"), Color.parseColor("#1B5E20"), textSize = 13.5f)

        val chatBtn = RectF(rightBox.centerX() + 6f, rightBox.bottom - 62f, rightBox.right - 20f, rightBox.bottom - 14f)
        drawGlossyButton(canvas, chatBtn, "💬 إرسال رسالة", Color.parseColor("#00897B"), Color.parseColor("#004D40"), textSize = 13.5f)
    }

    private fun drawGemShopModal(canvas: Canvas, panel: RectF) {
        drawOutlinedText(canvas, "💎 خزانة الزمرد والعمال الإضافيين", panel.centerX(), panel.top + 42f, 22f, Color.parseColor("#A7FFEB"))

        val items = listOf(
            Triple("🔨 تعيين عامل بناء إضافي (#${totalBuilders + 1})", "يبني ويطور عدة مباني في نفس الوقت", "50 💎"),
            Triple("🍗 شحنة مؤونة جيش فورية (+150 مؤونة)", "تملأ مخزن طعام الجيش فوراً للهجوم", "20 💎"),
            Triple("🧪 خزان إكسير أخضر مركّز (+2500 إكسير)", "يمد مزرعتك بالإكسير الأخضر النقي", "25 💎"),
            Triple("🪙 صندوق الذهب الملكي (+3000 ذهب)", "عملات ذهبية لترقية قاعة المزرعة والدفاعات", "25 💎")
        )

        val cols = 2
        val rows = 2
        val pad = 18f
        val gridTop = panel.top + 70f
        val cellW = (panel.width() - pad * 3) / 2f
        val cellH = (panel.height() - 95f - pad * 3) / 2f

        for (i in items.indices) {
            val c = i % cols
            val r = i / cols
            val cx = panel.left + pad + c * (cellW + pad)
            val cy = gridTop + r * (cellH + pad)
            val card = RectF(cx, cy, cx + cellW, cy + cellH)
            drawGlossyPanel(canvas, card, Color.parseColor("#DD004D40"), Color.parseColor("#1DE9B6"), 16f)

            drawOutlinedText(canvas, items[i].first, card.centerX(), card.top + 36f, 16.5f, Color.parseColor("#FFD54F"))
            drawOutlinedText(canvas, items[i].second, card.centerX(), card.top + 68f, 13.5f, Color.WHITE)

            val buyBtn = RectF(card.centerX() - 90f, card.bottom - 52f, card.centerX() + 90f, card.bottom - 12f)
            drawGlossyButton(canvas, buyBtn, "شراء (${items[i].third})", Color.parseColor("#00BFA5"), Color.parseColor("#00695C"), textSize = 15f)
        }
    }

    // ==================== TOUCH & INPUT HANDLING ====================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tx = event.x
        val ty = event.y

        // Tap anywhere on Loading Screen to enter Farm Wars immediately
        if (isLoadingScreen) {
            if (event.action == MotionEvent.ACTION_UP) {
                isLoadingScreen = false
                showBanner("🌾 مرحباً بك في FARM WARS! اطبخ مؤونة الجيش 🍗 في المطبخ وطور قاعة المزرعة!")
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
                if (hypot(tx - touchDownX, ty - touchDownY) > 14f && activeModal == ModalType.NONE) {
                    isDraggingMap = true
                    camX = (camX + dx).coerceIn(-screenW * 0.45f, screenW * 0.45f)
                    camY = (camY + dy).coerceIn(-screenH * 0.45f, screenH * 0.45f)
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

        // Top HUD Taps
        if (ty <= 64f) {
            if (tx >= screenW * 0.82f) {
                activeModal = ModalType.GEM_SHOP
                return
            }
            if (tx in (screenW * 0.33f)..(screenW * 0.50f)) {
                activeModal = ModalType.KITCHEN_RATIONS
                return
            }
        }

        val pad = 12f
        val barH = 82f
        val topY = screenH - barH - pad

        // Contextual Building Upgrade/Move Bar
        if (!inRaidMode && selectedBuilding != null) {
            val infoW = 500f
            val infoRect = RectF(screenW * 0.5f - infoW * 0.5f, topY - 70f, screenW * 0.5f + infoW * 0.5f, topY - 8f)
            if (infoRect.contains(tx, ty)) {
                val b = selectedBuilding!!
                val upBtn = RectF(infoRect.right - 235f, infoRect.top + 10f, infoRect.right - 95f, infoRect.bottom - 10f)
                val moveBtn = RectF(infoRect.right - 88f, infoRect.top + 10f, infoRect.right - 10f, infoRect.bottom - 10f)
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

        // Bottom Bar Taps
        if (ty >= topY) {
            if (!inRaidMode) {
                val btnCount = 6
                val gap = 10f
                val btnW = (screenW - pad * 2 - gap * (btnCount - 1)) / btnCount
                val idx = ((tx - pad) / (btnW + gap)).toInt().coerceIn(0, btnCount - 1)
                when (idx) {
                    0 -> startRaidBattle(false)
                    1 -> activeModal = ModalType.BUILD_SHOP
                    2 -> activeModal = ModalType.KITCHEN_RATIONS
                    3 -> activeModal = ModalType.ARMY_TRAIN
                    4 -> activeModal = ModalType.TRUCK_ORDERS
                    5 -> activeModal = ModalType.GUILD_WAR
                }
            } else {
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
            }
            return
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

        // Placing New Building
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
                        saveVillage()
                        showBanner("✅ تم بناء ${bType.titleAr}!")
                    } else {
                        showBanner("⚠️ الموارد غير كافية لبناء ${bType.titleAr}!")
                        buildModeType = null
                    }
                } else {
                    showBanner("⚠️ هذا الموقع مشغول بمبنى آخر!")
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

        // Tap Existing Building to Collect or Select
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
            showBanner("✅ هذا المبنى وصل لأقصى مستوى ترقية (المستوى 3)!")
            return
        }
        val mhLv = getManorHallLevel()
        if (b.type != BType.MANOR_HALL && b.level + 1 > mhLv) {
            showBanner("⚠️ يجب ترقية قاعة المزرعة الكبرى (Manor Hall) للمستوى ${b.level + 1} أولاً!")
            return
        }
        if (busyBuildersCount() >= totalBuilders) {
            showBanner("⚠️ جميع العمال مشغولون! عيّن عاملاً جديداً من خزانة الزمرد 💎")
            return
        }
        val upCost = (b.type.baseCostGold + b.type.baseCostElixir + 240) * b.level
        if (gold >= upCost) {
            gold -= upCost
            b.upgradeTotalTicks = 15 * 30
            b.upgradeTicksLeft = b.upgradeTotalTicks
            saveVillage()
            showBanner("🔨 بدأت ترقية ${b.type.titleAr} إلى المستوى ${b.level + 1}!")
        } else {
            showBanner("⚠️ تحتاج $upCost ذهب 🪙 للترقية!")
        }
    }

    private fun deployGuildReinforcements() {
        if (guildReinforcementsCalled) {
            showBanner("⚠️ تم استدعاء دعم التحالف بالفعل في هذه الغارة!")
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
        return true
    }

    private fun handleModalTap(tx: Float, ty: Float) {
        val panel = RectF(screenW * 0.08f, screenH * 0.11f, screenW * 0.92f, screenH * 0.87f)
        val closeBtn = RectF(panel.right - 58f, panel.top + 12f, panel.right - 12f, panel.top + 56f)
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
                val gridTop = panel.top + 64f
                val cellW = (panel.width() - pad * (cols + 1)) / cols
                val cellH = (panel.height() - 80f - pad * (rows + 1)) / rows
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
                            showBanner("🏗️ انقر على أي مربع أخضر في المزرعة لبناء ${t.titleAr}")
                        }
                        return
                    }
                }
            }

            ModalType.KITCHEN_RATIONS -> {
                val startY = panel.top + 96f
                val rowH = (panel.height() - 125f) / 3f
                for (i in 0..2) {
                    val ry = startY + i * rowH
                    val rowRect = RectF(panel.left + 24f, ry, panel.right - 24f, ry + rowH - 12f)
                    if (rowRect.contains(tx, ty)) {
                        when (i) {
                            0 -> {
                                if (wheatCount >= 2) {
                                    wheatCount -= 2
                                    armyRations += 35
                                    breadCount += 1
                                    showBanner("🥖 تم خبز وجبة المزارع السريعة (+35 مؤونة جيش 🍗)!")
                                } else {
                                    showBanner("⚠️ تحتاج 2 قمح 🌾! احصد القمح من الحقول أولاً.")
                                }
                            }
                            1 -> {
                                if (wheatCount >= 2 && meatCount >= 1) {
                                    wheatCount -= 2
                                    meatCount -= 1
                                    armyRations += 85
                                    showBanner("🍗 تم طهي وليمة الشواء المحارب (+85 مؤونة جيش 🍗)!")
                                } else {
                                    showBanner("⚠️ تحتاج 2 قمح 🌾 و 1 لحوم 🥩!")
                                }
                            }
                            2 -> {
                                if (wheatCount >= 3 && meatCount >= 2 && greenElixir >= 150) {
                                    wheatCount -= 3
                                    meatCount -= 2
                                    greenElixir -= 150
                                    armyRations += 160
                                    attackBuffPercent = 25
                                    showBanner("🔥 تم تجهيز وليمة الزمرد الملكية (+160 مؤونة 🍗 + 25% قوة هجوم للجيش)!")
                                } else {
                                    showBanner("⚠️ تحتاج 3 قمح 🌾 و 2 لحوم 🥩 و 150 إكسير أخضر 🧪!")
                                }
                            }
                        }
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
                val gridTop = panel.top + 64f
                val cellW = (panel.width() - pad * (cols + 1)) / cols
                val cellH = (panel.height() - 150f - pad * (rows + 1)) / rows
                for (i in units.indices) {
                    val c = i % cols
                    val r = i / cols
                    val cx = panel.left + pad + c * (cellW + pad)
                    val cy = gridTop + r * (cellH + pad)
                    val card = RectF(cx, cy, cx + cellW, cy + cellH)
                    if (card.contains(tx, ty)) {
                        val u = units[i]
                        if (mhLv < u.unlockManorLv) {
                            showBanner("🔒 تفتح وحدة ${u.titleAr} عند ترقية قاعة المزرعة للمستوى ${u.unlockManorLv}!")
                            return
                        }
                        if (greenElixir >= u.costElixir && armyRations >= u.costRations) {
                            greenElixir -= u.costElixir
                            armyRations -= u.costRations
                            armyRoster[u] = (armyRoster[u] ?: 0) + 1
                            saveVillage()
                            showBanner("✅ تم تدريب ${u.titleAr} وتزويده بالمؤونة!")
                        } else {
                            showBanner("⚠️ تحتاج 🧪${u.costElixir} إكسير أخضر و 🍗${u.costRations} مؤونة لتدريب ${u.titleAr}!")
                        }
                        return
                    }
                }

                // Spell Brewing
                val spellBarY = panel.bottom - 72f
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
                val startY = panel.top + 96f
                val rowH = (panel.height() - 120f) / 3f
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
                            showBanner("🚚 تم تسليم الشحنة واستلام الذهب والإكسير الأخضر والمؤونة!")
                        } else {
                            showBanner("⚠️ المنتجات الزراعية غير كافية! احصد القمح واللحوم أولاً.")
                        }
                        return
                    }
                }
            }

            ModalType.GUILD_WAR -> {
                val leftBox = RectF(panel.left + 20f, panel.top + 66f, panel.centerX() - 10f, panel.bottom - 20f)
                val warAtkBtn = RectF(leftBox.left + 24f, leftBox.top + 118f, leftBox.right - 24f, leftBox.top + 168f)
                val syncBtn = RectF(leftBox.left + 24f, leftBox.top + 182f, leftBox.right - 24f, leftBox.top + 230f)
                val editGuildBtn = RectF(leftBox.left + 24f, leftBox.top + 244f, leftBox.right - 24f, leftBox.top + 292f)

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

                val rightBox = RectF(panel.centerX() + 10f, panel.top + 66f, panel.right - 20f, panel.bottom - 20f)
                val donateBtn = RectF(rightBox.left + 20f, rightBox.bottom - 62f, rightBox.centerX() - 6f, rightBox.bottom - 14f)
                val chatBtn = RectF(rightBox.centerX() + 6f, rightBox.bottom - 62f, rightBox.right - 20f, rightBox.bottom - 14f)

                if (donateBtn.contains(tx, ty)) {
                    armyRoster[UType.MECH] = (armyRoster[UType.MECH] ?: 0) + 1
                    armyRations += 40
                    guildChat.add("🎁 التحالف دعم $playerName بآلي حصاد مدرع + 40 مؤونة 🍗!")
                    showBanner("🎁 استلمت آلي حصاد مدرع + 40 مؤونة جيش من التحالف!")
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
                val gridTop = panel.top + 70f
                val cellW = (panel.width() - pad * 3) / 2f
                val cellH = (panel.height() - 95f - pad * 3) / 2f
                for (i in 0..3) {
                    val c = i % 2
                    val r = i / 2
                    val cx = panel.left + pad + c * (cellW + pad)
                    val cy = gridTop + r * (cellH + pad)
                    val card = RectF(cx, cy, cx + cellW, cy + cellH)
                    if (card.contains(tx, ty)) {
                        when (i) {
                            0 -> if (gems >= 50) { gems -= 50; totalBuilders++; showBanner("🔨 تم تعيين عامل بناء إضافي!") } else showBanner("⚠️ تحتاج 50 جوهرة 💎!")
                            1 -> if (gems >= 20) { gems -= 20; armyRations += 150; showBanner("🍗 +150 مؤونة جيش جاهزة!") } else showBanner("⚠️ تحتاج 20 جوهرة 💎!")
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

    // ==================== INFINITYFREE + CLOUDFLARE SYNC ====================
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
                    showBanner("ℹ️ تم الحفظ محلياً (ارفع server_api.php لتفعيل السحابة)")
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
